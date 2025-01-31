/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.connectors.cassandra;

import org.apache.flink.configuration.Configuration;
import org.apache.flink.core.testutils.CheckedThread;
import org.apache.flink.streaming.api.operators.StreamSink;
import org.apache.flink.streaming.util.OneInputStreamOperatorTestHarness;
import org.apache.flink.util.ExceptionUtils;
import org.apache.flink.util.Preconditions;
import org.apache.flink.util.concurrent.FutureUtils;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.exceptions.NoHostAvailableException;
import com.google.common.util.concurrent.ListenableFuture;
import org.junit.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.powermock.api.mockito.PowerMockito.when;

/** Tests for the {@link CassandraSinkBase}. */
public class CassandraSinkBaseTest {

    private static final long DEFAULT_TEST_TIMEOUT = 5000;

    @Test(expected = NoHostAvailableException.class)
    public void testHostNotFoundErrorHandling() throws Exception {
        CassandraSinkBase base =
                new CassandraSinkBase(
                        new ClusterBuilder() {
                            @Override
                            protected Cluster buildCluster(Cluster.Builder builder) {
                                return builder.addContactPoint("127.0.0.1")
                                        .withoutJMXReporting()
                                        .withoutMetrics()
                                        .build();
                            }
                        },
                        CassandraSinkBaseConfig.newBuilder().build(),
                        new NoOpCassandraFailureHandler()) {
                    @Override
                    public ListenableFuture send(Object value) {
                        return null;
                    }
                };

        base.open(new Configuration());
    }

    @Test(timeout = DEFAULT_TEST_TIMEOUT)
    public void testSuccessfulPath() throws Exception {
        try (TestCassandraSink casSinkFunc = createOpenedTestCassandraSink()) {
            casSinkFunc.enqueueCompletableFuture(CompletableFuture.completedFuture(null));

            final int originalPermits = casSinkFunc.getAvailablePermits();
            assertThat(originalPermits).isGreaterThan(0);
            assertThat(casSinkFunc.getAcquiredPermits()).isEqualTo(0);

            casSinkFunc.invoke("hello");

            assertThat(casSinkFunc.getAvailablePermits()).isEqualTo(originalPermits);
            assertThat(casSinkFunc.getAcquiredPermits()).isEqualTo(0);
        }
    }

    @Test(timeout = DEFAULT_TEST_TIMEOUT)
    public void testThrowErrorOnClose() throws Exception {
        TestCassandraSink casSinkFunc = new TestCassandraSink();

        casSinkFunc.open(new Configuration());

        Exception cause = new RuntimeException();
        casSinkFunc.enqueueCompletableFuture(FutureUtils.completedExceptionally(cause));
        casSinkFunc.invoke("hello");
        try {
            casSinkFunc.close();

            fail("Close should have thrown an exception.");
        } catch (IOException e) {
            ExceptionUtils.findThrowable(e, candidate -> candidate == cause).orElseThrow(() -> e);
        }
    }

    @Test(timeout = DEFAULT_TEST_TIMEOUT)
    public void testThrowErrorOnInvoke() throws Exception {
        try (TestCassandraSink casSinkFunc = createOpenedTestCassandraSink()) {
            Exception cause = new RuntimeException();
            casSinkFunc.enqueueCompletableFuture(FutureUtils.completedExceptionally(cause));

            casSinkFunc.invoke("hello");

            try {
                casSinkFunc.invoke("world");
                fail("Sending of second value should have failed.");
            } catch (IOException e) {
                assertThat(e.getCause()).isEqualTo(cause);
                assertThat(casSinkFunc.getAcquiredPermits()).isEqualTo(0);
            }
        }
    }

    @Test(timeout = DEFAULT_TEST_TIMEOUT)
    public void testIgnoreError() throws Exception {
        Exception cause = new RuntimeException();
        CassandraFailureHandler failureHandler = failure -> assertThat(failure).isEqualTo(cause);

        try (TestCassandraSink casSinkFunc = createOpenedTestCassandraSink(failureHandler)) {

            casSinkFunc.enqueueCompletableFuture(FutureUtils.completedExceptionally(cause));
            casSinkFunc.enqueueCompletableFuture(FutureUtils.completedExceptionally(cause));

            casSinkFunc.invoke("hello");
            casSinkFunc.invoke("world");
        }
    }

    @Test(timeout = DEFAULT_TEST_TIMEOUT)
    public void testThrowErrorOnSnapshot() throws Exception {
        TestCassandraSink casSinkFunc = new TestCassandraSink();

        try (OneInputStreamOperatorTestHarness<String, Object> testHarness =
                createOpenedTestHarness(casSinkFunc)) {
            Exception cause = new RuntimeException();
            casSinkFunc.enqueueCompletableFuture(FutureUtils.completedExceptionally(cause));

            casSinkFunc.invoke("hello");
            assertThatThrownBy(() -> testHarness.snapshot(123L, 123L))
                    .hasCauseInstanceOf(IOException.class);
        }
    }

    @Test(timeout = DEFAULT_TEST_TIMEOUT)
    public void testWaitForPendingUpdatesOnSnapshot() throws Exception {
        final TestCassandraSink casSinkFunc = new TestCassandraSink();

        try (OneInputStreamOperatorTestHarness<String, Object> testHarness =
                createOpenedTestHarness(casSinkFunc)) {
            CompletableFuture<ResultSet> completableFuture = new CompletableFuture<>();
            casSinkFunc.enqueueCompletableFuture(completableFuture);

            casSinkFunc.invoke("hello");
            assertThat(casSinkFunc.getAcquiredPermits()).isEqualTo(1);

            final CountDownLatch latch = new CountDownLatch(1);
            Thread t =
                    new CheckedThread("Flink-CassandraSinkBaseTest") {
                        @Override
                        public void go() throws Exception {
                            testHarness.snapshot(123L, 123L);
                            latch.countDown();
                        }
                    };
            t.start();
            while (t.getState() != Thread.State.TIMED_WAITING) {
                Thread.sleep(5);
            }

            assertThat(casSinkFunc.getAcquiredPermits()).isEqualTo(1);
            completableFuture.complete(null);
            latch.await();
            assertThat(casSinkFunc.getAcquiredPermits()).isEqualTo(0);
        }
    }

    @Test(timeout = DEFAULT_TEST_TIMEOUT)
    public void testWaitForPendingUpdatesOnClose() throws Exception {
        TestCassandraSink casSinkFunc = new TestCassandraSink();

        try (OneInputStreamOperatorTestHarness<String, Object> testHarness =
                createOpenedTestHarness(casSinkFunc)) {

            CompletableFuture<ResultSet> completableFuture = new CompletableFuture<>();
            casSinkFunc.enqueueCompletableFuture(completableFuture);

            casSinkFunc.invoke("hello");
            assertThat(casSinkFunc.getAcquiredPermits()).isEqualTo(1);

            final CountDownLatch latch = new CountDownLatch(1);
            Thread t =
                    new CheckedThread("Flink-CassandraSinkBaseTest") {
                        @Override
                        public void go() throws Exception {
                            testHarness.close();
                            latch.countDown();
                        }
                    };
            t.start();
            while (t.getState() != Thread.State.TIMED_WAITING) {
                Thread.sleep(5);
            }

            assertThat(casSinkFunc.getAcquiredPermits()).isEqualTo(1);
            completableFuture.complete(null);
            latch.await();
            assertThat(casSinkFunc.getAcquiredPermits()).isEqualTo(0);
        }
    }

    @Test(timeout = DEFAULT_TEST_TIMEOUT)
    public void testReleaseOnSuccess() throws Exception {
        final CassandraSinkBaseConfig config =
                CassandraSinkBaseConfig.newBuilder().setMaxConcurrentRequests(1).build();

        try (TestCassandraSink testCassandraSink = createOpenedTestCassandraSink(config)) {
            assertThat(testCassandraSink.getAvailablePermits()).isEqualTo(1);
            assertThat(testCassandraSink.getAcquiredPermits()).isEqualTo(0);

            CompletableFuture<ResultSet> completableFuture = new CompletableFuture<>();
            testCassandraSink.enqueueCompletableFuture(completableFuture);
            testCassandraSink.invoke("N/A");

            assertThat(testCassandraSink.getAvailablePermits()).isEqualTo(0);
            assertThat(testCassandraSink.getAcquiredPermits()).isEqualTo(1);

            completableFuture.complete(null);

            assertThat(testCassandraSink.getAvailablePermits()).isEqualTo(1);
            assertThat(testCassandraSink.getAcquiredPermits()).isEqualTo(0);
        }
    }

    @Test(timeout = DEFAULT_TEST_TIMEOUT)
    public void testReleaseOnFailure() throws Exception {
        final CassandraSinkBaseConfig config =
                CassandraSinkBaseConfig.newBuilder().setMaxConcurrentRequests(1).build();
        final CassandraFailureHandler failureHandler = ignored -> {};

        try (TestCassandraSink testCassandraSink =
                createOpenedTestCassandraSink(config, failureHandler)) {
            assertThat(testCassandraSink.getAvailablePermits()).isEqualTo(1);
            assertThat(testCassandraSink.getAcquiredPermits()).isEqualTo(0);

            CompletableFuture<ResultSet> completableFuture = new CompletableFuture<>();
            testCassandraSink.enqueueCompletableFuture(completableFuture);
            testCassandraSink.invoke("N/A");

            assertThat(testCassandraSink.getAvailablePermits()).isEqualTo(0);
            assertThat(testCassandraSink.getAcquiredPermits()).isEqualTo(1);

            completableFuture.completeExceptionally(new RuntimeException());

            assertThat(testCassandraSink.getAvailablePermits()).isEqualTo(1);
            assertThat(testCassandraSink.getAcquiredPermits()).isEqualTo(0);
        }
    }

    @Test(timeout = DEFAULT_TEST_TIMEOUT)
    public void testReleaseOnThrowingSend() throws Exception {
        final CassandraSinkBaseConfig config =
                CassandraSinkBaseConfig.newBuilder().setMaxConcurrentRequests(1).build();

        Function<String, ListenableFuture<ResultSet>> failingSendFunction =
                ignoredMessage -> {
                    throwCheckedAsUnchecked(new Throwable("expected"));
                    //noinspection ReturnOfNull
                    return null;
                };

        try (TestCassandraSink testCassandraSink =
                new MockCassandraSink(config, failingSendFunction)) {
            testCassandraSink.open(new Configuration());
            assertThat(testCassandraSink.getAvailablePermits()).isEqualTo(1);
            assertThat(testCassandraSink.getAcquiredPermits()).isEqualTo(0);

            //noinspection OverlyBroadCatchBlock,NestedTryStatement
            try {
                testCassandraSink.invoke("none");
            } catch (Throwable e) {
                assertThat(e).isInstanceOf(Throwable.class);
                assertThat(testCassandraSink.getAvailablePermits()).isEqualTo(1);
                assertThat(testCassandraSink.getAcquiredPermits()).isEqualTo(0);
            }
        }
    }

    @Test(timeout = DEFAULT_TEST_TIMEOUT)
    public void testTimeoutExceptionOnInvoke() throws Exception {
        final CassandraSinkBaseConfig config =
                CassandraSinkBaseConfig.newBuilder()
                        .setMaxConcurrentRequests(1)
                        .setMaxConcurrentRequestsTimeout(Duration.ofMillis(1))
                        .build();

        try (TestCassandraSink testCassandraSink = createOpenedTestCassandraSink(config)) {
            CompletableFuture<ResultSet> completableFuture = new CompletableFuture<>();
            testCassandraSink.enqueueCompletableFuture(completableFuture);
            testCassandraSink.enqueueCompletableFuture(completableFuture);
            testCassandraSink.invoke("Invoke #1");

            try {
                testCassandraSink.invoke("Invoke #2");
                fail("Sending value should have experienced a TimeoutException");
            } catch (Exception e) {
                assertThat(e).isInstanceOf(TimeoutException.class);
            } finally {
                completableFuture.complete(null);
            }
        }
    }

    private TestCassandraSink createOpenedTestCassandraSink() {
        final TestCassandraSink testCassandraSink = new TestCassandraSink();
        testCassandraSink.open(new Configuration());
        return testCassandraSink;
    }

    private TestCassandraSink createOpenedTestCassandraSink(
            CassandraFailureHandler failureHandler) {
        final TestCassandraSink testCassandraSink = new TestCassandraSink(failureHandler);
        testCassandraSink.open(new Configuration());
        return testCassandraSink;
    }

    private TestCassandraSink createOpenedTestCassandraSink(CassandraSinkBaseConfig config) {
        final TestCassandraSink testCassandraSink = new TestCassandraSink(config);
        testCassandraSink.open(new Configuration());
        return testCassandraSink;
    }

    private TestCassandraSink createOpenedTestCassandraSink(
            CassandraSinkBaseConfig config, CassandraFailureHandler failureHandler) {
        final TestCassandraSink testCassandraSink = new TestCassandraSink(config, failureHandler);
        testCassandraSink.open(new Configuration());
        return testCassandraSink;
    }

    private OneInputStreamOperatorTestHarness<String, Object> createOpenedTestHarness(
            TestCassandraSink testCassandraSink) throws Exception {
        final StreamSink<String> testStreamSink = new StreamSink<>(testCassandraSink);
        final OneInputStreamOperatorTestHarness<String, Object> testHarness =
                new OneInputStreamOperatorTestHarness<>(testStreamSink);
        testHarness.open();
        return testHarness;
    }

    private static <T extends Throwable> void throwCheckedAsUnchecked(Throwable ex) throws T {
        //noinspection unchecked
        throw (T) ex;
    }

    private static class TestCassandraSink extends CassandraSinkBase<String, ResultSet>
            implements AutoCloseable {

        private static final ClusterBuilder builder;
        private static final Cluster cluster;
        private static final Session session;

        static {
            cluster = mock(Cluster.class);

            session = mock(Session.class);
            when(cluster.connect()).thenReturn(session);

            builder =
                    new ClusterBuilder() {
                        @Override
                        protected Cluster buildCluster(Cluster.Builder builder) {
                            return cluster;
                        }
                    };
        }

        private final Queue<ListenableFuture<ResultSet>> resultSetFutures = new LinkedList<>();

        TestCassandraSink() {
            this(CassandraSinkBaseConfig.newBuilder().build());
        }

        TestCassandraSink(CassandraSinkBaseConfig config) {
            this(config, new NoOpCassandraFailureHandler());
        }

        TestCassandraSink(CassandraFailureHandler failureHandler) {
            this(CassandraSinkBaseConfig.newBuilder().build(), failureHandler);
        }

        TestCassandraSink(CassandraSinkBaseConfig config, CassandraFailureHandler failureHandler) {
            super(builder, config, failureHandler);
        }

        @Override
        public ListenableFuture<ResultSet> send(String value) {
            return resultSetFutures.poll();
        }

        void enqueueCompletableFuture(CompletableFuture<ResultSet> completableFuture) {
            Preconditions.checkNotNull(completableFuture);
            resultSetFutures.offer(ResultSetFutures.fromCompletableFuture(completableFuture));
        }
    }

    private static class MockCassandraSink extends TestCassandraSink {
        private static final long serialVersionUID = -3363195776692829911L;

        private final Function<String, ListenableFuture<ResultSet>> sendFunction;

        MockCassandraSink(
                CassandraSinkBaseConfig config,
                Function<String, ListenableFuture<ResultSet>> sendFunction) {
            super(config, new NoOpCassandraFailureHandler());
            this.sendFunction = sendFunction;
        }

        @Override
        public ListenableFuture<ResultSet> send(String value) {
            return this.sendFunction.apply(value);
        }
    }
}

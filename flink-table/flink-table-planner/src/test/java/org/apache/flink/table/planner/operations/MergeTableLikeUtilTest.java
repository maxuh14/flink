/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.planner.operations;

import org.apache.flink.sql.parser.ddl.SqlTableColumn.SqlComputedColumn;
import org.apache.flink.sql.parser.ddl.SqlTableColumn.SqlMetadataColumn;
import org.apache.flink.sql.parser.ddl.SqlTableColumn.SqlRegularColumn;
import org.apache.flink.sql.parser.ddl.SqlTableLike.FeatureOption;
import org.apache.flink.sql.parser.ddl.SqlTableLike.MergingStrategy;
import org.apache.flink.sql.parser.ddl.SqlTableLike.SqlTableLikeOption;
import org.apache.flink.sql.parser.ddl.SqlWatermark;
import org.apache.flink.sql.parser.ddl.constraint.SqlConstraintEnforcement;
import org.apache.flink.sql.parser.ddl.constraint.SqlTableConstraint;
import org.apache.flink.sql.parser.ddl.constraint.SqlUniqueSpec;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.api.TableColumn;
import org.apache.flink.table.api.TableSchema;
import org.apache.flink.table.api.ValidationException;
import org.apache.flink.table.planner.calcite.FlinkTypeFactory;
import org.apache.flink.table.planner.calcite.FlinkTypeSystem;
import org.apache.flink.table.planner.utils.PlannerMocks;
import org.apache.flink.table.types.DataType;
import org.apache.flink.table.types.logical.LogicalType;

import org.apache.calcite.avatica.util.TimeUnit;
import org.apache.calcite.sql.SqlBasicCall;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlIntervalQualifier;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.type.SqlTypeUtil;
import org.apache.calcite.sql.validate.SqlValidator;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests for {@link MergeTableLikeUtil}. */
public class MergeTableLikeUtilTest {

    private final FlinkTypeFactory typeFactory = new FlinkTypeFactory(FlinkTypeSystem.INSTANCE);
    private final SqlValidator sqlValidator =
            PlannerMocks.create().getPlanner().getOrCreateSqlValidator();
    private final MergeTableLikeUtil util = new MergeTableLikeUtil(sqlValidator, SqlNode::toString);

    @Test
    public void mergePhysicalColumns() {
        TableSchema sourceSchema =
                TableSchema.builder()
                        .add(TableColumn.physical("one", DataTypes.INT()))
                        .add(TableColumn.physical("two", DataTypes.STRING()))
                        .build();

        List<SqlNode> derivedColumns =
                Arrays.asList(
                        regularColumn("three", DataTypes.INT()),
                        regularColumn("four", DataTypes.STRING()));

        TableSchema mergedSchema =
                util.mergeTables(
                        getDefaultMergingStrategies(),
                        sourceSchema,
                        derivedColumns,
                        Collections.emptyList(),
                        null);

        TableSchema expectedSchema =
                TableSchema.builder()
                        .add(TableColumn.physical("one", DataTypes.INT()))
                        .add(TableColumn.physical("two", DataTypes.STRING()))
                        .add(TableColumn.physical("three", DataTypes.INT()))
                        .add(TableColumn.physical("four", DataTypes.STRING()))
                        .build();

        assertThat(mergedSchema).isEqualTo(expectedSchema);
    }

    @Test
    public void mergeWithIncludeFailsOnDuplicateColumn() {
        TableSchema sourceSchema =
                TableSchema.builder().add(TableColumn.physical("one", DataTypes.INT())).build();

        List<SqlNode> derivedColumns =
                Arrays.asList(
                        regularColumn("one", DataTypes.INT()),
                        regularColumn("four", DataTypes.STRING()));

        assertThatThrownBy(
                        () ->
                                util.mergeTables(
                                        getDefaultMergingStrategies(),
                                        sourceSchema,
                                        derivedColumns,
                                        Collections.emptyList(),
                                        null))
                .isInstanceOf(ValidationException.class)
                .hasMessage("A column named 'one' already exists in the base table.");
    }

    @Test
    public void mergeWithIncludeFailsOnDuplicateRegularColumn() {
        TableSchema sourceSchema =
                TableSchema.builder().add(TableColumn.physical("one", DataTypes.INT())).build();

        List<SqlNode> derivedColumns =
                Arrays.asList(
                        regularColumn("two", DataTypes.INT()),
                        regularColumn("two", DataTypes.INT()),
                        regularColumn("four", DataTypes.STRING()));

        assertThatThrownBy(
                        () ->
                                util.mergeTables(
                                        getDefaultMergingStrategies(),
                                        sourceSchema,
                                        derivedColumns,
                                        Collections.emptyList(),
                                        null))
                .isInstanceOf(ValidationException.class)
                .hasMessage("A regular Column named 'two' already exists in the table.");
    }

    @Test
    public void mergeWithIncludeFailsOnDuplicateRegularColumnAndComputeColumn() {
        TableSchema sourceSchema =
                TableSchema.builder().add(TableColumn.physical("one", DataTypes.INT())).build();

        List<SqlNode> derivedColumns =
                Arrays.asList(
                        regularColumn("two", DataTypes.INT()),
                        computedColumn("three", plus("two", "3")),
                        regularColumn("three", DataTypes.INT()),
                        regularColumn("four", DataTypes.STRING()));

        assertThatThrownBy(
                        () ->
                                util.mergeTables(
                                        getDefaultMergingStrategies(),
                                        sourceSchema,
                                        derivedColumns,
                                        Collections.emptyList(),
                                        null))
                .isInstanceOf(ValidationException.class)
                .hasMessage(
                        "A column named 'three' already exists in the table. Duplicate columns "
                                + "exist in the compute column and regular column. ");
    }

    @Test
    public void mergeWithIncludeFailsOnDuplicateRegularColumnAndMetadataColumn() {
        TableSchema sourceSchema =
                TableSchema.builder().add(TableColumn.physical("one", DataTypes.INT())).build();

        List<SqlNode> derivedColumns =
                Arrays.asList(
                        metadataColumn("two", DataTypes.INT(), true),
                        computedColumn("three", plus("two", "3")),
                        regularColumn("two", DataTypes.INT()),
                        regularColumn("four", DataTypes.STRING()));

        assertThatThrownBy(
                        () ->
                                util.mergeTables(
                                        getDefaultMergingStrategies(),
                                        sourceSchema,
                                        derivedColumns,
                                        Collections.emptyList(),
                                        null))
                .isInstanceOf(ValidationException.class)
                .hasMessage(
                        "A column named 'two' already exists in the table. Duplicate columns "
                                + "exist in the metadata column and regular column. ");
    }

    @Test
    public void mergeGeneratedColumns() {
        TableSchema sourceSchema =
                TableSchema.builder()
                        .add(TableColumn.physical("one", DataTypes.INT()))
                        .add(TableColumn.computed("two", DataTypes.INT(), "one + 1"))
                        .build();

        List<SqlNode> derivedColumns =
                Arrays.asList(
                        regularColumn("three", DataTypes.INT()),
                        computedColumn("four", plus("one", "3")));

        TableSchema mergedSchema =
                util.mergeTables(
                        getDefaultMergingStrategies(),
                        sourceSchema,
                        derivedColumns,
                        Collections.emptyList(),
                        null);

        TableSchema expectedSchema =
                TableSchema.builder()
                        .add(TableColumn.physical("one", DataTypes.INT()))
                        .add(TableColumn.computed("two", DataTypes.INT(), "one + 1"))
                        .add(TableColumn.physical("three", DataTypes.INT()))
                        .add(TableColumn.computed("four", DataTypes.INT(), "`one` + 3"))
                        .build();

        assertThat(mergedSchema).isEqualTo(expectedSchema);
    }

    @Test
    public void mergeMetadataColumns() {
        TableSchema sourceSchema =
                TableSchema.builder()
                        .add(TableColumn.physical("one", DataTypes.INT()))
                        .add(TableColumn.metadata("two", DataTypes.INT(), false))
                        .add(TableColumn.computed("c", DataTypes.INT(), "ABS(two)"))
                        .build();

        List<SqlNode> derivedColumns =
                Arrays.asList(
                        regularColumn("three", DataTypes.INT()),
                        metadataColumn("four", DataTypes.INT(), true));

        TableSchema mergedSchema =
                util.mergeTables(
                        getDefaultMergingStrategies(),
                        sourceSchema,
                        derivedColumns,
                        Collections.emptyList(),
                        null);

        TableSchema expectedSchema =
                TableSchema.builder()
                        .add(TableColumn.physical("one", DataTypes.INT()))
                        .add(TableColumn.metadata("two", DataTypes.INT(), false))
                        .add(TableColumn.computed("c", DataTypes.INT(), "ABS(two)"))
                        .add(TableColumn.physical("three", DataTypes.INT()))
                        .add(TableColumn.metadata("four", DataTypes.INT(), true))
                        .build();

        assertThat(mergedSchema).isEqualTo(expectedSchema);
    }

    @Test
    public void mergeIncludingGeneratedColumnsFailsOnDuplicate() {
        TableSchema sourceSchema =
                TableSchema.builder()
                        .add(TableColumn.physical("one", DataTypes.INT()))
                        .add(TableColumn.computed("two", DataTypes.INT(), "one + 1"))
                        .build();

        List<SqlNode> derivedColumns =
                Collections.singletonList(computedColumn("two", plus("one", "3")));

        assertThatThrownBy(
                        () ->
                                util.mergeTables(
                                        getDefaultMergingStrategies(),
                                        sourceSchema,
                                        derivedColumns,
                                        Collections.emptyList(),
                                        null))
                .isInstanceOf(ValidationException.class)
                .hasMessage(
                        "A generated column named 'two' already exists in the base table. You "
                                + "might want to specify EXCLUDING GENERATED or "
                                + "OVERWRITING GENERATED.");
    }

    @Test
    public void mergeIncludingMetadataColumnsFailsOnDuplicate() {
        TableSchema sourceSchema =
                TableSchema.builder()
                        .add(TableColumn.physical("one", DataTypes.INT()))
                        .add(TableColumn.metadata("two", DataTypes.INT()))
                        .build();

        List<SqlNode> derivedColumns =
                Collections.singletonList(metadataColumn("two", DataTypes.INT(), false));

        assertThatThrownBy(
                        () ->
                                util.mergeTables(
                                        getDefaultMergingStrategies(),
                                        sourceSchema,
                                        derivedColumns,
                                        Collections.emptyList(),
                                        null))
                .isInstanceOf(ValidationException.class)
                .hasMessage(
                        "A metadata column named 'two' already exists in the base table. You "
                                + "might want to specify EXCLUDING METADATA or "
                                + "OVERWRITING METADATA.");
    }

    @Test
    public void mergeExcludingGeneratedColumnsDuplicate() {
        TableSchema sourceSchema =
                TableSchema.builder()
                        .add(TableColumn.physical("one", DataTypes.INT()))
                        .add(TableColumn.computed("two", DataTypes.INT(), "one + 1"))
                        .build();

        List<SqlNode> derivedColumns =
                Collections.singletonList(computedColumn("two", plus("one", "3")));

        Map<FeatureOption, MergingStrategy> mergingStrategies = getDefaultMergingStrategies();
        mergingStrategies.put(FeatureOption.GENERATED, MergingStrategy.EXCLUDING);

        TableSchema mergedSchema =
                util.mergeTables(
                        mergingStrategies,
                        sourceSchema,
                        derivedColumns,
                        Collections.emptyList(),
                        null);

        TableSchema expectedSchema =
                TableSchema.builder()
                        .add(TableColumn.physical("one", DataTypes.INT()))
                        .add(TableColumn.computed("two", DataTypes.INT(), "`one` + 3"))
                        .build();

        assertThat(mergedSchema).isEqualTo(expectedSchema);
    }

    @Test
    public void mergeExcludingMetadataColumnsDuplicate() {
        TableSchema sourceSchema =
                TableSchema.builder()
                        .add(TableColumn.physical("one", DataTypes.INT()))
                        .add(TableColumn.metadata("two", DataTypes.INT()))
                        .build();

        List<SqlNode> derivedColumns =
                Collections.singletonList(metadataColumn("two", DataTypes.BOOLEAN(), false));

        Map<FeatureOption, MergingStrategy> mergingStrategies = getDefaultMergingStrategies();
        mergingStrategies.put(FeatureOption.METADATA, MergingStrategy.EXCLUDING);

        TableSchema mergedSchema =
                util.mergeTables(
                        mergingStrategies,
                        sourceSchema,
                        derivedColumns,
                        Collections.emptyList(),
                        null);

        TableSchema expectedSchema =
                TableSchema.builder()
                        .add(TableColumn.physical("one", DataTypes.INT()))
                        .add(TableColumn.metadata("two", DataTypes.BOOLEAN()))
                        .build();

        assertThat(mergedSchema).isEqualTo(expectedSchema);
    }

    @Test
    public void mergeOverwritingGeneratedColumnsDuplicate() {
        TableSchema sourceSchema =
                TableSchema.builder()
                        .add(TableColumn.physical("one", DataTypes.INT()))
                        .add(TableColumn.computed("two", DataTypes.INT(), "one + 1"))
                        .build();

        List<SqlNode> derivedColumns =
                Collections.singletonList(computedColumn("two", plus("one", "3")));

        Map<FeatureOption, MergingStrategy> mergingStrategies = getDefaultMergingStrategies();
        mergingStrategies.put(FeatureOption.GENERATED, MergingStrategy.OVERWRITING);

        TableSchema mergedSchema =
                util.mergeTables(
                        mergingStrategies,
                        sourceSchema,
                        derivedColumns,
                        Collections.emptyList(),
                        null);

        TableSchema expectedSchema =
                TableSchema.builder()
                        .add(TableColumn.physical("one", DataTypes.INT()))
                        .add(TableColumn.computed("two", DataTypes.INT(), "`one` + 3"))
                        .build();

        assertThat(mergedSchema).isEqualTo(expectedSchema);
    }

    @Test
    public void mergeOverwritingMetadataColumnsDuplicate() {
        TableSchema sourceSchema =
                TableSchema.builder()
                        .add(TableColumn.physical("one", DataTypes.INT()))
                        .add(TableColumn.metadata("two", DataTypes.INT()))
                        .build();

        List<SqlNode> derivedColumns =
                Collections.singletonList(metadataColumn("two", DataTypes.BOOLEAN(), true));

        Map<FeatureOption, MergingStrategy> mergingStrategies = getDefaultMergingStrategies();
        mergingStrategies.put(FeatureOption.METADATA, MergingStrategy.OVERWRITING);

        TableSchema mergedSchema =
                util.mergeTables(
                        mergingStrategies,
                        sourceSchema,
                        derivedColumns,
                        Collections.emptyList(),
                        null);

        TableSchema expectedSchema =
                TableSchema.builder()
                        .add(TableColumn.physical("one", DataTypes.INT()))
                        .add(TableColumn.metadata("two", DataTypes.BOOLEAN(), true))
                        .build();

        assertThat(mergedSchema).isEqualTo(expectedSchema);
    }

    @Test
    public void mergeOverwritingPhysicalColumnWithGeneratedColumn() {
        TableSchema sourceSchema =
                TableSchema.builder()
                        .add(TableColumn.physical("one", DataTypes.INT()))
                        .add(TableColumn.physical("two", DataTypes.INT()))
                        .build();

        List<SqlNode> derivedColumns =
                Collections.singletonList(computedColumn("two", plus("one", "3")));

        Map<FeatureOption, MergingStrategy> mergingStrategies = getDefaultMergingStrategies();
        mergingStrategies.put(FeatureOption.GENERATED, MergingStrategy.OVERWRITING);

        assertThatThrownBy(
                        () ->
                                util.mergeTables(
                                        mergingStrategies,
                                        sourceSchema,
                                        derivedColumns,
                                        Collections.emptyList(),
                                        null))
                .isInstanceOf(ValidationException.class)
                .hasMessage(
                        "A column named 'two' already exists in the table. "
                                + "Duplicate columns exist in the compute column "
                                + "and regular column. ");
    }

    @Test
    public void mergeOverwritingComputedColumnWithMetadataColumn() {
        TableSchema sourceSchema =
                TableSchema.builder()
                        .add(TableColumn.physical("one", DataTypes.INT()))
                        .add(TableColumn.computed("two", DataTypes.INT(), "one + 3"))
                        .build();

        List<SqlNode> derivedColumns =
                Collections.singletonList(metadataColumn("two", DataTypes.BOOLEAN(), false));

        Map<FeatureOption, MergingStrategy> mergingStrategies = getDefaultMergingStrategies();
        mergingStrategies.put(FeatureOption.METADATA, MergingStrategy.OVERWRITING);

        assertThatThrownBy(
                        () ->
                                util.mergeTables(
                                        mergingStrategies,
                                        sourceSchema,
                                        derivedColumns,
                                        Collections.emptyList(),
                                        null))
                .isInstanceOf(ValidationException.class)
                .hasMessage(
                        "A column named 'two' already exists in the base table."
                                + " Metadata columns can only overwrite other metadata columns.");
    }

    @Test
    public void mergeWatermarks() {
        TableSchema sourceSchema =
                TableSchema.builder()
                        .add(TableColumn.physical("one", DataTypes.INT()))
                        .add(TableColumn.computed("two", DataTypes.INT(), "one + 1"))
                        .add(TableColumn.physical("timestamp", DataTypes.TIMESTAMP()))
                        .watermark(
                                "timestamp",
                                "timestamp - INTERVAL '5' SECOND",
                                DataTypes.TIMESTAMP())
                        .build();

        List<SqlNode> derivedColumns =
                Arrays.asList(
                        regularColumn("three", DataTypes.INT()),
                        computedColumn("four", plus("one", "3")));

        TableSchema mergedSchema =
                util.mergeTables(
                        getDefaultMergingStrategies(),
                        sourceSchema,
                        derivedColumns,
                        Collections.emptyList(),
                        null);

        TableSchema expectedSchema =
                TableSchema.builder()
                        .add(TableColumn.physical("one", DataTypes.INT()))
                        .add(TableColumn.computed("two", DataTypes.INT(), "one + 1"))
                        .add(TableColumn.physical("timestamp", DataTypes.TIMESTAMP()))
                        .watermark(
                                "timestamp",
                                "timestamp - INTERVAL '5' SECOND",
                                DataTypes.TIMESTAMP())
                        .add(TableColumn.physical("three", DataTypes.INT()))
                        .add(TableColumn.computed("four", DataTypes.INT(), "`one` + 3"))
                        .build();

        assertThat(mergedSchema).isEqualTo(expectedSchema);
    }

    @Test
    public void mergeIncludingWatermarksFailsOnDuplicate() {
        TableSchema sourceSchema =
                TableSchema.builder()
                        .add(TableColumn.physical("one", DataTypes.INT()))
                        .add(TableColumn.physical("timestamp", DataTypes.TIMESTAMP()))
                        .watermark(
                                "timestamp",
                                "timestamp - INTERVAL '5' SECOND",
                                DataTypes.TIMESTAMP())
                        .build();

        List<SqlWatermark> derivedWatermarkSpecs =
                Collections.singletonList(
                        new SqlWatermark(
                                SqlParserPos.ZERO,
                                identifier("timestamp"),
                                boundedStrategy("timestamp", "10")));

        assertThatThrownBy(
                        () ->
                                util.mergeTables(
                                        getDefaultMergingStrategies(),
                                        sourceSchema,
                                        Collections.emptyList(),
                                        derivedWatermarkSpecs,
                                        null))
                .isInstanceOf(ValidationException.class)
                .hasMessage(
                        "There already exists a watermark spec for column 'timestamp' in the "
                                + "base table. You might want to specify EXCLUDING WATERMARKS "
                                + "or OVERWRITING WATERMARKS.");
    }

    @Test
    public void mergeExcludingWatermarksDuplicate() {
        TableSchema sourceSchema =
                TableSchema.builder()
                        .add(TableColumn.physical("one", DataTypes.INT()))
                        .add(TableColumn.physical("timestamp", DataTypes.TIMESTAMP()))
                        .watermark(
                                "timestamp",
                                "timestamp - INTERVAL '5' SECOND",
                                DataTypes.TIMESTAMP())
                        .build();

        List<SqlWatermark> derivedWatermarkSpecs =
                Collections.singletonList(
                        new SqlWatermark(
                                SqlParserPos.ZERO,
                                identifier("timestamp"),
                                boundedStrategy("timestamp", "10")));

        Map<FeatureOption, MergingStrategy> mergingStrategies = getDefaultMergingStrategies();
        mergingStrategies.put(FeatureOption.WATERMARKS, MergingStrategy.EXCLUDING);

        TableSchema mergedSchema =
                util.mergeTables(
                        mergingStrategies,
                        sourceSchema,
                        Collections.emptyList(),
                        derivedWatermarkSpecs,
                        null);

        TableSchema expectedSchema =
                TableSchema.builder()
                        .add(TableColumn.physical("one", DataTypes.INT()))
                        .add(TableColumn.physical("timestamp", DataTypes.TIMESTAMP()))
                        .watermark(
                                "timestamp",
                                "`timestamp` - INTERVAL '10' SECOND",
                                DataTypes.TIMESTAMP())
                        .build();

        assertThat(mergedSchema).isEqualTo(expectedSchema);
    }

    @Test
    public void mergeOverwritingWatermarksDuplicate() {
        TableSchema sourceSchema =
                TableSchema.builder()
                        .add(TableColumn.physical("one", DataTypes.INT()))
                        .add(TableColumn.physical("timestamp", DataTypes.TIMESTAMP()))
                        .watermark(
                                "timestamp",
                                "timestamp - INTERVAL '5' SECOND",
                                DataTypes.TIMESTAMP())
                        .build();

        List<SqlWatermark> derivedWatermarkSpecs =
                Collections.singletonList(
                        new SqlWatermark(
                                SqlParserPos.ZERO,
                                identifier("timestamp"),
                                boundedStrategy("timestamp", "10")));

        Map<FeatureOption, MergingStrategy> mergingStrategies = getDefaultMergingStrategies();
        mergingStrategies.put(FeatureOption.WATERMARKS, MergingStrategy.OVERWRITING);

        TableSchema mergedSchema =
                util.mergeTables(
                        mergingStrategies,
                        sourceSchema,
                        Collections.emptyList(),
                        derivedWatermarkSpecs,
                        null);

        TableSchema expectedSchema =
                TableSchema.builder()
                        .add(TableColumn.physical("one", DataTypes.INT()))
                        .add(TableColumn.physical("timestamp", DataTypes.TIMESTAMP()))
                        .watermark(
                                "timestamp",
                                "`timestamp` - INTERVAL '10' SECOND",
                                DataTypes.TIMESTAMP())
                        .build();

        assertThat(mergedSchema).isEqualTo(expectedSchema);
    }

    @Test
    public void mergeConstraintsFromBaseTable() {
        TableSchema sourceSchema =
                TableSchema.builder()
                        .add(TableColumn.physical("one", DataTypes.INT().notNull()))
                        .add(TableColumn.physical("two", DataTypes.STRING().notNull()))
                        .add(TableColumn.physical("three", DataTypes.FLOAT()))
                        .primaryKey("constraint-42", new String[] {"one", "two"})
                        .build();

        TableSchema mergedSchema =
                util.mergeTables(
                        getDefaultMergingStrategies(),
                        sourceSchema,
                        Collections.emptyList(),
                        Collections.emptyList(),
                        null);

        TableSchema expectedSchema =
                TableSchema.builder()
                        .add(TableColumn.physical("one", DataTypes.INT().notNull()))
                        .add(TableColumn.physical("two", DataTypes.STRING().notNull()))
                        .add(TableColumn.physical("three", DataTypes.FLOAT()))
                        .primaryKey("constraint-42", new String[] {"one", "two"})
                        .build();

        assertThat(mergedSchema).isEqualTo(expectedSchema);
    }

    @Test
    public void mergeConstraintsFromDerivedTable() {
        TableSchema sourceSchema =
                TableSchema.builder()
                        .add(TableColumn.physical("one", DataTypes.INT().notNull()))
                        .add(TableColumn.physical("two", DataTypes.STRING().notNull()))
                        .add(TableColumn.physical("three", DataTypes.FLOAT()))
                        .build();

        TableSchema mergedSchema =
                util.mergeTables(
                        getDefaultMergingStrategies(),
                        sourceSchema,
                        Collections.emptyList(),
                        Collections.emptyList(),
                        primaryKey("one", "two"));

        TableSchema expectedSchema =
                TableSchema.builder()
                        .add(TableColumn.physical("one", DataTypes.INT().notNull()))
                        .add(TableColumn.physical("two", DataTypes.STRING().notNull()))
                        .add(TableColumn.physical("three", DataTypes.FLOAT()))
                        .primaryKey("PK_3531879", new String[] {"one", "two"})
                        .build();

        assertThat(mergedSchema).isEqualTo(expectedSchema);
    }

    @Test
    public void mergeIncludingConstraintsFailsOnDuplicate() {
        TableSchema sourceSchema =
                TableSchema.builder()
                        .add(TableColumn.physical("one", DataTypes.INT().notNull()))
                        .add(TableColumn.physical("two", DataTypes.STRING().notNull()))
                        .add(TableColumn.physical("three", DataTypes.FLOAT()))
                        .primaryKey("constraint-42", new String[] {"one", "two"})
                        .build();

        assertThatThrownBy(
                        () ->
                                util.mergeTables(
                                        getDefaultMergingStrategies(),
                                        sourceSchema,
                                        Collections.emptyList(),
                                        Collections.emptyList(),
                                        primaryKey("one", "two")))
                .isInstanceOf(ValidationException.class)
                .hasMessage(
                        "The base table already has a primary key. You might want to specify "
                                + "EXCLUDING CONSTRAINTS.");
    }

    @Test
    public void mergeExcludingConstraintsOnDuplicate() {
        TableSchema sourceSchema =
                TableSchema.builder()
                        .add(TableColumn.physical("one", DataTypes.INT().notNull()))
                        .add(TableColumn.physical("two", DataTypes.STRING().notNull()))
                        .add(TableColumn.physical("three", DataTypes.FLOAT().notNull()))
                        .primaryKey("constraint-42", new String[] {"one", "two", "three"})
                        .build();

        Map<FeatureOption, MergingStrategy> mergingStrategies = getDefaultMergingStrategies();
        mergingStrategies.put(FeatureOption.CONSTRAINTS, MergingStrategy.EXCLUDING);

        TableSchema mergedSchema =
                util.mergeTables(
                        mergingStrategies,
                        sourceSchema,
                        Collections.emptyList(),
                        Collections.emptyList(),
                        primaryKey("one", "two"));

        TableSchema expectedSchema =
                TableSchema.builder()
                        .add(TableColumn.physical("one", DataTypes.INT().notNull()))
                        .add(TableColumn.physical("two", DataTypes.STRING().notNull()))
                        .add(TableColumn.physical("three", DataTypes.FLOAT().notNull()))
                        .primaryKey("PK_3531879", new String[] {"one", "two"})
                        .build();

        assertThat(mergedSchema).isEqualTo(expectedSchema);
    }

    @Test
    public void mergePartitionsFromBaseTable() {
        List<String> sourcePartitions = Arrays.asList("col1", "col2");
        List<String> mergePartitions =
                util.mergePartitions(
                        getDefaultMergingStrategies().get(FeatureOption.PARTITIONS),
                        sourcePartitions,
                        Collections.emptyList());

        assertThat(mergePartitions).isEqualTo(sourcePartitions);
    }

    @Test
    public void mergePartitionsFromDerivedTable() {
        List<String> derivedPartitions = Arrays.asList("col1", "col2");
        List<String> mergePartitions =
                util.mergePartitions(
                        getDefaultMergingStrategies().get(FeatureOption.PARTITIONS),
                        Collections.emptyList(),
                        derivedPartitions);

        assertThat(mergePartitions).isEqualTo(derivedPartitions);
    }

    @Test
    public void mergeIncludingPartitionsFailsOnDuplicate() {
        List<String> sourcePartitions = Arrays.asList("col3", "col4");
        List<String> derivedPartitions = Arrays.asList("col1", "col2");

        assertThatThrownBy(
                        () ->
                                util.mergePartitions(
                                        MergingStrategy.INCLUDING,
                                        sourcePartitions,
                                        derivedPartitions))
                .isInstanceOf(ValidationException.class)
                .hasMessage(
                        "The base table already has partitions defined. You might want "
                                + "to specify EXCLUDING PARTITIONS.");
    }

    @Test
    public void mergeExcludingPartitionsOnDuplicate() {
        List<String> sourcePartitions = Arrays.asList("col3", "col4");
        List<String> derivedPartitions = Arrays.asList("col1", "col2");

        List<String> mergedPartitions =
                util.mergePartitions(
                        MergingStrategy.EXCLUDING, sourcePartitions, derivedPartitions);

        assertThat(mergedPartitions).isEqualTo(derivedPartitions);
    }

    @Test
    public void mergeOptions() {
        Map<String, String> sourceOptions = new HashMap<>();
        sourceOptions.put("offset", "1");
        sourceOptions.put("format", "json");

        Map<String, String> derivedOptions = new HashMap<>();
        derivedOptions.put("format.ignore-errors", "true");

        Map<String, String> mergedOptions =
                util.mergeOptions(
                        getDefaultMergingStrategies().get(FeatureOption.OPTIONS),
                        sourceOptions,
                        derivedOptions);

        Map<String, String> expectedOptions = new HashMap<>();
        expectedOptions.put("offset", "1");
        expectedOptions.put("format", "json");
        expectedOptions.put("format.ignore-errors", "true");

        assertThat(mergedOptions).isEqualTo(expectedOptions);
    }

    @Test
    public void mergeIncludingOptionsFailsOnDuplicate() {
        Map<String, String> sourceOptions = new HashMap<>();
        sourceOptions.put("offset", "1");

        Map<String, String> derivedOptions = new HashMap<>();
        derivedOptions.put("offset", "2");

        assertThatThrownBy(
                        () ->
                                util.mergeOptions(
                                        MergingStrategy.INCLUDING, sourceOptions, derivedOptions))
                .isInstanceOf(ValidationException.class)
                .hasMessage(
                        "There already exists an option ['offset' -> '1']  in the base table. "
                                + "You might want to specify EXCLUDING OPTIONS or "
                                + "OVERWRITING OPTIONS.");
    }

    @Test
    public void mergeExcludingOptionsDuplicate() {
        Map<String, String> sourceOptions = new HashMap<>();
        sourceOptions.put("offset", "1");
        sourceOptions.put("format", "json");

        Map<String, String> derivedOptions = new HashMap<>();
        derivedOptions.put("format", "csv");
        derivedOptions.put("format.ignore-errors", "true");

        Map<String, String> mergedOptions =
                util.mergeOptions(MergingStrategy.EXCLUDING, sourceOptions, derivedOptions);

        Map<String, String> expectedOptions = new HashMap<>();
        expectedOptions.put("format", "csv");
        expectedOptions.put("format.ignore-errors", "true");

        assertThat(mergedOptions).isEqualTo(expectedOptions);
    }

    @Test
    public void mergeOverwritingOptionsDuplicate() {
        Map<String, String> sourceOptions = new HashMap<>();
        sourceOptions.put("offset", "1");
        sourceOptions.put("format", "json");

        Map<String, String> derivedOptions = new HashMap<>();
        derivedOptions.put("offset", "2");
        derivedOptions.put("format.ignore-errors", "true");

        Map<String, String> mergedOptions =
                util.mergeOptions(MergingStrategy.OVERWRITING, sourceOptions, derivedOptions);

        Map<String, String> expectedOptions = new HashMap<>();
        expectedOptions.put("offset", "2");
        expectedOptions.put("format", "json");
        expectedOptions.put("format.ignore-errors", "true");

        assertThat(mergedOptions).isEqualTo(expectedOptions);
    }

    @Test
    public void defaultMergeStrategies() {
        Map<FeatureOption, MergingStrategy> mergingStrategies =
                util.computeMergingStrategies(Collections.emptyList());

        assertThat(mergingStrategies.get(FeatureOption.OPTIONS))
                .isEqualTo(MergingStrategy.OVERWRITING);
        assertThat(mergingStrategies.get(FeatureOption.PARTITIONS))
                .isEqualTo(MergingStrategy.INCLUDING);
        assertThat(mergingStrategies.get(FeatureOption.CONSTRAINTS))
                .isEqualTo(MergingStrategy.INCLUDING);
        assertThat(mergingStrategies.get(FeatureOption.GENERATED))
                .isEqualTo(MergingStrategy.INCLUDING);
        assertThat(mergingStrategies.get(FeatureOption.WATERMARKS))
                .isEqualTo(MergingStrategy.INCLUDING);
    }

    @Test
    public void includingAllMergeStrategyExpansion() {
        List<SqlTableLikeOption> inputOptions =
                Collections.singletonList(
                        new SqlTableLikeOption(MergingStrategy.INCLUDING, FeatureOption.ALL));

        Map<FeatureOption, MergingStrategy> mergingStrategies =
                util.computeMergingStrategies(inputOptions);

        assertThat(mergingStrategies.get(FeatureOption.OPTIONS))
                .isEqualTo(MergingStrategy.INCLUDING);
        assertThat(mergingStrategies.get(FeatureOption.PARTITIONS))
                .isEqualTo(MergingStrategy.INCLUDING);
        assertThat(mergingStrategies.get(FeatureOption.CONSTRAINTS))
                .isEqualTo(MergingStrategy.INCLUDING);
        assertThat(mergingStrategies.get(FeatureOption.GENERATED))
                .isEqualTo(MergingStrategy.INCLUDING);
        assertThat(mergingStrategies.get(FeatureOption.WATERMARKS))
                .isEqualTo(MergingStrategy.INCLUDING);
    }

    @Test
    public void excludingAllMergeStrategyExpansion() {
        List<SqlTableLikeOption> inputOptions =
                Collections.singletonList(
                        new SqlTableLikeOption(MergingStrategy.EXCLUDING, FeatureOption.ALL));

        Map<FeatureOption, MergingStrategy> mergingStrategies =
                util.computeMergingStrategies(inputOptions);

        assertThat(mergingStrategies.get(FeatureOption.OPTIONS))
                .isEqualTo(MergingStrategy.EXCLUDING);
        assertThat(mergingStrategies.get(FeatureOption.PARTITIONS))
                .isEqualTo(MergingStrategy.EXCLUDING);
        assertThat(mergingStrategies.get(FeatureOption.CONSTRAINTS))
                .isEqualTo(MergingStrategy.EXCLUDING);
        assertThat(mergingStrategies.get(FeatureOption.GENERATED))
                .isEqualTo(MergingStrategy.EXCLUDING);
        assertThat(mergingStrategies.get(FeatureOption.WATERMARKS))
                .isEqualTo(MergingStrategy.EXCLUDING);
    }

    @Test
    public void includingAllOverwriteOptionsMergeStrategyExpansion() {
        List<SqlTableLikeOption> inputOptions =
                Arrays.asList(
                        new SqlTableLikeOption(MergingStrategy.EXCLUDING, FeatureOption.ALL),
                        new SqlTableLikeOption(
                                MergingStrategy.INCLUDING, FeatureOption.CONSTRAINTS));

        Map<FeatureOption, MergingStrategy> mergingStrategies =
                util.computeMergingStrategies(inputOptions);

        assertThat(mergingStrategies.get(FeatureOption.OPTIONS))
                .isEqualTo(MergingStrategy.EXCLUDING);
        assertThat(mergingStrategies.get(FeatureOption.PARTITIONS))
                .isEqualTo(MergingStrategy.EXCLUDING);
        assertThat(mergingStrategies.get(FeatureOption.CONSTRAINTS))
                .isEqualTo(MergingStrategy.INCLUDING);
        assertThat(mergingStrategies.get(FeatureOption.GENERATED))
                .isEqualTo(MergingStrategy.EXCLUDING);
        assertThat(mergingStrategies.get(FeatureOption.METADATA))
                .isEqualTo(MergingStrategy.EXCLUDING);
        assertThat(mergingStrategies.get(FeatureOption.WATERMARKS))
                .isEqualTo(MergingStrategy.EXCLUDING);
    }

    private Map<FeatureOption, MergingStrategy> getDefaultMergingStrategies() {
        return util.computeMergingStrategies(Collections.emptyList());
    }

    private SqlNode regularColumn(String name, DataType type) {
        LogicalType logicalType = type.getLogicalType();
        return new SqlRegularColumn(
                SqlParserPos.ZERO,
                identifier(name),
                null,
                SqlTypeUtil.convertTypeToSpec(
                                typeFactory.createFieldTypeFromLogicalType(logicalType))
                        .withNullable(logicalType.isNullable()),
                null);
    }

    private SqlNode computedColumn(String name, SqlNode expression) {
        return new SqlComputedColumn(SqlParserPos.ZERO, identifier(name), null, expression);
    }

    private SqlNode metadataColumn(String name, DataType type, boolean isVirtual) {
        final LogicalType logicalType = type.getLogicalType();
        return new SqlMetadataColumn(
                SqlParserPos.ZERO,
                identifier(name),
                null,
                SqlTypeUtil.convertTypeToSpec(
                                typeFactory.createFieldTypeFromLogicalType(logicalType))
                        .withNullable(logicalType.isNullable()),
                null,
                isVirtual);
    }

    private SqlNode plus(String column, String value) {
        return new SqlBasicCall(
                SqlStdOperatorTable.PLUS,
                new SqlNode[] {
                    identifier(column), SqlLiteral.createExactNumeric(value, SqlParserPos.ZERO)
                },
                SqlParserPos.ZERO);
    }

    private SqlNode boundedStrategy(String rowtimeColumn, String delay) {
        return new SqlBasicCall(
                SqlStdOperatorTable.MINUS,
                new SqlNode[] {
                    identifier(rowtimeColumn),
                    SqlLiteral.createInterval(
                            1,
                            delay,
                            new SqlIntervalQualifier(
                                    TimeUnit.SECOND, TimeUnit.SECOND, SqlParserPos.ZERO),
                            SqlParserPos.ZERO)
                },
                SqlParserPos.ZERO);
    }

    private SqlIdentifier identifier(String name) {
        return new SqlIdentifier(name, SqlParserPos.ZERO);
    }

    private SqlTableConstraint primaryKey(String... columns) {
        return new SqlTableConstraint(
                null,
                SqlUniqueSpec.PRIMARY_KEY.symbol(SqlParserPos.ZERO),
                new SqlNodeList(
                        Arrays.stream(columns).map(this::identifier).collect(Collectors.toList()),
                        SqlParserPos.ZERO),
                SqlConstraintEnforcement.ENFORCED.symbol(SqlParserPos.ZERO),
                true,
                SqlParserPos.ZERO);
    }
}

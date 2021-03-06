/*
 * Copyright 2019 Aiven Oy
 * Copyright 2016 Confluent Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.aiven.connect.elasticsearch;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.apache.kafka.connect.data.Decimal;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.data.Time;
import org.apache.kafka.connect.data.Timestamp;
import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.sink.SinkRecord;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

public class DataConverterTest {

    private DataConverter converter;
    private String key;
    private String topic;
    private int partition;
    private long offset;
    private String index;
    private String type;
    private Schema schema;

    @Before
    public void setUp() {
        converter = new DataConverter(true, DataConverter.BehaviorOnNullValues.DEFAULT);
        key = "key";
        topic = "topic";
        partition = 0;
        offset = 0;
        index = "index";
        type = "type";
        schema = SchemaBuilder
            .struct()
            .name("struct")
            .field("string", Schema.STRING_SCHEMA)
            .build();
    }

    @Test
    public void primitives() {
        assertIdenticalAfterPreProcess(Schema.INT8_SCHEMA);
        assertIdenticalAfterPreProcess(Schema.INT16_SCHEMA);
        assertIdenticalAfterPreProcess(Schema.INT32_SCHEMA);
        assertIdenticalAfterPreProcess(Schema.INT64_SCHEMA);
        assertIdenticalAfterPreProcess(Schema.FLOAT32_SCHEMA);
        assertIdenticalAfterPreProcess(Schema.FLOAT64_SCHEMA);
        assertIdenticalAfterPreProcess(Schema.BOOLEAN_SCHEMA);
        assertIdenticalAfterPreProcess(Schema.STRING_SCHEMA);
        assertIdenticalAfterPreProcess(Schema.BYTES_SCHEMA);

        assertIdenticalAfterPreProcess(Schema.OPTIONAL_INT16_SCHEMA);
        assertIdenticalAfterPreProcess(Schema.OPTIONAL_INT32_SCHEMA);
        assertIdenticalAfterPreProcess(Schema.OPTIONAL_INT64_SCHEMA);
        assertIdenticalAfterPreProcess(Schema.OPTIONAL_FLOAT32_SCHEMA);
        assertIdenticalAfterPreProcess(Schema.OPTIONAL_FLOAT64_SCHEMA);
        assertIdenticalAfterPreProcess(Schema.OPTIONAL_BOOLEAN_SCHEMA);
        assertIdenticalAfterPreProcess(Schema.OPTIONAL_STRING_SCHEMA);
        assertIdenticalAfterPreProcess(Schema.OPTIONAL_BYTES_SCHEMA);

        assertIdenticalAfterPreProcess(SchemaBuilder.int8().defaultValue((byte) 42).build());
        assertIdenticalAfterPreProcess(SchemaBuilder.int16().defaultValue((short) 42).build());
        assertIdenticalAfterPreProcess(SchemaBuilder.int32().defaultValue(42).build());
        assertIdenticalAfterPreProcess(SchemaBuilder.int64().defaultValue(42L).build());
        assertIdenticalAfterPreProcess(SchemaBuilder.float32().defaultValue(42.0f).build());
        assertIdenticalAfterPreProcess(SchemaBuilder.float64().defaultValue(42.0d).build());
        assertIdenticalAfterPreProcess(SchemaBuilder.bool().defaultValue(true).build());
        assertIdenticalAfterPreProcess(SchemaBuilder.string().defaultValue("foo").build());
        assertIdenticalAfterPreProcess(SchemaBuilder.bytes().defaultValue(new byte[0]).build());
    }

    private void assertIdenticalAfterPreProcess(final Schema schema) {
        assertEquals(schema, converter.preProcessSchema(schema));
    }

    @Test
    public void decimal() {
        final Schema origSchema = Decimal.schema(2);
        final Schema preProcessedSchema = converter.preProcessSchema(origSchema);
        assertEquals(Schema.FLOAT64_SCHEMA, preProcessedSchema);

        assertEquals(0.02, converter.preProcessValue(new BigDecimal("0.02"), origSchema, preProcessedSchema));

        // optional
        assertEquals(
            Schema.OPTIONAL_FLOAT64_SCHEMA,
            converter.preProcessSchema(Decimal.builder(2).optional().build())
        );

        // defval
        assertEquals(
            SchemaBuilder.float64().defaultValue(0.00).build(),
            converter.preProcessSchema(Decimal.builder(2).defaultValue(new BigDecimal("0.00")).build())
        );
    }

    @Test
    public void array() {
        final Schema origSchema = SchemaBuilder.array(Decimal.schema(2)).schema();
        final Schema preProcessedSchema = converter.preProcessSchema(origSchema);
        assertEquals(SchemaBuilder.array(Schema.FLOAT64_SCHEMA).build(), preProcessedSchema);

        assertEquals(
            Arrays.asList(0.02, 0.42),
            converter.preProcessValue(
                Arrays.asList(
                    new BigDecimal("0.02"), new BigDecimal("0.42")
                ),
                origSchema,
                preProcessedSchema
            )
        );

        // optional
        assertEquals(
            SchemaBuilder.array(preProcessedSchema.valueSchema()).optional().build(),
            converter.preProcessSchema(SchemaBuilder.array(Decimal.schema(2)).optional().build())
        );

        // defval
        assertEquals(
            SchemaBuilder.array(preProcessedSchema.valueSchema()).defaultValue(Collections.emptyList()).build(),
            converter.preProcessSchema(
                SchemaBuilder.array(Decimal.schema(2))
                    .defaultValue(Collections.emptyList()).build()
            )
        );
    }

    @Test
    public void map() {
        final Schema origSchema = SchemaBuilder.map(Schema.INT32_SCHEMA, Decimal.schema(2)).build();
        final Schema preProcessedSchema = converter.preProcessSchema(origSchema);
        assertEquals(
            SchemaBuilder.array(
                SchemaBuilder.struct().name(Schema.INT32_SCHEMA.type().name() + "-" + Decimal.LOGICAL_NAME)
                    .field(ElasticsearchSinkConnectorConstants.MAP_KEY, Schema.INT32_SCHEMA)
                    .field(ElasticsearchSinkConnectorConstants.MAP_VALUE, Schema.FLOAT64_SCHEMA)
                    .build()
            ).build(),
            preProcessedSchema
        );

        final Map<Object, Object> origValue = new HashMap<>();
        origValue.put(1, new BigDecimal("0.02"));
        origValue.put(2, new BigDecimal("0.42"));
        assertEquals(
            new HashSet<>(Arrays.asList(
                new Struct(preProcessedSchema.valueSchema())
                    .put(ElasticsearchSinkConnectorConstants.MAP_KEY, 1)
                    .put(ElasticsearchSinkConnectorConstants.MAP_VALUE, 0.02),
                new Struct(preProcessedSchema.valueSchema())
                    .put(ElasticsearchSinkConnectorConstants.MAP_KEY, 2)
                    .put(ElasticsearchSinkConnectorConstants.MAP_VALUE, 0.42)
            )),
            new HashSet<>((List<?>) converter.preProcessValue(origValue, origSchema, preProcessedSchema))
        );

        // optional
        assertEquals(
            SchemaBuilder.array(preProcessedSchema.valueSchema()).optional().build(),
            converter.preProcessSchema(SchemaBuilder.map(Schema.INT32_SCHEMA, Decimal.schema(2)).optional().build())
        );

        // defval
        assertEquals(
            SchemaBuilder.array(
                preProcessedSchema.valueSchema())
                .defaultValue(Collections.emptyList())
                .build(),
            converter.preProcessSchema(
                SchemaBuilder.map(Schema.INT32_SCHEMA, Decimal.schema(2))
                    .defaultValue(Collections.emptyMap())
                    .build()
            )
        );
    }

    @Test
    public void stringKeyedMapNonCompactFormat() {
        final Schema origSchema = SchemaBuilder.map(Schema.STRING_SCHEMA, Schema.INT32_SCHEMA).build();

        final Map<Object, Object> origValue = new HashMap<>();
        origValue.put("field1", 1);
        origValue.put("field2", 2);

        // Use the older non-compact format for map entries with string keys
        converter = new DataConverter(false, DataConverter.BehaviorOnNullValues.DEFAULT);

        final Schema preProcessedSchema = converter.preProcessSchema(origSchema);
        assertEquals(
            SchemaBuilder.array(
                SchemaBuilder.struct().name(
                    Schema.STRING_SCHEMA.type().name()
                        + "-"
                        + Schema.INT32_SCHEMA.type().name()
                ).field(ElasticsearchSinkConnectorConstants.MAP_KEY, Schema.STRING_SCHEMA)
                 .field(ElasticsearchSinkConnectorConstants.MAP_VALUE, Schema.INT32_SCHEMA)
                 .build()
            ).build(),
            preProcessedSchema
        );
        assertEquals(
            new HashSet<>(Arrays.asList(
                new Struct(preProcessedSchema.valueSchema())
                    .put(ElasticsearchSinkConnectorConstants.MAP_KEY, "field1")
                    .put(ElasticsearchSinkConnectorConstants.MAP_VALUE, 1),
                new Struct(preProcessedSchema.valueSchema())
                    .put(ElasticsearchSinkConnectorConstants.MAP_KEY, "field2")
                    .put(ElasticsearchSinkConnectorConstants.MAP_VALUE, 2)
            )),
            new HashSet<>((List<?>) converter.preProcessValue(origValue, origSchema, preProcessedSchema))
        );
    }

    @Test
    public void stringKeyedMapCompactFormat() {
        final Schema origSchema = SchemaBuilder.map(Schema.STRING_SCHEMA, Schema.INT32_SCHEMA).build();

        final Map<Object, Object> origValue = new HashMap<>();
        origValue.put("field1", 1);
        origValue.put("field2", 2);

        // Use the newer compact format for map entries with string keys
        converter = new DataConverter(true, DataConverter.BehaviorOnNullValues.DEFAULT);
        final Schema preProcessedSchema = converter.preProcessSchema(origSchema);
        assertEquals(
            SchemaBuilder.map(Schema.STRING_SCHEMA, Schema.INT32_SCHEMA).build(),
            preProcessedSchema
        );
        final HashMap<?, ?> newValue = (HashMap<?, ?>)
            converter.preProcessValue(origValue, origSchema, preProcessedSchema);
        assertEquals(origValue, newValue);
    }

    @Test
    public void struct() {
        final Schema origSchema = SchemaBuilder.struct().name("struct").field("decimal", Decimal.schema(2)).build();
        final Schema preProcessedSchema = converter.preProcessSchema(origSchema);
        assertEquals(
            SchemaBuilder.struct().name("struct").field("decimal", Schema.FLOAT64_SCHEMA).build(),
            preProcessedSchema
        );

        assertEquals(
            new Struct(preProcessedSchema).put("decimal", 0.02),
            converter.preProcessValue(
                new Struct(origSchema)
                    .put("decimal", new BigDecimal("0.02")),
                origSchema,
                preProcessedSchema
            )
        );

        // optional
        assertEquals(
            SchemaBuilder.struct().name("struct").field("decimal", Schema.FLOAT64_SCHEMA).optional().build(),
            converter.preProcessSchema(
                SchemaBuilder.struct().name("struct").field("decimal", Decimal.schema(2))
                    .optional()
                    .build()
            )
        );
    }

    @Test
    public void optionalFieldsWithoutDefaults() {
        // One primitive type should be enough
        testOptionalFieldWithoutDefault(SchemaBuilder.bool());
        // Logical types
        testOptionalFieldWithoutDefault(Decimal.builder(2));
        testOptionalFieldWithoutDefault(Time.builder());
        testOptionalFieldWithoutDefault(Timestamp.builder());
        // Complex types
        testOptionalFieldWithoutDefault(SchemaBuilder.array(Schema.BOOLEAN_SCHEMA));
        testOptionalFieldWithoutDefault(SchemaBuilder.struct().field("innerField", Schema.BOOLEAN_SCHEMA));
        testOptionalFieldWithoutDefault(SchemaBuilder.map(Schema.STRING_SCHEMA, Schema.BOOLEAN_SCHEMA));
        // Have to test maps with useCompactMapEntries set to true and set to false
        converter = new DataConverter(false, DataConverter.BehaviorOnNullValues.DEFAULT);
        testOptionalFieldWithoutDefault(SchemaBuilder.map(Schema.STRING_SCHEMA, Schema.BOOLEAN_SCHEMA));
    }

    private void testOptionalFieldWithoutDefault(final SchemaBuilder optionalFieldSchema) {
        final Schema origSchema = SchemaBuilder.struct().name("struct").field(
            "optionalField", optionalFieldSchema.optional().build()
        ).build();
        final Schema preProcessedSchema = converter.preProcessSchema(origSchema);

        final Object preProcessedValue = converter.preProcessValue(
            new Struct(origSchema).put("optionalField", null), origSchema, preProcessedSchema
        );

        assertEquals(new Struct(preProcessedSchema).put("optionalField", null), preProcessedValue);
    }

    @Test
    public void ignoreOnNullValue() {
        converter = new DataConverter(true, DataConverter.BehaviorOnNullValues.IGNORE);

        final SinkRecord sinkRecord = createSinkRecordWithValue(null);
        assertNull(converter.convertRecord(sinkRecord, index, type, false, false));
    }

    @Test
    public void deleteOnNullValue() {
        converter = new DataConverter(true, DataConverter.BehaviorOnNullValues.DELETE);

        final SinkRecord sinkRecord = createSinkRecordWithValue(null);
        final IndexableRecord expectedRecord = createIndexableRecordWithPayload(null);
        final IndexableRecord actualRecord = converter.convertRecord(sinkRecord, index, type, false, false);

        assertEquals(expectedRecord, actualRecord);
    }

    @Test
    public void ignoreDeleteOnNullValueWithNullKey() {
        converter = new DataConverter(true, DataConverter.BehaviorOnNullValues.DELETE);
        key = null;

        final SinkRecord sinkRecord = createSinkRecordWithValue(null);
        assertNull(converter.convertRecord(sinkRecord, index, type, false, false));
    }

    @Test
    public void failOnNullValue() {
        converter = new DataConverter(true, DataConverter.BehaviorOnNullValues.FAIL);

        final SinkRecord sinkRecord = createSinkRecordWithValue(null);
        //FIXME asdsadasd
        try {
            converter.convertRecord(sinkRecord, index, type, false, false);
            fail("should fail on null-valued record with behaviorOnNullValues = FAIL");
        } catch (final DataException e) {
            // expected
        }
    }

    public SinkRecord createSinkRecordWithValue(final Object value) {
        return new SinkRecord(topic, partition, Schema.STRING_SCHEMA, key, schema, value, offset);
    }

    public IndexableRecord createIndexableRecordWithPayload(final String payload) {
        return new IndexableRecord(new Key(index, type, key), payload, offset);
    }

}

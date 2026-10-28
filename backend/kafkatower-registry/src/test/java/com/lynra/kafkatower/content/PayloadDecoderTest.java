package com.lynra.kafkatower.content;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.EncoderFactory;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PayloadDecoderTest {

    private static final String AVRO_SCHEMA = """
            {"type":"record","name":"Order","fields":[{"name":"id","type":"int"},{"name":"name","type":"string"}]}""";

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] avroOrder() throws Exception {
        Schema schema = new Schema.Parser().parse(AVRO_SCHEMA);
        GenericRecord r = new GenericData.Record(schema);
        r.put("id", 7);
        r.put("name", "bob");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        var enc = EncoderFactory.get().binaryEncoder(out, null);
        new GenericDatumWriter<GenericRecord>(schema).write(r, enc);
        enc.flush();
        return out.toByteArray();
    }

    @Test
    void stringIsAlwaysDecoded() {
        var d = PayloadDecoder.create(ContentFormat.STRING, null).decode(bytes("hello"));
        assertEquals("hello", d.text());
        assertTrue(d.decoded());
    }

    @Test
    void jsonIsCompactedAndInvalidJsonFallsBackToRawString() {
        var decoder = PayloadDecoder.create(ContentFormat.JSON, null);
        assertEquals("{\"a\":1}", decoder.decode(bytes("{ \"a\" : 1 }")).text());

        var bad = decoder.decode(bytes("not json"));
        assertEquals("not json", bad.text());
        assertFalse(bad.decoded());
    }

    @Test
    void schemaFormatsWithoutSchemaFallBackToRawString() {
        var d = PayloadDecoder.create(ContentFormat.AVRO, null).decode(bytes("raw"));
        assertEquals("raw", d.text());
        assertFalse(d.decoded());
    }

    @Test
    void avroDecodesPlainAndConfluentFramedPayloads() throws Exception {
        var decoder = PayloadDecoder.create(ContentFormat.AVRO,
                new TopicSchema("c", "t", ContentFormat.AVRO, null, AVRO_SCHEMA));
        byte[] payload = avroOrder();
        assertEquals("{\"id\":7,\"name\":\"bob\"}", decoder.decode(payload).text());

        byte[] framed = new byte[payload.length + 5];
        System.arraycopy(payload, 0, framed, 5, payload.length); // magic 0 + schema id 0
        var framedResult = decoder.decode(framed);
        assertEquals("{\"id\":7,\"name\":\"bob\"}", framedResult.text());
        assertTrue(framedResult.decoded());
    }

    @Test
    void avroWithLeftoverBytesFallsBackToRawInsteadOfDecodingGarbage() {
        var decoder = PayloadDecoder.create(ContentFormat.AVRO,
                new TopicSchema("c", "t", ContentFormat.AVRO, null, "{\"type\":\"record\",\"name\":\"O\",\"fields\":[{\"name\":\"id\",\"type\":\"int\"}]}"));
        var d = decoder.decode(bytes("{\"status\":\"PAID\"}"));
        assertEquals("{\"status\":\"PAID\"}", d.text());
        assertFalse(d.decoded());
    }

    @Test
    void invalidAvroSchemaIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> PayloadDecoder.create(ContentFormat.AVRO,
                new TopicSchema("c", "t", ContentFormat.AVRO, null, "{not a schema")));
    }

    @Test
    void protobufDecodesWithADescriptorSet() throws Exception {
        FileDescriptorProto file = FileDescriptorProto.newBuilder().setName("order.proto").setPackage("shop").setSyntax("proto3")
                .addMessageType(DescriptorProto.newBuilder().setName("Order")
                        .addField(FieldDescriptorProto.newBuilder().setName("id").setNumber(1)
                                .setType(FieldDescriptorProto.Type.TYPE_INT32).setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL))
                        .addField(FieldDescriptorProto.newBuilder().setName("name").setNumber(2)
                                .setType(FieldDescriptorProto.Type.TYPE_STRING).setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)))
                .build();
        String desc = Base64.getEncoder().encodeToString(FileDescriptorSet.newBuilder().addFile(file).build().toByteArray());
        var order = FileDescriptor.buildFrom(file, new FileDescriptor[0]).findMessageTypeByName("Order");
        var message = DynamicMessage.newBuilder(order)
                .setField(order.findFieldByName("id"), 7)
                .setField(order.findFieldByName("name"), "bob")
                .build();

        var decoder = PayloadDecoder.create(ContentFormat.PROTOBUF, new TopicSchema("c", "t", ContentFormat.PROTOBUF, "shop.Order", desc));
        var d = decoder.decode(message.toByteArray());
        assertEquals("{\"id\":7,\"name\":\"bob\"}", d.text());
        assertTrue(d.decoded());

        assertThrows(IllegalArgumentException.class, () -> PayloadDecoder.create(ContentFormat.PROTOBUF,
                new TopicSchema("c", "t", ContentFormat.PROTOBUF, "Missing", desc)));
    }
}

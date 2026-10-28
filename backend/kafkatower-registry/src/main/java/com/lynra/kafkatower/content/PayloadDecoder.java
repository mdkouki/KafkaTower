package com.lynra.kafkatower.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.util.JsonFormat;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.io.BinaryDecoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.io.JsonEncoder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Turns a record value's bytes into text. Structured formats render as compact JSON; anything that
 * can't be decoded (no schema, wrong schema, not JSON) falls back to a plain UTF-8 string and is
 * flagged {@code decoded=false} so the UI can say so.
 */
public final class PayloadDecoder {

    public record Decoded(String text, boolean decoded) {
    }

    private interface Mapper {
        String apply(byte[] bytes) throws Exception;
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final PayloadDecoder PLAIN = new PayloadDecoder(null, false);

    private final Mapper mapper;
    private final boolean rawIsDecoded;

    private PayloadDecoder(Mapper mapper, boolean rawIsDecoded) {
        this.mapper = mapper;
        this.rawIsDecoded = rawIsDecoded;
    }

    /** A decoder that only renders UTF-8 and reports it as not decoded — used when a needed schema is absent. */
    public static PayloadDecoder plain() {
        return PLAIN;
    }

    /** @throws IllegalArgumentException if a schema is required but invalid */
    public static PayloadDecoder create(ContentFormat format, TopicSchema schema) {
        if (format == ContentFormat.STRING) {
            return new PayloadDecoder(null, true);
        }
        if (format == ContentFormat.JSON) {
            return new PayloadDecoder(b -> JSON.readTree(b).toString(), false);
        }
        if (schema == null || schema.getFormat() != format) {
            return PLAIN;
        }
        try {
            return format == ContentFormat.AVRO ? avro(schema.getContent()) : protobuf(schema);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid " + format + " schema: " + e.getMessage(), e);
        }
    }

    public Decoded decode(byte[] bytes) {
        String raw = new String(bytes, StandardCharsets.UTF_8);
        if (mapper == null) {
            return new Decoded(raw, rawIsDecoded);
        }
        try {
            return new Decoded(mapper.apply(bytes), true);
        } catch (Exception e) {
            return new Decoded(raw, false);
        }
    }

    private static PayloadDecoder avro(String schemaJson) {
        Schema schema = new Schema.Parser().parse(schemaJson);
        GenericDatumReader<Object> reader = new GenericDatumReader<>(schema);
        return new PayloadDecoder(bytes -> {
            // Confluent wire format: magic byte 0 + 4-byte schema id before the Avro payload.
            if (bytes.length > 5 && bytes[0] == 0) {
                try {
                    return avroJson(reader, schema, bytes, 5);
                } catch (Exception ignored) {
                    // not framed after all — fall through to a plain Avro decode
                }
            }
            return avroJson(reader, schema, bytes, 0);
        }, false);
    }

    private static String avroJson(GenericDatumReader<Object> reader, Schema schema, byte[] bytes, int offset) throws IOException {
        BinaryDecoder in = DecoderFactory.get().binaryDecoder(bytes, offset, bytes.length - offset, null);
        Object datum = reader.read(null, in);
        if (!in.isEnd()) {
            // Avro ignores trailing bytes, so a wrong schema can "decode" garbage — insist on a full match.
            throw new IOException("payload has bytes left over after the schema");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JsonEncoder encoder = EncoderFactory.get().jsonEncoder(schema, out);
        new GenericDatumWriter<>(schema).write(datum, encoder);
        encoder.flush();
        return out.toString(StandardCharsets.UTF_8);
    }

    private static PayloadDecoder protobuf(TopicSchema schema) throws Exception {
        FileDescriptorSet set = FileDescriptorSet.parseFrom(Base64.getDecoder().decode(schema.getContent()));
        Descriptor message = findMessage(set, schema.getMessageName());
        JsonFormat.Printer printer = JsonFormat.printer().omittingInsignificantWhitespace();
        return new PayloadDecoder(bytes -> printer.print(DynamicMessage.parseFrom(message, bytes)), false);
    }

    /** Builds every file in the set (protoc --include_imports orders dependencies first) and picks the named message. */
    private static Descriptor findMessage(FileDescriptorSet set, String name) throws Exception {
        Map<String, FileDescriptor> built = new HashMap<>();
        FileDescriptor last = null;
        for (FileDescriptorProto proto : set.getFileList()) {
            FileDescriptor[] deps = proto.getDependencyList().stream().map(built::get).toArray(FileDescriptor[]::new);
            last = FileDescriptor.buildFrom(proto, deps);
            built.put(proto.getName(), last);
        }
        if (last == null) {
            throw new IllegalArgumentException("descriptor set is empty");
        }
        if (name == null || name.isBlank()) {
            if (last.getMessageTypes().isEmpty()) {
                throw new IllegalArgumentException("no message type found; provide a message name");
            }
            return last.getMessageTypes().get(0);
        }
        for (FileDescriptor file : built.values()) {
            for (Descriptor d : file.getMessageTypes()) {
                if (d.getFullName().equals(name) || d.getName().equals(name)) {
                    return d;
                }
            }
        }
        throw new IllegalArgumentException("message '" + name + "' not found in descriptor set");
    }
}

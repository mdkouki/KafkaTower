package com.lynra.kafkatower.content;

/** How a record value's bytes are interpreted. AVRO and PROTOBUF need an uploaded schema. */
public enum ContentFormat {
    STRING, JSON, AVRO, PROTOBUF;

    public boolean needsSchema() {
        return this == AVRO || this == PROTOBUF;
    }
}

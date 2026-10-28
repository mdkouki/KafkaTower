-- Uploaded Avro / Protobuf schemas used by the topic Content tab to decode record values.
-- One schema per (cluster, topic); protobuf descriptor sets are stored base64-encoded.
CREATE TABLE IF NOT EXISTS topic_schemas (
    id           VARCHAR(600) PRIMARY KEY,
    cluster_name VARCHAR(255) NOT NULL,
    topic        VARCHAR(255) NOT NULL,
    format       VARCHAR(20)  NOT NULL,
    message_name VARCHAR(500),
    content      TEXT         NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL
);

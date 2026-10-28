package com.lynra.kafkatower.content;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;

/** The uploaded schema for one topic on one cluster. {@code content} is Avro JSON, or base64 for a protobuf descriptor set. */
@Entity
@Table(name = "topic_schemas")
public class TopicSchema {

    @Id
    private String id;

    @Column(nullable = false)
    private String clusterName;

    @Column(nullable = false)
    private String topic;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ContentFormat format;

    private String messageName;

    @Lob
    @Column(nullable = false, columnDefinition = "CLOB")
    private String content;

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    protected TopicSchema() {
    }

    public TopicSchema(String clusterName, String topic, ContentFormat format, String messageName, String content) {
        this.id = idOf(clusterName, topic);
        this.clusterName = clusterName;
        this.topic = topic;
        this.format = format;
        this.messageName = messageName;
        this.content = content;
    }

    public static String idOf(String clusterName, String topic) {
        return clusterName + "::" + topic;
    }

    public ContentFormat getFormat() {
        return format;
    }

    public String getMessageName() {
        return messageName;
    }

    public String getContent() {
        return content;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

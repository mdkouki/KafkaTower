package com.lynra.kafkatower.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One admin-console settings section (e.g. {@code opensearch}, {@code metrics}) stored as JSON. */
@Entity
@Table(name = "admin_settings")
public class AdminSetting {

    @Id
    @Column(name = "section", length = 32)
    private String section;

    // Plain text column. Not @Lob: on PostgreSQL Hibernate would store a large object and put only
    // its OID in the column, which the startup reader (plain JDBC) then reads as a number.
    @Column(name = "payload", length = 1_000_000)
    private String payload;

    public AdminSetting() {}

    public AdminSetting(String section, String payload) {
        this.section = section;
        this.payload = payload;
    }

    public String getSection() { return section; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
}

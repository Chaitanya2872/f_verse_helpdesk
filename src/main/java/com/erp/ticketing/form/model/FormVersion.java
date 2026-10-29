package com.erp.ticketing.form.model;

import com.erp.ticketing.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/**
 * One immutable-once-published version of a request type's form. Tickets keep the
 * id of the version they were submitted against, so editing the form later never
 * changes how old tickets are validated or displayed (BRS 5.2).
 */
@Entity
@Table(name = "form_versions")
@Getter
@Setter
public class FormVersion extends BaseEntity {

    public enum Status { DRAFT, PUBLISHED, RETIRED }

    @Column(name = "ticket_type_id", nullable = false)
    private Long ticketTypeId;

    @Column(name = "version", nullable = false)
    private int version;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.DRAFT;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "schema_json", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> schema;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "published_at")
    private Instant publishedAt;
}

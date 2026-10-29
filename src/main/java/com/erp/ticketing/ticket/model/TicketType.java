package com.erp.ticketing.ticket.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "ticket_types")
@Getter
@Setter
public class TicketType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, unique = true, length = 100)
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "instructions")
    private String instructions;

    @Column(name = "department_id")
    private Long departmentId;

    @Column(name = "service_owner_id")
    private Long serviceOwnerId;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "lifecycle_status", nullable = false, length = 20)
    private Lifecycle lifecycleStatus = Lifecycle.PUBLISHED;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "audience", nullable = false, length = 20)
    private Audience audience = Audience.ALL;

    @Column(name = "keywords")
    private String keywords;

    @Column(name = "icon", length = 50)
    private String icon;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "default_confidentiality", nullable = false, length = 20)
    private Confidentiality defaultConfidentiality = Confidentiality.STANDARD;

    /** DRAFT and ARCHIVED are hidden from requesters; RETIRED keeps history but takes no new requests. */
    public enum Lifecycle { DRAFT, PUBLISHED, RETIRED, ARCHIVED }

    public enum Audience { ALL, EMPLOYEE, CUSTOMER }
}

package com.erp.ticketing.sla.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;

/**
 * A named working-hours calendar. {@code workingHoursJson} is null (or an
 * absent day key within it) to mean "always open" / "day off" respectively.
 * Shape: {"monday":{"start":"09:00","end":"18:00"}, ..., "sunday": null} -
 * see {@code SlaCalculationService} for the parsing contract. This is a plain
 * reference-data entity (no created_at column), same pattern as TicketType/Category.
 */
@Entity
@Table(name = "sla_calendars")
@Getter
@Setter
public class SlaCalendar {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "timezone", nullable = false, length = 100)
    private String timezone = "UTC";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "working_hours_json", columnDefinition = "jsonb")
    private Map<String, Object> workingHoursJson;

    /** ISO dates (yyyy-MM-dd) on which the calendar is closed: holidays and exceptional closures. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "holidays_json", columnDefinition = "jsonb")
    private java.util.List<String> holidaysJson;
}

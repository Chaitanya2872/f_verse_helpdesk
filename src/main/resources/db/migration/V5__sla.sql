CREATE TABLE sla_calendars (
    id                  BIGSERIAL PRIMARY KEY,
    name                VARCHAR(255) NOT NULL,
    timezone            VARCHAR(100) NOT NULL DEFAULT 'UTC',
    working_hours_json  JSONB
);

CREATE TABLE sla_policies (
    id                      BIGSERIAL PRIMARY KEY,
    name                    VARCHAR(255) NOT NULL,
    description             TEXT,
    conditions_json         JSONB,
    first_response_minutes  INT NOT NULL,
    resolution_minutes      INT NOT NULL,
    calendar_id             BIGINT REFERENCES sla_calendars(id),
    is_active               BOOLEAN NOT NULL DEFAULT true
);

ALTER TABLE tickets ADD CONSTRAINT fk_tickets_sla_policy FOREIGN KEY (sla_policy_id) REFERENCES sla_policies(id);
ALTER TABLE tickets ADD COLUMN sla_breached BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE tickets ADD COLUMN sla_warning_sent BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE tickets ADD COLUMN sla_breach_notified BOOLEAN NOT NULL DEFAULT false;

-- 24x7 calendar: working_hours_json is NULL, meaning "always open" - SlaCalculationService
-- treats a null/absent calendar or a null working_hours_json as "add minutes directly, no
-- working-hours walk needed".
INSERT INTO sla_calendars (name, timezone, working_hours_json)
VALUES ('24x7', 'UTC', NULL);

-- Business-hours calendar: Mon-Fri 09:00-18:00 UTC, weekends closed.
-- Shape: {"monday":{"start":"09:00","end":"18:00"}, ..., "sunday": null} - a missing or
-- null day means non-working (see SlaCalculationService for the parsing contract).
INSERT INTO sla_calendars (name, timezone, working_hours_json)
VALUES ('Business Hours (Mon-Fri 09:00-18:00 UTC)', 'UTC',
        '{"monday":{"start":"09:00","end":"18:00"},"tuesday":{"start":"09:00","end":"18:00"},"wednesday":{"start":"09:00","end":"18:00"},"thursday":{"start":"09:00","end":"18:00"},"friday":{"start":"09:00","end":"18:00"},"saturday":null,"sunday":null}'::jsonb);

-- P1-specific policy: tight minutes, measured against business hours only.
INSERT INTO sla_policies (name, description, conditions_json, first_response_minutes, resolution_minutes, calendar_id, is_active)
VALUES ('P1 Critical', 'Tight SLA applied to P1 priority tickets, measured in business hours',
        '{"priority":"P1"}'::jsonb, 30, 240,
        (SELECT id FROM sla_calendars WHERE name = 'Business Hours (Mon-Fri 09:00-18:00 UTC)'), true);

-- Wildcard default policy (conditions_json NULL matches every ticket that no more specific
-- policy already claimed), generous minutes, 24x7 calendar so every ticket resolves to *some* policy.
INSERT INTO sla_policies (name, description, conditions_json, first_response_minutes, resolution_minutes, calendar_id, is_active)
VALUES ('Default SLA', 'Wildcard default SLA applied when no more specific policy matches', NULL, 480, 2880,
        (SELECT id FROM sla_calendars WHERE name = '24x7'), true);

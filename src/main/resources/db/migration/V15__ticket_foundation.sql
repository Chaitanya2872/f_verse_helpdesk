-- Enterprise helpdesk BRS/FRS phase 1 foundation: lifecycle states, ticket
-- identity, confidentiality, resolution data, assignment history, audit.

-- Spec 6.3 lifecycle. NEW is the "Submitted" state (kept as NEW so existing rows
-- and clients need no data migration; the UI labels it "Submitted").
ALTER TABLE tickets DROP CONSTRAINT tickets_status_check;
ALTER TABLE tickets ADD CONSTRAINT tickets_status_check CHECK (status IN (
    'NEW','TRIAGE','ASSIGNED','IN_PROGRESS','WAITING_CUSTOMER','WAITING_APPROVAL','WAITING_VENDOR',
    'RESOLVED','CLOSED','CANCELLED','REOPENED'));

-- Spec 6.1: source channel list.
ALTER TABLE tickets DROP CONSTRAINT tickets_source_check;
ALTER TABLE tickets ADD CONSTRAINT tickets_source_check CHECK (source IN (
    'PORTAL','EMAIL','API','IN_ERP','CUSTOMER_MOBILE','AGENT'));

-- Immutable internal UUID (spec 6.1) and optimistic locking (spec 20.1).
ALTER TABLE tickets ADD COLUMN public_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE tickets ADD CONSTRAINT uq_tickets_public_id UNIQUE (public_id);
ALTER TABLE tickets ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- Requester vs creator are recorded separately for on-behalf tickets (spec 4.4).
ALTER TABLE tickets ADD COLUMN creator_id BIGINT REFERENCES users(id);
UPDATE tickets SET creator_id = requester_id WHERE creator_id IS NULL;

-- Confidentiality classification (spec 6.1, 7.1).
ALTER TABLE tickets ADD COLUMN confidentiality VARCHAR(20) NOT NULL DEFAULT 'STANDARD'
    CHECK (confidentiality IN ('STANDARD','RESTRICTED','CONFIDENTIAL'));

-- Resolution record (spec 6.1, 6.3).
ALTER TABLE tickets ADD COLUMN resolution_code VARCHAR(50);
ALTER TABLE tickets ADD COLUMN resolution_summary TEXT;
ALTER TABLE tickets ADD COLUMN resolved_by BIGINT REFERENCES users(id);
ALTER TABLE tickets ADD COLUMN reopen_count INT NOT NULL DEFAULT 0;

-- Impact / urgency inputs for priority (spec 8.2).
ALTER TABLE tickets ADD COLUMN impact VARCHAR(10) CHECK (impact IN ('LOW','MEDIUM','HIGH'));
ALTER TABLE tickets ADD COLUMN urgency VARCHAR(10) CHECK (urgency IN ('LOW','MEDIUM','HIGH'));

-- External references: customer case id, order id, dev issue id... (spec 6.1, 16.4).
ALTER TABLE tickets ADD COLUMN external_ref VARCHAR(100);
CREATE INDEX idx_tickets_external_ref ON tickets (external_ref) WHERE external_ref IS NOT NULL;
CREATE INDEX idx_tickets_confidentiality ON tickets (confidentiality) WHERE confidentiality <> 'STANDARD';
CREATE INDEX idx_tickets_department_status ON tickets (department_id, status);
CREATE INDEX idx_tickets_resolution_due ON tickets (resolution_due) WHERE resolution_due IS NOT NULL;

-- Per-user grants onto restricted/confidential tickets: explicitly assigned
-- investigators, authorised HR roles (spec 7.1). Ordinary staff see none of these.
CREATE TABLE ticket_access_grants (
    id          BIGSERIAL PRIMARY KEY,
    ticket_id   BIGINT NOT NULL REFERENCES tickets(id),
    user_id     BIGINT NOT NULL REFERENCES users(id),
    granted_by  BIGINT REFERENCES users(id),
    reason      VARCHAR(500),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (ticket_id, user_id)
);

-- Assignment history (spec 6.4, 11.2, AT-007).
CREATE TABLE ticket_assignments (
    id                  BIGSERIAL PRIMARY KEY,
    ticket_id           BIGINT NOT NULL REFERENCES tickets(id),
    from_department_id  BIGINT REFERENCES departments(id),
    to_department_id    BIGINT REFERENCES departments(id),
    from_assignee_id    BIGINT REFERENCES users(id),
    to_assignee_id      BIGINT REFERENCES users(id),
    actor_id            BIGINT REFERENCES users(id),
    reason              VARCHAR(500),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_ticket_assignments_ticket ON ticket_assignments (ticket_id, created_at);

-- Audit trail for administrative and sensitive events (spec 18.3). Append-only:
-- the application never updates or deletes rows, and the trigger enforces it.
CREATE TABLE audit_events (
    id              BIGSERIAL PRIMARY KEY,
    actor_id        BIGINT REFERENCES users(id),
    action          VARCHAR(60) NOT NULL,
    target_type     VARCHAR(60) NOT NULL,
    target_id       VARCHAR(100),
    source_channel  VARCHAR(30),
    correlation_id  VARCHAR(100),
    before_json     JSONB,
    after_json      JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_events_target ON audit_events (target_type, target_id, created_at);
CREATE INDEX idx_audit_events_created ON audit_events (created_at);

CREATE FUNCTION audit_events_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_events is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_events_immutable
    BEFORE UPDATE OR DELETE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION audit_events_immutable();

-- Seed the first assignment row for existing tickets so history is never empty.
INSERT INTO ticket_assignments (ticket_id, to_department_id, to_assignee_id, reason, created_at)
SELECT id, department_id, assignee_id, 'Initial (backfilled)', created_at FROM tickets;

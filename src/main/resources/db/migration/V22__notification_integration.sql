-- Integration with the external notification-service: who is told about which ticket events,
-- a durable outbox of messages to hand over, and a log of every delivery attempt.

-- HR / Admin recipients are configured, never hard-coded. A NULL department means "all departments".
CREATE TABLE ticket_notification_recipients (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT NOT NULL REFERENCES users(id),
    event_type    VARCHAR(40) NOT NULL CHECK (event_type IN ('TICKET_CREATED', 'TICKET_RESOLVED')),
    department_id BIGINT REFERENCES departments(id),
    active        BOOLEAN NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_ticket_notification_recipient
    ON ticket_notification_recipients (user_id, event_type, COALESCE(department_id, 0));

-- Transactional outbox: one row per (event, recipient, channel), written in the same transaction as
-- the ticket change, so it exists if and only if the change committed.
CREATE TABLE notification_outbox (
    id                BIGSERIAL PRIMARY KEY,
    idempotency_key   VARCHAR(200) NOT NULL UNIQUE,
    event_type        VARCHAR(40) NOT NULL,
    channel           VARCHAR(20) NOT NULL CHECK (channel IN ('EMAIL', 'WHATSAPP')),
    ticket_id         BIGINT NOT NULL REFERENCES tickets(id) ON DELETE CASCADE,
    recipient_user_id BIGINT NOT NULL REFERENCES users(id),
    recipient_name    VARCHAR(255),
    recipient_email   VARCHAR(320),
    subject           VARCHAR(300),
    body_html         TEXT,
    body_text         TEXT,
    status            VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'ACCEPTED', 'FAILED')),
    attempts          INT NOT NULL DEFAULT 0,
    next_attempt_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    lease_until       TIMESTAMPTZ,
    last_error        VARCHAR(500),
    correlation_id    VARCHAR(100),
    accepted_at       TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    version           BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_notification_outbox_due ON notification_outbox (status, next_attempt_at);
CREATE INDEX idx_notification_outbox_ticket ON notification_outbox (ticket_id);

CREATE TABLE notification_delivery_attempts (
    id           BIGSERIAL PRIMARY KEY,
    outbox_id    BIGINT NOT NULL REFERENCES notification_outbox(id) ON DELETE CASCADE,
    attempt_no   INT NOT NULL,
    outcome      VARCHAR(20) NOT NULL,
    http_status  INT,
    detail       VARCHAR(500),
    attempted_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_notification_delivery_attempts_outbox ON notification_delivery_attempts (outbox_id);

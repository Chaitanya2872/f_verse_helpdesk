CREATE TABLE csat_surveys (
    id              BIGSERIAL PRIMARY KEY,
    ticket_id       BIGINT NOT NULL UNIQUE REFERENCES tickets(id),
    requester_id    BIGINT NOT NULL REFERENCES users(id),
    rating          SMALLINT CHECK (rating BETWEEN 1 AND 5),
    comment         TEXT,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','SUBMITTED')),
    submitted_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_csat_surveys_requester_id ON csat_surveys (requester_id);
CREATE INDEX idx_csat_surveys_status ON csat_surveys (status);

-- Seed the CSAT_REQUEST notification template, following the V8 pattern
-- exactly (same table/columns; see NotificationService.notify(User, Ticket,
-- String, Map) for the {{placeholder}} rendering contract).
INSERT INTO notification_templates (event_type, subject_template, body_template) VALUES
('CSAT_REQUEST', 'How did we do? Rate ticket {{ticketNumber}}',
 'Ticket {{ticketNumber}} ({{title}}) has been resolved. Please take a moment to rate your experience with our support team.');

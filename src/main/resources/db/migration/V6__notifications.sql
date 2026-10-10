CREATE TABLE notifications (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES users(id),
    ticket_id       BIGINT REFERENCES tickets(id),
    type            VARCHAR(20) NOT NULL CHECK (type IN ('EMAIL','IN_APP')),
    event_type      VARCHAR(50) NOT NULL,
    subject         VARCHAR(500),
    body            TEXT,
    is_sent         BOOLEAN NOT NULL DEFAULT false,
    is_read         BOOLEAN NOT NULL DEFAULT false,
    sent_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_notifications_user_created ON notifications(user_id, created_at DESC);

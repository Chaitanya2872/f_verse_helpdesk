CREATE TABLE ticket_types (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(100) UNIQUE NOT NULL,
    description     TEXT
);

CREATE TABLE categories (
    id                  BIGSERIAL PRIMARY KEY,
    ticket_type_id      BIGINT NOT NULL REFERENCES ticket_types(id),
    name                VARCHAR(150) NOT NULL,
    parent_category_id  BIGINT REFERENCES categories(id)
);

CREATE SEQUENCE ticket_number_seq START 1;

CREATE TABLE tickets (
    id                  BIGSERIAL PRIMARY KEY,
    ticket_number       VARCHAR(30) UNIQUE NOT NULL,
    type_id             BIGINT NOT NULL REFERENCES ticket_types(id),
    category_id         BIGINT REFERENCES categories(id),
    title               VARCHAR(500) NOT NULL,
    description         TEXT,
    priority            VARCHAR(2) NOT NULL CHECK (priority IN ('P1','P2','P3','P4')),
    status              VARCHAR(30) NOT NULL DEFAULT 'NEW' CHECK (status IN ('NEW','IN_PROGRESS','WAITING_CUSTOMER','RESOLVED','CLOSED','CANCELLED')),
    sub_status          VARCHAR(50),
    requester_id        BIGINT NOT NULL REFERENCES users(id),
    assignee_id         BIGINT REFERENCES users(id),
    team_id             BIGINT REFERENCES teams(id),
    sla_policy_id       BIGINT,
    first_response_due  TIMESTAMPTZ,
    resolution_due      TIMESTAMPTZ,
    first_response_at   TIMESTAMPTZ,
    resolved_at         TIMESTAMPTZ,
    closed_at           TIMESTAMPTZ,
    source              VARCHAR(20) NOT NULL DEFAULT 'PORTAL' CHECK (source IN ('PORTAL','EMAIL','API','IN_ERP')),
    tags                JSONB,
    custom_fields       JSONB,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE comments (
    id              BIGSERIAL PRIMARY KEY,
    ticket_id       BIGINT NOT NULL REFERENCES tickets(id),
    author_id       BIGINT NOT NULL REFERENCES users(id),
    body            TEXT NOT NULL,
    is_internal     BOOLEAN NOT NULL DEFAULT false,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE attachments (
    id              BIGSERIAL PRIMARY KEY,
    ticket_id       BIGINT NOT NULL REFERENCES tickets(id),
    comment_id      BIGINT REFERENCES comments(id),
    file_name       VARCHAR(500),
    file_type       VARCHAR(100),
    file_size_bytes BIGINT,
    storage_path    VARCHAR(1000),
    uploaded_by     BIGINT REFERENCES users(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE activity_logs (
    id              BIGSERIAL PRIMARY KEY,
    ticket_id       BIGINT NOT NULL REFERENCES tickets(id),
    actor_id        BIGINT REFERENCES users(id),
    action_type     VARCHAR(50) NOT NULL,
    field_name      VARCHAR(100),
    old_value       TEXT,
    new_value       TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_tickets_status_priority_created ON tickets (status, priority, created_at);
CREATE INDEX idx_tickets_requester_assignee_team ON tickets (requester_id, assignee_id, team_id);
CREATE INDEX idx_comments_ticket_id ON comments (ticket_id);
CREATE INDEX idx_activity_logs_ticket_id ON activity_logs (ticket_id);

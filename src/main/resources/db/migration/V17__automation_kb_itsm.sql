-- Enterprise helpdesk BRS/FRS phase 4-5: automation rules, knowledge-base
-- governance, and problem / change / major-incident records.

-- --------------------------------------------------------------- automation
CREATE TABLE automation_rules (
    id                      BIGSERIAL PRIMARY KEY,
    name                    VARCHAR(255) NOT NULL,
    owner_id                BIGINT REFERENCES users(id),
    enabled                 BOOLEAN NOT NULL DEFAULT false,
    trigger_event           VARCHAR(30) NOT NULL
        CHECK (trigger_event IN ('TICKET_CREATED','STATUS_CHANGED','COMMENT_ADDED')),
    conditions_json         JSONB NOT NULL DEFAULT '{}'::jsonb,
    actions_json            JSONB NOT NULL,
    max_runs_per_ticket     INT NOT NULL DEFAULT 3 CHECK (max_runs_per_ticket BETWEEN 1 AND 20),
    version                 INT NOT NULL DEFAULT 1,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE automation_runs (
    id              BIGSERIAL PRIMARY KEY,
    rule_id         BIGINT NOT NULL REFERENCES automation_rules(id),
    rule_version    INT NOT NULL,
    ticket_id       BIGINT NOT NULL REFERENCES tickets(id),
    dry_run         BOOLEAN NOT NULL DEFAULT false,
    result          VARCHAR(20) NOT NULL CHECK (result IN ('APPLIED','NO_MATCH','FAILED','SKIPPED_LIMIT')),
    actions_json    JSONB,
    error           TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_automation_runs_rule_ticket ON automation_runs (rule_id, ticket_id);
CREATE INDEX idx_automation_runs_created ON automation_runs (created_at);

-- ---------------------------------------------------------- knowledge base
ALTER TABLE kb_articles DROP CONSTRAINT kb_articles_status_check;
ALTER TABLE kb_articles ADD CONSTRAINT kb_articles_status_check
    CHECK (status IN ('DRAFT','PUBLISHED','RETIRED'));
ALTER TABLE kb_articles ADD COLUMN review_due_at TIMESTAMPTZ;
ALTER TABLE kb_articles ADD COLUMN ticket_type_id BIGINT REFERENCES ticket_types(id);
ALTER TABLE kb_articles ADD COLUMN helpful_count INT NOT NULL DEFAULT 0;
ALTER TABLE kb_articles ADD COLUMN not_helpful_count INT NOT NULL DEFAULT 0;
ALTER TABLE kb_articles ADD COLUMN resolved_count INT NOT NULL DEFAULT 0;
-- Audience: STAFF articles hold internal procedures and are never shown to requesters.
ALTER TABLE kb_articles ADD COLUMN audience VARCHAR(20) NOT NULL DEFAULT 'ALL'
    CHECK (audience IN ('ALL','STAFF'));

CREATE TABLE kb_feedback (
    id              BIGSERIAL PRIMARY KEY,
    article_id      BIGINT NOT NULL REFERENCES kb_articles(id),
    user_id         BIGINT NOT NULL REFERENCES users(id),
    helpful         BOOLEAN NOT NULL,
    resolved_issue  BOOLEAN NOT NULL DEFAULT false,
    comment         VARCHAR(1000),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (article_id, user_id)
);

CREATE TABLE kb_search_log (
    id              BIGSERIAL PRIMARY KEY,
    query           VARCHAR(300) NOT NULL,
    result_count    INT NOT NULL,
    user_id         BIGINT REFERENCES users(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_kb_search_log_zero ON kb_search_log (created_at) WHERE result_count = 0;

-- ----------------------------------------------------------------- problems
CREATE SEQUENCE problem_number_seq START 1;
CREATE SEQUENCE change_number_seq START 1;
CREATE SEQUENCE major_incident_number_seq START 1;

CREATE TABLE problems (
    id              BIGSERIAL PRIMARY KEY,
    problem_number  VARCHAR(30) NOT NULL UNIQUE,
    title           VARCHAR(500) NOT NULL,
    description     TEXT,
    status          VARCHAR(20) NOT NULL DEFAULT 'OPEN'
        CHECK (status IN ('OPEN','INVESTIGATING','KNOWN_ERROR','RESOLVED','CLOSED')),
    symptoms        TEXT,
    root_cause      TEXT,
    workaround      TEXT,
    permanent_fix   TEXT,
    owner_id        BIGINT REFERENCES users(id),
    review_date     DATE,
    kb_article_id   BIGINT REFERENCES kb_articles(id),
    created_by      BIGINT REFERENCES users(id),
    version         BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE problem_tickets (
    problem_id  BIGINT NOT NULL REFERENCES problems(id),
    ticket_id   BIGINT NOT NULL REFERENCES tickets(id),
    linked_by   BIGINT REFERENCES users(id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (problem_id, ticket_id)
);

-- ------------------------------------------------------------------ changes
CREATE TABLE change_requests (
    id                  BIGSERIAL PRIMARY KEY,
    change_number       VARCHAR(30) NOT NULL UNIQUE,
    title               VARCHAR(500) NOT NULL,
    change_type         VARCHAR(20) NOT NULL CHECK (change_type IN ('STANDARD','NORMAL','EMERGENCY')),
    status              VARCHAR(20) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT','SUBMITTED','APPROVED','REJECTED','SCHEDULED','IMPLEMENTING','COMPLETED','FAILED','CANCELLED')),
    reason              TEXT,
    scope               TEXT,
    risk                VARCHAR(10) CHECK (risk IN ('LOW','MEDIUM','HIGH')),
    impact              VARCHAR(10) CHECK (impact IN ('LOW','MEDIUM','HIGH')),
    affected_services   TEXT,
    implementation_plan TEXT,
    test_plan           TEXT,
    rollback_plan       TEXT,
    scheduled_start     TIMESTAMPTZ,
    scheduled_end       TIMESTAMPTZ,
    actual_start        TIMESTAMPTZ,
    actual_end          TIMESTAMPTZ,
    outcome             TEXT,
    owner_id            BIGINT REFERENCES users(id),
    approved_by         BIGINT REFERENCES users(id),
    approved_at         TIMESTAMPTZ,
    approval_comment    TEXT,
    version             BIGINT NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (scheduled_end IS NULL OR scheduled_start IS NULL OR scheduled_end > scheduled_start)
);
CREATE INDEX idx_change_requests_schedule ON change_requests (scheduled_start, scheduled_end)
    WHERE status IN ('SCHEDULED','IMPLEMENTING');

CREATE TABLE change_tickets (
    change_id   BIGINT NOT NULL REFERENCES change_requests(id),
    ticket_id   BIGINT NOT NULL REFERENCES tickets(id),
    PRIMARY KEY (change_id, ticket_id)
);

CREATE TABLE change_problems (
    change_id   BIGINT NOT NULL REFERENCES change_requests(id),
    problem_id  BIGINT NOT NULL REFERENCES problems(id),
    PRIMARY KEY (change_id, problem_id)
);

-- ---------------------------------------------------------- major incidents
CREATE TABLE major_incidents (
    id                      BIGSERIAL PRIMARY KEY,
    incident_number         VARCHAR(30) NOT NULL UNIQUE,
    title                   VARCHAR(500) NOT NULL,
    status                  VARCHAR(20) NOT NULL DEFAULT 'DECLARED'
        CHECK (status IN ('DECLARED','MITIGATED','RESOLVED','CLOSED')),
    severity                VARCHAR(10) NOT NULL CHECK (severity IN ('SEV1','SEV2','SEV3')),
    reason                  TEXT NOT NULL,
    scope                   TEXT,
    affected_services       TEXT,
    customer_impact         TEXT,
    incident_lead_id        BIGINT REFERENCES users(id),
    comms_lead_id           BIGINT REFERENCES users(id),
    started_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    mitigated_at            TIMESTAMPTZ,
    resolved_at             TIMESTAMPTZ,
    closed_at               TIMESTAMPTZ,
    update_cadence_minutes  INT NOT NULL DEFAULT 30 CHECK (update_cadence_minutes > 0),
    next_update_due_at      TIMESTAMPTZ,
    post_incident_review    TEXT,
    action_items            TEXT,
    declared_by             BIGINT REFERENCES users(id),
    version                 BIGINT NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE major_incident_tickets (
    incident_id BIGINT NOT NULL REFERENCES major_incidents(id),
    ticket_id   BIGINT NOT NULL REFERENCES tickets(id),
    PRIMARY KEY (incident_id, ticket_id)
);

CREATE TABLE major_incident_updates (
    id          BIGSERIAL PRIMARY KEY,
    incident_id BIGINT NOT NULL REFERENCES major_incidents(id),
    author_id   BIGINT REFERENCES users(id),
    message     TEXT NOT NULL,
    is_public   BOOLEAN NOT NULL DEFAULT false,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_major_incident_updates_incident ON major_incident_updates (incident_id, created_at);

-- Enterprise helpdesk BRS/FRS phase 2: catalog + dynamic forms, workflow
-- transitions, approvals with delegation, SLA pause/holidays/escalation.

-- Access grants can expire (BRS 3.1). Added here, not in V15, because V15 was already applied to dev databases.
ALTER TABLE ticket_access_grants ADD COLUMN expires_at TIMESTAMPTZ;

-- ---------------------------------------------------------------- catalog
ALTER TABLE ticket_types ADD COLUMN instructions TEXT;
ALTER TABLE ticket_types ADD COLUMN department_id BIGINT REFERENCES departments(id);
ALTER TABLE ticket_types ADD COLUMN service_owner_id BIGINT REFERENCES users(id);
ALTER TABLE ticket_types ADD COLUMN lifecycle_status VARCHAR(20) NOT NULL DEFAULT 'PUBLISHED'
    CHECK (lifecycle_status IN ('DRAFT','PUBLISHED','RETIRED','ARCHIVED'));
ALTER TABLE ticket_types ADD COLUMN audience VARCHAR(20) NOT NULL DEFAULT 'ALL'
    CHECK (audience IN ('ALL','EMPLOYEE','CUSTOMER'));
ALTER TABLE ticket_types ADD COLUMN keywords TEXT;
ALTER TABLE ticket_types ADD COLUMN icon VARCHAR(50);
-- Requests of this type start with at least this classification (e.g. confidential grievance).
ALTER TABLE ticket_types ADD COLUMN default_confidentiality VARCHAR(20) NOT NULL DEFAULT 'STANDARD'
    CHECK (default_confidentiality IN ('STANDARD','RESTRICTED','CONFIDENTIAL'));

CREATE TABLE form_versions (
    id              BIGSERIAL PRIMARY KEY,
    ticket_type_id  BIGINT NOT NULL REFERENCES ticket_types(id),
    version         INT NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','PUBLISHED','RETIRED')),
    schema_json     JSONB NOT NULL,
    created_by      BIGINT REFERENCES users(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ,
    UNIQUE (ticket_type_id, version)
);
-- At most one published form per request type.
CREATE UNIQUE INDEX uq_form_versions_one_published ON form_versions (ticket_type_id) WHERE status = 'PUBLISHED';

ALTER TABLE tickets ADD COLUMN form_version_id BIGINT REFERENCES form_versions(id);

-- --------------------------------------------------------------- workflow
-- ticket_type_id NULL = the default workflow; a type with its own rows uses only those.
CREATE TABLE workflow_transitions (
    id              BIGSERIAL PRIMARY KEY,
    ticket_type_id  BIGINT REFERENCES ticket_types(id),
    from_status     VARCHAR(30) NOT NULL,
    to_status       VARCHAR(30) NOT NULL,
    UNIQUE NULLS NOT DISTINCT (ticket_type_id, from_status, to_status)
);

INSERT INTO workflow_transitions (ticket_type_id, from_status, to_status) VALUES
    (NULL, 'NEW', 'TRIAGE'),
    (NULL, 'NEW', 'ASSIGNED'),
    (NULL, 'NEW', 'IN_PROGRESS'),
    (NULL, 'NEW', 'CANCELLED'),
    (NULL, 'TRIAGE', 'ASSIGNED'),
    (NULL, 'TRIAGE', 'IN_PROGRESS'),
    (NULL, 'TRIAGE', 'CANCELLED'),
    (NULL, 'ASSIGNED', 'TRIAGE'),
    (NULL, 'ASSIGNED', 'IN_PROGRESS'),
    (NULL, 'ASSIGNED', 'WAITING_CUSTOMER'),
    (NULL, 'ASSIGNED', 'WAITING_APPROVAL'),
    (NULL, 'ASSIGNED', 'CANCELLED'),
    (NULL, 'IN_PROGRESS', 'WAITING_CUSTOMER'),
    (NULL, 'IN_PROGRESS', 'WAITING_VENDOR'),
    (NULL, 'IN_PROGRESS', 'WAITING_APPROVAL'),
    (NULL, 'IN_PROGRESS', 'RESOLVED'),
    (NULL, 'IN_PROGRESS', 'CANCELLED'),
    (NULL, 'WAITING_CUSTOMER', 'IN_PROGRESS'),
    (NULL, 'WAITING_CUSTOMER', 'RESOLVED'),
    (NULL, 'WAITING_CUSTOMER', 'CANCELLED'),
    (NULL, 'WAITING_VENDOR', 'IN_PROGRESS'),
    (NULL, 'WAITING_VENDOR', 'RESOLVED'),
    (NULL, 'WAITING_VENDOR', 'CANCELLED'),
    (NULL, 'WAITING_APPROVAL', 'IN_PROGRESS'),
    (NULL, 'WAITING_APPROVAL', 'CANCELLED'),
    (NULL, 'RESOLVED', 'CLOSED'),
    (NULL, 'RESOLVED', 'REOPENED'),
    (NULL, 'RESOLVED', 'IN_PROGRESS'),
    (NULL, 'REOPENED', 'ASSIGNED'),
    (NULL, 'REOPENED', 'IN_PROGRESS'),
    (NULL, 'REOPENED', 'WAITING_CUSTOMER'),
    (NULL, 'REOPENED', 'WAITING_VENDOR'),
    (NULL, 'REOPENED', 'RESOLVED'),
    (NULL, 'REOPENED', 'CANCELLED');

-- -------------------------------------------------------------- approvals
CREATE TABLE approval_policies (
    id                  BIGSERIAL PRIMARY KEY,
    name                VARCHAR(255) NOT NULL,
    ticket_type_id      BIGINT REFERENCES ticket_types(id),
    mode                VARCHAR(20) NOT NULL DEFAULT 'SINGLE' CHECK (mode IN ('SINGLE','SEQUENTIAL','PARALLEL')),
    timeout_hours       INT NOT NULL DEFAULT 72,
    block_self_approval BOOLEAN NOT NULL DEFAULT true,
    version             INT NOT NULL DEFAULT 1,
    is_active           BOOLEAN NOT NULL DEFAULT true,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE approval_policy_steps (
    id              BIGSERIAL PRIMARY KEY,
    policy_id       BIGINT NOT NULL REFERENCES approval_policies(id) ON DELETE CASCADE,
    step_order      INT NOT NULL,
    approver_type   VARCHAR(30) NOT NULL CHECK (approver_type IN ('USER','DEPARTMENT_MANAGER')),
    approver_id     BIGINT REFERENCES users(id),
    CHECK (approver_type <> 'USER' OR approver_id IS NOT NULL)
);

CREATE TABLE approval_tasks (
    id                  BIGSERIAL PRIMARY KEY,
    ticket_id           BIGINT NOT NULL REFERENCES tickets(id),
    policy_id           BIGINT REFERENCES approval_policies(id),
    policy_version      INT,
    step_order          INT NOT NULL DEFAULT 1,
    approver_id         BIGINT NOT NULL REFERENCES users(id),
    acted_by_id         BIGINT REFERENCES users(id),
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('QUEUED','PENDING','APPROVED','REJECTED','CHANGES_REQUESTED','WITHDRAWN','EXPIRED')),
    decision_comment    TEXT,
    due_at              TIMESTAMPTZ,
    decided_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_approval_tasks_ticket ON approval_tasks (ticket_id);
CREATE INDEX idx_approval_tasks_approver_pending ON approval_tasks (approver_id) WHERE status = 'PENDING';

CREATE TABLE approval_delegations (
    id              BIGSERIAL PRIMARY KEY,
    delegator_id    BIGINT NOT NULL REFERENCES users(id),
    delegate_id     BIGINT NOT NULL REFERENCES users(id),
    starts_at       TIMESTAMPTZ NOT NULL,
    ends_at         TIMESTAMPTZ NOT NULL,
    is_active       BOOLEAN NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (delegator_id <> delegate_id),
    CHECK (ends_at > starts_at)
);

-- -------------------------------------------------------------------- SLA
ALTER TABLE sla_policies ADD COLUMN version INT NOT NULL DEFAULT 1;
ALTER TABLE sla_policies ADD COLUMN pause_statuses VARCHAR(200) NOT NULL DEFAULT 'WAITING_CUSTOMER';
ALTER TABLE sla_policies ADD COLUMN warning_percent INT NOT NULL DEFAULT 80 CHECK (warning_percent BETWEEN 1 AND 99);
ALTER TABLE sla_policies ADD COLUMN escalate_after_breach_minutes INT;
ALTER TABLE sla_calendars ADD COLUMN holidays_json JSONB;

ALTER TABLE tickets ADD COLUMN sla_policy_version INT;
ALTER TABLE tickets ADD COLUMN sla_paused_at TIMESTAMPTZ;
ALTER TABLE tickets ADD COLUMN sla_resolution_remaining_minutes BIGINT;
ALTER TABLE tickets ADD COLUMN sla_first_response_remaining_minutes BIGINT;
ALTER TABLE tickets ADD COLUMN sla_escalated BOOLEAN NOT NULL DEFAULT false;

CREATE TABLE sla_pauses (
    id          BIGSERIAL PRIMARY KEY,
    ticket_id   BIGINT NOT NULL REFERENCES tickets(id),
    status      VARCHAR(30) NOT NULL,
    started_at  TIMESTAMPTZ NOT NULL,
    ended_at    TIMESTAMPTZ
);
CREATE INDEX idx_sla_pauses_ticket ON sla_pauses (ticket_id);

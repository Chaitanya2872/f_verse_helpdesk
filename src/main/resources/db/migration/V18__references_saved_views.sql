-- External record references (BRS 16.1, 16.4) and saved queue views (BRS 4.2).

-- Ticket links to records owned by other systems. Only a reference and a short label are kept: the
-- helpdesk is not a second inventory, and a reference never grants access to the underlying record.
ALTER TABLE ticket_erp_links ADD COLUMN source_system VARCHAR(50) NOT NULL DEFAULT 'ERP';
ALTER TABLE ticket_erp_links ADD COLUMN label VARCHAR(255);
ALTER TABLE ticket_erp_links ADD COLUMN created_by BIGINT REFERENCES users(id);
CREATE UNIQUE INDEX uq_ticket_erp_links_ref ON ticket_erp_links (ticket_id, source_system, entity_type, entity_id);

-- The same kind of reference for problems, changes and major incidents (affected assets, applications...).
CREATE TABLE entity_links (
    id              BIGSERIAL PRIMARY KEY,
    owner_type      VARCHAR(20) NOT NULL CHECK (owner_type IN ('PROBLEM','CHANGE','MAJOR_INCIDENT')),
    owner_id        BIGINT NOT NULL,
    source_system   VARCHAR(50) NOT NULL DEFAULT 'ERP',
    entity_type     VARCHAR(50) NOT NULL,
    entity_id       VARCHAR(100) NOT NULL,
    label           VARCHAR(255),
    created_by      BIGINT REFERENCES users(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (owner_type, owner_id, source_system, entity_type, entity_id)
);
CREATE INDEX idx_entity_links_entity ON entity_links (source_system, entity_type, entity_id);

CREATE TABLE saved_views (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES users(id),
    name            VARCHAR(100) NOT NULL,
    filters_json    JSONB NOT NULL,
    shared          BOOLEAN NOT NULL DEFAULT false,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, name)
);

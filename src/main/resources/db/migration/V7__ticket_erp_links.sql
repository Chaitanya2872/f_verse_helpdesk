CREATE TABLE ticket_erp_links (
    id                      BIGSERIAL PRIMARY KEY,
    ticket_id               BIGINT NOT NULL REFERENCES tickets(id),
    entity_type             VARCHAR(50) NOT NULL,
    entity_id               VARCHAR(100) NOT NULL,
    entity_snapshot_json    JSONB,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_ticket_erp_links_entity ON ticket_erp_links(entity_type, entity_id);

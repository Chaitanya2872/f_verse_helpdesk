CREATE TABLE routing_rules (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(255) NOT NULL,
    order_index     INT NOT NULL DEFAULT 0,
    ticket_type_id  BIGINT REFERENCES ticket_types(id),
    category_id     BIGINT REFERENCES categories(id),
    priority        VARCHAR(2) CHECK (priority IN ('P1','P2','P3','P4')),
    team_id         BIGINT REFERENCES teams(id),
    assignee_id     BIGINT REFERENCES users(id),
    is_active       BOOLEAN NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_routing_rules_active_order ON routing_rules(is_active, order_index);

-- Seed a default team so every ticket has somewhere to land out of the box.
INSERT INTO teams (name, description, created_at)
VALUES ('General Support', 'Default catch-all team for tickets that do not match a more specific routing rule', now());

-- Catch-all rule: every field is a wildcard (NULL) except the destination team,
-- so it always matches and guarantees new tickets are routed to *something*.
INSERT INTO routing_rules (name, order_index, ticket_type_id, category_id, priority, team_id, assignee_id, is_active)
VALUES ('Catch-all default', 0, NULL, NULL, NULL, (SELECT id FROM teams WHERE name = 'General Support'), NULL, true);

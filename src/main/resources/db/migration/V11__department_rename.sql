-- Repurpose "teams" into first-class, mandatory "departments" (rename, not
-- drop/recreate, so V4's seeded "General Support" row keeps id=1 and every
-- existing team_id FK value stays valid with no ticket-side data migration).

ALTER TABLE teams RENAME TO departments;
ALTER TABLE departments RENAME COLUMN name TO department_name;
ALTER TABLE departments ADD COLUMN manager_id BIGINT REFERENCES users(id);
ALTER TABLE departments ADD COLUMN sla_policy_id BIGINT REFERENCES sla_policies(id);

ALTER TABLE team_members RENAME TO department_members;
ALTER TABLE department_members RENAME COLUMN team_id TO department_id;

ALTER TABLE tickets RENAME COLUMN team_id TO department_id;
ALTER TABLE routing_rules RENAME COLUMN team_id TO department_id;

-- Backfill safety net: every ticket already gets department_id set at create
-- time via TicketService.applyRouting's catch-all rule (seeded in V4), so
-- this should be a no-op on this codebase's own data - but guards against any
-- row inserted before that rule existed or seeded out-of-band.
UPDATE tickets SET department_id = 1 WHERE department_id IS NULL;

ALTER TABLE tickets ALTER COLUMN department_id SET NOT NULL;

-- Cosmetic renames for clarity (indexes/sequences auto-track the underlying
-- column/table rename functionally; only their own names need updating).
ALTER INDEX idx_tickets_requester_assignee_team RENAME TO idx_tickets_requester_assignee_department;
ALTER SEQUENCE IF EXISTS teams_id_seq RENAME TO departments_id_seq;

-- The TICKET_CREATED template seeded in V8 still says "team" - keep the
-- persisted wording in sync with the department rename (NotificationService
-- prefers this DB row over its hardcoded fallback strings).
UPDATE notification_templates
SET subject_template = 'New ticket for your department: {{ticketNumber}}',
    body_template = 'Ticket {{ticketNumber}} ({{title}}) has been routed to your department.'
WHERE event_type = 'TICKET_CREATED';

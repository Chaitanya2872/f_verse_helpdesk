CREATE TABLE notification_templates (
  id BIGSERIAL PRIMARY KEY,
  event_type VARCHAR(50) UNIQUE NOT NULL,
  subject_template VARCHAR(500) NOT NULL,
  body_template TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Seed one row per existing event type, using {{placeholder}} tokens. Wording
-- matches what TicketService/SlaMonitoringJob previously hardcoded inline at
-- their notify(...) call sites (see NotificationService.notify(User, Ticket,
-- String, Map) for the rendering contract - missing placeholder keys are left
-- as literal "{{key}}" tokens rather than throwing).
--
-- TICKET_CREATED is fired from two call sites in TicketService.notifyOnCreate()
-- (direct-assignee-on-create, and team-routed-on-create) which previously used
-- two different hardcoded strings under the same "TICKET_CREATED" event type.
-- Since this table has one row per event_type, the team-routed wording (the
-- more general of the two) was chosen as the single canonical template.
INSERT INTO notification_templates (event_type, subject_template, body_template) VALUES
('TICKET_CREATED', 'New ticket for your team: {{ticketNumber}}',
 'Ticket {{ticketNumber}} ({{title}}) has been routed to your team.'),
('TICKET_ASSIGNED', 'Ticket assigned to you: {{ticketNumber}}',
 'Ticket {{ticketNumber}} ({{title}}) has been assigned to you.'),
('TICKET_RESOLVED', 'Your ticket has been resolved: {{ticketNumber}}',
 'Ticket {{ticketNumber}} ({{title}}) has been marked resolved.'),
('SLA_WARNING', 'SLA at risk: {{ticketNumber}}',
 'Ticket {{ticketNumber}} ({{title}}) is approaching its resolution SLA due date.'),
('SLA_BREACHED', 'SLA breached: {{ticketNumber}}',
 'Ticket {{ticketNumber}} ({{title}}) has breached its resolution SLA.');

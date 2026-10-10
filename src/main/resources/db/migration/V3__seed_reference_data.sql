INSERT INTO roles (name) VALUES
    ('REQUESTER'),
    ('AGENT'),
    ('MANAGER'),
    ('ADMIN');

INSERT INTO ticket_types (name, description) VALUES
    ('Incident', 'An unplanned interruption or reduction in quality of a service'),
    ('Service Request', 'A request for something new to be provided or changed'),
    ('Change Request', 'A request to modify configuration or a controlled item'),
    ('Problem', 'The underlying cause of one or more incidents');

INSERT INTO categories (ticket_type_id, name) VALUES
    ((SELECT id FROM ticket_types WHERE name = 'Incident'), 'Posting Failure'),
    ((SELECT id FROM ticket_types WHERE name = 'Incident'), 'Data Discrepancy'),
    ((SELECT id FROM ticket_types WHERE name = 'Service Request'), 'Access Request'),
    ((SELECT id FROM ticket_types WHERE name = 'Service Request'), 'Master Data Change'),
    ((SELECT id FROM ticket_types WHERE name = 'Change Request'), 'Configuration Change'),
    ((SELECT id FROM ticket_types WHERE name = 'Problem'), 'Recurring Issue');

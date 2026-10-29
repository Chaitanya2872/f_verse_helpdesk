-- Demo data for the helpdesk, for local development only.
--
-- NOT a Flyway migration on purpose: it must never run in staging or production.
-- Run it after the service has started once (so every migration has been applied):
--
--   psql -U postgres -h localhost -d fawnix_helpdesk -f dev-data/seed-demo-data.sql
--
-- Safe to run again: it first removes the tickets and related records it created earlier (they carry
-- the tag "demo" or the DEMO- prefix) and recreates them with fresh dates, so the dashboard always
-- looks current. People, departments, services and categories are only added when missing.
-- Demo people have erp_user_id "demo-..." and cannot log in; sign in with your own account to see them.

BEGIN;

-- ------------------------------------------------------------------ reset previous demo records
CREATE TEMP TABLE demo_tickets ON COMMIT DROP AS SELECT id FROM tickets WHERE tags ? 'demo';

DELETE FROM comments WHERE ticket_id IN (SELECT id FROM demo_tickets);
DELETE FROM activity_logs WHERE ticket_id IN (SELECT id FROM demo_tickets);
DELETE FROM attachments WHERE ticket_id IN (SELECT id FROM demo_tickets);
DELETE FROM notifications WHERE ticket_id IN (SELECT id FROM demo_tickets);
DELETE FROM csat_surveys WHERE ticket_id IN (SELECT id FROM demo_tickets);
DELETE FROM ticket_assignments WHERE ticket_id IN (SELECT id FROM demo_tickets);
DELETE FROM ticket_erp_links WHERE ticket_id IN (SELECT id FROM demo_tickets);
DELETE FROM approval_tasks WHERE ticket_id IN (SELECT id FROM demo_tickets);
DELETE FROM sla_pauses WHERE ticket_id IN (SELECT id FROM demo_tickets);
DELETE FROM ticket_access_grants WHERE ticket_id IN (SELECT id FROM demo_tickets);
DELETE FROM automation_runs WHERE ticket_id IN (SELECT id FROM demo_tickets);
DELETE FROM problem_tickets WHERE ticket_id IN (SELECT id FROM demo_tickets);
DELETE FROM change_tickets WHERE ticket_id IN (SELECT id FROM demo_tickets);
DELETE FROM major_incident_tickets WHERE ticket_id IN (SELECT id FROM demo_tickets);
DELETE FROM tickets WHERE id IN (SELECT id FROM demo_tickets);

DELETE FROM entity_links WHERE owner_type = 'PROBLEM' AND owner_id IN (SELECT id FROM problems WHERE problem_number LIKE 'DEMO-%');
DELETE FROM entity_links WHERE owner_type = 'CHANGE' AND owner_id IN (SELECT id FROM change_requests WHERE change_number LIKE 'DEMO-%');
DELETE FROM entity_links WHERE owner_type = 'MAJOR_INCIDENT' AND owner_id IN (SELECT id FROM major_incidents WHERE incident_number LIKE 'DEMO-%');
DELETE FROM change_problems WHERE change_id IN (SELECT id FROM change_requests WHERE change_number LIKE 'DEMO-%');
DELETE FROM change_tickets WHERE change_id IN (SELECT id FROM change_requests WHERE change_number LIKE 'DEMO-%');
DELETE FROM problem_tickets WHERE problem_id IN (SELECT id FROM problems WHERE problem_number LIKE 'DEMO-%');
DELETE FROM problems WHERE problem_number LIKE 'DEMO-%';
DELETE FROM change_requests WHERE change_number LIKE 'DEMO-%';
DELETE FROM major_incident_updates WHERE incident_id IN (SELECT id FROM major_incidents WHERE incident_number LIKE 'DEMO-%');
DELETE FROM major_incident_tickets WHERE incident_id IN (SELECT id FROM major_incidents WHERE incident_number LIKE 'DEMO-%');
DELETE FROM major_incidents WHERE incident_number LIKE 'DEMO-%';
DELETE FROM automation_runs WHERE rule_id IN (SELECT id FROM automation_rules WHERE name LIKE 'Demo:%');
DELETE FROM automation_rules WHERE name LIKE 'Demo:%';
DELETE FROM approval_tasks WHERE policy_id IN (SELECT id FROM approval_policies WHERE name LIKE 'Demo:%');
DELETE FROM approval_policy_steps WHERE policy_id IN (SELECT id FROM approval_policies WHERE name LIKE 'Demo:%');
DELETE FROM approval_policies WHERE name LIKE 'Demo:%';

-- -------------------------------------------------------------------------------------- people
INSERT INTO users (erp_user_id, email, name, is_active) VALUES
 ('demo-mgr-suresh',  'suresh.rao@demo.local',      'Suresh Rao',      true),
 ('demo-mgr-deepa',   'deepa.menon@demo.local',     'Deepa Menon',     true),
 ('demo-agt-aarav',   'aarav.sharma@demo.local',    'Aarav Sharma',    true),
 ('demo-agt-priya',   'priya.nair@demo.local',      'Priya Nair',      true),
 ('demo-agt-rohan',   'rohan.mehta@demo.local',     'Rohan Mehta',     true),
 ('demo-agt-ananya',  'ananya.iyer@demo.local',     'Ananya Iyer',     true),
 ('demo-agt-vikram',  'vikram.singh@demo.local',    'Vikram Singh',    true),
 ('demo-agt-neha',    'neha.kapoor@demo.local',     'Neha Kapoor',     true),
 ('demo-agt-karthik', 'karthik.reddy@demo.local',   'Karthik Reddy',   true),
 ('demo-agt-meera',   'meera.joshi@demo.local',     'Meera Joshi',     true),
 ('demo-emp-isha',    'isha.verma@demo.local',      'Isha Verma',      true),
 ('demo-emp-arjun',   'arjun.patel@demo.local',     'Arjun Patel',     true),
 ('demo-emp-kavya',   'kavya.rao@demo.local',       'Kavya Rao',       true),
 ('demo-emp-manish',  'manish.gupta@demo.local',    'Manish Gupta',    true),
 ('demo-emp-sunita',  'sunita.das@demo.local',      'Sunita Das',      true),
 ('demo-emp-rahul',   'rahul.khanna@demo.local',    'Rahul Khanna',    true),
 ('demo-emp-divya',   'divya.krishnan@demo.local',  'Divya Krishnan',  true),
 ('demo-emp-nikhil',  'nikhil.bose@demo.local',     'Nikhil Bose',     true),
 ('demo-emp-pooja',   'pooja.malhotra@demo.local',  'Pooja Malhotra',  true),
 ('demo-emp-sanjay',  'sanjay.pillai@demo.local',   'Sanjay Pillai',   true),
 ('demo-emp-lakshmi', 'lakshmi.narayan@demo.local', 'Lakshmi Narayan', true),
 ('demo-emp-faisal',  'faisal.khan@demo.local',     'Faisal Khan',     true)
ON CONFLICT (erp_user_id) DO NOTHING;

-- ---------------------------------------------------------------------------------- departments
INSERT INTO departments (department_name, description, manager_id)
SELECT d.name, d.descr, (SELECT id FROM users WHERE erp_user_id = d.mgr)
FROM (VALUES
  ('HR Service Desk',          'Payroll, leave, letters and employee data',         'demo-mgr-deepa'),
  ('Software Support',         'Application bugs, access and releases',             'demo-mgr-suresh'),
  ('Finance Service Desk',     'Invoices, reimbursements and cost centres',         'demo-mgr-deepa'),
  ('Procurement',              'Requisitions, vendors and purchase orders',         'demo-mgr-suresh'),
  ('Infrastructure',           'Devices, network, accounts and servers',            'demo-mgr-suresh'),
  ('Sales & Customer Support', 'Customer complaints, orders and onboarding',        'demo-mgr-deepa')
) AS d(name, descr, mgr)
WHERE NOT EXISTS (SELECT 1 FROM departments x WHERE x.department_name = d.name);

INSERT INTO department_members (department_id, user_id, is_lead)
SELECT dep.id, u.id, m.is_lead
FROM (VALUES
  ('HR Service Desk',          'demo-agt-priya',   true),
  ('HR Service Desk',          'demo-agt-meera',   false),
  ('Software Support',         'demo-agt-aarav',   true),
  ('Software Support',         'demo-agt-rohan',   false),
  ('Software Support',         'demo-agt-karthik', false),
  ('Finance Service Desk',     'demo-agt-ananya',  true),
  ('Finance Service Desk',     'demo-agt-meera',   false),
  ('Procurement',              'demo-agt-vikram',  true),
  ('Procurement',              'demo-agt-neha',    false),
  ('Infrastructure',           'demo-agt-rohan',   true),
  ('Infrastructure',           'demo-agt-karthik', false),
  ('Infrastructure',           'demo-agt-vikram',  false),
  ('Sales & Customer Support', 'demo-agt-neha',    true),
  ('Sales & Customer Support', 'demo-agt-ananya',  false),
  ('Software Support',         'demo-mgr-suresh',  true),
  ('Infrastructure',           'demo-mgr-suresh',  true),
  ('Procurement',              'demo-mgr-suresh',  true),
  ('HR Service Desk',          'demo-mgr-deepa',   true),
  ('Finance Service Desk',     'demo-mgr-deepa',   true),
  ('Sales & Customer Support', 'demo-mgr-deepa',   true)
) AS m(dept, erp, is_lead)
JOIN departments dep ON dep.department_name = m.dept
JOIN users u ON u.erp_user_id = m.erp
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------- services and categories
UPDATE ticket_types SET department_id = (SELECT id FROM departments WHERE department_name = 'Software Support')
 WHERE name IN ('Incident', 'Change Request', 'Problem') AND department_id IS NULL;
UPDATE ticket_types SET department_id = (SELECT id FROM departments WHERE department_name = 'Infrastructure')
 WHERE name = 'Service Request' AND department_id IS NULL;

INSERT INTO ticket_types (name, description, instructions, department_id, service_owner_id, keywords, default_confidentiality)
SELECT s.name, s.descr, s.instr, dep.id, (SELECT id FROM users WHERE erp_user_id = s.owner), s.kw, s.conf
FROM (VALUES
  ('Payroll Query',          'Questions about salary, deductions or payslips',  'Include the month and what looks wrong.',                      'HR Service Desk',          'demo-agt-priya',   'salary payslip deduction', 'STANDARD'),
  ('Leave & Attendance',     'Leave balance and attendance corrections',        'Give the dates and the reason for the correction.',            'HR Service Desk',          'demo-agt-priya',   'leave attendance holiday', 'STANDARD'),
  ('Confidential Grievance', 'Raise a workplace concern in confidence',         'Only the HR investigators assigned to your case can see this.', 'HR Service Desk',          'demo-agt-meera',   'grievance complaint',      'CONFIDENTIAL'),
  ('Application Bug',        'Something in an internal application is broken',  'Say which application, what you did and the error shown.',    'Software Support',         'demo-agt-aarav',   'bug error crash',          'STANDARD'),
  ('Invoice Status',         'Where is a vendor or customer invoice',           'Have the invoice number ready.',                               'Finance Service Desk',     'demo-agt-ananya',  'invoice payment vendor',   'STANDARD'),
  ('Reimbursement',          'Claim or chase an expense reimbursement',         'Attach receipts, never card numbers.',                         'Finance Service Desk',     'demo-agt-ananya',  'expense claim refund',     'STANDARD'),
  ('Purchase Requisition',   'Request to buy goods or services',                'Include justification, estimated cost and cost centre.',       'Procurement',              'demo-agt-vikram',  'purchase buy po',          'STANDARD'),
  ('Vendor Onboarding',      'Add or update a vendor',                          'Attach the vendor registration documents.',                    'Procurement',              'demo-agt-neha',    'vendor supplier',          'STANDARD'),
  ('Laptop & Peripherals',   'New, replacement or repair of devices',           'Give the asset tag if you have one.',                          'Infrastructure',           'demo-agt-rohan',   'laptop monitor keyboard',  'STANDARD'),
  ('VPN & Network',          'Connectivity and VPN problems',                   'Say where you are working from and the error shown.',          'Infrastructure',           'demo-agt-karthik', 'vpn wifi network',         'STANDARD'),
  ('Product Complaint',      'A customer is unhappy with a product',            'Include the order number and what went wrong.',                'Sales & Customer Support', 'demo-agt-neha',    'complaint product defect', 'STANDARD'),
  ('Order & Delivery Query', 'Where is an order',                               'Give the order number.',                                       'Sales & Customer Support', 'demo-agt-neha',    'order delivery shipment',  'STANDARD')
) AS s(name, descr, instr, dept, owner, kw, conf)
JOIN departments dep ON dep.department_name = s.dept
WHERE NOT EXISTS (SELECT 1 FROM ticket_types t WHERE t.name = s.name);

UPDATE ticket_types SET service_owner_id = (SELECT id FROM users WHERE erp_user_id = 'demo-agt-aarav')
 WHERE name = 'Incident' AND service_owner_id IS NULL;

INSERT INTO categories (ticket_type_id, name)
SELECT t.id, c.name
FROM (VALUES
  ('Payroll Query', 'Deduction question'), ('Payroll Query', 'Payslip not received'),
  ('Leave & Attendance', 'Leave balance'), ('Leave & Attendance', 'Attendance correction'),
  ('Confidential Grievance', 'Workplace concern'),
  ('Application Bug', 'CRM'), ('Application Bug', 'Inventory'), ('Application Bug', 'Reporting'),
  ('Invoice Status', 'Vendor invoice'), ('Invoice Status', 'Customer invoice'),
  ('Reimbursement', 'Travel'), ('Reimbursement', 'Medical'),
  ('Purchase Requisition', 'Goods'), ('Purchase Requisition', 'Services'),
  ('Vendor Onboarding', 'New vendor'), ('Vendor Onboarding', 'Bank detail change'),
  ('Laptop & Peripherals', 'New device'), ('Laptop & Peripherals', 'Repair'),
  ('VPN & Network', 'VPN'), ('VPN & Network', 'Wi-Fi'),
  ('Product Complaint', 'Defect'), ('Product Complaint', 'Wrong item'),
  ('Order & Delivery Query', 'Delayed delivery'), ('Order & Delivery Query', 'Order status')
) AS c(type_name, name)
JOIN ticket_types t ON t.name = c.type_name
WHERE NOT EXISTS (SELECT 1 FROM categories x WHERE x.ticket_type_id = t.id AND x.name = c.name);

-- Route each service to its department (only if no rule for that service exists yet).
INSERT INTO routing_rules (name, order_index, ticket_type_id, department_id, is_active)
SELECT 'Route ' || t.name, 10, t.id, t.department_id, true
FROM ticket_types t
WHERE t.department_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM routing_rules r WHERE r.ticket_type_id = t.id);

-- --------------------------------------------------------------------------------------- tickets
DO $$
DECLARE
  default_sla_resp  int := 480;
  default_sla_res   int := 2880;
  p1_resp           int := 30;
  p1_res            int := 240;
  requesters        bigint[];
  n_tickets         int := 190;
  i                 int;
  t                 record;
  req               bigint;
  agent             bigint;
  prio              text;
  stat              text;
  src               text;
  created           timestamptz;
  age_days          double precision;
  r                 double precision;
  first_resp_min    int;
  res_hours         double precision;
  fr_due            timestamptz;
  res_due           timestamptz;
  fr_at             timestamptz;
  res_at            timestamptz;
  closed            timestamptz;
  breached          boolean;
  warned            boolean;
  title             text;
  conf              text;
  new_id            bigint;
  resolve_code      text;
  cat               bigint;
  dept              bigint;
  seq               bigint;
  titles            jsonb := '{
    "Incident": ["Production CRM is returning 500 errors", "Posting failure on month-end journal", "Report export times out", "Login page stuck on loading", "Data mismatch between inventory and ledger"],
    "Service Request": ["Need access to the shared finance drive", "Create a mailbox for the new joiner", "Install licensed design software", "Add me to the sales distribution list"],
    "Change Request": ["Add a new approval step to purchase orders", "Update tax rate configuration", "Enable multi-currency on quotes"],
    "Problem": ["Recurring VPN drops for remote staff", "Nightly sync fails every Tuesday"],
    "Payroll Query": ["Unexpected deduction on this month payslip", "Overtime missing from payslip", "Tax declaration not reflected"],
    "Leave & Attendance": ["Leave balance shows zero", "Attendance marked absent while on duty", "Comp-off not credited"],
    "Confidential Grievance": ["Concern about a team practice", "Request for a confidential discussion"],
    "Application Bug": ["Cannot save a quotation in the CRM", "Dashboard totals do not match the report", "Inventory count off after transfer", "Export to Excel gives a blank file"],
    "Invoice Status": ["Vendor invoice unpaid after 45 days", "Customer asking for a duplicate invoice", "Invoice stuck in approval"],
    "Reimbursement": ["Travel claim rejected without reason", "Medical reimbursement pending for 3 weeks", "Cab expenses not credited"],
    "Purchase Requisition": ["Requisition for 12 laptops for the new team", "Annual maintenance contract renewal", "Office furniture for the Pune floor"],
    "Vendor Onboarding": ["Onboard a new logistics vendor", "Update bank details for an existing vendor"],
    "Laptop & Peripherals": ["Laptop battery drains in an hour", "Need an external monitor", "Replace a broken keyboard", "New joiner laptop setup"],
    "VPN & Network": ["VPN disconnects every few minutes", "Wi-Fi unavailable on the third floor", "Cannot reach the file server from home"],
    "Product Complaint": ["Customer received a damaged unit", "Wrong item shipped to a dealer", "Warranty claim for a faulty batch"],
    "Order & Delivery Query": ["Order delayed beyond promised date", "Where is order SO-48211", "Partial delivery received"]
  }';
  tcount            int;
  tlist             jsonb;
BEGIN
  SELECT array_agg(id) INTO requesters FROM users WHERE erp_user_id LIKE 'demo-emp-%';

  FOR i IN 1..n_tickets LOOP
    -- a service, favouring the busier ones
    SELECT tt.id, tt.name, tt.department_id, tt.default_confidentiality
      INTO t
      FROM ticket_types tt
     WHERE tt.lifecycle_status = 'PUBLISHED' AND tt.department_id IS NOT NULL
     ORDER BY random() * (CASE WHEN tt.name IN ('Incident', 'Application Bug', 'VPN & Network', 'Payroll Query') THEN 0.45 ELSE 1 END)
     LIMIT 1;

    dept := t.department_id;
    req := requesters[1 + floor(random() * array_length(requesters, 1))::int];
    r := random();
    prio := CASE WHEN r < 0.06 THEN 'P1' WHEN r < 0.28 THEN 'P2' WHEN r < 0.78 THEN 'P3' ELSE 'P4' END;
    r := random();
    src := CASE WHEN r < 0.72 THEN 'PORTAL' WHEN r < 0.85 THEN 'EMAIL' WHEN r < 0.94 THEN 'AGENT' ELSE 'API' END;

    -- ticket volume rises towards today, with a weekday bias
    age_days := 45 * power(random(), 1.35);
    created := now() - make_interval(secs => age_days * 86400);
    IF extract(isodow FROM created) IN (6, 7) AND random() < 0.7 THEN
      created := created - interval '2 days';
    END IF;

    -- workload member of the owning department
    SELECT u.id INTO agent
      FROM department_members dm JOIN users u ON u.id = dm.user_id
     WHERE dm.department_id = dept AND u.erp_user_id LIKE 'demo-agt-%'
     ORDER BY random() LIMIT 1;

    -- status depends on age
    r := random();
    IF age_days > 18 THEN
      stat := CASE WHEN r < 0.62 THEN 'CLOSED' WHEN r < 0.93 THEN 'RESOLVED' WHEN r < 0.965 THEN 'CANCELLED' WHEN r < 0.985 THEN 'IN_PROGRESS' ELSE 'WAITING_VENDOR' END;
    ELSIF age_days > 6 THEN
      stat := CASE WHEN r < 0.42 THEN 'CLOSED' WHEN r < 0.78 THEN 'RESOLVED' WHEN r < 0.88 THEN 'IN_PROGRESS' WHEN r < 0.92 THEN 'WAITING_CUSTOMER' WHEN r < 0.95 THEN 'WAITING_VENDOR' WHEN r < 0.97 THEN 'REOPENED' ELSE 'CANCELLED' END;
    ELSE
      stat := CASE WHEN r < 0.13 THEN 'NEW' WHEN r < 0.21 THEN 'TRIAGE' WHEN r < 0.33 THEN 'ASSIGNED' WHEN r < 0.55 THEN 'IN_PROGRESS' WHEN r < 0.61 THEN 'WAITING_CUSTOMER' WHEN r < 0.64 THEN 'WAITING_APPROVAL' WHEN r < 0.80 THEN 'RESOLVED' WHEN r < 0.92 THEN 'CLOSED' WHEN r < 0.94 THEN 'REOPENED' WHEN r < 0.96 THEN 'WAITING_VENDOR' ELSE 'CANCELLED' END;
    END IF;
    IF stat IN ('NEW', 'TRIAGE') THEN agent := CASE WHEN random() < 0.7 THEN NULL ELSE agent END; END IF;

    -- SLA targets and timeline
    IF prio = 'P1' THEN
      fr_due := created + make_interval(mins => p1_resp);  res_due := created + make_interval(mins => p1_res);
    ELSE
      fr_due := created + make_interval(mins => default_sla_resp);  res_due := created + make_interval(mins => default_sla_res);
    END IF;

    fr_at := NULL; res_at := NULL; closed := NULL;
    IF stat NOT IN ('NEW', 'TRIAGE', 'CANCELLED') OR (stat = 'CANCELLED' AND random() < 0.3) THEN
      first_resp_min := CASE prio WHEN 'P1' THEN 5 + floor(random() * 50)::int ELSE 10 + floor(random() * 420)::int END;
      fr_at := created + make_interval(mins => first_resp_min);
    END IF;
    IF stat IN ('RESOLVED', 'CLOSED') THEN
      -- most work finishes inside its target; roughly one ticket in nine overruns
      res_hours := (CASE prio WHEN 'P1' THEN 0.5 + random() * 3.2 ELSE 2 + random() * 38 END)
                   * (CASE WHEN random() < 0.11 THEN 2.4 ELSE 1 END);
      res_at := least(created + make_interval(secs => res_hours * 3600), now() - interval '10 minutes');
      IF fr_at IS NULL OR fr_at > res_at THEN fr_at := created + (res_at - created) / 3; END IF;
      IF stat = 'CLOSED' THEN closed := least(res_at + make_interval(hours => 24 + floor(random() * 48)::int), now() - interval '5 minutes'); END IF;
    END IF;

    breached := (res_at IS NOT NULL AND res_at > res_due)
             OR (res_at IS NULL AND stat NOT IN ('CANCELLED') AND res_due < now());
    warned := breached OR (res_at IS NULL AND stat NOT IN ('CANCELLED') AND now() > created + (res_due - created) * 0.8);

    title := titles -> t.name ->> floor(random() * jsonb_array_length(titles -> t.name))::int;
    IF title IS NULL THEN title := t.name || ' request'; END IF;
    conf := CASE WHEN t.default_confidentiality <> 'STANDARD' THEN t.default_confidentiality ELSE 'STANDARD' END;

    SELECT id INTO cat FROM categories WHERE ticket_type_id = t.id ORDER BY random() LIMIT 1;
    seq := nextval('ticket_number_seq');
    resolve_code := (ARRAY['FIXED', 'FIXED', 'FIXED', 'WORKAROUND', 'INFORMATION_PROVIDED', 'NO_FAULT_FOUND', 'DUPLICATE'])[1 + floor(random() * 7)::int];

    INSERT INTO tickets (ticket_number, type_id, category_id, title, description, priority, status, requester_id, creator_id,
                         assignee_id, department_id, sla_policy_id, first_response_due, resolution_due, first_response_at,
                         resolved_at, closed_at, source, tags, created_at, updated_at, sla_breached, sla_warning_sent,
                         sla_breach_notified, confidentiality, resolution_code, resolution_summary, resolved_by, reopen_count,
                         impact, urgency, external_ref, sla_paused_at, sla_resolution_remaining_minutes)
    VALUES ((SELECT code FROM departments WHERE id = dept) || '-' || extract(year FROM created)::int || '-' || lpad(seq::text, 6, '0'), t.id, cat, title,
            title || '. ' || (ARRAY['Happening since this morning.', 'Needs attention before the end of the week.', 'Blocking my work right now.', 'Not urgent but it keeps coming back.'])[1 + floor(random() * 4)::int],
            prio, stat, req, req, agent, dept, CASE WHEN prio = 'P1' THEN 1 ELSE 2 END, fr_due, res_due, fr_at, res_at, closed,
            src, '{"demo": true}'::jsonb, created, coalesce(closed, res_at, fr_at, created), breached, warned, breached, conf,
            CASE WHEN res_at IS NOT NULL THEN resolve_code END,
            CASE WHEN res_at IS NOT NULL THEN (ARRAY['Restarted the service and confirmed it works.', 'Corrected the record and informed the requester.', 'Shared the steps and the requester confirmed.', 'Replaced the faulty part.', 'Configuration fixed, monitored for a day.'])[1 + floor(random() * 5)::int] END,
            CASE WHEN res_at IS NOT NULL THEN agent END,
            CASE WHEN stat = 'REOPENED' THEN 1 WHEN stat IN ('CLOSED', 'RESOLVED') AND random() < 0.07 THEN 1 ELSE 0 END,
            CASE WHEN random() < 0.4 THEN (ARRAY['LOW', 'MEDIUM', 'HIGH'])[1 + floor(random() * 3)::int] END,
            CASE WHEN random() < 0.4 THEN (ARRAY['LOW', 'MEDIUM', 'HIGH'])[1 + floor(random() * 3)::int] END,
            CASE WHEN random() < 0.18 THEN 'CASE-' || (1000 + floor(random() * 9000)::int) END,
            CASE WHEN stat = 'WAITING_CUSTOMER' THEN now() - make_interval(hours => 1 + floor(random() * 30)::int) END,
            CASE WHEN stat = 'WAITING_CUSTOMER' THEN 300 + floor(random() * 1500)::int END)
    RETURNING id INTO new_id;

    INSERT INTO ticket_assignments (ticket_id, to_department_id, to_assignee_id, reason, created_at)
    VALUES (new_id, dept, agent, 'Initial routing', created);

    INSERT INTO activity_logs (ticket_id, actor_id, action_type, created_at) VALUES (new_id, req, 'CREATED', created);
    IF stat <> 'NEW' THEN
      INSERT INTO activity_logs (ticket_id, actor_id, action_type, field_name, old_value, new_value, created_at)
      VALUES (new_id, agent, 'FIELD_CHANGED', 'status', 'NEW', stat, coalesce(fr_at, created + interval '30 minutes'));
    END IF;

    -- conversation
    IF fr_at IS NOT NULL AND agent IS NOT NULL THEN
      INSERT INTO comments (ticket_id, author_id, body, is_internal, created_at)
      VALUES (new_id, agent, (ARRAY['Thanks for raising this. I am looking into it now.', 'I have picked this up and will update you shortly.', 'Could you share a screenshot or the exact time it happened?', 'We have reproduced the issue and are working on a fix.'])[1 + floor(random() * 4)::int], false, fr_at);
      IF random() < 0.45 THEN
        INSERT INTO comments (ticket_id, author_id, body, is_internal, created_at)
        VALUES (new_id, agent, (ARRAY['Checked the logs, looks like a configuration issue on our side.', 'Raised with the vendor, waiting for their reply.', 'Similar to an earlier case, applying the same fix.'])[1 + floor(random() * 3)::int], true, fr_at + interval '20 minutes');
      END IF;
      IF random() < 0.55 THEN
        INSERT INTO comments (ticket_id, author_id, body, is_internal, created_at)
        VALUES (new_id, req, (ARRAY['Sent the screenshot, please check.', 'Still happening, any update?', 'Thanks, that works now.', 'It occurred again this afternoon.'])[1 + floor(random() * 4)::int], false, fr_at + interval '2 hours');
      END IF;
    END IF;

    -- satisfaction survey for finished work
    IF stat IN ('RESOLVED', 'CLOSED') THEN
      r := random();
      IF r < 0.55 THEN
        INSERT INTO csat_surveys (ticket_id, requester_id, rating, comment, status, submitted_at, created_at, updated_at)
        VALUES (new_id, req,
                CASE WHEN random() < 0.40 THEN 5 WHEN random() < 0.6 THEN 4 WHEN random() < 0.6 THEN 3 WHEN random() < 0.6 THEN 2 ELSE 1 END,
                CASE WHEN random() < 0.4 THEN (ARRAY['Quick and helpful, thank you.', 'Solved, but took longer than expected.', 'Great communication throughout.', 'Had to follow up twice.', 'Very professional.'])[1 + floor(random() * 5)::int] END,
                'SUBMITTED', res_at + interval '4 hours', res_at, res_at + interval '4 hours');
      ELSIF r < 0.75 THEN
        INSERT INTO csat_surveys (ticket_id, requester_id, status, created_at, updated_at) VALUES (new_id, req, 'PENDING', res_at, res_at);
      END IF;
    END IF;

    -- asset reference on device tickets
    IF t.name = 'Laptop & Peripherals' AND random() < 0.7 THEN
      INSERT INTO ticket_erp_links (ticket_id, entity_type, entity_id, source_system, label, entity_snapshot_json)
      VALUES (new_id, 'ASSET', 'LAP-' || lpad((100 + floor(random() * 900))::int::text, 5, '0'), 'CMDB',
              (ARRAY['Dell Latitude 7440', 'Lenovo ThinkPad T14', 'HP EliteBook 840'])[1 + floor(random() * 3)::int], '{}'::jsonb);
    END IF;

    -- approval waiting for the department manager
    IF stat = 'WAITING_APPROVAL' THEN
      INSERT INTO approval_tasks (ticket_id, policy_id, policy_version, step_order, approver_id, status, due_at, created_at)
      SELECT new_id, (SELECT id FROM approval_policies WHERE name = 'Demo: manager approval'), 1, 1, d.manager_id, 'PENDING',
             now() + interval '2 days', now() - interval '3 hours'
        FROM departments d WHERE d.id = dept AND d.manager_id IS NOT NULL;
    END IF;
  END LOOP;
END $$;

-- an approval policy that documents the pattern; inactive so it never affects live tickets
INSERT INTO approval_policies (name, ticket_type_id, mode, timeout_hours, block_self_approval, version, is_active)
VALUES ('Demo: manager approval', NULL, 'SINGLE', 48, true, 1, false);
INSERT INTO approval_policy_steps (policy_id, step_order, approver_type)
SELECT id, 1, 'DEPARTMENT_MANAGER' FROM approval_policies WHERE name = 'Demo: manager approval';
-- (the DO block above ran before the policy existed, so link its pending tasks now)
UPDATE approval_tasks SET policy_id = (SELECT id FROM approval_policies WHERE name = 'Demo: manager approval')
 WHERE policy_id IS NULL AND ticket_id IN (SELECT id FROM tickets WHERE tags ? 'demo');

-- a handful of recent purchasing and finance requests waiting on their manager
WITH picked AS (
  UPDATE tickets SET status = 'WAITING_APPROVAL'
   WHERE id IN (SELECT t.id FROM tickets t JOIN ticket_types tt ON tt.id = t.type_id
                 WHERE t.tags ? 'demo' AND t.status IN ('IN_PROGRESS', 'ASSIGNED', 'NEW', 'TRIAGE')
                   AND tt.name IN ('Purchase Requisition', 'Reimbursement', 'Vendor Onboarding', 'Laptop & Peripherals')
                   AND t.created_at > now() - interval '12 days'
                   AND NOT EXISTS (SELECT 1 FROM approval_tasks a WHERE a.ticket_id = t.id)
                 ORDER BY random() LIMIT 7)
  RETURNING id, department_id)
INSERT INTO approval_tasks (ticket_id, policy_id, policy_version, step_order, approver_id, status, due_at, created_at)
SELECT p.id, (SELECT id FROM approval_policies WHERE name = 'Demo: manager approval'), 1, 1, d.manager_id, 'PENDING',
       now() + interval '2 days', now() - interval '5 hours'
  FROM picked p JOIN departments d ON d.id = p.department_id AND d.manager_id IS NOT NULL;

-- decisions already made on purchasing and reimbursement requests
INSERT INTO approval_tasks (ticket_id, policy_id, policy_version, step_order, approver_id, acted_by_id, status, decision_comment, decided_at, created_at)
SELECT tk.id, (SELECT id FROM approval_policies WHERE name = 'Demo: manager approval'), 1, 1, d.manager_id, d.manager_id,
       CASE WHEN random() < 0.85 THEN 'APPROVED' ELSE 'REJECTED' END,
       (ARRAY['Within budget.', 'Approved, please proceed.', 'Needs a lower quote.'])[1 + floor(random() * 3)::int],
       tk.created_at + make_interval(hours => 4 + floor(random() * 40)::int), tk.created_at + interval '10 minutes'
  FROM tickets tk
  JOIN ticket_types tt ON tt.id = tk.type_id AND tt.name IN ('Purchase Requisition', 'Reimbursement', 'Vendor Onboarding')
  JOIN departments d ON d.id = tk.department_id AND d.manager_id IS NOT NULL
 WHERE tk.tags ? 'demo' AND tk.status IN ('IN_PROGRESS', 'RESOLVED', 'CLOSED')
   AND tk.created_at + interval '2 days' < now();

-- ------------------------------------------------------------------------------------- problems
INSERT INTO problems (problem_number, title, description, status, symptoms, root_cause, workaround, permanent_fix, owner_id, review_date, created_by, created_at, updated_at)
SELECT p.num, p.title, p.descr, p.status, p.symptoms, p.cause, p.workaround, p.fix,
       (SELECT id FROM users WHERE erp_user_id = p.owner), current_date + p.review, (SELECT id FROM users WHERE erp_user_id = p.owner),
       now() - make_interval(days => p.age), now() - make_interval(days => p.age / 3)
FROM (VALUES
  ('DEMO-PRB-001', 'Recurring VPN drops for remote staff', 'Several remote employees lose VPN every 20-30 minutes.', 'KNOWN_ERROR',
   'Disconnects roughly every 25 minutes on home broadband.', 'Gateway idle timeout is shorter than the client keep-alive.', 'Reconnect via the backup gateway vpn2.', NULL, 'demo-agt-karthik', 14, 21),
  ('DEMO-PRB-002', 'Nightly sync fails every Tuesday', 'The inventory sync job fails during the weekly maintenance window.', 'INVESTIGATING',
   'Job log shows connection refused at 02:10.', NULL, NULL, NULL, 'demo-agt-aarav', 7, 9),
  ('DEMO-PRB-003', 'Payslip PDFs missing company logo', 'Logo missing on payslips generated from the new template.', 'RESOLVED',
   'Logo area blank on PDF.', 'Template referenced an asset path that was removed.', 'Regenerate using the old template.', 'Template path corrected in release 4.2.', 'demo-agt-priya', 60, 34)
) AS p(num, title, descr, status, symptoms, cause, workaround, fix, owner, review, age);

INSERT INTO problem_tickets (problem_id, ticket_id)
SELECT p.id, tk.id FROM problems p
JOIN LATERAL (SELECT id FROM tickets WHERE tags ? 'demo' AND title ILIKE '%VPN%' ORDER BY id LIMIT 6) tk ON true
WHERE p.problem_number = 'DEMO-PRB-001' ON CONFLICT DO NOTHING;

-- -------------------------------------------------------------------------------------- changes
INSERT INTO change_requests (change_number, title, change_type, status, reason, scope, risk, impact, affected_services, implementation_plan,
                             test_plan, rollback_plan, scheduled_start, scheduled_end, actual_start, actual_end, outcome, owner_id,
                             approved_by, approved_at, approval_comment, created_at, updated_at)
SELECT c.num, c.title, c.ctype, c.status, c.reason, 'Production', c.risk, c.impact, c.services, 'Deploy during the window and monitor.',
       CASE WHEN c.ctype = 'EMERGENCY' THEN NULL ELSE 'Smoke tests in UAT before release.' END, 'Redeploy the previous version.',
       now() + make_interval(hours => c.start_h), now() + make_interval(hours => c.end_h),
       CASE WHEN c.status IN ('IMPLEMENTING', 'COMPLETED') THEN now() + make_interval(hours => c.start_h) END,
       CASE WHEN c.status = 'COMPLETED' THEN now() + make_interval(hours => c.end_h) END,
       CASE WHEN c.status = 'COMPLETED' THEN 'Completed without incident.' END,
       (SELECT id FROM users WHERE erp_user_id = c.owner),
       CASE WHEN c.status IN ('APPROVED', 'SCHEDULED', 'IMPLEMENTING', 'COMPLETED') AND c.ctype <> 'STANDARD' THEN (SELECT id FROM users WHERE erp_user_id = 'demo-mgr-suresh') END,
       CASE WHEN c.status IN ('APPROVED', 'SCHEDULED', 'IMPLEMENTING', 'COMPLETED') THEN now() - interval '2 days' END,
       CASE WHEN c.status IN ('APPROVED', 'SCHEDULED', 'IMPLEMENTING', 'COMPLETED') THEN CASE WHEN c.ctype = 'STANDARD' THEN 'Pre-approved standard change' ELSE 'Risk understood, go ahead.' END END,
       now() - interval '6 days', now() - interval '1 day'
FROM (VALUES
  ('DEMO-CHG-001', 'Upgrade CRM to release 4.3', 'NORMAL', 'SCHEDULED', 'Security fixes and reporting improvements', 'MEDIUM', 'MEDIUM', 'CRM, Reporting', 'demo-agt-aarav', 70, 74),
  ('DEMO-CHG-002', 'Replace core switch in the Pune server room', 'EMERGENCY', 'COMPLETED', 'Switch failing, intermittent packet loss', 'HIGH', 'HIGH', 'Network', 'demo-agt-rohan', -30, -27),
  ('DEMO-CHG-003', 'Rotate API keys for the payments integration', 'STANDARD', 'APPROVED', 'Quarterly rotation', 'LOW', 'LOW', 'Payments', 'demo-agt-karthik', 200, 201),
  ('DEMO-CHG-004', 'Enable multi-currency on quotations', 'NORMAL', 'SUBMITTED', 'Needed by the export sales team', 'MEDIUM', 'MEDIUM', 'CRM, Quotations', 'demo-agt-aarav', 300, 304)
) AS c(num, title, ctype, status, reason, risk, impact, services, owner, start_h, end_h);

INSERT INTO entity_links (owner_type, owner_id, source_system, entity_type, entity_id, label)
SELECT 'CHANGE', id, 'CMDB', 'ASSET', 'SW-PUNE-01', 'Core switch, Pune server room' FROM change_requests WHERE change_number = 'DEMO-CHG-002'
ON CONFLICT DO NOTHING;

-- ------------------------------------------------------------------------------ major incidents
INSERT INTO major_incidents (incident_number, title, status, severity, reason, scope, affected_services, customer_impact, incident_lead_id,
                             comms_lead_id, started_at, mitigated_at, resolved_at, closed_at, update_cadence_minutes, next_update_due_at,
                             post_incident_review, action_items, declared_by, created_at, updated_at)
SELECT i.num, i.title, i.status, i.sev, i.reason, i.scope, i.services, i.impact,
       (SELECT id FROM users WHERE erp_user_id = 'demo-agt-karthik'), (SELECT id FROM users WHERE erp_user_id = 'demo-mgr-suresh'),
       now() - make_interval(hours => i.started_h), CASE WHEN i.status IN ('MITIGATED', 'RESOLVED', 'CLOSED') THEN now() - make_interval(hours => i.started_h - 2) END,
       CASE WHEN i.status IN ('RESOLVED', 'CLOSED') THEN now() - make_interval(hours => i.started_h - 3) END,
       CASE WHEN i.status = 'CLOSED' THEN now() - make_interval(hours => i.started_h - 30) END,
       30, CASE WHEN i.status IN ('DECLARED', 'MITIGATED') THEN now() - interval '15 minutes' END,
       i.review, i.actions, (SELECT id FROM users WHERE erp_user_id = 'demo-mgr-suresh'), now() - make_interval(hours => i.started_h), now()
FROM (VALUES
  ('DEMO-MI-001', 'Email delivery delays', 'DECLARED', 'SEV2', 'Outbound mail is queueing for up to 40 minutes', 'All offices', 'Email', 'Emails to customers are arriving late.', 3, NULL, NULL),
  ('DEMO-MI-002', 'ERP unavailable during month-end close', 'CLOSED', 'SEV1', 'Database failover did not complete', 'Finance and Sales', 'ERP', 'Users could not post invoices for 2 hours.', 120,
   'Root cause: standby lagging after a storage patch. Failover runbook missing a lag check.', 'Add replication lag alert; update runbook; rehearse failover quarterly.')
) AS i(num, title, status, sev, reason, scope, services, impact, started_h, review, actions);

INSERT INTO major_incident_updates (incident_id, author_id, message, is_public, created_at)
SELECT m.id, (SELECT id FROM users WHERE erp_user_id = 'demo-agt-karthik'), u.msg, u.pub, now() - make_interval(mins => u.mins)
FROM major_incidents m
JOIN (VALUES
  ('DEMO-MI-001', 'Mail gateway queue is growing; checking the relay.', false, 170),
  ('DEMO-MI-001', 'We are aware that some emails are delayed and are working on it.', true, 160),
  ('DEMO-MI-001', 'Queue draining slowly after a relay restart; monitoring.', true, 60),
  ('DEMO-MI-002', 'ERP database failover in progress.', true, 7190),
  ('DEMO-MI-002', 'Service restored. A full review will follow.', true, 7070)
) AS u(num, msg, pub, mins) ON u.num = m.incident_number;

INSERT INTO major_incident_tickets (incident_id, ticket_id)
SELECT m.id, tk.id FROM major_incidents m
JOIN LATERAL (SELECT id FROM tickets WHERE tags ? 'demo' AND status NOT IN ('CLOSED', 'CANCELLED') ORDER BY created_at DESC LIMIT 4) tk ON true
WHERE m.incident_number = 'DEMO-MI-001' ON CONFLICT DO NOTHING;

-- -------------------------------------------------------------------------------------- automation
INSERT INTO automation_rules (name, owner_id, enabled, trigger_event, conditions_json, actions_json, max_runs_per_ticket)
VALUES ('Demo: VPN issues to P2 and tagged', (SELECT id FROM users WHERE erp_user_id = 'demo-mgr-suresh'), false, 'TICKET_CREATED',
        '{"textContains": "vpn"}', '[{"type": "SET_PRIORITY", "value": "P2"}, {"type": "ADD_TAG", "tag": "network"}]', 3);

COMMIT;

-- Summary
SELECT 'tickets' AS what, count(*) FROM tickets WHERE tags ? 'demo'
UNION ALL SELECT 'comments', count(*) FROM comments WHERE ticket_id IN (SELECT id FROM tickets WHERE tags ? 'demo')
UNION ALL SELECT 'surveys', count(*) FROM csat_surveys WHERE ticket_id IN (SELECT id FROM tickets WHERE tags ? 'demo')
UNION ALL SELECT 'approvals', count(*) FROM approval_tasks WHERE policy_id IN (SELECT id FROM approval_policies WHERE name LIKE 'Demo:%')
UNION ALL SELECT 'problems', count(*) FROM problems WHERE problem_number LIKE 'DEMO-%'
UNION ALL SELECT 'changes', count(*) FROM change_requests WHERE change_number LIKE 'DEMO-%'
UNION ALL SELECT 'major incidents', count(*) FROM major_incidents WHERE incident_number LIKE 'DEMO-%';

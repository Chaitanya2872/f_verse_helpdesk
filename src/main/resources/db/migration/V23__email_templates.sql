-- Email templates for the ticket events sent through the notification-service. They live in the same
-- admin-editable table as the in-app wording (Admin > Notification Templates); the EMAIL_ prefix keeps them apart
-- from the in-app TICKET_* rows. The body is HTML. {{placeholder}} values are HTML-escaped when rendered, so a ticket
-- title can never inject markup; the markup around them is the admin's.
--
-- Placeholders (all events): ticketNumber, title, description, priority, status, department, creatorName,
--   requesterName, handlerName, createdAt, ticketLink
-- Assigned also:  assignedBy, assignedAt, resolutionDue, firstResponseDue
-- Resolved also:  resolvedAt, resolvedBy, resolutionCode, resolutionNotes
-- A placeholder with no value renders as "-"; an unknown placeholder renders as nothing.
-- For tickets that are not STANDARD confidentiality, title / description / resolutionNotes are replaced with a
-- neutral note by the application before rendering, whatever the template says.

INSERT INTO notification_templates (event_type, subject_template, body_template) VALUES
('EMAIL_TICKET_CREATED',
 'New ticket {{ticketNumber}} - {{title}}',
 $tpl$<div style="font-family:Arial,Helvetica,sans-serif;font-size:14px;color:#0f172a;max-width:600px">
<h2 style="font-size:18px;margin:0 0 12px">New ticket {{ticketNumber}}</h2>
<table style="border-collapse:collapse;width:100%">
<tr><td style="padding:6px 12px 6px 0;color:#64748b;vertical-align:top;white-space:nowrap">Ticket</td><td style="padding:6px 0">{{ticketNumber}} - {{title}}</td></tr>
<tr><td style="padding:6px 12px 6px 0;color:#64748b;vertical-align:top;white-space:nowrap">Description</td><td style="padding:6px 0">{{description}}</td></tr>
<tr><td style="padding:6px 12px 6px 0;color:#64748b;vertical-align:top;white-space:nowrap">Created by</td><td style="padding:6px 0">{{creatorName}}</td></tr>
<tr><td style="padding:6px 12px 6px 0;color:#64748b;vertical-align:top;white-space:nowrap">Created</td><td style="padding:6px 0">{{createdAt}}</td></tr>
<tr><td style="padding:6px 12px 6px 0;color:#64748b;vertical-align:top;white-space:nowrap">Department</td><td style="padding:6px 0">{{department}}</td></tr>
<tr><td style="padding:6px 12px 6px 0;color:#64748b;vertical-align:top;white-space:nowrap">Priority</td><td style="padding:6px 0">{{priority}}</td></tr>
<tr><td style="padding:6px 12px 6px 0;color:#64748b;vertical-align:top;white-space:nowrap">Status</td><td style="padding:6px 0">{{status}}</td></tr>
</table>
<p style="margin:16px 0 0"><a href="{{ticketLink}}" style="background:#2563eb;color:#ffffff;padding:8px 14px;border-radius:6px;text-decoration:none">Open ticket</a></p>
</div>$tpl$),

('EMAIL_TICKET_ASSIGNED',
 'Ticket assigned to you: {{ticketNumber}} - {{title}}',
 $tpl$<div style="font-family:Arial,Helvetica,sans-serif;font-size:14px;color:#0f172a;max-width:600px">
<h2 style="font-size:18px;margin:0 0 12px">Ticket assigned to you: {{ticketNumber}}</h2>
<table style="border-collapse:collapse;width:100%">
<tr><td style="padding:6px 12px 6px 0;color:#64748b;vertical-align:top;white-space:nowrap">Ticket</td><td style="padding:6px 0">{{ticketNumber}} - {{title}}</td></tr>
<tr><td style="padding:6px 12px 6px 0;color:#64748b;vertical-align:top;white-space:nowrap">Description</td><td style="padding:6px 0">{{description}}</td></tr>
<tr><td style="padding:6px 12px 6px 0;color:#64748b;vertical-align:top;white-space:nowrap">Assigned to</td><td style="padding:6px 0">{{handlerName}}</td></tr>
<tr><td style="padding:6px 12px 6px 0;color:#64748b;vertical-align:top;white-space:nowrap">Assigned by</td><td style="padding:6px 0">{{assignedBy}}</td></tr>
<tr><td style="padding:6px 12px 6px 0;color:#64748b;vertical-align:top;white-space:nowrap">Assigned</td><td style="padding:6px 0">{{assignedAt}}</td></tr>
<tr><td style="padding:6px 12px 6px 0;color:#64748b;vertical-align:top;white-space:nowrap">First response due</td><td style="padding:6px 0">{{firstResponseDue}}</td></tr>
<tr><td style="padding:6px 12px 6px 0;color:#64748b;vertical-align:top;white-space:nowrap">Resolution due</td><td style="padding:6px 0">{{resolutionDue}}</td></tr>
<tr><td style="padding:6px 12px 6px 0;color:#64748b;vertical-align:top;white-space:nowrap">Department</td><td style="padding:6px 0">{{department}}</td></tr>
<tr><td style="padding:6px 12px 6px 0;color:#64748b;vertical-align:top;white-space:nowrap">Priority</td><td style="padding:6px 0">{{priority}}</td></tr>
<tr><td style="padding:6px 12px 6px 0;color:#64748b;vertical-align:top;white-space:nowrap">Status</td><td style="padding:6px 0">{{status}}</td></tr>
</table>
<p style="margin:16px 0 0"><a href="{{ticketLink}}" style="background:#2563eb;color:#ffffff;padding:8px 14px;border-radius:6px;text-decoration:none">Open ticket</a></p>
</div>$tpl$),

('EMAIL_TICKET_RESOLVED',
 'Ticket resolved: {{ticketNumber}} - {{title}}',
 $tpl$<div style="font-family:Arial,Helvetica,sans-serif;font-size:14px;color:#0f172a;max-width:600px">
<h2 style="font-size:18px;margin:0 0 12px">Ticket resolved: {{ticketNumber}}</h2>
<table style="border-collapse:collapse;width:100%">
<tr><td style="padding:6px 12px 6px 0;color:#64748b;vertical-align:top;white-space:nowrap">Ticket</td><td style="padding:6px 0">{{ticketNumber}} - {{title}}</td></tr>
<tr><td style="padding:6px 12px 6px 0;color:#64748b;vertical-align:top;white-space:nowrap">Resolution</td><td style="padding:6px 0">{{resolutionCode}}</td></tr>
<tr><td style="padding:6px 12px 6px 0;color:#64748b;vertical-align:top;white-space:nowrap">Resolution notes</td><td style="padding:6px 0">{{resolutionNotes}}</td></tr>
<tr><td style="padding:6px 12px 6px 0;color:#64748b;vertical-align:top;white-space:nowrap">Resolved</td><td style="padding:6px 0">{{resolvedAt}}</td></tr>
<tr><td style="padding:6px 12px 6px 0;color:#64748b;vertical-align:top;white-space:nowrap">Created by</td><td style="padding:6px 0">{{creatorName}}</td></tr>
<tr><td style="padding:6px 12px 6px 0;color:#64748b;vertical-align:top;white-space:nowrap">Handled by</td><td style="padding:6px 0">{{handlerName}}</td></tr>
<tr><td style="padding:6px 12px 6px 0;color:#64748b;vertical-align:top;white-space:nowrap">Department</td><td style="padding:6px 0">{{department}}</td></tr>
<tr><td style="padding:6px 12px 6px 0;color:#64748b;vertical-align:top;white-space:nowrap">Priority</td><td style="padding:6px 0">{{priority}}</td></tr>
<tr><td style="padding:6px 12px 6px 0;color:#64748b;vertical-align:top;white-space:nowrap">Status</td><td style="padding:6px 0">{{status}}</td></tr>
</table>
<p style="margin:16px 0 0"><a href="{{ticketLink}}" style="background:#2563eb;color:#ffffff;padding:8px 14px;border-radius:6px;text-decoration:none">Open ticket</a></p>
</div>$tpl$);

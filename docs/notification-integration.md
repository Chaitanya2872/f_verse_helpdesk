# Helpdesk → notification-service integration

The helpdesk never sends mail itself. When a ticket is created, assigned or resolved it records a message in a
durable outbox, and a dispatcher hands committed messages to the external **notification-service
(`f_verse_notifications`)**, which owns SMTP credentials and delivery. Nothing in the notification-service was
changed for this: it uses the endpoint that service already exposes for other modules, so existing recruitment,
onboarding, approval and interview mail is unaffected.

## Status

| Channel  | Status |
|----------|--------|
| Email    | **Implemented** against the notification-service's existing `POST /internal/notifications/email`. |
| WhatsApp | **Implemented** for the approved Meta templates `ticket_created`, `ticket_assigned`, `ticket_reminder`, through the notification-service's `POST /internal/notifications/whatsapp/template`. Off by default. See "WhatsApp template notifications". |

## Notification-service contract (read from its source)

`POST {NOTIFICATION_SERVICE_BASE_URL}/internal/notifications/email`

* **Auth:** header `X-Internal-Service-Secret` must equal that service's `app.security.internal-service-secret`
  (`INTERNAL_SERVICE_SECRET`). A mismatch gives an empty `403`.
* **Tracing:** `X-Correlation-Id` is sent on every call (the id of the request that changed the ticket).
* **Request (JSON):** `module`, `eventType`, `to[]`, `subject`, `bodyText`, `bodyHtml`, `idempotencyKey`.
  The helpdesk renders the message from its own templates (below) and sends it composed; it does not use
  `templateKey`, because the notification-service has no helpdesk templates.
* **Responses:** `200 {sent:true, status:"SENT"|"DEDUPLICATED"}` when SMTP accepted the message;
  `502 {sent:false, status:"FAILED", error}` for any failure it reports.
* **Idempotency:** the notification-service suppresses a repeated `idempotencyKey` for 10 minutes, in memory only.
  The helpdesk's own durable key (below) is the real guarantee.

How each outcome is handled:

| Response | Treated as | Result |
|---|---|---|
| 2xx, `sent:true` (incl. `DEDUPLICATED`) | accepted | row `ACCEPTED` |
| timeout, connection error, 408, 429, 5xx (incl. its own 502) | retryable | rescheduled with backoff; `FAILED` after `max-attempts` |
| 401 / 403 | permanent (bad secret) | row `FAILED`, no retry |
| 400 / other 4xx | permanent | row `FAILED`, no retry |

`429` honours `Retry-After`. Note the notification-service also answers **its own validation errors with 502**, so a
malformed address looks transient; the bounded retry limit stops it looping.

"Accepted" means the mail provider took the message. It is not a delivery receipt.

## Event flow

```
TicketService (inside the ticket transaction)
  create()  ──► publish TicketCreatedEvent(ticketId)
  update()  ──► publish TicketAssignedEvent(ticketId, assignee, actor, at)   only if the handler really changed
            └─► publish TicketResolvedEvent(ticketId, reopenCount)          only on a move INTO Resolved

TicketNotificationRecorder (@EventListener, same transaction)
  loads the ticket, RecipientResolver picks recipients, TicketEmailComposer builds the mail,
  INSERT ... ON CONFLICT DO NOTHING into notification_outbox (one row per event x recipient x channel)

        ── transaction commits (or rolls back and the rows vanish with it) ──

NotificationDispatcher (@Scheduled every poll-interval)
  claim due rows (FOR UPDATE SKIP LOCKED + lease) ► call notification-service OUTSIDE any DB tx
  ► record the attempt and outcome in notification_delivery_attempts / notification_outbox
```

Because the rows only exist once the ticket change has committed, a notification can never be sent for a change that
was rolled back, and a slow or failing notification-service can never roll back or delay a ticket operation.

### Recipients

| Event | Who |
|---|---|
| Ticket created | Admin-configured `TICKET_CREATED` subscribers for the ticket's department (or all departments) |
| Ticket assigned | The new handler only |
| Ticket resolved | Admin-configured `TICKET_RESOLVED` subscribers, the ticket's creator, its requester, and its handler |

People in several roles, and accounts sharing one address, are emailed once. Inactive accounts and people with no
usable address are skipped (logged without the address). There is **no default fan-out to a role**: HR and Admin
recipients are chosen with the admin API below.

Tickets that are not `STANDARD` confidentiality never have their title, description or resolution text placed in an
email - only the number and the link.

### Email templates

The wording lives in the helpdesk, in the same admin-editable table as the in-app notifications
(`notification_templates`, edited under **Admin > Notification Templates**). Three rows are seeded by
`V23__email_templates.sql`:

| Template | Sent for |
|---|---|
| `EMAIL_TICKET_CREATED` | ticket created |
| `EMAIL_TICKET_ASSIGNED` | ticket assigned |
| `EMAIL_TICKET_RESOLVED` | ticket resolved |

Subject and body use `{{placeholder}}` tokens; the body is HTML.

* **All events:** `ticketNumber`, `title`, `description`, `priority`, `status`, `department`, `creatorName`,
  `requesterName`, `handlerName`, `createdAt`, `ticketLink`
* **Assigned also:** `assignedBy`, `assignedAt`, `resolutionDue`, `firstResponseDue`
* **Resolved also:** `resolvedAt`, `resolvedBy`, `resolutionCode`, `resolutionNotes`

Rules: values are HTML-escaped in the body (so ticket text cannot inject markup) and used as plain text in the subject
(line breaks removed, max 250 characters); a placeholder with no value renders as `-`; an unknown placeholder renders
as nothing; a plain-text alternative is derived from the HTML and ends with the ticket link. For tickets that are not
`STANDARD` confidentiality, `title`, `description` and `resolutionNotes` are replaced with a neutral note **before**
rendering, so editing a template cannot leak them. If a template row is missing, or renders an empty subject or body,
the built-in wording is used so the mail still goes out. Edits apply to messages recorded after the change; messages
already in the outbox keep the content they were composed with.

### Idempotency and duplicates

`idempotency_key = {event key}:{recipientUserId}:{channel}`, unique in `notification_outbox`.

* created: `TICKET_CREATED:{ticketId}`
* assigned: `TICKET_ASSIGNED:{ticketId}:{assigneeId}:{epochMillis}`; re-saving the same handler publishes nothing
* resolved: `TICKET_RESOLVED:{ticketId}:{reopenCount}`, so reopen-then-resolve-again is a new event while a replay of the
  same one is not

## Configuration

All under `app.notification-service.*`; the environment variables:

| Variable | Default | Purpose |
|---|---|---|
| `NOTIFICATION_SERVICE_ENABLED` | `false` | Master switch. Off: nothing is recorded or sent. |
| `NOTIFICATION_SERVICE_BASE_URL` | `http://localhost:8092` | Where the notification-service listens |
| `NOTIFICATION_SERVICE_INTERNAL_SECRET` | *(empty)* | Must equal the notification-service's `INTERNAL_SERVICE_SECRET` |
| `NOTIFICATION_SERVICE_CONNECT_TIMEOUT` | `3s` | |
| `NOTIFICATION_SERVICE_READ_TIMEOUT` | `15s` | |
| `HELPDESK_APP_BASE_URL` | `http://localhost:5173` | Origin used to build the link to a ticket |
| `NOTIFICATION_SERVICE_MAX_ATTEMPTS` | `6` | Hand-over attempts before a row is `FAILED` |
| `NOTIFICATION_SERVICE_RETRY_BASE_DELAY` | `30s` | Backoff is `base * 2^(attempt-1)`, capped |
| `NOTIFICATION_SERVICE_RETRY_MAX_DELAY` | `15m` | |
| `NOTIFICATION_SERVICE_POLL_INTERVAL_MS` | `5000` | Dispatcher tick |

Set the secret from your secrets manager or environment; never commit it.

## Admin API (`ADMIN` role only)

* `GET /api/helpdesk/admin/notification-recipients`
* `POST /api/helpdesk/admin/notification-recipients` body `{userId, eventType: TICKET_CREATED|TICKET_RESOLVED, departmentId?}`
* `DELETE /api/helpdesk/admin/notification-recipients/{id}`
* `GET /api/helpdesk/admin/notification-outbox?status=FAILED|PENDING|ACCEPTED`
* `GET /api/helpdesk/admin/notification-outbox/{id}/attempts`
* `POST /api/helpdesk/admin/notification-outbox/{id}/retry` - re-queues a `FAILED` row with a fresh retry budget and the
  same idempotency key

Responses show delivery state only, never the notification-service's raw responses or any secret.

## Database (Flyway)

`V22`: `ticket_notification_recipients`, `notification_outbox`, `notification_delivery_attempts`.
`V23__email_templates.sql`: the three `EMAIL_TICKET_*` template rows.
`V24__whatsapp_notifications.sql`: WhatsApp columns on `notification_outbox`, and the `CANCELLED` status.

## Build, run, test

```bash
cd server/services/f_verse_helpdesk
mvn -o test "-Dspring.datasource.password=postgres"      # the whole suite, against the local ticketing_test database
mvn -o test -Dtest=NotificationServiceClientTest,RecipientResolverTest,TicketEmailComposerTest   # no database needed
mvn -o test -Dtest=NotificationIntegrationIT "-Dspring.datasource.password=postgres"

# run, with the integration on
NOTIFICATION_SERVICE_ENABLED=true \
NOTIFICATION_SERVICE_BASE_URL=http://localhost:8092 \
NOTIFICATION_SERVICE_INTERNAL_SECRET=<same value as the notification-service> \
HELPDESK_APP_BASE_URL=https://helpdesk.example.com \
mvn spring-boot:run
```

Tests mock the notification-service; no real email is ever sent. WhatsApp: `WhatsAppNotificationIT` (database),
`WhatsAppTemplateComposerTest`, `ReminderPolicyTest`, `PhoneNumbersTest`, `IdentityPhoneDirectoryTest` here, and
`WhatsAppCloudClientTest` / `WhatsAppTemplateServiceTest` in the notification-service (Meta mocked) - no WhatsApp
message is ever sent.

## WhatsApp template notifications

Same outbox, same dispatcher, same retry/backoff, same admin API as email. A WhatsApp row (`channel = WHATSAPP`)
stores a Meta template name, its language and its variables (JSON) instead of a subject and body.

```
ticket change ──► TicketNotificationEvent ──► WhatsAppNotificationRecorder (same transaction)
SLA interval  ──► WhatsAppReminderJob (reads tickets, never saves them)
                        │  INSERT ... ON CONFLICT DO NOTHING into notification_outbox (channel WHATSAPP)
                        ▼
NotificationDispatcher ──► WhatsAppDeliveryHandler
                             1. still applies? (else CANCELLED)  2. phone from identity-service  3. send
                        ──► notification-service POST /internal/notifications/whatsapp/template
                        ──► Meta Cloud API POST /{version}/{phone-number-id}/messages
```

| Template | Raised when | Recipient | Variables, in order |
|---|---|---|---|
| `ticket_created` | ticket created | requester | requester name, ticket no., subject, priority, created |
| `ticket_assigned` | handler changed through the ticket API; or ticket created with a handler already set by routing/automation | new handler | handler name, ticket no., subject, description, priority, assigned by (`Auto-assignment` for routing/automation), resolution due |
| `ticket_reminder` | `WhatsAppReminderJob`, see below | current handler | handler name, ticket no., subject, status, priority, resolution due, reminder type |

Priority is shown as the web app labels it (P1 Critical, P2 High, P3 Medium, P4 Low). Dates use
`app.whatsapp.display-time-zone` ("11 Nov 2026, 11:30 AM"; " UTC" is appended when the zone is UTC). Values are made
one-line and never empty (Meta rejects both). Restricted/confidential tickets never carry their title or description.
Headers are static template text, so no header parameters are sent. A URL button is only sent when
`url-button-index` is configured for that template (its value is the ticket id) - check the approved template in
Business Manager first; nothing is assumed.

**Reminders.** Every SLA monitoring interval, for each ticket that has an active assignee, a resolution due date, a
status other than RESOLVED/CLOSED/CANCELLED and a running (not paused) SLA clock, the highest stage reached is queued:
`SLA Breached` (past resolution due) > `SLA Due Soon` (past the ticket's SLA policy `warning_percent`, the same rule
as the in-app SLA warning) > `Second Reminder` / `First Reminder` (optional `%` of the SLA window, unset = never
sent). Key: `TICKET_REMINDER:{ticket}:{reopenCount}:{type}:{assignee}:WHATSAPP`, so each stage is sent at most once
per assignee and resolution round - whatever the number of scans or application instances (the unique key plus
ON CONFLICT DO NOTHING makes a concurrent duplicate a no-op, and the dispatcher's SKIP LOCKED lease makes the send
single). A new assignee gets their own reminders.

**Before each send** an assignment or reminder is re-checked and marked `CANCELLED` (not sent, not retried) when the
ticket is now RESOLVED/CLOSED/CANCELLED, has another handler, or (reminders) its SLA is paused. Switching WhatsApp off
cancels whatever is still queued.

**Phone numbers** come from identity-service (`GET /internal/users/profile/{erpUserId}`, which now also returns
`phoneNumber`), looked up at the first send attempt and normalised to international digits: `+`/`00` prefixes and
separators are stripped; a number without a country code is only accepted when `WHATSAPP_DEFAULT_COUNTRY_CODE` is set.
No number, or an invalid one, is a permanent failure shown in the admin outbox; identity-service being down is
retried. The admin retry looks the number up again.

**Outcomes.** `ACCEPTED` = Meta accepted the message and returned its id (stored as `provider_message_id`). This is
**not** a delivery confirmation: no WhatsApp status webhook is consumed anywhere in this codebase, so there is no
`DELIVERED` state rather than an invented one. The notification-service answers 422 for anything that can never
succeed (unknown template, parameter mismatch, undeliverable number, rejected token: not retried) and 502/503 for
transient failures (rate limits, Meta 5xx, timeouts, channel not configured: retried with backoff up to
`NOTIFICATION_SERVICE_MAX_ATTEMPTS`). Meta has no idempotency key for messages; the notification-service suppresses a
repeated key for 10 minutes in memory, so a lost response followed by a retry can, rarely, deliver twice.

**Configuration**

Helpdesk (`app.whatsapp.*`; also requires `NOTIFICATION_SERVICE_ENABLED=true`):

| Variable | Default | Purpose |
|---|---|---|
| `HELPDESK_WHATSAPP_ENABLED` (falls back to `WHATSAPP_ENABLED`) | `false` | Master switch for recording and sending |
| `WHATSAPP_TEMPLATE_LANGUAGE` | `en_US` | Language the templates were approved in |
| `WHATSAPP_TEMPLATE_TICKET_CREATED` / `_ASSIGNED` / `_REMINDER` | `ticket_created` / `ticket_assigned` / `ticket_reminder` | Template names |
| `WHATSAPP_TEMPLATE_TICKET_*_URL_BUTTON_INDEX` | *(unset)* | Only if the approved template has a dynamic URL button |
| `WHATSAPP_DISPLAY_TIME_ZONE` | `UTC` | e.g. `Asia/Kolkata` |
| `WHATSAPP_DEFAULT_COUNTRY_CODE` | *(blank)* | e.g. `91`; blank rejects numbers without a country code |
| `WHATSAPP_REMINDERS_ENABLED` | `true` | Reminder job switch |
| `WHATSAPP_FIRST_REMINDER_PERCENT` / `WHATSAPP_SECOND_REMINDER_PERCENT` | *(unset)* | Optional early reminders |
| `IDENTITY_SERVICE_BASE_URL` | `http://localhost:8081` | Phone lookup |
| `IDENTITY_SERVICE_INTERNAL_SECRET` (falls back to `INTERNAL_SERVICE_SECRET`) | *(blank)* | identity-service's internal secret |

Notification-service (`notifications.whatsapp.*`) - the only place Meta credentials exist:

| Variable | Default | Purpose |
|---|---|---|
| `WHATSAPP_ENABLED` | `false` | Channel switch |
| `WHATSAPP_ACCESS_TOKEN` | *(blank)* | System-user token. Secret - environment only |
| `WHATSAPP_PHONE_NUMBER_ID` | *(blank)* | Sending number's id |
| `WHATSAPP_API_VERSION` | *(blank)* | e.g. `v21.0`; required, never guessed |
| `WHATSAPP_GRAPH_BASE_URL` | `https://graph.facebook.com` | |
| `WHATSAPP_CONNECT_TIMEOUT` / `WHATSAPP_READ_TIMEOUT` | `5s` / `15s` | |

`V24__whatsapp_notifications.sql` adds `template_name`, `template_language`, `template_params`, `recipient_phone`,
`provider_message_id` to `notification_outbox` and the `CANCELLED` status. The admin outbox list
(`?status=CANCELLED` is accepted too) shows `templateName`, `providerMessageId` and the number masked to its last four
digits.

## Open items for the notification-service owner

1. **WhatsApp delivery receipts.** Meta reports sent/delivered/read/failed through a webhook. The gateway already
   whitelists `/api/integrations/whatsapp/webhook` for crm-service, which is not in this repository; nothing here
   consumes WhatsApp statuses, so rows stop at `ACCEPTED`. `provider_message_id` is stored so a webhook consumer can
   later move a row to delivered/failed.
2. **Automation-driven reassignment.** An automation rule that changes the handler on a status change raises no
   event (same as for email), so no `ticket_assigned` is sent for it. Routing/automation at creation is covered.
3. **Where templates live.** They are now in the helpdesk (see "Email templates"). If you would rather the
   notification-service own the wording, it would need matching templates; `TicketEmailComposer` is the one place to
   switch to a `templateKey` plus variables.
4. **Distinguishing validation from provider errors.** `/internal/notifications/email` returns 502 for both; a distinct
   status (e.g. 400/422) for validation would let the helpdesk stop retrying a bad request immediately. (The new
   WhatsApp endpoint already does this: 422 vs 502.)
5. **Email delivery receipts.** There is no callback or status lookup, so "accepted" cannot be upgraded to "delivered".
6. **Secret in their config.** `f_verse_notifications/src/main/resources/application.yml` has a real-looking SMTP
   password as a default (`SMTP_PASSWORD`) and a default internal secret. Both should come only from the environment
   and the committed password should be rotated. This service was not modified.

## Known limits

* Only a manual assignment through the ticket update API raises the "assigned" email. A handler set by a routing rule at
  creation, or by an automation rule, does not (creation subscribers still get the created email).
* Per-user notification preferences are not modelled in the helpdesk; the configured subscriptions and account status are
  the filters.
* The recorder runs inside the ticket transaction and swallows its own failures so notifications cannot fail a ticket
  operation; a failed recording is logged and that event's emails are lost (the ticket is unaffected).
* The helpdesk's existing in-app notifications and its disabled JavaMail path (`app.notifications.email-enabled`) are
  untouched and independent of this integration.

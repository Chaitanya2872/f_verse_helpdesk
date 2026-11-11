-- WhatsApp template notifications reuse the existing transactional outbox (its channel column already
-- allows 'WHATSAPP'). A WhatsApp row carries a Meta template name and its variables instead of a subject
-- and body, the number it was sent to, and Meta's message id once accepted.
ALTER TABLE notification_outbox
    ADD COLUMN template_name       VARCHAR(100),
    ADD COLUMN template_language   VARCHAR(20),
    ADD COLUMN template_params     TEXT,
    ADD COLUMN recipient_phone     VARCHAR(20),
    ADD COLUMN provider_message_id VARCHAR(200);

-- CANCELLED: deliberately not sent because it no longer applied when its turn came (ticket closed or
-- reassigned before a reminder went out, or WhatsApp switched off). Kept apart from FAILED so the
-- admin failure list only shows real delivery problems.
ALTER TABLE notification_outbox DROP CONSTRAINT notification_outbox_status_check;
ALTER TABLE notification_outbox
    ADD CONSTRAINT notification_outbox_status_check CHECK (status IN ('PENDING', 'ACCEPTED', 'FAILED', 'CANCELLED'));

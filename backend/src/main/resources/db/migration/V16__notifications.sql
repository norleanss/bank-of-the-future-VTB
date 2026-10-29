-- notification: уведомления клиенту в кабинете; одно событие смены статуса даёт не больше одного уведомления.
-- Текст — без ограничения длины: в него входят запрос сведений и причины отказа.
CREATE TABLE notification.notifications (
    id             UUID          PRIMARY KEY,
    event_id       UUID          NOT NULL UNIQUE,
    recipient_id   UUID          NOT NULL,
    application_id UUID,
    type           VARCHAR(32)   NOT NULL CHECK (type IN ('APPLICATION_SUBMITTED', 'INFO_REQUESTED', 'DECISION_MADE',
                                                         'OFFER_CONFIRMED', 'OFFER_EXPIRED', 'SIGNED', 'DISBURSED',
                                                         'STATUS_CHANGED')),
    title          VARCHAR(255)  NOT NULL,
    body           TEXT          NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL,
    read_at        TIMESTAMPTZ
);

CREATE INDEX notifications_recipient_idx ON notification.notifications (recipient_id, created_at DESC);
CREATE INDEX notifications_unread_idx ON notification.notifications (recipient_id, application_id) WHERE read_at IS NULL;

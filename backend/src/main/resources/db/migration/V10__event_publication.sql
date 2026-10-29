-- Журнал публикации событий Spring Modulith (схема v2): необработанное событие доставляется после перезапуска.
CREATE SCHEMA IF NOT EXISTS events;

CREATE TABLE events.event_publication (
    id                     UUID        NOT NULL PRIMARY KEY,
    listener_id            TEXT        NOT NULL,
    event_type             TEXT        NOT NULL,
    serialized_event       TEXT        NOT NULL,
    publication_date       TIMESTAMPTZ NOT NULL,
    completion_date        TIMESTAMPTZ,
    status                 TEXT,
    completion_attempts    INT,
    last_resubmission_date TIMESTAMPTZ
);

CREATE INDEX event_publication_serialized_event_hash_idx ON events.event_publication USING hash (serialized_event);
CREATE INDEX event_publication_by_completion_date_idx ON events.event_publication (completion_date);

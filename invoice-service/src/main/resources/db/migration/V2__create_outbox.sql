CREATE TABLE outbox_event (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    aggregate_id   UUID         NOT NULL,
    type           VARCHAR(64)  NOT NULL,
    payload        TEXT         NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL,
    sent_at        TIMESTAMPTZ
);

CREATE INDEX idx_outbox_event_unsent ON outbox_event (id) WHERE sent_at IS NULL;

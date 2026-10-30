CREATE TABLE invoice (
    id                 UUID PRIMARY KEY,
    number             VARCHAR(64)  NOT NULL,
    issue_date         DATE         NOT NULL,
    due_date           DATE,
    currency           VARCHAR(3)   NOT NULL,
    issuer_name        VARCHAR(255) NOT NULL,
    issuer_tax_id      VARCHAR(64)  NOT NULL,
    issuer_country     VARCHAR(2)   NOT NULL,
    customer_name      VARCHAR(255) NOT NULL,
    customer_tax_id    VARCHAR(64)  NOT NULL,
    customer_country   VARCHAR(2)   NOT NULL,
    status             VARCHAR(16)  NOT NULL,
    rejection_reason   TEXT,
    signed_xml         TEXT,
    created_at         TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_invoice_issuer_number UNIQUE (issuer_tax_id, number)
);

CREATE TABLE invoice_line (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    invoice_id         UUID          NOT NULL REFERENCES invoice (id) ON DELETE CASCADE,
    position           INT           NOT NULL,
    description        VARCHAR(512)  NOT NULL,
    quantity           NUMERIC(19,4) NOT NULL,
    unit_price         NUMERIC(19,4) NOT NULL,
    tax_rate_percent   NUMERIC(5,2)  NOT NULL
);

CREATE INDEX idx_invoice_line_invoice ON invoice_line (invoice_id);

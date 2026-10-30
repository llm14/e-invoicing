package com.example.einvoicing.domain;

import java.util.UUID;

/**
 * An invoice and its processing lifecycle:
 * Received → Validated → Signed → Delivered, or Rejected from any non-terminal state.
 * Immutable: every transition returns a new instance.
 */
public record Invoice(UUID id, InvoiceDocument document, InvoiceStatus status, String signedXml) {

    public static Invoice receive(InvoiceDocument document) {
        return new Invoice(UUID.randomUUID(), document, new InvoiceStatus.Received(), null);
    }

    public Invoice validated() {
        if (!(status instanceof InvoiceStatus.Received)) {
            throw new IllegalInvoiceTransitionException(status, "validate");
        }
        return new Invoice(id, document, new InvoiceStatus.Validated(), null);
    }

    public Invoice signed(String signedXml) {
        if (!(status instanceof InvoiceStatus.Validated)) {
            throw new IllegalInvoiceTransitionException(status, "sign");
        }
        if (signedXml == null || signedXml.isBlank()) {
            throw new IllegalArgumentException("signedXml must not be blank");
        }
        return new Invoice(id, document, new InvoiceStatus.Signed(), signedXml);
    }

    public Invoice delivered() {
        if (!(status instanceof InvoiceStatus.Signed)) {
            throw new IllegalInvoiceTransitionException(status, "deliver");
        }
        return new Invoice(id, document, new InvoiceStatus.Delivered(), signedXml);
    }

    public Invoice rejected(String reason) {
        if (status.isTerminal()) {
            throw new IllegalInvoiceTransitionException(status, "reject");
        }
        return new Invoice(id, document, new InvoiceStatus.Rejected(reason), signedXml);
    }
}

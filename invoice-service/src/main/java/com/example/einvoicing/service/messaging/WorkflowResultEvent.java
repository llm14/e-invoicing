package com.example.einvoicing.service.messaging;

import com.example.einvoicing.domain.Invoice;
import com.example.einvoicing.domain.InvoiceStatus;
import com.example.einvoicing.service.persistence.InvoiceMapper;
import java.time.Instant;
import java.util.UUID;

/**
 * Published to {@code workflow.results} once, when an invoice workflow ends. {@code outcome} is the
 * invoice's final status (DELIVERED or REJECTED); {@code reason} is set only for REJECTED.
 */
public record WorkflowResultEvent(
        String workflowId, UUID invoiceId, String number, String outcome, String reason, Instant occurredAt) {

    /** The workflow id is the invoice id. */
    public static WorkflowResultEvent of(Invoice invoice) {
        return new WorkflowResultEvent(
                invoice.id().toString(),
                invoice.id(),
                invoice.document().number(),
                InvoiceMapper.statusCode(invoice.status()),
                invoice.status() instanceof InvoiceStatus.Rejected rejected ? rejected.reason() : null,
                Instant.now());
    }
}

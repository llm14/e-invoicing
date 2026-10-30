package com.example.einvoicing.service.messaging;

import com.example.einvoicing.domain.Invoice;
import com.example.einvoicing.domain.InvoiceStatus;
import com.example.einvoicing.service.persistence.InvoiceMapper;
import java.time.Instant;
import java.util.UUID;

/** Published to {@code invoices.events} each time an invoice changes state. */
public record InvoiceEvent(UUID invoiceId, String number, String status, String reason, Instant occurredAt) {

    public static InvoiceEvent of(Invoice invoice) {
        return new InvoiceEvent(
                invoice.id(),
                invoice.document().number(),
                InvoiceMapper.statusCode(invoice.status()),
                invoice.status() instanceof InvoiceStatus.Rejected rejected ? rejected.reason() : null,
                Instant.now());
    }
}

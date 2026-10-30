package com.example.einvoicing.service.api;

import com.example.einvoicing.domain.Invoice;
import com.example.einvoicing.domain.InvoiceStatus;
import java.math.BigDecimal;
import java.util.UUID;

public record InvoiceResponse(
        UUID id,
        String number,
        String status,
        String rejectionReason,
        String currency,
        BigDecimal netTotal,
        BigDecimal taxTotal,
        BigDecimal grossTotal) {

    public static InvoiceResponse from(Invoice invoice) {
        var document = invoice.document();
        return new InvoiceResponse(
                invoice.id(),
                document.number(),
                invoice.status().getClass().getSimpleName().toUpperCase(),
                invoice.status() instanceof InvoiceStatus.Rejected rejected ? rejected.reason() : null,
                document.currency().getCurrencyCode(),
                document.netTotal().amount(),
                document.taxTotal().amount(),
                document.grossTotal().amount());
    }
}

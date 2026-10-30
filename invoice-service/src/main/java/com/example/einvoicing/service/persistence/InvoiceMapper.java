package com.example.einvoicing.service.persistence;

import com.example.einvoicing.domain.Invoice;
import com.example.einvoicing.domain.InvoiceDocument;
import com.example.einvoicing.domain.InvoiceLine;
import com.example.einvoicing.domain.InvoiceStatus;
import com.example.einvoicing.domain.Money;
import com.example.einvoicing.domain.Party;
import java.time.Instant;
import java.util.Currency;

public final class InvoiceMapper {

    private InvoiceMapper() {}

    public static InvoiceEntity toEntity(Invoice invoice) {
        var document = invoice.document();
        var entity = new InvoiceEntity();
        entity.id = invoice.id();
        entity.number = document.number();
        entity.issueDate = document.issueDate();
        entity.dueDate = document.dueDate();
        entity.currency = document.currency().getCurrencyCode();
        entity.issuerName = document.issuer().name();
        entity.issuerTaxId = document.issuer().taxId();
        entity.issuerCountry = document.issuer().countryCode();
        entity.customerName = document.customer().name();
        entity.customerTaxId = document.customer().taxId();
        entity.customerCountry = document.customer().countryCode();
        entity.createdAt = Instant.now();
        for (InvoiceLine line : document.lines()) {
            var lineEntity = new InvoiceLineEntity();
            lineEntity.description = line.description();
            lineEntity.quantity = line.quantity();
            lineEntity.unitPrice = line.unitPrice().amount();
            lineEntity.taxRatePercent = line.taxRatePercent();
            entity.lines.add(lineEntity);
        }
        applyState(invoice, entity);
        return entity;
    }

    /** Copies the mutable lifecycle fields; the document itself never changes after receipt. */
    public static void applyState(Invoice invoice, InvoiceEntity entity) {
        entity.status = statusCode(invoice.status());
        entity.rejectionReason =
                invoice.status() instanceof InvoiceStatus.Rejected rejected ? rejected.reason() : null;
        entity.signedXml = invoice.signedXml();
    }

    public static Invoice toDomain(InvoiceEntity entity) {
        var currency = Currency.getInstance(entity.currency);
        var lines = entity.lines.stream()
                .map(l -> new InvoiceLine(
                        l.description, l.quantity, new Money(l.unitPrice, currency), l.taxRatePercent))
                .toList();
        var document = new InvoiceDocument(
                entity.number,
                entity.issueDate,
                entity.dueDate,
                new Party(entity.issuerName, entity.issuerTaxId, entity.issuerCountry),
                new Party(entity.customerName, entity.customerTaxId, entity.customerCountry),
                currency,
                lines);
        return new Invoice(entity.id, document, status(entity), entity.signedXml);
    }

    public static String statusCode(InvoiceStatus status) {
        return switch (status) {
            case InvoiceStatus.Received r -> "RECEIVED";
            case InvoiceStatus.Validated v -> "VALIDATED";
            case InvoiceStatus.Signed s -> "SIGNED";
            case InvoiceStatus.Delivered d -> "DELIVERED";
            case InvoiceStatus.Rejected r -> "REJECTED";
        };
    }

    private static InvoiceStatus status(InvoiceEntity entity) {
        return switch (entity.status) {
            case "RECEIVED" -> new InvoiceStatus.Received();
            case "VALIDATED" -> new InvoiceStatus.Validated();
            case "SIGNED" -> new InvoiceStatus.Signed();
            case "DELIVERED" -> new InvoiceStatus.Delivered();
            case "REJECTED" -> new InvoiceStatus.Rejected(entity.rejectionReason);
            default -> throw new IllegalStateException("Unknown invoice status: " + entity.status);
        };
    }
}

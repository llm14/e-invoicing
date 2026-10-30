package com.example.einvoicing.service.api;

import com.example.einvoicing.domain.InvoiceDocument;
import com.example.einvoicing.domain.InvoiceLine;
import com.example.einvoicing.domain.Money;
import com.example.einvoicing.domain.Party;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;

public record SubmitInvoiceRequest(
        String number,
        LocalDate issueDate,
        LocalDate dueDate,
        PartyDto issuer,
        PartyDto customer,
        String currency,
        List<LineDto> lines) {

    public record PartyDto(String name, String taxId, String countryCode) {
        Party toDomain() {
            return new Party(name, taxId, countryCode);
        }
    }

    public record LineDto(
            String description, BigDecimal quantity, BigDecimal unitPrice, BigDecimal taxRatePercent) {
        InvoiceLine toDomain(Currency currency) {
            if (unitPrice == null) {
                throw new IllegalArgumentException("line unitPrice is required");
            }
            return new InvoiceLine(description, quantity, new Money(unitPrice, currency), taxRatePercent);
        }
    }

    /** @throws IllegalArgumentException if the request is incomplete or violates a domain rule */
    public InvoiceDocument toDocument() {
        if (currency == null || issuer == null || customer == null || lines == null) {
            throw new IllegalArgumentException("currency, issuer, customer and lines are required");
        }
        var parsedCurrency = Currency.getInstance(currency);
        return new InvoiceDocument(
                number,
                issueDate,
                dueDate,
                issuer.toDomain(),
                customer.toDomain(),
                parsedCurrency,
                lines.stream().map(l -> l.toDomain(parsedCurrency)).toList());
    }
}

package com.example.einvoicing.domain;

import java.time.LocalDate;
import java.util.Currency;
import java.util.List;

/**
 * The business content of an invoice. Tax is rounded per line, and the totals are the sums of
 * the rounded line amounts.
 */
public record InvoiceDocument(
        String number,
        LocalDate issueDate,
        LocalDate dueDate,
        Party issuer,
        Party customer,
        Currency currency,
        List<InvoiceLine> lines) {

    public InvoiceDocument {
        if (number == null || number.isBlank()) {
            throw new IllegalArgumentException("number must not be blank");
        }
        if (issueDate == null || issuer == null || customer == null || currency == null) {
            throw new IllegalArgumentException("issueDate, issuer, customer and currency are required");
        }
        if (dueDate != null && dueDate.isBefore(issueDate)) {
            throw new IllegalArgumentException("dueDate must not be before issueDate");
        }
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("an invoice needs at least one line");
        }
        lines = List.copyOf(lines);
        for (InvoiceLine line : lines) {
            if (!line.unitPrice().currency().equals(currency)) {
                throw new IllegalArgumentException(
                        "line '" + line.description() + "' is not in " + currency);
            }
        }
    }

    public Money netTotal() {
        return lines.stream().map(InvoiceLine::netAmount).reduce(Money.zero(currency), Money::plus);
    }

    public Money taxTotal() {
        return lines.stream().map(InvoiceLine::taxAmount).reduce(Money.zero(currency), Money::plus);
    }

    public Money grossTotal() {
        return netTotal().plus(taxTotal());
    }
}

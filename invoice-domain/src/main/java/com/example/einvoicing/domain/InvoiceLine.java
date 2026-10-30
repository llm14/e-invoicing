package com.example.einvoicing.domain;

import java.math.BigDecimal;

/** A line item. {@code taxRatePercent} is e.g. 23 for 23%. */
public record InvoiceLine(
        String description, BigDecimal quantity, Money unitPrice, BigDecimal taxRatePercent) {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public InvoiceLine {
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description must not be blank");
        }
        if (quantity == null || quantity.signum() <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        if (taxRatePercent == null
                || taxRatePercent.signum() < 0
                || taxRatePercent.compareTo(HUNDRED) > 0) {
            throw new IllegalArgumentException("taxRatePercent must be between 0 and 100");
        }
    }

    public Money netAmount() {
        return unitPrice.times(quantity);
    }

    public Money taxAmount() {
        return netAmount().times(taxRatePercent.divide(HUNDRED));
    }

    public Money grossAmount() {
        return netAmount().plus(taxAmount());
    }
}

package com.example.einvoicing.domain;

public class IllegalInvoiceTransitionException extends IllegalStateException {

    public IllegalInvoiceTransitionException(InvoiceStatus from, String action) {
        super("Cannot " + action + " an invoice that is " + from.getClass().getSimpleName());
    }
}

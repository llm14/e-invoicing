package com.example.einvoicing.service;

public class DuplicateInvoiceException extends RuntimeException {

    public DuplicateInvoiceException(String issuerTaxId, String number) {
        super("Invoice '" + number + "' already exists for issuer " + issuerTaxId);
    }
}

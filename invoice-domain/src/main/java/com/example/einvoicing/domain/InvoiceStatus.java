package com.example.einvoicing.domain;

public sealed interface InvoiceStatus {
    record Received() implements InvoiceStatus {}
    record Validated() implements InvoiceStatus {}
    record Signed() implements InvoiceStatus {}
    record Delivered() implements InvoiceStatus {}
    record Rejected(String reason) implements InvoiceStatus {}
}

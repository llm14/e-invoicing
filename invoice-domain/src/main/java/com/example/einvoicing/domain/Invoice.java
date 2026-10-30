package com.example.einvoicing.domain;

import java.util.UUID;

public record Invoice(UUID id, String issuer, String xml, InvoiceStatus status) {}

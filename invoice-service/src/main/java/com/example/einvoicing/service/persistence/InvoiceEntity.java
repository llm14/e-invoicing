package com.example.einvoicing.service.persistence;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "invoice")
public class InvoiceEntity {

    @Id
    public UUID id;

    public String number;
    public LocalDate issueDate;
    public LocalDate dueDate;
    public String currency;

    public String issuerName;
    public String issuerTaxId;
    public String issuerCountry;
    public String customerName;
    public String customerTaxId;
    public String customerCountry;

    public String status;
    public String rejectionReason;
    public String signedXml;
    public Instant createdAt;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "invoice_id", nullable = false)
    @OrderColumn(name = "position")
    public List<InvoiceLineEntity> lines = new ArrayList<>();
}

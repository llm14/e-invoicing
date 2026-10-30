package com.example.einvoicing.service.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

@Entity
@Table(name = "invoice_line")
public class InvoiceLineEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    public String description;
    public BigDecimal quantity;
    public BigDecimal unitPrice;
    public BigDecimal taxRatePercent;
}

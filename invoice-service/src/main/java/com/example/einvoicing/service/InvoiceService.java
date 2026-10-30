package com.example.einvoicing.service;

import com.example.einvoicing.domain.Invoice;
import com.example.einvoicing.domain.InvoiceDocument;
import com.example.einvoicing.service.messaging.OutboxRepository;
import com.example.einvoicing.service.persistence.InvoiceRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class InvoiceService {

    @Inject
    InvoiceRepository repository;

    @Inject
    OutboxRepository outbox;

    @Transactional
    public Invoice submit(InvoiceDocument document) {
        if (repository.exists(document.issuer().taxId(), document.number())) {
            throw new DuplicateInvoiceException(document.issuer().taxId(), document.number());
        }
        var invoice = Invoice.receive(document);
        repository.save(invoice);
        outbox.record(invoice);
        return invoice;
    }

    /** Persists a lifecycle change to an already submitted invoice and queues its event. */
    @Transactional
    public void save(Invoice invoice) {
        repository.save(invoice);
        outbox.record(invoice);
    }

    @Transactional
    public Optional<Invoice> find(UUID id) {
        return repository.find(id);
    }
}

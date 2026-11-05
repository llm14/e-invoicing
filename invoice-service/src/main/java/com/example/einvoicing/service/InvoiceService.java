package com.example.einvoicing.service;

import com.example.einvoicing.domain.Invoice;
import com.example.einvoicing.domain.InvoiceDocument;
import com.example.einvoicing.service.messaging.OutboxRepository;
import com.example.einvoicing.service.persistence.InvoiceRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
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

    /** Queues the final workflow result of a finished invoice; does nothing if already queued. */
    @Transactional
    public void recordResult(Invoice invoice) {
        outbox.recordResult(invoice);
    }

    @Transactional
    public Optional<Invoice> find(UUID id) {
        return repository.find(id);
    }

    /** Oldest unfinished invoices received before the cutoff, for the recovery sweep. */
    @Transactional
    public List<UUID> unfinishedReceivedBefore(Instant cutoff, int limit) {
        return repository.findUnfinishedIdsReceivedBefore(cutoff, limit);
    }
}

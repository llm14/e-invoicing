package com.example.einvoicing.service;

import com.example.einvoicing.domain.Invoice;
import com.example.einvoicing.domain.InvoiceDocument;
import com.example.einvoicing.service.temporal.InvoiceWorkflows;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Entry point shared by every way an invoice can arrive (REST, Kafka): store it, start processing. */
@ApplicationScoped
public class InvoiceIntake {

    @Inject
    InvoiceService invoices;

    @Inject
    InvoiceWorkflows workflows;

    /** @throws DuplicateInvoiceException if the issuer already submitted this invoice number */
    public Invoice receive(InvoiceDocument document) {
        var invoice = invoices.submit(document);
        try {
            workflows.start(invoice.id());
        } catch (RuntimeException e) {
            // The invoice is stored but processing did not start; it stays RECEIVED.
            // TODO: recover with a periodic sweep of invoices stuck in RECEIVED.
            Log.errorf(e, "Could not start workflow for invoice %s", invoice.id());
        }
        return invoice;
    }
}

package com.example.einvoicing.service.processing;

import com.example.einvoicing.crypto.XmlSigner;
import com.example.einvoicing.domain.Invoice;
import com.example.einvoicing.domain.InvoiceStatus;
import com.example.einvoicing.service.InvoiceService;
import com.example.einvoicing.workflow.InvoiceActivities;
import io.quarkus.logging.Log;
import io.temporal.failure.ApplicationFailure;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.UUID;

/**
 * Each step first checks the invoice's current state and does nothing if the step already
 * happened, so Temporal can safely re-run an activity after a crash or a lost response.
 * A singleton (not a normal-scoped bean) so Temporal registers the real object, not a proxy.
 */
@Singleton
public class InvoiceActivitiesImpl implements InvoiceActivities {

    @Inject
    InvoiceService invoices;

    @Inject
    InvoiceValidator validator;

    @Inject
    InvoiceXmlBuilder xmlBuilder;

    @Inject
    XmlSigner signer;

    @Inject
    DeliveryClient delivery;

    @Override
    public void validate(UUID invoiceId) {
        var invoice = load(invoiceId);
        if (!(invoice.status() instanceof InvoiceStatus.Received)) {
            return;
        }
        var problems = validator.problems(invoice.document());
        if (!problems.isEmpty()) {
            throw ApplicationFailure.newNonRetryableFailure(String.join("; ", problems), INVALID_INVOICE);
        }
        invoices.save(invoice.validated());
    }

    @Override
    public void sign(UUID invoiceId) {
        var invoice = load(invoiceId);
        if (!(invoice.status() instanceof InvoiceStatus.Validated)) {
            return;
        }
        var signedXml = signer.sign(xmlBuilder.build(invoice.document()));
        invoices.save(invoice.signed(signedXml));
    }

    @Override
    public void deliver(UUID invoiceId) {
        var invoice = load(invoiceId);
        if (!(invoice.status() instanceof InvoiceStatus.Signed)) {
            return;
        }
        delivery.deliver(invoiceId, invoice.signedXml());
        invoices.save(invoice.delivered());
    }

    @Override
    public void reject(UUID invoiceId, String reason) {
        var invoice = load(invoiceId);
        if (invoice.status().isTerminal()) {
            if (invoice.status() instanceof InvoiceStatus.Delivered) {
                Log.warnf("Not rejecting invoice %s: already delivered", invoiceId);
            }
            return;
        }
        invoices.save(invoice.rejected(reason));
    }

    private Invoice load(UUID invoiceId) {
        return invoices.find(invoiceId)
                .orElseThrow(() -> ApplicationFailure.newNonRetryableFailure(
                        "invoice " + invoiceId + " not found", INVALID_INVOICE));
    }
}

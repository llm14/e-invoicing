package com.example.einvoicing.service.processing;

import com.example.einvoicing.crypto.XmlSigner;
import com.example.einvoicing.domain.Invoice;
import com.example.einvoicing.domain.InvoiceStatus;
import com.example.einvoicing.service.InvoiceService;
import com.example.einvoicing.workflow.InvoiceActivities;
import io.quarkus.logging.Log;
import io.temporal.activity.Activity;
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
        attempt("validate", invoiceId, () -> doValidate(invoiceId));
    }

    @Override
    public void sign(UUID invoiceId) {
        attempt("sign", invoiceId, () -> doSign(invoiceId));
    }

    @Override
    public void deliver(UUID invoiceId) {
        attempt("deliver", invoiceId, () -> doDeliver(invoiceId));
    }

    @Override
    public void reject(UUID invoiceId, String reason) {
        attempt("reject", invoiceId, () -> doReject(invoiceId, reason));
    }

    @Override
    public void recordResult(UUID invoiceId) {
        attempt("recordResult", invoiceId, () -> doRecordResult(invoiceId));
    }

    private void doValidate(UUID invoiceId) {
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

    private void doSign(UUID invoiceId) {
        var invoice = load(invoiceId);
        if (!(invoice.status() instanceof InvoiceStatus.Validated)) {
            return;
        }
        var signedXml = signer.sign(xmlBuilder.build(invoice.document()));
        invoices.save(invoice.signed(signedXml));
    }

    private void doDeliver(UUID invoiceId) {
        var invoice = load(invoiceId);
        if (!(invoice.status() instanceof InvoiceStatus.Signed)) {
            return;
        }
        try {
            delivery.deliver(invoiceId, invoice.signedXml());
        } catch (DeliveryRefusedException e) {
            throw ApplicationFailure.newNonRetryableFailure(e.getMessage(), DELIVERY_REFUSED);
        }
        invoices.save(invoice.delivered());
    }

    private void doReject(UUID invoiceId, String reason) {
        var invoice = load(invoiceId);
        if (invoice.status().isTerminal()) {
            if (invoice.status() instanceof InvoiceStatus.Delivered) {
                Log.warnf("Not rejecting invoice %s: already delivered", invoiceId);
            }
            return;
        }
        invoices.save(invoice.rejected(reason));
    }

    private void doRecordResult(UUID invoiceId) {
        var invoice = load(invoiceId);
        if (!invoice.status().isTerminal()) {
            // Retried by Temporal: the workflow only gets here after delivering or rejecting.
            throw new IllegalStateException("invoice " + invoiceId + " has not finished: " + invoice.status());
        }
        invoices.recordResult(invoice);
    }

    /**
     * Logs every failed attempt with its number, so a step that keeps retrying shows up in our own
     * logs and not only in the Temporal UI. The failure itself is rethrown for Temporal to handle.
     */
    private static void attempt(String step, UUID invoiceId, Runnable body) {
        try {
            body.run();
        } catch (RuntimeException e) {
            Log.warnf("%s failed for invoice %s (attempt %s): %s", step, invoiceId, attemptNumber(), e.getMessage());
            throw e;
        }
    }

    private static String attemptNumber() {
        try {
            return String.valueOf(Activity.getExecutionContext().getInfo().getAttempt());
        } catch (RuntimeException e) {
            return "unknown"; // not running inside a Temporal activity, e.g. a direct call from a test
        }
    }

    private Invoice load(UUID invoiceId) {
        return invoices.find(invoiceId)
                .orElseThrow(() -> ApplicationFailure.newNonRetryableFailure(
                        "invoice " + invoiceId + " not found", INVALID_INVOICE));
    }
}

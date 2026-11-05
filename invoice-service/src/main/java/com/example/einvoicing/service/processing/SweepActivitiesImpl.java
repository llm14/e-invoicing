package com.example.einvoicing.service.processing;

import com.example.einvoicing.service.InvoiceService;
import com.example.einvoicing.service.temporal.InvoiceWorkflows;
import com.example.einvoicing.workflow.SweepActivities;
import io.quarkus.logging.Log;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.time.Instant;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Finds invoices that should be in progress but may not be, and tries to start a workflow for each.
 * Temporal answers "already started" for a healthy run, so invoices that are merely slow, for
 * example retrying through an outage, are left alone. A singleton for the same reason as
 * {@link InvoiceActivitiesImpl}: Temporal must register the real object, not a proxy.
 */
@Singleton
public class SweepActivitiesImpl implements SweepActivities {

    /** Younger invoices are skipped: their workflow was probably started a moment ago. */
    @ConfigProperty(name = "app.sweep.min-age", defaultValue = "PT2M")
    Duration minAge;

    /** Caps one run; anything left over is picked up by the next one. */
    @ConfigProperty(name = "app.sweep.batch-size", defaultValue = "500")
    int batchSize;

    @Inject
    InvoiceService invoices;

    @Inject
    InvoiceWorkflows workflows;

    @Override
    public int restartStuckInvoices() {
        var candidates = invoices.unfinishedReceivedBefore(Instant.now().minus(minAge), batchSize);
        int started = 0;
        for (var invoiceId : candidates) {
            if (workflows.startIfNotRunning(invoiceId)) {
                Log.warnf("Sweep restarted the workflow of invoice %s", invoiceId);
                started++;
            }
        }
        Log.debugf("Sweep checked %d unfinished invoices, restarted %d", candidates.size(), started);
        return started;
    }
}

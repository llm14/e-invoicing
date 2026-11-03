package com.example.einvoicing.service.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.einvoicing.domain.Invoice;
import com.example.einvoicing.domain.InvoiceDocument;
import com.example.einvoicing.domain.InvoiceLine;
import com.example.einvoicing.domain.InvoiceStatus;
import com.example.einvoicing.domain.Money;
import com.example.einvoicing.domain.Party;
import com.example.einvoicing.service.InvoiceService;
import com.example.einvoicing.service.temporal.InvoiceWorkflows;
import com.example.einvoicing.service.temporal.TemporalRuntime;
import com.example.einvoicing.workflow.InvoiceWorkflow;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Needs the docker-compose stack (Postgres and Temporal) to be running. */
@QuarkusTest
@TestProfile(SweepTest.NoMinimumAge.class)
class SweepTest {

    /** Lets the sweep pick up an invoice the moment it is stored, instead of after two minutes. */
    public static class NoMinimumAge implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("app.sweep.min-age", "PT0S");
        }
    }

    @Inject
    InvoiceService invoices;

    @Inject
    InvoiceWorkflows workflows;

    @Inject
    SweepActivitiesImpl sweep;

    @Inject
    TemporalRuntime temporal;

    /** Stored but with no workflow, as when Temporal was down at submission time. */
    private Invoice storeWithoutStartingAWorkflow() {
        var eur = Currency.getInstance("EUR");
        var document = new InvoiceDocument(
                "SWEEP " + UUID.randomUUID(),
                LocalDate.of(2026, 6, 1),
                null,
                new Party("ACME Lda", "PT500000000", "PT"),
                new Party("Client SA", "ES600000000", "ES"),
                eur,
                List.of(new InvoiceLine(
                        "Widget",
                        BigDecimal.valueOf(3),
                        new Money(new BigDecimal("19.99"), eur),
                        BigDecimal.valueOf(23))));
        return invoices.submit(document);
    }

    private void awaitStatus(UUID id, Class<? extends InvoiceStatus> status) {
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> assertThat(invoices.find(id).orElseThrow().status())
                        .isInstanceOf(status));
    }

    @Test
    void sweepStartsTheWorkflowOfAnInvoiceThatNeverGotOne() {
        var invoice = storeWithoutStartingAWorkflow();
        assertThat(invoice.status()).isInstanceOf(InvoiceStatus.Received.class);

        assertThat(sweep.restartStuckInvoices()).isGreaterThanOrEqualTo(1);

        awaitStatus(invoice.id(), InvoiceStatus.Delivered.class);
    }

    @Test
    void aFinishedInvoiceIsNeverRestarted() {
        var invoice = storeWithoutStartingAWorkflow();
        assertThat(workflows.startIfNotRunning(invoice.id())).isTrue();
        awaitStatus(invoice.id(), InvoiceStatus.Delivered.class);

        // The first run completed, so the id cannot be reused.
        assertThat(workflows.startIfNotRunning(invoice.id())).isFalse();
    }

    @Test
    void aWorkflowThatWasTerminatedIsRestartedAndResumesTheInvoice() {
        var invoice = storeWithoutStartingAWorkflow();
        // Start a run that no worker will ever pick up (nobody polls this queue), then terminate it:
        // the workflow is dead and the invoice is still RECEIVED.
        var dead = temporal.client()
                .newWorkflowStub(
                        InvoiceWorkflow.class,
                        WorkflowOptions.newBuilder()
                                .setTaskQueue("nobody-polls-this-queue")
                                .setWorkflowId(invoice.id().toString())
                                .setWorkflowIdReusePolicy(
                                        WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE_FAILED_ONLY)
                                .build());
        WorkflowClient.start(dead::process, invoice.id());
        temporal.client()
                .newUntypedWorkflowStub(invoice.id().toString())
                .terminate("test: simulate a dead workflow");

        assertThat(workflows.startIfNotRunning(invoice.id())).isTrue();

        awaitStatus(invoice.id(), InvoiceStatus.Delivered.class);
    }
}

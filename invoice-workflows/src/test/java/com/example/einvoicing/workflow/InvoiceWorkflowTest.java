package com.example.einvoicing.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import io.temporal.client.WorkflowOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InvoiceWorkflowTest {

    private TestWorkflowEnvironment env;
    private FakeActivities activities;

    @BeforeEach
    void start() {
        env = TestWorkflowEnvironment.newInstance();
        activities = new FakeActivities();
        var worker = env.newWorker(InvoiceWorkflow.TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(InvoiceWorkflowImpl.class);
        worker.registerActivitiesImplementations(activities);
        env.start();
    }

    @AfterEach
    void stop() {
        env.close();
    }

    private InvoiceWorkflow newWorkflow(UUID id) {
        return env.getWorkflowClient()
                .newWorkflowStub(
                        InvoiceWorkflow.class,
                        WorkflowOptions.newBuilder()
                                .setTaskQueue(InvoiceWorkflow.TASK_QUEUE)
                                .setWorkflowId(id.toString())
                                .build());
    }

    @Test
    void happyPathRunsEveryStepInOrder() {
        var id = UUID.randomUUID();
        var workflow = newWorkflow(id);

        workflow.process(id);

        assertThat(activities.calls).containsExactly("validate", "sign", "deliver");
        assertThat(workflow.getStatus()).isEqualTo("DELIVERED");
    }

    @Test
    void invalidInvoiceIsRejectedWithoutRetryOrSigning() {
        var id = UUID.randomUUID();
        activities.onValidate = invoice -> {
            throw ApplicationFailure.newNonRetryableFailure("total is negative", InvoiceActivities.INVALID_INVOICE);
        };
        var workflow = newWorkflow(id);

        workflow.process(id);

        assertThat(activities.calls).containsExactly("validate", "reject:total is negative");
        assertThat(workflow.getStatus()).isEqualTo("REJECTED");
    }

    @Test
    void transientDeliveryFailuresAreRetried() {
        var id = UUID.randomUUID();
        var attempts = new AtomicInteger();
        activities.onDeliver = invoice -> {
            if (attempts.incrementAndGet() < 3) {
                throw new IllegalStateException("recipient unavailable");
            }
        };
        var workflow = newWorkflow(id);

        workflow.process(id);

        assertThat(attempts).hasValue(3);
        assertThat(activities.calls).doesNotContain("reject:recipient unavailable");
        assertThat(workflow.getStatus()).isEqualTo("DELIVERED");
    }

    @Test
    void permanentDeliveryFailureRejectsTheInvoice() {
        var id = UUID.randomUUID();
        activities.onDeliver = invoice -> {
            throw new IllegalStateException("recipient unavailable");
        };
        var workflow = newWorkflow(id);

        workflow.process(id);

        assertThat(activities.calls.stream().filter("deliver"::equals).count()).isEqualTo(8);
        assertThat(activities.calls).last().asString().startsWith("reject:");
        assertThat(workflow.getStatus()).isEqualTo("REJECTED");
    }

    @Test
    void cancelSignalStopsProcessingAtTheNextStep() {
        var id = UUID.randomUUID();
        var workflow = newWorkflow(id);
        activities.onSign = invoice -> env.getWorkflowClient()
                .newWorkflowStub(InvoiceWorkflow.class, id.toString())
                .cancel();

        workflow.process(id);

        assertThat(activities.calls).containsExactly("validate", "sign", "reject:cancelled by request");
        assertThat(workflow.getStatus()).isEqualTo("REJECTED");
    }

    /** Records calls; each step can be overridden to fail or to have a side effect. */
    static class FakeActivities implements InvoiceActivities {
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        Consumer<UUID> onValidate = invoice -> {};
        Consumer<UUID> onSign = invoice -> {};
        Consumer<UUID> onDeliver = invoice -> {};

        @Override
        public void validate(UUID invoiceId) {
            calls.add("validate");
            onValidate.accept(invoiceId);
        }

        @Override
        public void sign(UUID invoiceId) {
            calls.add("sign");
            onSign.accept(invoiceId);
        }

        @Override
        public void deliver(UUID invoiceId) {
            calls.add("deliver");
            onDeliver.accept(invoiceId);
        }

        @Override
        public void reject(UUID invoiceId, String reason) {
            calls.add("reject:" + reason);
        }
    }
}

package com.example.einvoicing.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.WorkerFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Behaviour the in-memory test server in {@link InvoiceWorkflowTest} cannot reproduce, run against
 * the real Temporal server from docker-compose (localhost:7233). Skipped unless run with
 * {@code -PslowTests}.
 */
@Tag("temporal-server")
@Timeout(60)
class InvoiceWorkflowRealServerTest {

    /**
     * A cancel that arrives while a step attempt is running waits for that attempt: it finishes and
     * is not interrupted, so it cannot go on changing the invoice after the invoice has been
     * rejected. The workflow then stops before the next step.
     */
    @Test
    void aStepThatIsAlreadyRunningFinishesBeforeCancelTakesEffect() {
        var service = WorkflowServiceStubs.newLocalServiceStubs();
        var client = WorkflowClient.newInstance(service);
        var queue = "real-server-test-" + UUID.randomUUID();
        var id = UUID.randomUUID();
        List<String> calls = Collections.synchronizedList(new ArrayList<>());
        var factory = WorkerFactory.newInstance(client);
        var worker = factory.newWorker(queue);
        worker.registerWorkflowImplementationTypes(InvoiceWorkflowImpl.class);
        worker.registerActivitiesImplementations(new InvoiceActivities() {
            @Override
            public void validate(UUID invoiceId) {
                calls.add("validate");
            }

            @Override
            public void sign(UUID invoiceId) {
                calls.add("sign");
                client.newWorkflowStub(InvoiceWorkflow.class, invoiceId.toString()).cancel();
                try {
                    Thread.sleep(1500); // still running when the cancel reaches the server
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                calls.add("sign finished");
            }

            @Override
            public void deliver(UUID invoiceId) {
                calls.add("deliver");
            }

            @Override
            public void reject(UUID invoiceId, String reason) {
                calls.add("reject:" + reason);
            }

            @Override
            public void recordResult(UUID invoiceId) {
                calls.add("recordResult");
            }
        });
        factory.start();
        try {
            var workflow = client.newWorkflowStub(
                    InvoiceWorkflow.class,
                    WorkflowOptions.newBuilder().setTaskQueue(queue).setWorkflowId(id.toString()).build());

            workflow.process(id);

            assertThat(calls)
                    .containsExactly("validate", "sign", "sign finished", "reject:cancelled by request", "recordResult");
        } finally {
            factory.shutdownNow();
        }
    }
}

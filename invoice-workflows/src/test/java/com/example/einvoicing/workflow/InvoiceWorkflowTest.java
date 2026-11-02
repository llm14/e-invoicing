package com.example.einvoicing.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerFactoryOptions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
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

    /**
     * Durability: the process executing activities dies while {@code sign} is running. A brand-new
     * one, with no memory of the first, picks the work up and the workflow carries on from the history
     * held by the Temporal server; {@code validate}, already recorded as completed, is not run again.
     */
    @Test
    @Tag("slow")
    void workflowResumesWhereItStoppedWhenTheWorkerCrashes() throws Exception {
        var id = UUID.randomUUID();
        var queue = "crash-test";
        var workflow = env.getWorkflowClient()
                .newWorkflowStub(
                        InvoiceWorkflow.class,
                        WorkflowOptions.newBuilder()
                                .setTaskQueue(queue)
                                .setWorkflowId(id.toString())
                                .build());

        var beforeCrash = new FakeActivities();
        var signStarted = new CountDownLatch(1);
        beforeCrash.onSign = invoice -> {
            signStarted.countDown();
            try {
                new CountDownLatch(1).await(); // never released: the worker is killed while here
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("worker crashed", e);
            }
        };
        var workflowWorker = startWorker(queue, true, null);
        var firstActivityWorker = startWorker(queue, false, beforeCrash);

        WorkflowClient.start(workflow::process, id);
        assertThat(signStarted.await(10, TimeUnit.SECONDS)).isTrue();

        firstActivityWorker.shutdownNow();
        firstActivityWorker.awaitTermination(10, TimeUnit.SECONDS);
        assertThat(beforeCrash.calls).containsExactly("validate", "sign");

        var afterCrash = new FakeActivities();
        var secondActivityWorker = startWorker(queue, false, afterCrash);
        try {
            // The dead worker never reported on 'sign', so the server only retries it once the
            // activity's start-to-close timeout (30s) expires. The test server does not skip time
            // while an activity is in flight, so this part of the test takes about 30 real seconds.
            WorkflowStub.fromTyped(workflow).getResult(60, TimeUnit.SECONDS, Void.class);

            assertThat(afterCrash.calls).containsExactly("sign", "deliver");
            assertThat(workflow.getStatus()).isEqualTo("DELIVERED");
        } finally {
            secondActivityWorker.shutdownNow();
            workflowWorker.shutdownNow();
        }
    }

    /**
     * A worker that is independent of the environment's own, so a test can stop it on its own. It
     * runs the workflow, the activities, or both. The workflow cache is off, so every workflow task
     * rebuilds the workflow's state by replaying its history, as after a restart.
     */
    private WorkerFactory startWorker(String taskQueue, boolean workflows, InvoiceActivities activities) {
        var factory = WorkerFactory.newInstance(
                env.getWorkflowClient(),
                WorkerFactoryOptions.newBuilder().setWorkflowCacheSize(0).build());
        var worker = factory.newWorker(taskQueue);
        if (workflows) {
            worker.registerWorkflowImplementationTypes(InvoiceWorkflowImpl.class);
        }
        if (activities != null) {
            worker.registerActivitiesImplementations(activities);
        }
        factory.start();
        return factory;
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

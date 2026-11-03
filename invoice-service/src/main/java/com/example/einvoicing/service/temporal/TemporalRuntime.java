package com.example.einvoicing.service.temporal;

import com.example.einvoicing.service.processing.InvoiceActivitiesImpl;
import com.example.einvoicing.workflow.InvoiceWorkflowImpl;
import io.quarkus.logging.Log;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.WorkerFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Owns the connection to Temporal and runs the invoice worker inside this application. */
@ApplicationScoped
public class TemporalRuntime {

    @ConfigProperty(name = "app.temporal.target")
    String target;

    @ConfigProperty(name = "app.temporal.task-queue")
    String taskQueue;

    /** How long a stopping worker lets running activities finish and report before they are interrupted. */
    @ConfigProperty(name = "app.temporal.shutdown-drain", defaultValue = "PT30S")
    Duration shutdownDrain;

    @Inject
    InvoiceActivitiesImpl activities;

    private WorkflowServiceStubs service;
    private WorkerFactory factory;
    private WorkflowClient client;

    void start(@Observes StartupEvent event) {
        service = WorkflowServiceStubs.newServiceStubs(
                WorkflowServiceStubsOptions.newBuilder().setTarget(target).build());
        client = WorkflowClient.newInstance(service);
        factory = WorkerFactory.newInstance(client);
        var worker = factory.newWorker(taskQueue);
        worker.registerWorkflowImplementationTypes(InvoiceWorkflowImpl.class);
        worker.registerActivitiesImplementations(activities);
        factory.start();
        Log.infof("Temporal worker polling '%s' on %s", taskQueue, target);
    }

    /**
     * Stops polling, then gives running activities time to finish and report their result. Closing
     * the connection straight away would leave Temporal waiting for the activity's timeout and then
     * running it a second time, for example delivering an invoice twice.
     */
    void stop(@Observes ShutdownEvent event) throws InterruptedException {
        factory.shutdown();
        factory.awaitTermination(shutdownDrain.toMillis(), TimeUnit.MILLISECONDS);
        if (!factory.isTerminated()) {
            Log.warnf("Temporal worker still busy after %s, interrupting running activities", shutdownDrain);
            factory.shutdownNow();
        }
        service.shutdown();
    }

    public WorkflowClient client() {
        return client;
    }

    public String taskQueue() {
        return taskQueue;
    }
}

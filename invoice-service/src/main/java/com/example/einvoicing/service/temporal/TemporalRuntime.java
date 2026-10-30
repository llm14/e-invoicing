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
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Owns the connection to Temporal and runs the invoice worker inside this application. */
@ApplicationScoped
public class TemporalRuntime {

    @ConfigProperty(name = "app.temporal.target")
    String target;

    @ConfigProperty(name = "app.temporal.task-queue")
    String taskQueue;

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

    void stop(@Observes ShutdownEvent event) {
        factory.shutdown();
        service.shutdown();
    }

    public WorkflowClient client() {
        return client;
    }

    public String taskQueue() {
        return taskQueue;
    }
}

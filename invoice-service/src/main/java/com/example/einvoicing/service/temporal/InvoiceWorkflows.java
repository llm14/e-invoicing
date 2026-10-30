package com.example.einvoicing.service.temporal;

import com.example.einvoicing.workflow.InvoiceWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.UUID;

/** Starts and signals invoice workflows. The workflow id is the invoice id. */
@ApplicationScoped
public class InvoiceWorkflows {

    @Inject
    TemporalRuntime temporal;

    /** Returns as soon as Temporal has accepted the workflow; it does not wait for completion. */
    public void start(UUID invoiceId) {
        var workflow = temporal.client()
                .newWorkflowStub(
                        InvoiceWorkflow.class,
                        WorkflowOptions.newBuilder()
                                .setTaskQueue(temporal.taskQueue())
                                .setWorkflowId(invoiceId.toString())
                                .build());
        WorkflowClient.start(workflow::process, invoiceId);
    }

    /** @throws io.temporal.client.WorkflowNotFoundException if no workflow is running for the invoice */
    public void cancel(UUID invoiceId) {
        temporal.client().newWorkflowStub(InvoiceWorkflow.class, invoiceId.toString()).cancel();
    }
}

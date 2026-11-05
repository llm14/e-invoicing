package com.example.einvoicing.service.temporal;

import com.example.einvoicing.workflow.InvoiceWorkflow;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.UUID;

/** Starts and signals invoice workflows. The workflow id is the invoice id. */
@ApplicationScoped
public class InvoiceWorkflows {

    @Inject
    TemporalRuntime temporal;

    /**
     * Returns as soon as Temporal has accepted the workflow; it does not wait for completion.
     *
     * <p>The id can be reused only after a run that failed, timed out or was terminated, never after
     * one that completed. That lets the recovery sweep restart a dead workflow without ever
     * repeating a finished one.
     *
     * @throws WorkflowExecutionAlreadyStarted if a run is in progress or already completed
     */
    public void start(UUID invoiceId) {
        var workflow = temporal.client()
                .newWorkflowStub(
                        InvoiceWorkflow.class,
                        WorkflowOptions.newBuilder()
                                .setTaskQueue(temporal.taskQueue())
                                .setWorkflowId(invoiceId.toString())
                                .setWorkflowIdReusePolicy(
                                        WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE_FAILED_ONLY)
                                .build());
        WorkflowClient.start(workflow::process, invoiceId);
    }

    /** Like {@link #start}, but treats "already started" as success. @return true if a run was started */
    public boolean startIfNotRunning(UUID invoiceId) {
        try {
            start(invoiceId);
            return true;
        } catch (WorkflowExecutionAlreadyStarted e) {
            return false;
        }
    }

    /** @throws io.temporal.client.WorkflowNotFoundException if no workflow is running for the invoice */
    public void cancel(UUID invoiceId) {
        temporal.client().newWorkflowStub(InvoiceWorkflow.class, invoiceId.toString()).cancel();
    }
}

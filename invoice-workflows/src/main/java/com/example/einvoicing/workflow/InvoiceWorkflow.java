package com.example.einvoicing.workflow;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.util.UUID;

@WorkflowInterface
public interface InvoiceWorkflow {

    /**
     * Runs validate → sign → deliver. Infrastructure failures are retried until they succeed; the
     * invoice is rejected only when it is invalid, the recipient refuses it, or it is cancelled.
     */
    @WorkflowMethod
    void process(UUID invoiceId);

    /**
     * Stops validation or signing, even while a step is retrying; has no effect once delivery has
     * started. A step attempt that is already running is allowed to finish first.
     */
    @SignalMethod
    void cancel();

    /** One of VALIDATING, SIGNING, DELIVERING, DELIVERED, REJECTED. */
    @QueryMethod
    String getStatus();
}

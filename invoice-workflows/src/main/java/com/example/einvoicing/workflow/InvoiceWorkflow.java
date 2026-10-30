package com.example.einvoicing.workflow;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.util.UUID;

@WorkflowInterface
public interface InvoiceWorkflow {

    String TASK_QUEUE = "invoice-tasks";

    /** Runs validate → sign → deliver. On failure or cancellation the invoice is rejected. */
    @WorkflowMethod
    void process(UUID invoiceId);

    /** Stops processing at the next step boundary; has no effect once delivery has started. */
    @SignalMethod
    void cancel();

    /** One of VALIDATING, SIGNING, DELIVERING, DELIVERED, REJECTED. */
    @QueryMethod
    String getStatus();
}

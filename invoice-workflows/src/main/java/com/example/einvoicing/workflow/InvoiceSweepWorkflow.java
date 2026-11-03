package com.example.einvoicing.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Safety net for invoices whose processing never started or whose workflow died. A Temporal
 * Schedule runs it periodically; each run restarts the workflow of every unfinished invoice that
 * has no running workflow.
 */
@WorkflowInterface
public interface InvoiceSweepWorkflow {

    /** Id of the Temporal Schedule that starts this workflow. */
    String SCHEDULE_ID = "invoice-sweep";

    /** @return how many invoice workflows were started */
    @WorkflowMethod
    int sweep();
}

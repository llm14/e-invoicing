package com.example.einvoicing.workflow;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.util.UUID;

@WorkflowInterface
public interface InvoiceWorkflow {
    @WorkflowMethod
    void process(UUID invoiceId);

    @SignalMethod
    void cancel();

    @QueryMethod
    String getStatus();
}

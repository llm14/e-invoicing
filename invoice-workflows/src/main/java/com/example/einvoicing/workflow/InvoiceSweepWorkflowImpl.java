package com.example.einvoicing.workflow;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;

public class InvoiceSweepWorkflowImpl implements InvoiceSweepWorkflow {

    /** A failed run is not retried for long: the schedule starts the next one a minute later. */
    private final SweepActivities activities = Workflow.newActivityStub(
            SweepActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(2))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
                    .build());

    @Override
    public int sweep() {
        return activities.restartStuckInvoices();
    }
}

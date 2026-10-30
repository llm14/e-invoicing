package com.example.einvoicing.workflow;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.UUID;

public class InvoiceWorkflowImpl implements InvoiceWorkflow {

    private static final String CANCELLED = "cancelled";

    private final InvoiceActivities local = Workflow.newActivityStub(
            InvoiceActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setMaximumAttempts(3)
                            .setDoNotRetry(InvoiceActivities.INVALID_INVOICE)
                            .build())
                    .build());

    /** The recipient can be down for a while, so delivery backs off and retries for longer. */
    private final InvoiceActivities remote = Workflow.newActivityStub(
            InvoiceActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(60))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(2))
                            .setBackoffCoefficient(2.0)
                            .setMaximumInterval(Duration.ofMinutes(1))
                            .setMaximumAttempts(8)
                            .setDoNotRetry(InvoiceActivities.INVALID_INVOICE)
                            .build())
                    .build());

    /** Compensation must not give up early, otherwise an invoice is left half-processed. */
    private final InvoiceActivities compensation = Workflow.newActivityStub(
            InvoiceActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(1))
                            .setMaximumInterval(Duration.ofSeconds(30))
                            .build())
                    .build());

    private String status = "VALIDATING";
    private boolean cancelRequested;

    @Override
    public void process(UUID invoiceId) {
        try {
            local.validate(invoiceId);
            failIfCancelled();

            status = "SIGNING";
            local.sign(invoiceId);
            failIfCancelled();

            status = "DELIVERING";
            remote.deliver(invoiceId);
            status = "DELIVERED";
        } catch (ActivityFailure e) {
            compensate(invoiceId, reasonOf(e));
        } catch (ApplicationFailure e) {
            if (!CANCELLED.equals(e.getType())) {
                throw e;
            }
            compensate(invoiceId, "cancelled by request");
        }
    }

    @Override
    public void cancel() {
        cancelRequested = true;
    }

    @Override
    public String getStatus() {
        return status;
    }

    private void failIfCancelled() {
        if (cancelRequested) {
            throw ApplicationFailure.newNonRetryableFailure("cancel requested", CANCELLED);
        }
    }

    private void compensate(UUID invoiceId, String reason) {
        compensation.reject(invoiceId, reason);
        status = "REJECTED";
    }

    private static String reasonOf(ActivityFailure failure) {
        return switch (failure.getCause()) {
            case ApplicationFailure application -> application.getOriginalMessage();
            case Throwable other when other.getMessage() != null -> other.getMessage();
            case null, default -> failure.getMessage();
        };
    }
}

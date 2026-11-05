package com.example.einvoicing.workflow;

import io.temporal.activity.ActivityCancellationType;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.CanceledFailure;
import io.temporal.workflow.CancellationScope;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.UUID;

public class InvoiceWorkflowImpl implements InvoiceWorkflow {

    private static final String CANCELLED = "cancelled";
    private static final String CANCELLED_REASON = "cancelled by request";

    /**
     * Validation and signing retry every failure except an invalid invoice, with no attempt limit:
     * an outage of the database or the key service must delay an invoice, not reject it. Waiting
     * for a cancelled attempt to finish means a cancel never leaves a step running in the
     * background that could still change the invoice after it has been rejected.
     */
    private final InvoiceActivities preDelivery = Workflow.newActivityStub(
            InvoiceActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setCancellationType(ActivityCancellationType.WAIT_CANCELLATION_COMPLETED)
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(1))
                            .setBackoffCoefficient(2.0)
                            .setMaximumInterval(Duration.ofMinutes(1))
                            .setDoNotRetry(InvoiceActivities.INVALID_INVOICE)
                            .build())
                    .build());

    /**
     * The recipient can be down for a long time, and a signed invoice may already have reached it,
     * so delivery keeps retrying. Only the recipient refusing the invoice for good ends it.
     */
    private final InvoiceActivities delivery = Workflow.newActivityStub(
            InvoiceActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(60))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(2))
                            .setBackoffCoefficient(2.0)
                            .setMaximumInterval(Duration.ofMinutes(1))
                            .setDoNotRetry(InvoiceActivities.INVALID_INVOICE, InvoiceActivities.DELIVERY_REFUSED)
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

    /** Only writes a database row, but must not give up: every run has to report its result. */
    private final InvoiceActivities results = Workflow.newActivityStub(
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
    private CancellationScope beforeDelivery;

    @Override
    public void process(UUID invoiceId) {
        try {
            runBeforeDelivery(invoiceId);
            failIfCancelled();

            status = "DELIVERING";
            delivery.deliver(invoiceId);
            status = "DELIVERED";
        } catch (CanceledFailure e) {
            compensate(invoiceId, CANCELLED_REASON);
        } catch (ActivityFailure e) {
            compensate(invoiceId, e.getCause() instanceof CanceledFailure ? CANCELLED_REASON : reasonOf(e));
        } catch (ApplicationFailure e) {
            if (!CANCELLED.equals(e.getType())) {
                throw e;
            }
            compensate(invoiceId, CANCELLED_REASON);
        }
        results.recordResult(invoiceId);
    }

    /** Runs validate and sign in a scope that {@link #cancel()} can interrupt. */
    private void runBeforeDelivery(UUID invoiceId) {
        beforeDelivery = Workflow.newCancellationScope(() -> {
            preDelivery.validate(invoiceId);
            status = "SIGNING";
            preDelivery.sign(invoiceId);
        });
        if (cancelRequested) {
            beforeDelivery.cancel(); // the signal arrived before the scope existed
        }
        beforeDelivery.run();
    }

    @Override
    public void cancel() {
        cancelRequested = true;
        if (beforeDelivery != null) {
            beforeDelivery.cancel();
        }
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

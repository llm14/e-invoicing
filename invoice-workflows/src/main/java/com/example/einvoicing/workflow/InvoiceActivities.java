package com.example.einvoicing.workflow;

import io.temporal.activity.ActivityInterface;
import java.util.UUID;

/**
 * Side-effecting steps of invoice processing. Implementations load the invoice by id (payloads
 * stay small) and must be idempotent, since Temporal may run an activity more than once.
 *
 * <p>An unrecoverable problem is reported with a non-retryable {@code ApplicationFailure} of type
 * {@link #INVALID_INVOICE}; any other exception is retried according to the workflow's policy.
 */
@ActivityInterface
public interface InvoiceActivities {

    String INVALID_INVOICE = "InvalidInvoice";

    /** Checks business rules and moves the invoice to Validated. */
    void validate(UUID invoiceId);

    /** Produces the invoice XML, signs it and moves the invoice to Signed. */
    void sign(UUID invoiceId);

    /** Sends the signed invoice to the recipient and moves the invoice to Delivered. */
    void deliver(UUID invoiceId);

    /** Compensation: moves the invoice to Rejected. */
    void reject(UUID invoiceId, String reason);
}

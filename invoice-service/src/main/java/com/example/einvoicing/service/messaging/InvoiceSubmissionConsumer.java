package com.example.einvoicing.service.messaging;

import com.example.einvoicing.service.DuplicateInvoiceException;
import com.example.einvoicing.service.InvoiceIntake;
import com.example.einvoicing.service.api.SubmitInvoiceRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;

/**
 * Consumes invoices (same JSON as {@code POST /invoices}) from {@code invoices.submitted}.
 * A message that cannot be parsed or fails validation throws, which sends it to the
 * dead-letter topic {@code invoices.submitted.dlq} instead of blocking the partition.
 */
@ApplicationScoped
public class InvoiceSubmissionConsumer {

    @Inject
    ObjectMapper mapper;

    @Inject
    InvoiceIntake intake;

    @Incoming("invoices-submitted")
    @Blocking
    public void consume(String payload) throws JsonProcessingException {
        var request = mapper.readValue(payload, SubmitInvoiceRequest.class);
        try {
            var invoice = intake.receive(request.toDocument());
            Log.infof("Received invoice %s (%s) from Kafka", invoice.document().number(), invoice.id());
        } catch (DuplicateInvoiceException e) {
            // Kafka delivers at least once, so a redelivered message is expected: already handled.
            Log.infof("Ignoring duplicate from Kafka: %s", e.getMessage());
        }
    }
}

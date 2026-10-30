package com.example.einvoicing.service.messaging;

import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.reactive.messaging.MutinyEmitter;
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Message;

/**
 * Publishes outbox rows to Kafka, keyed by invoice id so each invoice's events stay ordered.
 * Delivery is at-least-once: a crash between the broker ack and the commit republishes the
 * event, so consumers should deduplicate on the {@code event-id} header.
 */
@ApplicationScoped
public class OutboxRelay {

    private static final int BATCH_SIZE = 100;

    @Inject
    OutboxRepository outbox;

    @Inject
    @Channel("invoice-events")
    MutinyEmitter<String> emitter;

    // The first tick would otherwise race the messaging runtime, which connects the emitter
    // channel asynchronously after startup. Unsent rows simply wait in the table.
    @Scheduled(every = "1s", delayed = "5s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    @Transactional
    void relay() {
        for (var event : outbox.lockUnsent(BATCH_SIZE)) {
            try {
                emitter.sendMessageAndAwait(toMessage(event));
            } catch (RuntimeException e) {
                // Keep what was already sent; the rest is retried on the next tick, in order.
                Log.warnf("Could not publish outbox event %d, will retry: %s", event.id, e.getMessage());
                return;
            }
            outbox.markSent(event);
        }
    }

    private static Message<String> toMessage(OutboxEventEntity event) {
        var headers = new RecordHeaders()
                .add("event-id", String.valueOf(event.id).getBytes(StandardCharsets.UTF_8))
                .add("event-type", event.type.getBytes(StandardCharsets.UTF_8));
        return Message.of(event.payload)
                .addMetadata(OutgoingKafkaRecordMetadata.<String>builder()
                        .withKey(event.aggregateId.toString())
                        .withHeaders(headers)
                        .build());
    }
}

package com.example.einvoicing.service.messaging;

import com.example.einvoicing.domain.Invoice;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.LockOptions;

/**
 * Transactional outbox: state changes write their event row in the same transaction as the
 * invoice update, and {@link OutboxRelay} publishes the rows to Kafka afterwards.
 */
@ApplicationScoped
public class OutboxRepository implements PanacheRepository<OutboxEventEntity> {

    /** Outbox event type of a workflow's final result; the relay sends it to {@code workflow.results}. */
    public static final String WORKFLOW_RESULT = "workflow.result";

    @Inject
    ObjectMapper mapper;

    /** Must be called inside the transaction that persists the invoice change. */
    public void record(Invoice invoice) {
        var event = InvoiceEvent.of(invoice);
        persist(invoice.id(), "invoice." + event.status().toLowerCase(), event.occurredAt(), event);
    }

    /**
     * Queues the workflow's final result for {@code workflow.results}, once per invoice: a re-run of
     * the activity (Temporal may repeat it) finds the row and does nothing.
     * Must be called inside a transaction.
     */
    public void recordResult(Invoice invoice) {
        if (count("aggregateId = ?1 and type = ?2", invoice.id(), WORKFLOW_RESULT) > 0) {
            return;
        }
        var event = WorkflowResultEvent.of(invoice);
        persist(invoice.id(), WORKFLOW_RESULT, event.occurredAt(), event);
    }

    private void persist(UUID aggregateId, String type, Instant createdAt, Object event) {
        var entity = new OutboxEventEntity();
        entity.aggregateId = aggregateId;
        entity.type = type;
        entity.createdAt = createdAt;
        try {
            entity.payload = mapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize " + event, e);
        }
        persist(entity);
    }

    /** Oldest unsent events; rows locked by another relay instance are skipped. */
    public List<OutboxEventEntity> lockUnsent(int limit) {
        return find("sentAt is null order by id")
                .withLock(LockModeType.PESSIMISTIC_WRITE)
                .withHint("jakarta.persistence.lock.timeout", LockOptions.SKIP_LOCKED)
                .page(0, limit)
                .list();
    }

    public void markSent(OutboxEventEntity event) {
        event.sentAt = Instant.now();
    }
}

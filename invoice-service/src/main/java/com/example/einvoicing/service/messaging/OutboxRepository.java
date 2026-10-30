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
import org.hibernate.LockOptions;

/**
 * Transactional outbox: state changes write their event row in the same transaction as the
 * invoice update, and {@link OutboxRelay} publishes the rows to Kafka afterwards.
 */
@ApplicationScoped
public class OutboxRepository implements PanacheRepository<OutboxEventEntity> {

    @Inject
    ObjectMapper mapper;

    /** Must be called inside the transaction that persists the invoice change. */
    public void record(Invoice invoice) {
        var event = InvoiceEvent.of(invoice);
        var entity = new OutboxEventEntity();
        entity.aggregateId = invoice.id();
        entity.type = "invoice." + event.status().toLowerCase();
        entity.createdAt = event.occurredAt();
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

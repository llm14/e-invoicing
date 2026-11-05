package com.example.einvoicing.service.persistence;

import com.example.einvoicing.domain.Invoice;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import com.example.einvoicing.domain.InvoiceStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class InvoiceRepository implements PanacheRepositoryBase<InvoiceEntity, UUID> {

    /** Inserts a new invoice, or updates the lifecycle state of an existing one. */
    public void save(Invoice invoice) {
        var existing = findById(invoice.id());
        if (existing == null) {
            persist(InvoiceMapper.toEntity(invoice));
        } else {
            InvoiceMapper.applyState(invoice, existing);
        }
    }

    public boolean exists(String issuerTaxId, String number) {
        return count("issuerTaxId = ?1 and number = ?2", issuerTaxId, number) > 0;
    }

    public Optional<Invoice> find(UUID id) {
        return Optional.ofNullable(findById(id)).map(InvoiceMapper::toDomain);
    }

    /** Ids of invoices that have not reached Delivered or Rejected, oldest first, received before the cutoff. */
    public List<UUID> findUnfinishedIdsReceivedBefore(Instant cutoff, int limit) {
        var finished = List.of(
                InvoiceMapper.statusCode(new InvoiceStatus.Delivered()),
                InvoiceMapper.statusCode(new InvoiceStatus.Rejected(null)));
        return find("status not in ?1 and createdAt < ?2 order by createdAt", finished, cutoff)
                .page(0, limit)
                .stream()
                .map(entity -> entity.id)
                .toList();
    }
}

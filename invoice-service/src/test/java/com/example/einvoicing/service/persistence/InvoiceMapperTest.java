package com.example.einvoicing.service.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.einvoicing.domain.Invoice;
import com.example.einvoicing.domain.InvoiceDocument;
import com.example.einvoicing.domain.InvoiceLine;
import com.example.einvoicing.domain.InvoiceStatus;
import com.example.einvoicing.domain.Money;
import com.example.einvoicing.domain.Party;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.Test;

class InvoiceMapperTest {

    private static Invoice newInvoice() {
        var document = new InvoiceDocument(
                "FT 2026/1",
                LocalDate.of(2026, 6, 1),
                null,
                new Party("ACME Lda", "PT500000000", "PT"),
                new Party("Client SA", "ES600000000", "ES"),
                Currency.getInstance("EUR"),
                List.of(new InvoiceLine("Widget", new BigDecimal("3"), Money.of("19.99", "EUR"), new BigDecimal("23"))));
        return Invoice.receive(document);
    }

    @Test
    void roundTripsADocument() {
        var invoice = newInvoice();
        assertThat(InvoiceMapper.toDomain(InvoiceMapper.toEntity(invoice))).isEqualTo(invoice);
    }

    @Test
    void roundTripsEveryLifecycleState() {
        var received = newInvoice();
        var signed = received.validated().signed("<signed/>");

        for (var invoice : List.of(
                received, received.validated(), signed, signed.delivered(), received.rejected("bad"))) {
            assertThat(InvoiceMapper.toDomain(InvoiceMapper.toEntity(invoice))).isEqualTo(invoice);
        }
    }

    @Test
    void applyStateUpdatesOnlyLifecycleFields() {
        var received = newInvoice();
        var entity = InvoiceMapper.toEntity(received);

        InvoiceMapper.applyState(received.rejected("expired cert"), entity);

        assertThat(entity.status).isEqualTo("REJECTED");
        assertThat(entity.rejectionReason).isEqualTo("expired cert");
        assertThat(InvoiceMapper.toDomain(entity).status()).isEqualTo(new InvoiceStatus.Rejected("expired cert"));
    }
}

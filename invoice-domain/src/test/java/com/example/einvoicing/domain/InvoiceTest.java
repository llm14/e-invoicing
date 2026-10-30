package com.example.einvoicing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.Test;

class InvoiceTest {

    private static final Currency EUR = Currency.getInstance("EUR");
    private static final Party ACME = new Party("ACME Lda", "PT500000000", "PT");
    private static final Party CUSTOMER = new Party("Client SA", "ES600000000", "ES");

    private static InvoiceLine line(String qty, String price, String taxPercent) {
        return new InvoiceLine("Item", new BigDecimal(qty), Money.of(price, "EUR"), new BigDecimal(taxPercent));
    }

    private static InvoiceDocument document(InvoiceLine... lines) {
        return new InvoiceDocument(
                "FT 2026/1", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 1), ACME, CUSTOMER, EUR, List.of(lines));
    }

    @Test
    void moneyIsRoundedToTheCurrencyScale() {
        assertThat(Money.of("10.005", "EUR").amount()).isEqualByComparingTo("10.01");
        assertThat(Money.of("100", "JPY").amount().scale()).isZero();
    }

    @Test
    void moneyRejectsMixedCurrencies() {
        assertThatThrownBy(() -> Money.of("1", "EUR").plus(Money.of("1", "USD")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lineAmounts() {
        var line = line("3", "19.99", "23");
        assertThat(line.netAmount().amount()).isEqualByComparingTo("59.97");
        assertThat(line.taxAmount().amount()).isEqualByComparingTo("13.79");
        assertThat(line.grossAmount().amount()).isEqualByComparingTo("73.76");
    }

    @Test
    void documentTotalsSumRoundedLines() {
        var doc = document(line("1", "10.00", "23"), line("2", "5.50", "6"));
        assertThat(doc.netTotal().amount()).isEqualByComparingTo("21.00");
        assertThat(doc.taxTotal().amount()).isEqualByComparingTo("2.96");
        assertThat(doc.grossTotal().amount()).isEqualByComparingTo("23.96");
    }

    @Test
    void documentRejectsInvalidContent() {
        assertThatThrownBy(() -> document()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InvoiceDocument(
                        "N", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 5, 1), ACME, CUSTOMER, EUR,
                        List.of(line("1", "1", "0"))))
                .hasMessageContaining("dueDate");
        assertThatThrownBy(() -> new InvoiceDocument(
                        "N", LocalDate.of(2026, 6, 1), null, ACME, CUSTOMER, EUR,
                        List.of(new InvoiceLine("x", BigDecimal.ONE, Money.of("1", "USD"), BigDecimal.ZERO))))
                .hasMessageContaining("not in EUR");
    }

    @Test
    void lineAndPartyRejectInvalidValues() {
        assertThatThrownBy(() -> line("0", "1", "0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> line("1", "1", "101")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Party("X", "1", "pt")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void happyPathLifecycle() {
        var invoice = Invoice.receive(document(line("1", "10", "23")));
        assertThat(invoice.status()).isInstanceOf(InvoiceStatus.Received.class);

        var delivered = invoice.validated().signed("<signed/>").delivered();

        assertThat(delivered.status()).isInstanceOf(InvoiceStatus.Delivered.class);
        assertThat(delivered.signedXml()).isEqualTo("<signed/>");
        assertThat(delivered.id()).isEqualTo(invoice.id());
    }

    @Test
    void transitionsMustFollowTheLifecycle() {
        var received = Invoice.receive(document(line("1", "10", "23")));
        assertThatThrownBy(() -> received.signed("<s/>")).isInstanceOf(IllegalInvoiceTransitionException.class);
        assertThatThrownBy(received::delivered).isInstanceOf(IllegalInvoiceTransitionException.class);
        assertThatThrownBy(() -> received.validated().validated())
                .isInstanceOf(IllegalInvoiceTransitionException.class);
    }

    @Test
    void rejectionIsPossibleUntilTerminal() {
        var received = Invoice.receive(document(line("1", "10", "23")));
        var rejected = received.validated().rejected("bad signature");

        assertThat(rejected.status()).isEqualTo(new InvoiceStatus.Rejected("bad signature"));
        assertThatThrownBy(() -> rejected.rejected("again")).isInstanceOf(IllegalInvoiceTransitionException.class);
        assertThatThrownBy(() -> received.validated().signed("<s/>").delivered().rejected("late"))
                .isInstanceOf(IllegalInvoiceTransitionException.class);
    }
}

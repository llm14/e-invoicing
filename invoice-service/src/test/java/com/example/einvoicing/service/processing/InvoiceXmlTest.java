package com.example.einvoicing.service.processing;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.einvoicing.crypto.EnvelopedXmlSignatureValidator;
import com.example.einvoicing.crypto.EnvelopedXmlSigner;
import com.example.einvoicing.domain.InvoiceDocument;
import com.example.einvoicing.domain.InvoiceLine;
import com.example.einvoicing.domain.Money;
import com.example.einvoicing.domain.Party;
import java.math.BigDecimal;
import java.security.KeyPairGenerator;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.Test;

class InvoiceXmlTest {

    private static InvoiceDocument document(String description, String customerTaxId) {
        return new InvoiceDocument(
                "FT 2026/1",
                LocalDate.of(2026, 6, 1),
                null,
                new Party("ACME & Sons Lda", "PT500000000", "PT"),
                new Party("Client SA", customerTaxId, "ES"),
                Currency.getInstance("EUR"),
                List.of(new InvoiceLine(description, new BigDecimal("3"), Money.of("19.99", "EUR"), new BigDecimal("23"))));
    }

    @Test
    void xmlContainsTheInvoiceDataAndEscapesText() {
        var xml = new InvoiceXmlBuilder().build(document("<b>Widget</b>", "ES600000000"));

        assertThat(xml)
                .contains("<ID>FT 2026/1</ID>")
                .contains("<Name>ACME &amp; Sons Lda</Name>")
                .contains("<Description>&lt;b&gt;Widget&lt;/b&gt;</Description>")
                .contains("<Gross>73.76</Gross>")
                .doesNotContain("<DueDate>");
    }

    @Test
    void generatedXmlCanBeSignedAndVerified() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var keys = generator.generateKeyPair();

        var signed = new EnvelopedXmlSigner(keys.getPrivate())
                .sign(new InvoiceXmlBuilder().build(document("Widget", "ES600000000")));

        assertThat(new EnvelopedXmlSignatureValidator(keys.getPublic()).validate(signed)).isTrue();
    }

    @Test
    void validatorFlagsNonPositiveTotalsAndSelfInvoicing() {
        var validator = new InvoiceValidator();

        assertThat(validator.problems(document("Widget", "ES600000000"))).isEmpty();
        assertThat(validator.problems(document("Widget", "PT500000000"))).containsExactly("issuer and customer must differ");
    }
}

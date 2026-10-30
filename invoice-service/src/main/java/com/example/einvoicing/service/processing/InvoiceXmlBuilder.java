package com.example.einvoicing.service.processing;

import com.example.einvoicing.domain.InvoiceDocument;
import com.example.einvoicing.domain.InvoiceLine;
import com.example.einvoicing.domain.Party;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.StringWriter;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Renders an invoice as a simple, namespace-free XML document (not a UBL/CII conformant one). */
@ApplicationScoped
public class InvoiceXmlBuilder {

    public String build(InvoiceDocument invoice) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            Document xml = factory.newDocumentBuilder().newDocument();

            Element root = xml.createElement("Invoice");
            xml.appendChild(root);
            text(xml, root, "ID", invoice.number());
            text(xml, root, "IssueDate", invoice.issueDate().toString());
            if (invoice.dueDate() != null) {
                text(xml, root, "DueDate", invoice.dueDate().toString());
            }
            text(xml, root, "DocumentCurrencyCode", invoice.currency().getCurrencyCode());
            party(xml, root, "Supplier", invoice.issuer());
            party(xml, root, "Customer", invoice.customer());

            int position = 1;
            for (InvoiceLine line : invoice.lines()) {
                Element element = xml.createElement("InvoiceLine");
                root.appendChild(element);
                text(xml, element, "ID", String.valueOf(position++));
                text(xml, element, "Description", line.description());
                text(xml, element, "Quantity", line.quantity().toPlainString());
                text(xml, element, "UnitPrice", line.unitPrice().amount().toPlainString());
                text(xml, element, "TaxPercent", line.taxRatePercent().toPlainString());
                text(xml, element, "NetAmount", line.netAmount().amount().toPlainString());
                text(xml, element, "TaxAmount", line.taxAmount().amount().toPlainString());
            }

            Element totals = xml.createElement("Totals");
            root.appendChild(totals);
            text(xml, totals, "Net", invoice.netTotal().amount().toPlainString());
            text(xml, totals, "Tax", invoice.taxTotal().amount().toPlainString());
            text(xml, totals, "Gross", invoice.grossTotal().amount().toPlainString());

            return serialize(xml);
        } catch (Exception e) {
            throw new IllegalStateException("Could not build invoice XML", e);
        }
    }

    private static void party(Document xml, Element root, String role, Party party) {
        Element element = xml.createElement(role);
        root.appendChild(element);
        text(xml, element, "Name", party.name());
        text(xml, element, "TaxID", party.taxId());
        text(xml, element, "Country", party.countryCode());
    }

    private static void text(Document xml, Element parent, String name, String value) {
        Element element = xml.createElement(name);
        element.setTextContent(value);
        parent.appendChild(element);
    }

    private static String serialize(Document xml) throws Exception {
        var transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        var out = new StringWriter();
        transformer.transform(new DOMSource(xml), new StreamResult(out));
        return out.toString();
    }
}

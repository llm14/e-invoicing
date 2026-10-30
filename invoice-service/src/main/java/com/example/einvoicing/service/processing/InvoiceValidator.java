package com.example.einvoicing.service.processing;

import com.example.einvoicing.domain.InvoiceDocument;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;

/** Business rules beyond what the domain types already enforce. */
@ApplicationScoped
public class InvoiceValidator {

    /** @return human-readable problems; empty if the invoice is acceptable */
    public List<String> problems(InvoiceDocument invoice) {
        var problems = new ArrayList<String>();
        if (invoice.grossTotal().amount().signum() <= 0) {
            problems.add("gross total must be positive");
        }
        if (invoice.issuer().taxId().equals(invoice.customer().taxId())) {
            problems.add("issuer and customer must differ");
        }
        return problems;
    }
}

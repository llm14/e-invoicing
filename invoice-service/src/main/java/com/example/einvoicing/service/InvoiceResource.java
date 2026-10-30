package com.example.einvoicing.service;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

@Path("/invoices")
public class InvoiceResource {

    @GET
    public String ping() {
        return "invoice-service up";
    }
}

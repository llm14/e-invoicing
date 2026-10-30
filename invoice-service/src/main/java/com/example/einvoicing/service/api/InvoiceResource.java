package com.example.einvoicing.service.api;

import com.example.einvoicing.service.InvoiceService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.util.UUID;

@Path("/invoices")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class InvoiceResource {

    @Inject
    InvoiceService service;

    @POST
    public Response submit(SubmitInvoiceRequest request, @Context UriInfo uriInfo) {
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }
        var invoice = service.submit(request.toDocument());
        var location = uriInfo.getAbsolutePathBuilder().path(invoice.id().toString()).build();
        return Response.created(location).entity(InvoiceResponse.from(invoice)).build();
    }

    @GET
    @Path("/{id}")
    public InvoiceResponse get(@PathParam("id") UUID id) {
        return service.find(id).map(InvoiceResponse::from).orElseThrow(NotFoundException::new);
    }
}

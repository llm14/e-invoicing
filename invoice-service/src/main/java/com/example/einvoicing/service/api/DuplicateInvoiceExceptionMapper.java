package com.example.einvoicing.service.api;

import com.example.einvoicing.service.DuplicateInvoiceException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.Map;

@Provider
public class DuplicateInvoiceExceptionMapper implements ExceptionMapper<DuplicateInvoiceException> {

    @Override
    public Response toResponse(DuplicateInvoiceException e) {
        return Response.status(Response.Status.CONFLICT)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("error", e.getMessage()))
                .build();
    }
}

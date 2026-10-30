package com.example.einvoicing.service.processing;

import java.util.UUID;

public interface DeliveryClient {

    /**
     * Sends the signed invoice to the recipient. The invoice id is the idempotency key: delivering
     * the same invoice twice must not create a duplicate on the recipient's side.
     *
     * @throws RuntimeException if the recipient could not be reached (the caller may retry)
     */
    void deliver(UUID invoiceId, String signedXml);
}

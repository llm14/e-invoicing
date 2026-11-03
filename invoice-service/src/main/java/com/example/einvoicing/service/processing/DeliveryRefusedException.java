package com.example.einvoicing.service.processing;

/** The recipient refused the invoice for good, so delivering it again cannot succeed. */
public class DeliveryRefusedException extends RuntimeException {

    public DeliveryRefusedException(String message) {
        super(message);
    }
}

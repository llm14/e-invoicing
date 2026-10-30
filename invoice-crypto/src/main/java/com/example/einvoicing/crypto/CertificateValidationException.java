package com.example.einvoicing.crypto;

import java.security.GeneralSecurityException;

public class CertificateValidationException extends GeneralSecurityException {

    public CertificateValidationException(String message, Throwable cause) {
        super(message, cause);
    }

    public CertificateValidationException(String message) {
        super(message);
    }
}

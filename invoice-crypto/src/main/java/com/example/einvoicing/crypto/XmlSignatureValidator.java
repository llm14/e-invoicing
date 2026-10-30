package com.example.einvoicing.crypto;

public interface XmlSignatureValidator {
    /**
     * @return true only if the document carries exactly one enveloped signature, covering the
     *     whole document, that verifies against the trusted key
     * @throws IllegalArgumentException if the XML is malformed or declares a DOCTYPE
     */
    boolean validate(String signedXml);
}

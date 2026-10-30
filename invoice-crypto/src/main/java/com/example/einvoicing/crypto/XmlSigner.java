package com.example.einvoicing.crypto;

public interface XmlSigner {
    String sign(String xml);

    boolean validate(String signedXml);
}

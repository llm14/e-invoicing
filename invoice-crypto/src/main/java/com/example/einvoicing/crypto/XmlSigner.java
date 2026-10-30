package com.example.einvoicing.crypto;

public interface XmlSigner {
    /** Returns the document with an enveloped XML signature over its whole content. */
    String sign(String xml);
}

package com.example.einvoicing.crypto;

import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.util.Set;
import javax.xml.crypto.KeySelector;
import javax.xml.crypto.MarshalException;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureException;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMValidateContext;

/**
 * Validates against a key the caller already trusts, never against a key embedded in the
 * document. Rejects documents whose signature does not cover the whole document
 * (signature-wrapping defence).
 */
public final class EnvelopedXmlSignatureValidator implements XmlSignatureValidator {

    private final KeySelector keySelector;

    public EnvelopedXmlSignatureValidator(PublicKey trustedKey) {
        this(KeySelector.singletonKeySelector(trustedKey));
    }

    private EnvelopedXmlSignatureValidator(KeySelector keySelector) {
        this.keySelector = keySelector;
    }

    /**
     * Trusts the certificate chain embedded in the signature, provided it validates (at the
     * clock's time) against {@code trusted}. Any trust or validity failure yields {@code false}.
     */
    public static EnvelopedXmlSignatureValidator forTrustedCertificates(
            Set<X509Certificate> trusted, Clock clock) {
        return new EnvelopedXmlSignatureValidator(
                new X509ChainKeySelector(new CertificateValidator(trusted, clock)));
    }

    @Override
    public boolean validate(String signedXml) {
        var document = XmlDocuments.parse(signedXml);
        var signatures = document.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature");
        if (signatures.getLength() != 1) {
            return false;
        }
        try {
            var context = new DOMValidateContext(keySelector, signatures.item(0));
            context.setProperty("org.jcp.xml.dsig.secureValidation", Boolean.TRUE);
            var signature = XMLSignatureFactory.getInstance("DOM").unmarshalXMLSignature(context);
            var references = signature.getSignedInfo().getReferences();
            if (references.size() != 1 || !"".equals(((Reference) references.get(0)).getURI())) {
                return false;
            }
            return signature.validate(context);
        } catch (MarshalException | XMLSignatureException e) {
            return false;
        }
    }
}

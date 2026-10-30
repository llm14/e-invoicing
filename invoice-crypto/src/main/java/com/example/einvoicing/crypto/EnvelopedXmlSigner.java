package com.example.einvoicing.crypto;

import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;
import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.SignatureMethod;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec;
import javax.xml.crypto.dsig.spec.TransformParameterSpec;

/** RSA-SHA256 enveloped XMLDSig signer using the JDK's built-in implementation. */
public final class EnvelopedXmlSigner implements XmlSigner {

    private final PrivateKey privateKey;
    private final List<X509Certificate> chain;

    /** Signs without embedding a certificate; verifiers must already know the matching public key. */
    public EnvelopedXmlSigner(PrivateKey privateKey) {
        this.privateKey = privateKey;
        this.chain = List.of();
    }

    /** Signs and embeds the identity's certificate chain as KeyInfo/X509Data. */
    public EnvelopedXmlSigner(SigningIdentity identity) {
        this.privateKey = identity.privateKey();
        this.chain = identity.chain();
    }

    @Override
    public String sign(String xml) {
        var document = XmlDocuments.parse(xml);
        try {
            var factory = XMLSignatureFactory.getInstance("DOM");
            var reference = factory.newReference(
                    "",
                    factory.newDigestMethod(DigestMethod.SHA256, null),
                    List.of(factory.newTransform(Transform.ENVELOPED, (TransformParameterSpec) null)),
                    null,
                    null);
            var signedInfo = factory.newSignedInfo(
                    factory.newCanonicalizationMethod(
                            CanonicalizationMethod.EXCLUSIVE, (C14NMethodParameterSpec) null),
                    factory.newSignatureMethod(SignatureMethod.RSA_SHA256, null),
                    List.of(reference));
            var keyInfo = chain.isEmpty()
                    ? null
                    : factory.getKeyInfoFactory()
                            .newKeyInfo(List.of(factory.getKeyInfoFactory().newX509Data(List.copyOf(chain))));
            factory.newXMLSignature(signedInfo, keyInfo)
                    .sign(new DOMSignContext(privateKey, document.getDocumentElement()));
        } catch (Exception e) {
            throw new IllegalStateException("Could not sign XML document", e);
        }
        return XmlDocuments.serialize(document);
    }
}

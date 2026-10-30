package com.example.einvoicing.crypto;

import java.security.GeneralSecurityException;
import java.security.cert.CertPathValidator;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * PKIX path validation of a signer chain against trusted certificates, evaluated at the clock's
 * current time. Revocation (CRL/OCSP) is not checked.
 */
public final class CertificateValidator {

    private static final int KEY_USAGE_DIGITAL_SIGNATURE = 0;

    private final Set<X509Certificate> trusted;
    private final Clock clock;

    public CertificateValidator(Set<X509Certificate> trusted, Clock clock) {
        this.trusted = Set.copyOf(trusted);
        this.clock = clock;
    }

    /** @param chain signer certificate first, followed by any intermediates (a root may be included) */
    public void validate(List<X509Certificate> chain) throws CertificateValidationException {
        if (chain.isEmpty()) {
            throw new CertificateValidationException("Empty certificate chain");
        }
        var signer = chain.get(0);
        var keyUsage = signer.getKeyUsage();
        if (keyUsage != null && !keyUsage[KEY_USAGE_DIGITAL_SIGNATURE]) {
            throw new CertificateValidationException("Signer certificate is not allowed to sign");
        }
        var now = Date.from(clock.instant());
        try {
            signer.checkValidity(now);
            if (trusted.contains(signer)) {
                return;
            }
            var path = chain.stream().filter(c -> !trusted.contains(c)).collect(Collectors.toList());
            var anchors = trusted.stream().map(c -> new TrustAnchor(c, null)).collect(Collectors.toSet());
            var params = new PKIXParameters(anchors);
            params.setRevocationEnabled(false);
            params.setDate(now);
            CertPathValidator.getInstance("PKIX")
                    .validate(CertificateFactory.getInstance("X.509").generateCertPath(path), params);
        } catch (CertificateException e) {
            throw new CertificateValidationException("Certificate is not valid: " + e.getMessage(), e);
        } catch (GeneralSecurityException e) {
            throw new CertificateValidationException("Certificate chain is not valid or not trusted: " + e.getMessage(), e);
        }
    }
}

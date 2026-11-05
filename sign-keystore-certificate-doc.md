# Signing, keys, certificates and keystores

## The analogy: a notary and your personal stamp

Imagine you sign contracts with a **personal wax seal**.

| Real world | Crypto world |
|---|---|
| Your seal stamp (only you own it, never lent out) | **Private key** |
| The imprint it leaves, which anyone can compare against a known sample | **Public key** (can be shared freely) |
| A **government ID card** that says "this imprint belongs to Acme Ltd, issued by the Ministry, valid until 2028" | **Certificate** |
| The Ministry that issues IDs and that everyone trusts | **Certificate Authority (CA)** |
| The locked drawer where you keep your stamp and ID | **Keystore** |
| Pressing the stamp on the contract | **Signing** |
| Someone checking the imprint and the ID | **Validation** |
| The contract you sealed | **The XML invoice** |

The key idea is that **the stamp proves "someone with this stamp did this"**, and the **ID card proves "this stamp belongs to Acme"**. You need both. A stamp alone proves nothing about who you are.

## The pieces in more detail

### 1. Key pair: private + public
You generate two mathematically linked keys.
- **Private key**: secret. It creates signatures.
- **Public key**: shareable. It verifies signatures.

Something signed with the private key can only be verified by the matching public key. Nobody can forge a signature without the private key, and knowing the public key does not let you derive the private one.

### 2. Certificate: the public key plus an identity, vouched for by someone
A certificate (X.509) is a small document containing:
- the **public key**
- the **identity** ("CN=Acme Ltd", tax number, and so on)
- **validity dates**
- the **issuer** (the CA)
- the **CA's own signature** over all of that

The last item matters most. The CA's signature is what makes a stranger trust the identity.

### 3. Certificate Authority and the chain of trust
Your certificate is usually signed by an *intermediate* CA, which is signed by a *root* CA. This is the **chain**:

```
Root CA  ──signs──►  Intermediate CA  ──signs──►  Your certificate
(pre-trusted)
```

Verifiers do not trust you directly. They trust a **root they already have** (a *trust anchor*) and follow the chain down.

### 4. Keystore: where you keep these
A keystore is a protected file or service holding keys and certificates. The same word covers two different things:

| | **Keystore** (yours) | **Truststore** (others') |
|---|---|---|
| Holds | Your private key + your certificate chain | Root/intermediate CA certificates you trust |
| Used for | **Signing** | **Validating** |
| Secrecy | Highly secret | Not secret, but must not be tampered with |

Formats include PKCS#12 (`.p12`/`.pfx`) and JKS. Modern setups often use an **HSM or cloud KMS** instead of a file, so the private key never leaves the secure device.

## The full flow, end to end

### Phase 0: getting an identity (once, or yearly)
```
1. Generate key pair (private key stays in keystore/KMS)
2. Build a CSR (Certificate Signing Request):
   "here is my public key and my name, signed with my private key
    to prove I own it"
3. Send the CSR to the CA. The CA checks who you are.
4. CA returns your signed certificate (plus its chain).
5. Store it with the private key. This is your "signing identity".
```
The private key is never sent anywhere. Only the public key travels in the CSR.

### Phase 1: signing an invoice (every invoice)
```
invoice XML
   │ 1. canonicalize + hash (digest)
   ▼
 digest ──2. sign with PRIVATE KEY──► signature value
   │
   ▼
 3. put signature + your CERTIFICATE into the XML
```

### Phase 2: validating (by the receiver)
```
received signed XML
  1. Read the certificate in the XML
  2. Is it trustworthy?
       - chains up to a root I trust (truststore)?
       - not expired, not revoked?
       - allowed to sign (key usage)?
  3. Recompute the XML's hash
  4. Use the certificate's PUBLIC KEY to check the signature
       ✓ matches  → untampered AND signed by the owner of that certificate
       ✗ mismatch → altered or forged
```

## What each step guarantees

- **Integrity**: the content was not changed (the hash matches).
- **Authenticity**: the signer holds the private key for that certificate.
- **Identity**: the certificate, vouched for by a trusted CA, says that key belongs to Acme.
- **Non-repudiation** (roughly): Acme can't easily claim it didn't sign, since only it has the private key.

It does **not** guarantee confidentiality. The XML is still readable. Encryption is a separate concern.

## How the e-invoicing project maps to this

| Concept | In this repo |
|---|---|
| Private key kept safe | KMS via `RemoteSigner`; the key never enters the JVM |
| Phase 0, the CSR | `CsrBuilder.createPem` |
| Your identity (key + chain) | `SigningIdentity` |
| Signing | `EnvelopedXmlSigner` puts signature + certificate in the XML |
| Truststore | `KeyStoreLoader.loadTrustAnchors` |
| Validation | `EnvelopedXmlSignatureValidator.forTrustedCertificates` |

## The most common real-world case

Most projects you'll meet look like this:

1. A **qualified or national CA** issues the company a certificate, often on a hardware token, an HSM or a cloud KMS.
2. The app loads the identity from a **PKCS#12 file** (simple, common) or an HSM/KMS (more secure).
3. Signing happens in the app, or via a signing service.
4. The **receiver** is often a government or partner. They validate against their own truststore, and often check **revocation** (CRL or OCSP) too.

## Pitfalls to look out for

- **Confusing keystore and truststore.** Using one where the other belongs is a classic error.
- **Expired certificates.** Signing quietly works until validation fails in production. Monitor expiry dates.
- **Missing chain.** If you don't embed intermediates, the verifier can't build the path to the root.
- **Trusting any certificate in the XML.** A signature validating mathematically is not enough. You must also check that the certificate chains to *your* trust anchors. Otherwise an attacker signs with their own certificate.
- **Weak key protection.** A `.p12` file with its password beside it in the repo undermines everything.
- **Different "signing" standards.** Plain XMLDSig, XAdES (adds timestamps and policy), JWS and PDF signatures all use the same ideas but have different formats. Check which one your new project requires.

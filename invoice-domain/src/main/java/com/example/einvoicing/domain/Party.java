package com.example.einvoicing.domain;

/** An issuer or customer. {@code countryCode} is ISO 3166-1 alpha-2. */
public record Party(String name, String taxId, String countryCode) {

    public Party {
        requireText(name, "name");
        requireText(taxId, "taxId");
        if (countryCode == null || !countryCode.matches("[A-Z]{2}")) {
            throw new IllegalArgumentException("countryCode must be 2 uppercase letters");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}

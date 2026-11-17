package com.erp.ticketing.security;

import io.jsonwebtoken.ExpiredJwtException;

/**
 * Why {@link JwtAuthenticationFilter} refused a bearer token. Logged as a short code so an
 * operator can tell a stale session from a key mismatch or a revocation feed outage; the client
 * always gets the same generic 401 body.
 */
public enum TokenRejectionReason {

    /** Signed by a trusted key but past its {@code exp}. */
    EXPIRED("expired"),
    /** Not signed by a key this service trusts - typically a JWT_PUBLIC_KEY mismatch. */
    BAD_SIGNATURE("bad_signature"),
    /** Malformed, unsupported or otherwise invalid token or claims. */
    BAD_CLAIMS("bad_claims"),
    /** Valid signature, but identity-service revoked the user's tokens or the session. */
    REVOKED("revoked"),
    /** Valid signature, but the revocation list has not loaded yet (fail-closed). */
    REVOCATIONS_NOT_LOADED("revocations_not_loaded");

    private final String code;

    TokenRejectionReason(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** Classifies a jjwt parse failure. Never inspects or returns the token itself. */
    static TokenRejectionReason fromParseFailure(Exception failure) {
        if (failure instanceof ExpiredJwtException) {
            return EXPIRED;
        }
        // SignatureException and the key exceptions (wrong algorithm/key type) are SecurityExceptions.
        if (failure instanceof io.jsonwebtoken.security.SecurityException) {
            return BAD_SIGNATURE;
        }
        return BAD_CLAIMS;
    }
}

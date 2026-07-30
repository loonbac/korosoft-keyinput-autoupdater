package com.korosoft.keyinput.auth;

/**
 * Result of a {@code /register}, {@code /login} or {@code /refresh} call against KoroAuth,
 * translated from the plugin's HTTP status codes + {@code {"error": "..."}} bodies (see
 * {@code PublicAuthHandlers.respond} in {@code plugins-custom/KoroAuth}). One-to-one with the
 * plugin's {@code AuthService.Outcome.Status} enum, plus a client-only {@link Kind#NETWORK_ERROR}
 * for connection/TLS failures that never reached the server at all.
 */
public record AuthOutcome(Kind kind, String token) {

    public enum Kind {
        /** 200 — {@code {"token": "..."}}. */
        SUCCESS,
        /** 403 — {@code not_whitelisted}. */
        NOT_WHITELISTED,
        /** 409 — {@code already_registered}. */
        ALREADY_REGISTERED,
        /** 400 — {@code invalid_username}. */
        INVALID_USERNAME,
        /** 401 — {@code invalid_credentials} (also returned by /refresh for an invalid/expired token). */
        INVALID_CREDENTIALS,
        /** 429 — {@code rate_limited}. */
        RATE_LIMITED,
        /** 400 — {@code bad_request} (missing/empty username or password). */
        BAD_REQUEST,
        /** 405 — {@code method_not_allowed}. Should not happen; every call here is POST. */
        METHOD_NOT_ALLOWED,
        /** 500 — {@code server_error}. */
        SERVER_ERROR,
        /** Client-side only: the HTTP request itself failed (DNS, TLS, timeout, unparseable body). */
        NETWORK_ERROR
    }

    public static AuthOutcome success(String token) {
        return new AuthOutcome(Kind.SUCCESS, token);
    }

    public static AuthOutcome of(Kind kind) {
        return new AuthOutcome(kind, null);
    }

    public boolean isSuccess() {
        return kind == Kind.SUCCESS;
    }
}

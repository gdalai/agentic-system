package com.assessment.agentic_system.shortener;

/** Domain exceptions for the shortener, mapped to HTTP status by {@code ApiExceptionHandler}. */
public final class UrlExceptions {

    private UrlExceptions() {
    }

    public static class UrlNotFoundException extends RuntimeException {
        public UrlNotFoundException(String code) {
            super("No short URL found for code '" + code + "'");
        }
    }

    public static class UrlExpiredException extends RuntimeException {
        public UrlExpiredException(String code) {
            super("Short URL '" + code + "' has expired");
        }
    }
}

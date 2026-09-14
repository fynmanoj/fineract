package org.apache.fineract.infrastructure.security.domain;

public enum SessionPolicy {

    SLIDING_IDLE, HARD_EXPIRY;

    public static SessionPolicy fromString(final String value) {
        if (value == null) {
            return SLIDING_IDLE;
        }
        return SessionPolicy.valueOf(value);
    }
}

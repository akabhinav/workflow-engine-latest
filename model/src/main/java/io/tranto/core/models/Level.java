package io.tranto.core.models;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Locale;

/**
 * Log level for task/execution log lines, ordered from most to least verbose.
 */
public enum Level {
    TRACE,
    DEBUG,
    INFO,
    WARN,
    ERROR;

    @JsonCreator
    public static Level fromString(final String value) {
        if (value == null) {
            return INFO;
        }
        try {
            return Level.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return INFO;
        }
    }

    /** @return true if this level is at least as severe as {@code other}. */
    public boolean isAtLeast(final Level other) {
        return this.ordinal() >= other.ordinal();
    }
}

package io.tranto.core.models;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Locale;

/**
 * The role a running Tranto process plays. A single process may be a self-contained
 * {@link #STANDALONE}/{@link #LOCAL} node (all roles in one JVM) or a single specialised
 * service in a distributed deployment. Chosen at startup and used to decide which
 * services and queue subscribers boot.
 */
public enum ServerType {
    /** Zero-config all-in-one dev node: every role + embedded H2 + local storage. */
    LOCAL,
    /** All roles in one JVM, but against a real external database/storage. */
    STANDALONE,
    /** The flow state machine. */
    EXECUTOR,
    /** Cron/trigger evaluation. */
    SCHEDULER,
    /** Runs tasks. */
    WORKER,
    /** Distributes jobs to remote workers (gRPC). */
    CONTROLLER,
    /** Indexes logs/metrics. */
    INDEXER,
    /** REST API + UI. */
    WEBSERVER,
    /** Unrecognised value (forward-compatible deserialization). */
    UNKNOWN;

    @JsonCreator
    public static ServerType fromString(final String value) {
        if (value == null) {
            return UNKNOWN;
        }
        try {
            return ServerType.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }

    /** @return true if this single process runs every role in one JVM. */
    public boolean isAllInOne() {
        return this == LOCAL || this == STANDALONE;
    }
}

package io.tranto.core.models.flows;

import com.fasterxml.jackson.annotation.JsonCreator;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.Locale;

/**
 * A typed, declared flow input. When an execution starts, each provided value is coerced to the
 * declared {@link Type} and validated; missing required inputs (with no default) fail the execution
 * before any task runs.
 *
 * <p>The input is addressed by {@link #id} (Kestra's older {@code name} is accepted as an alias).
 * {@code required} defaults to {@code true}; a {@code defaults} value makes an input optional in
 * practice even when required, because the default supplies the missing value.</p>
 */
@SuperBuilder(toBuilder = true)
@Getter
@NoArgsConstructor
public class Input {

    /** The primitive type an input value is coerced and validated against. */
    public enum Type {
        STRING, INT, FLOAT, BOOLEAN, DURATION, JSON;

        /** Case-insensitive lookup so YAML can say {@code type: int}. */
        @JsonCreator
        public static Type fromString(final String value) {
            return value == null ? STRING : Type.valueOf(value.toUpperCase(Locale.ROOT));
        }
    }

    /** Input id, referenced in expressions as {@code {{ inputs.<id> }}}. */
    private String id;

    /** Legacy alias for {@link #id}. */
    private String name;

    /** The declared type; defaults to {@link Type#STRING}. */
    private Type type = Type.STRING;

    /** Whether a value must be supplied (unless a default exists). Defaults to {@code true}. */
    private boolean required = true;

    /** Default value used when none is provided. */
    private Object defaults;

    /** Optional human description. */
    private String description;

    /** @return the addressable key: {@link #id} when set, otherwise {@link #name}. */
    public String key() {
        return id != null ? id : name;
    }
}

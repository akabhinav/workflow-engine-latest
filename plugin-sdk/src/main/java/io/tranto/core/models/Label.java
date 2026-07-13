package io.tranto.core.models;

import jakarta.validation.constraints.NotBlank;

/**
 * A key/value label attached to a flow or execution, used for organisation and filtering.
 * Immutable value type.
 */
public record Label(@NotBlank String key, @NotBlank String value) {

    /** Prefix reserved for Tranto-managed system labels (e.g. {@code system.correlationId}). */
    public static final String SYSTEM_PREFIX = "system.";
}

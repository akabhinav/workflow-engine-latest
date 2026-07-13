package io.tranto.plugin.aws.dynamodb;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts between plain YAML/JSON values (what a flow author writes) and DynamoDB
 * {@link AttributeValue}s (what the SDK expects), both directions. Supports the common scalar types
 * (string, number, boolean, null) plus nested maps and lists.
 */
final class Attributes {

    private Attributes() {
    }

    /** Convert a {@code {name: value}} map into a DynamoDB item. */
    static Map<String, AttributeValue> toItem(final Map<String, Object> in) {
        Map<String, AttributeValue> out = new LinkedHashMap<>();
        if (in != null) {
            in.forEach((k, v) -> out.put(k, toValue(v)));
        }
        return out;
    }

    /** Convert a single value into an {@link AttributeValue}. */
    static AttributeValue toValue(final Object value) {
        if (value == null) {
            return AttributeValue.builder().nul(true).build();
        }
        if (value instanceof String s) {
            return AttributeValue.builder().s(s).build();
        }
        if (value instanceof Boolean b) {
            return AttributeValue.builder().bool(b).build();
        }
        if (value instanceof Number n) {
            return AttributeValue.builder().n(n.toString()).build();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, AttributeValue> nested = new LinkedHashMap<>();
            map.forEach((k, v) -> nested.put(String.valueOf(k), toValue(v)));
            return AttributeValue.builder().m(nested).build();
        }
        if (value instanceof Iterable<?> iterable) {
            List<AttributeValue> list = new ArrayList<>();
            iterable.forEach(e -> list.add(toValue(e)));
            return AttributeValue.builder().l(list).build();
        }
        return AttributeValue.builder().s(String.valueOf(value)).build();
    }

    /** Convert a DynamoDB item back into a plain {@code {name: value}} map. */
    static Map<String, Object> fromItem(final Map<String, AttributeValue> in) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (in != null) {
            in.forEach((k, v) -> out.put(k, fromValue(v)));
        }
        return out;
    }

    /** Convert a single {@link AttributeValue} back into a plain value. */
    static Object fromValue(final AttributeValue value) {
        return switch (value.type()) {
            case S -> value.s();
            case N -> parseNumber(value.n());
            case BOOL -> value.bool();
            case NUL -> null;
            case M -> fromItem(value.m());
            case L -> value.l().stream().map(Attributes::fromValue).toList();
            default -> value.toString();
        };
    }

    private static Object parseNumber(final String n) {
        try {
            return Long.parseLong(n);
        } catch (NumberFormatException notLong) {
            try {
                return Double.parseDouble(n);
            } catch (NumberFormatException notDouble) {
                return n;
            }
        }
    }
}

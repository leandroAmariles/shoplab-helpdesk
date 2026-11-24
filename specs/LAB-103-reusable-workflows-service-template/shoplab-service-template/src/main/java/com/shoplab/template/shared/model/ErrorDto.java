package com.shoplab.template.shared.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * Representación tipada de un error devuelto al cliente.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ErrorDto(
        String code,
        String description,
        Map<String, String> attributes
) {
    public static ErrorDto of(String code, String description) {
        return new ErrorDto(code, description, Map.of());
    }

    public static ErrorDto of(String code, String description, Map<String, String> attributes) {
        return new ErrorDto(code, description, attributes != null ? attributes : Map.of());
    }
}

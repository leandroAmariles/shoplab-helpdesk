package com.shoplab.template.shared.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Collections;
import java.util.List;

/**
 * Envoltorio estándar uniforme para todas las respuestas de API del microservicio.
 *
 * @param <T> tipo de la carga útil (data)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResponseDto<T>(
        T data,
        List<ErrorDto> errors
) {
    public static <T> ResponseDto<T> success(T data) {
        return new ResponseDto<>(data, Collections.emptyList());
    }

    public static <T> ResponseDto<T> failure(List<ErrorDto> errors) {
        return new ResponseDto<>(null, errors != null ? errors : Collections.emptyList());
    }

    public static <T> ResponseDto<T> failure(ErrorDto error) {
        return new ResponseDto<>(null, error != null ? List.of(error) : Collections.emptyList());
    }
}

package com.shoplab.helpdesk.shared.infrastructure.in.rest.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The body of every error. {@code detail} is only present when the exception offers a safe one (see
 * {@code PublicDetail}); the other errors keep exactly the two fields they always had.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String code, String message, String detail) {

    public ErrorResponse(String code, String message) {
        this(code, message, null);
    }
}

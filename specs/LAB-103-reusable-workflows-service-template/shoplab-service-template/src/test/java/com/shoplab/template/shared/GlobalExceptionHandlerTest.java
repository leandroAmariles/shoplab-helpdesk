package com.shoplab.template.shared;

import com.shoplab.template.shared.exception.BusinessException;
import com.shoplab.template.shared.exception.GlobalExceptionHandler;
import com.shoplab.template.shared.exception.ResponseCodeEnum;
import com.shoplab.template.shared.exception.TechnicalException;
import com.shoplab.template.shared.model.ResponseDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
    }

    @Test
    @DisplayName("Debe manejar BusinessException retornando 4xx con código tipado")
    void should_handle_business_exception() {
        BusinessException ex = new BusinessException(ResponseCodeEnum.RESOURCE_NOT_FOUND, "Entity 123 not found");

        StepVerifier.create(handler.handleBusiness(ex))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    ResponseDto<Void> body = response.getBody();
                    assertThat(body).isNotNull();
                    assertThat(body.data()).isNull();
                    assertThat(body.errors()).hasSize(1);
                    assertThat(body.errors().get(0).code()).isEqualTo("TMPL_GE_00002");
                    assertThat(body.errors().get(0).description()).isEqualTo("Entity 123 not found");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Debe manejar TechnicalException retornando 5xx con código tipado")
    void should_handle_technical_exception() {
        TechnicalException ex = new TechnicalException(ResponseCodeEnum.DATABASE_ERROR);

        StepVerifier.create(handler.handleTechnical(ex))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                    ResponseDto<Void> body = response.getBody();
                    assertThat(body).isNotNull();
                    assertThat(body.errors().get(0).code()).isEqualTo("TMPL_DB_00001");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Debe manejar Throwable no controlado con fallback a UNKNOWN_ERROR")
    void should_handle_fallback_throwable() {
        RuntimeException ex = new NullPointerException("Unexpected null reference");

        StepVerifier.create(handler.handleFallback(ex))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                    ResponseDto<Void> body = response.getBody();
                    assertThat(body).isNotNull();
                    assertThat(body.errors().get(0).code()).isEqualTo("TMPL_GE_99999");
                })
                .verifyComplete();
    }
}

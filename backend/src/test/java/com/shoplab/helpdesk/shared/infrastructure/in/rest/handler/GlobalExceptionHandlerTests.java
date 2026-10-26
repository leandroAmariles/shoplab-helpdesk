package com.shoplab.helpdesk.shared.infrastructure.in.rest.handler;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shoplab.helpdesk.shared.exception.BusinessException;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import com.shoplab.helpdesk.shared.exception.TechnicalException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The only place where exceptions become HTTP responses (specs/LAB-12, FR-009). */
@WebMvcTest(controllers = GlobalExceptionHandlerTests.ProbeController.class)
@Import({GlobalExceptionHandler.class, GlobalExceptionHandlerTests.ProbeController.class})
class GlobalExceptionHandlerTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void businessExceptionUsesTheStatusAndCodeOfItsResponseCode() throws Exception {
        mockMvc.perform(get("/probe/business"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("SLHD_CHAT_00001"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void technicalExceptionWithModelUnavailableCodeIsServiceUnavailable() throws Exception {
        mockMvc.perform(get("/probe/technical"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SLHD_CHAT_00002"));
    }

    @Test
    void unexpectedExceptionIsInternalErrorWithoutLeakingItsMessage() throws Exception {
        mockMvc.perform(get("/probe/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("SLHD_GENE_00001"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("secret internal detail"))));
    }

    @Test
    void unreadableBodyIsBadRequestWithGenericCode() throws Exception {
        mockMvc.perform(post("/probe/body").contentType(MediaType.APPLICATION_JSON).content("{\"message\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SLHD_GENE_00002"));
    }

    @Test
    void errorIsJsonEvenWhenTheClientAsksForEventStream() throws Exception {
        mockMvc.perform(get("/probe/technical").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("SLHD_CHAT_00002"));
    }

    /** Test-only routes that throw each kind of exception. */
    @RestController
    static class ProbeController {

        @GetMapping("/probe/business")
        String business() {
            throw new BusinessException(ResponseCodeEnum.INVALID_CHAT_MESSAGE);
        }

        @GetMapping("/probe/technical")
        String technical() {
            throw new TechnicalException(ResponseCodeEnum.CHAT_MODEL_UNAVAILABLE, new IllegalStateException("down"));
        }

        @GetMapping("/probe/unexpected")
        String unexpected() {
            throw new IllegalStateException("secret internal detail");
        }

        @PostMapping("/probe/body")
        String body(@RequestBody ProbeBody body) {
            return body.message();
        }
    }

    record ProbeBody(String message) {
    }
}

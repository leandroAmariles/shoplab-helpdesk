package com.shoplab.helpdesk.shared.infrastructure.in.rest.filter;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTests {

    private static final String HEADER = "X-Request-Id";

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void generatesAnIdWhenTheRequestHasNone() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInChain = new AtomicReference<>();

        filter.doFilter(new MockHttpServletRequest(), response, captureMdc(seenInChain));

        String generated = response.getHeader(HEADER);
        assertThat(generated).isNotBlank();
        assertThat(seenInChain.get()).isEqualTo(generated);
    }

    @Test
    void keepsAValidIdSentByTheClient() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HEADER, "client-id-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInChain = new AtomicReference<>();

        filter.doFilter(request, response, captureMdc(seenInChain));

        assertThat(response.getHeader(HEADER)).isEqualTo("client-id-123");
        assertThat(seenInChain.get()).isEqualTo("client-id-123");
    }

    @Test
    void replacesAnIdThatIsTooLong() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HEADER, "x".repeat(65));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, captureMdc(new AtomicReference<>()));

        assertThat(response.getHeader(HEADER)).hasSizeLessThanOrEqualTo(64).isNotEqualTo("x".repeat(65));
    }

    @Test
    void replacesAnIdWithControlCharacters() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HEADER, "bad\nid");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, captureMdc(new AtomicReference<>()));

        assertThat(response.getHeader(HEADER)).doesNotContain("\n").isNotEqualTo("bad\nid");
    }

    @Test
    void cleansTheMdcWhenTheRequestEnds() throws Exception {
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                captureMdc(new AtomicReference<>()));

        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    private FilterChain captureMdc(AtomicReference<String> target) {
        return (request, response) -> target.set(MDC.get(RequestIdFilter.MDC_KEY));
    }
}

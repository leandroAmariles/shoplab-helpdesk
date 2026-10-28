package com.shoplab.helpdesk.retrieval.infrastructure.out.ingest;

import com.shoplab.helpdesk.retrieval.application.port.out.DocumentParserPort;
import com.shoplab.helpdesk.retrieval.config.IngestClientProperties;
import com.shoplab.helpdesk.retrieval.domain.model.ParsedDocument;
import com.shoplab.helpdesk.retrieval.infrastructure.out.ingest.dto.IngestErrorBody;
import com.shoplab.helpdesk.retrieval.infrastructure.out.ingest.dto.IngestResponse;
import com.shoplab.helpdesk.shared.exception.ExceptionUtil;
import com.shoplab.helpdesk.shared.exception.HelpdeskException;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/** Calls {@code POST /ingest} of the ingest service (contract: {@code ingest/openapi.json}) with a multipart form. */
@Component
public class IngestWebClientAdapter implements DocumentParserPort {

    private static final Logger LOG = LoggerFactory.getLogger(IngestWebClientAdapter.class);
    private static final String INGEST_PATH = "/ingest";
    private static final String FILE_PART = "file";

    private final WebClient webClient;
    private final IngestClientProperties properties;
    private final IngestResponseMapper responseMapper;
    private final IngestErrorMapper errorMapper;

    public IngestWebClientAdapter(WebClient ingestWebClient, IngestClientProperties properties,
            IngestResponseMapper responseMapper, IngestErrorMapper errorMapper) {
        this.webClient = ingestWebClient;
        this.properties = properties;
        this.responseMapper = responseMapper;
        this.errorMapper = errorMapper;
    }

    @Override
    public Mono<ParsedDocument> parse(String name, byte[] content) {
        return webClient.post()
                .uri(INGEST_PATH)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(multipart(name, content)))
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> errorCodeOf(response)
                        .map(code -> rejection(name, response.statusCode(), code)))
                .bodyToMono(IngestResponse.class)
                .timeout(properties.timeout())
                .switchIfEmpty(Mono.error(() -> ExceptionUtil.technical(ResponseCodeEnum.INGEST_SERVICE_FAILED,
                        new IllegalStateException("The ingest service answered without a body"))))
                .map(responseMapper::toParsedDocument)
                .onErrorMap(error -> !(error instanceof HelpdeskException), errorMapper::fromTransportFailure);
    }

    private HelpdeskException rejection(String name, HttpStatusCode status, String code) {
        LOG.warn("The ingest service did not accept the document: name={}, status={}, code={}", name,
                status.value(), code);
        return errorMapper.fromRejection(status, code);
    }

    private static Mono<String> errorCodeOf(ClientResponse response) {
        return response.bodyToMono(IngestErrorBody.class)
                .map(IngestErrorBody::code)
                .defaultIfEmpty("NO_BODY")
                .onErrorReturn("UNREADABLE_BODY");
    }

    private static MultiValueMap<String, HttpEntity<?>> multipart(String name, byte[] content) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part(FILE_PART, new ByteArrayResource(content))
                .filename(name)
                .contentType(MediaType.APPLICATION_OCTET_STREAM);
        return builder.build();
    }
}

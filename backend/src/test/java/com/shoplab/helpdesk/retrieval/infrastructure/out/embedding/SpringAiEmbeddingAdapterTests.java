package com.shoplab.helpdesk.retrieval.infrastructure.out.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.openai.core.http.Headers;
import com.openai.errors.BadRequestException;
import com.openai.errors.InternalServerException;
import com.openai.errors.UnauthorizedException;
import com.openai.errors.UnprocessableEntityException;
import com.openai.models.ErrorObject;
import com.shoplab.helpdesk.retrieval.domain.model.enums.EmbeddingPurpose;
import com.shoplab.helpdesk.shared.constants.GatewayHeaders;
import com.shoplab.helpdesk.shared.exception.HelpdeskException;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.web.client.ResourceAccessException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class SpringAiEmbeddingAdapterTests {

    private static final List<String> TEXTS = List.of("uno", "dos");
    private static final String SECRET = "token-secreto-123";

    private final EmbeddingModel model = mock(EmbeddingModel.class);
    private final SpringAiEmbeddingAdapter adapter = new SpringAiEmbeddingAdapter(model, "embeddings", "bge-m3");

    private static float[] vector(int size) {
        return new float[size];
    }

    private static EmbeddingResponse response(float[]... vectors) {
        return new EmbeddingResponse(java.util.stream.IntStream.range(0, vectors.length)
                .mapToObj(index -> new Embedding(vectors[index], index)).toList());
    }

    private static HelpdeskException failureOf(Mono<?> call) {
        return (HelpdeskException) call.then().materialize().block().getThrowable();
    }

    private void failsWith(ResponseCodeEnum expected, Throwable cause) {
        when(model.call(any(EmbeddingRequest.class))).thenThrow(cause instanceof RuntimeException runtime ? runtime
                : new IllegalStateException(cause));
        assertThat(failureOf(adapter.embed(TEXTS, EmbeddingPurpose.SEARCH_QUERY)).getResponseCode())
                .isEqualTo(expected);
    }

    private static ErrorObject error(String type, int status) {
        return ErrorObject.builder().message("quotes the prompt: " + SECRET).type(type).code(String.valueOf(status))
                .param(Optional.empty()).build();
    }

    @Test
    void returnsOneVectorPerTextInTheSameOrder() {
        float[] first = vector(1024);
        float[] second = vector(1024);
        first[0] = 1;
        second[1] = 1;
        when(model.call(any(EmbeddingRequest.class))).thenReturn(response(first, second));

        StepVerifier.create(adapter.embed(TEXTS, EmbeddingPurpose.DOCUMENT_INDEX))
                .assertNext(vectors -> assertThat(vectors).containsExactly(first, second))
                .verifyComplete();
    }

    @Test
    void asksForTheLogicalModelAndSaysWhyTheTextsAreEmbedded() {
        when(model.call(any(EmbeddingRequest.class))).thenReturn(response(vector(1024), vector(1024)));

        adapter.embed(TEXTS, EmbeddingPurpose.DOCUMENT_INDEX).block();

        ArgumentCaptor<EmbeddingRequest> request = ArgumentCaptor.forClass(EmbeddingRequest.class);
        verify(model).call(request.capture());
        OpenAiEmbeddingOptions options = (OpenAiEmbeddingOptions) request.getValue().getOptions();
        assertThat(options.getModel()).isEqualTo("embeddings");
        assertThat(options.getUser()).isEqualTo("purpose:document-index");
        assertThat(options.getDimensions()).isNull();
        assertThat(options.getCustomHeaders()).isNullOrEmpty();
    }

    @Test
    void sendsTheIdOfTheChatRequestWhenThereIsOne() {
        when(model.call(any(EmbeddingRequest.class))).thenReturn(response(vector(1024), vector(1024)));

        adapter.embed(TEXTS, EmbeddingPurpose.SEARCH_QUERY)
                .contextWrite(context -> context.put(GatewayHeaders.REQUEST_ID_CONTEXT_KEY, "req-42"))
                .block();

        ArgumentCaptor<EmbeddingRequest> request = ArgumentCaptor.forClass(EmbeddingRequest.class);
        verify(model).call(request.capture());
        OpenAiEmbeddingOptions options = (OpenAiEmbeddingOptions) request.getValue().getOptions();
        assertThat(options.getUser()).isEqualTo("purpose:search-query");
        assertThat(options.getCustomHeaders()).containsEntry(GatewayHeaders.REQUEST_ID_HEADER, "req-42");
    }

    @Test
    void knowsTheNameOfTheRealModelBehindTheLogicalOne() {
        assertThat(adapter.modelName()).isEqualTo("bge-m3");
    }

    @Test
    void withoutTextsItDoesNotCallTheModel() {
        StepVerifier.create(adapter.embed(List.of(), EmbeddingPurpose.DOCUMENT_INDEX)).expectNext(List.of())
                .verifyComplete();

        verifyNoInteractions(model);
    }

    @Test
    void aVectorOfAnotherSizeIsRejectedWithTheSizeMismatchError() {
        when(model.call(any(EmbeddingRequest.class))).thenReturn(response(vector(1024), vector(768)));

        assertThat(failureOf(adapter.embed(TEXTS, EmbeddingPurpose.DOCUMENT_INDEX)).getResponseCode())
                .isEqualTo(ResponseCodeEnum.EMBEDDING_DIMENSION_MISMATCH);
    }

    @Test
    void aDifferentNumberOfVectorsThanTextsIsAFailureOfTheModel() {
        when(model.call(any(EmbeddingRequest.class))).thenReturn(response(vector(1024)));

        assertThat(failureOf(adapter.embed(TEXTS, EmbeddingPurpose.DOCUMENT_INDEX)).getResponseCode())
                .isEqualTo(ResponseCodeEnum.EMBEDDING_SERVICE_FAILED);
    }

    @Test
    void aConnectionProblemMeansTheModelIsNotAvailable() {
        failsWith(ResponseCodeEnum.EMBEDDING_SERVICE_UNAVAILABLE,
                new ResourceAccessException("I/O error", new IOException("refused")));
    }

    @Test
    void aTimeoutHiddenInsideAnotherExceptionMeansTheModelIsNotAvailable() {
        failsWith(ResponseCodeEnum.EMBEDDING_SERVICE_UNAVAILABLE,
                new IllegalStateException("wrapped", new TimeoutException("slow")));
    }

    @Test
    void anyOtherErrorOfTheModelIsAFailure() {
        failsWith(ResponseCodeEnum.EMBEDDING_SERVICE_FAILED, new IllegalArgumentException("input too long"));
    }

    @Test
    void anExhaustedBudgetIsItsOwnErrorAndNotAnUnavailableModel() {
        // The gateway answers 422 with type budget_exceeded when the key has spent its budget.
        failsWith(ResponseCodeEnum.EMBEDDING_BUDGET_EXCEEDED, UnprocessableEntityException.builder()
                .headers(Headers.builder().build()).error(error("budget_exceeded", 422)).build());
    }

    @Test
    void aRejectedKeyAndAGatewayFailureMeanTheModelIsNotAvailable() {
        failsWith(ResponseCodeEnum.EMBEDDING_SERVICE_UNAVAILABLE, UnauthorizedException.builder()
                .headers(Headers.builder().build()).error(error("auth_error", 401)).build());
        failsWith(ResponseCodeEnum.EMBEDDING_SERVICE_UNAVAILABLE, InternalServerException.builder()
                .statusCode(503).headers(Headers.builder().build()).error(error("None", 503))
                .build());
    }

    @Test
    void anotherKindOfBadRequestIsAFailureOfTheModel() {
        failsWith(ResponseCodeEnum.EMBEDDING_SERVICE_FAILED, BadRequestException.builder()
                .headers(Headers.builder().build()).error(error("None", 400)).build());
    }
}

package com.shoplab.helpdesk.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoplab.helpdesk.retrieval.domain.model.ParsedDocument;
import com.shoplab.helpdesk.retrieval.infrastructure.out.ingest.IngestResponseMapper;
import com.shoplab.helpdesk.retrieval.infrastructure.out.ingest.dto.IngestErrorBody;
import com.shoplab.helpdesk.retrieval.infrastructure.out.ingest.dto.IngestResponse;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The client of the ingest service matches ingest/openapi.json, the contract of {@code POST /ingest} that the service
 * checks on its side (specs/LAB-20, FR-016, SC-008).
 */
class IngestContractTests {

    private static final Path CONTRACT = Path.of("../ingest/openapi.json");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static JsonNode contract;

    @BeforeAll
    static void readTheContract() throws IOException {
        contract = JSON.readTree(Files.readString(CONTRACT));
    }

    private static Set<String> propertiesOf(String schema) {
        Set<String> names = new HashSet<>();
        contract.at("/components/schemas/" + schema + "/properties").propertyNames().forEach(names::add);
        return names;
    }

    private static Set<String> requiredOf(String schema) {
        Set<String> names = new HashSet<>();
        contract.at("/components/schemas/" + schema + "/required").forEach(name -> names.add(name.asString()));
        return names;
    }

    private static Set<String> componentsOf(Class<?> record) {
        return new HashSet<>(Arrays.stream(record.getRecordComponents()).map(RecordComponent::getName).toList());
    }

    @Test
    void theClientReadsExactlyWhatTheContractAnswers() {
        assertThat(componentsOf(IngestResponse.class)).isEqualTo(propertiesOf("IngestResponse"));
        assertThat(componentsOf(IngestResponse.DocumentInfo.class)).isEqualTo(propertiesOf("DocumentInfo"));
        assertThat(componentsOf(IngestResponse.Chunk.class)).isEqualTo(propertiesOf("Chunk"));
        assertThat(componentsOf(IngestResponse.ChunkMetadata.class)).isEqualTo(propertiesOf("ChunkMetadata"));
    }

    @Test
    void everyFieldTheClientNeedsIsRequiredByTheContract() {
        assertThat(requiredOf("IngestResponse")).containsAll(componentsOf(IngestResponse.class));
        assertThat(requiredOf("DocumentInfo")).containsAll(componentsOf(IngestResponse.DocumentInfo.class));
        assertThat(requiredOf("Chunk")).containsAll(componentsOf(IngestResponse.Chunk.class));
        assertThat(requiredOf("ChunkMetadata")).containsAll(componentsOf(IngestResponse.ChunkMetadata.class));
    }

    @Test
    void theClientReadsTheErrorBodyOfTheContract() {
        assertThat(componentsOf(IngestErrorBody.class)).isEqualTo(propertiesOf("ErrorResponse"));
    }

    @Test
    void theRequestIsAMultipartFormWithTheFilePartTheClientSends() {
        JsonNode content = contract.at("/paths/~1ingest/post/requestBody/content");
        String bodySchema = content.get("multipart/form-data").get("schema").get("$ref").asString();

        assertThat(content.propertyNames()).containsExactly("multipart/form-data");
        assertThat(propertiesOf(bodySchema.substring(bodySchema.lastIndexOf('/') + 1))).contains("file");
    }

    @Test
    void theClientHandlesEveryErrorStatusTheContractDeclares() {
        Set<String> statuses = new HashSet<>();
        contract.at("/paths/~1ingest/post/responses").propertyNames().forEach(statuses::add);

        assertThat(statuses).containsExactlyInAnyOrder("200", "413", "415", "422", "500");
    }

    @Test
    void theExampleOfTheContractIsAnAnswerTheClientAccepts() {
        JsonNode example = contract.at("/components/schemas/IngestResponse/examples/0");

        IngestResponse response = JSON.treeToValue(example, IngestResponse.class);
        ParsedDocument parsed = new IngestResponseMapper().toParsedDocument(response);

        assertThat(parsed.name()).isEqualTo(example.at("/document/name").asString());
        assertThat(parsed.chunks()).hasSameSizeAs(example.get("chunks"));
        assertThat(parsed.chunks().get(0).position()).isZero();
    }
}

package com.shoplab.helpdesk.retrieval.infrastructure.out.ingest;

import com.shoplab.helpdesk.retrieval.domain.model.ParsedChunk;
import com.shoplab.helpdesk.retrieval.domain.model.ParsedDocument;
import com.shoplab.helpdesk.retrieval.domain.model.enums.DocumentFormat;
import com.shoplab.helpdesk.retrieval.infrastructure.out.ingest.dto.IngestResponse;
import com.shoplab.helpdesk.shared.exception.ExceptionUtil;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import com.shoplab.helpdesk.shared.exception.TechnicalException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Turns the answer of the ingest service into a {@link ParsedDocument}. An answer that breaks the contract (no chunks,
 * unknown format, positions that are not 0, 1, 2...) is a failure of the service, not a document to store.
 */
@Component
public class IngestResponseMapper {

    public ParsedDocument toParsedDocument(IngestResponse response) {
        if (response == null || response.document() == null || response.chunks() == null
                || response.chunks().isEmpty()) {
            throw malformed("the answer has no document or no chunks");
        }
        IngestResponse.DocumentInfo document = response.document();
        DocumentFormat format = DocumentFormat.fromExternalValue(document.format())
                .orElseThrow(() -> malformed("unknown format '" + document.format() + "'"));
        if (document.name() == null || document.name().isBlank() || document.title() == null) {
            throw malformed("the document has no name or no title");
        }
        List<ParsedChunk> chunks = new ArrayList<>(response.chunks().size());
        for (int index = 0; index < response.chunks().size(); index++) {
            chunks.add(toParsedChunk(response.chunks().get(index), index));
        }
        return new ParsedDocument(document.name(), document.title(), format, chunks);
    }

    private ParsedChunk toParsedChunk(IngestResponse.Chunk chunk, int expectedPosition) {
        if (chunk == null || chunk.metadata() == null || chunk.text() == null) {
            throw malformed("chunk " + expectedPosition + " is incomplete");
        }
        String text = chunk.text().replace("\0", "");
        if (text.isBlank()) {
            throw malformed("chunk " + expectedPosition + " has no text");
        }
        if (chunk.metadata().position() != expectedPosition) {
            throw malformed("chunk " + expectedPosition + " has position " + chunk.metadata().position());
        }
        String section = chunk.metadata().section() == null ? "" : chunk.metadata().section();
        return new ParsedChunk(expectedPosition, text, section);
    }

    private TechnicalException malformed(String reason) {
        return ExceptionUtil.technical(ResponseCodeEnum.INGEST_SERVICE_FAILED,
                new IllegalStateException("The ingest service answered against its contract: " + reason));
    }
}

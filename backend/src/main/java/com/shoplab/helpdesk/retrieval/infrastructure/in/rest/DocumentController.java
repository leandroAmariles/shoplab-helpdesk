package com.shoplab.helpdesk.retrieval.infrastructure.in.rest;

import com.shoplab.helpdesk.retrieval.application.port.in.IngestDocumentServicePort;
import com.shoplab.helpdesk.retrieval.application.port.in.ListDocumentsServicePort;
import com.shoplab.helpdesk.retrieval.config.UploadProperties;
import com.shoplab.helpdesk.retrieval.domain.model.IngestDocumentCommand;
import com.shoplab.helpdesk.retrieval.infrastructure.in.rest.dto.DocumentSummaryResponse;
import com.shoplab.helpdesk.retrieval.infrastructure.in.rest.dto.DocumentUploadResponse;
import com.shoplab.helpdesk.retrieval.infrastructure.in.rest.util.mapper.DocumentResponseMapper;
import com.shoplab.helpdesk.shared.infrastructure.in.rest.filter.RequestIdFilter;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Upload and list of the documents of the knowledge base (specs/LAB-25). Uploading reuses the ingestion of LAB-20;
 * the embeddings start on their own from the ingestion event (LAB-21). Errors are typed and mapped only by the
 * {@code GlobalExceptionHandler}; a part over the size limit ends as a {@code DataBufferLimitException}.
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private static final byte[] NO_CONTENT = new byte[0];

    private final IngestDocumentServicePort ingestDocument;
    private final ListDocumentsServicePort listDocuments;
    private final UploadProperties properties;

    public DocumentController(IngestDocumentServicePort ingestDocument, ListDocumentsServicePort listDocuments,
            UploadProperties properties) {
        this.ingestDocument = ingestDocument;
        this.listDocuments = listDocuments;
        this.properties = properties;
    }

    @PostMapping
    public Mono<ResponseEntity<DocumentUploadResponse>> upload(@RequestPart("file") FilePart file,
            @RequestAttribute(name = RequestIdFilter.REQUEST_ATTRIBUTE, required = false) String requestId) {
        return DataBufferUtils.join(file.content(), properties.maxBytes())
                .map(DocumentController::readAndRelease)
                .defaultIfEmpty(NO_CONTENT)
                .map(content -> new IngestDocumentCommand(lastSegment(file.filename()), content, requestId))
                .flatMap(ingestDocument::ingest)
                .map(stored -> ResponseEntity.status(stored.replaced() ? HttpStatus.OK : HttpStatus.CREATED)
                        .body(DocumentResponseMapper.toUploadResponse(stored)));
    }

    @GetMapping
    public Flux<DocumentSummaryResponse> list() {
        return listDocuments.list().map(DocumentResponseMapper::toSummaryResponse);
    }

    static String lastSegment(String filename) {
        if (filename == null) {
            return null;
        }
        int separator = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
        return filename.substring(separator + 1);
    }

    private static byte[] readAndRelease(DataBuffer buffer) {
        try {
            byte[] bytes = new byte[buffer.readableByteCount()];
            buffer.read(bytes);
            return bytes;
        } finally {
            DataBufferUtils.release(buffer);
        }
    }
}

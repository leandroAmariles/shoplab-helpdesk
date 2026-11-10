package com.shoplab.helpdesk.retrieval.infrastructure.out.persistence;

import com.shoplab.helpdesk.retrieval.application.port.out.DocumentCatalogPort;
import com.shoplab.helpdesk.retrieval.domain.model.DocumentSummary;
import com.shoplab.helpdesk.retrieval.domain.model.enums.IndexStatus;
import com.shoplab.helpdesk.shared.exception.ExceptionUtil;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import com.shoplab.helpdesk.shared.infrastructure.out.blocking.BlockingCalls;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.context.annotation.DependsOn;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * Lists the documents of the {@code retrieval} schema with the number of chunks stored for each (with or without
 * vector). Blocking JDBC runs through {@link BlockingCalls}.
 */
@Component
@DependsOn("moduleSchemaMigrator")
public class JdbcDocumentCatalogAdapter implements DocumentCatalogPort {

    private static final String LIST_DOCUMENTS = """
            SELECT d.id, d.source, d.title, d.index_status, d.index_error, d.updated_at, d.indexed_at,
                   count(c.id) AS chunk_count
            FROM retrieval.document d
            LEFT JOIN retrieval.chunk c ON c.document_id = d.id
            GROUP BY d.id
            ORDER BY d.updated_at DESC, d.id
            """;

    private final JdbcTemplate jdbc;

    public JdbcDocumentCatalogAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Flux<DocumentSummary> listDocuments() {
        return BlockingCalls.stream(() -> jdbc.query(LIST_DOCUMENTS, (rs, row) -> toSummary(rs)))
                .onErrorMap(DataAccessException.class,
                        error -> ExceptionUtil.technical(ResponseCodeEnum.DATABASE_UNAVAILABLE, error));
    }

    private static DocumentSummary toSummary(ResultSet rs) throws SQLException {
        return new DocumentSummary(rs.getObject("id", UUID.class), rs.getString("source"), rs.getString("title"),
                IndexStatus.fromExternalValue(rs.getString("index_status")), rs.getInt("chunk_count"),
                rs.getString("index_error"), instant(rs, "updated_at"), instant(rs, "indexed_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}

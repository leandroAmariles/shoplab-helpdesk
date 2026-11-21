package com.shoplab.helpdesk.eval.application.usecase;

import com.shoplab.helpdesk.eval.domain.model.EvalCase;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Identifies the content of a case: the SHA-256 of its five fields, so the same id with other content is another
 * revision (research.md R3). Fields are separated by the unit separator and the expected documents, sorted, by the
 * record separator, so no two different cases can be told apart only by where a separator falls.
 */
@Component
public class CaseRevisionHasher {

    private static final String FIELD_SEPARATOR = "\u001f";
    private static final String DOCUMENT_SEPARATOR = "\u001e";

    public String hash(EvalCase evalCase) {
        List<String> documents = evalCase.expectedDocuments().stream().sorted().toList();
        String canonical = String.join(FIELD_SEPARATOR, evalCase.caseId(), evalCase.category(), evalCase.question(),
                evalCase.referenceAnswer(), String.join(DOCUMENT_SEPARATOR, documents));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is part of every Java platform", error);
        }
    }
}

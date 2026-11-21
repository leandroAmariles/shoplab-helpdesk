package com.shoplab.helpdesk.eval.infrastructure.in.rest;

import com.shoplab.helpdesk.eval.application.port.in.FindEvalRunServicePort;
import com.shoplab.helpdesk.eval.application.port.in.SaveEvalRunServicePort;
import com.shoplab.helpdesk.eval.domain.model.constants.EvalConstants;
import com.shoplab.helpdesk.eval.infrastructure.in.rest.dto.EvalRunRequest;
import com.shoplab.helpdesk.eval.infrastructure.in.rest.dto.EvalRunSavedResponse;
import com.shoplab.helpdesk.eval.infrastructure.in.rest.dto.StoredRunResponse;
import com.shoplab.helpdesk.eval.infrastructure.in.rest.util.mapper.EvalRunRequestMapper;
import com.shoplab.helpdesk.eval.infrastructure.in.rest.util.mapper.EvalSettingsMapper;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * The door of the evaluation tool of {@code eval/} to the history (specs/LAB-31, ADR-0028): it hands over a whole run
 * and the token. The tool has no database credentials (ADR-0015). Errors are typed and mapped only by the
 * {@code GlobalExceptionHandler}.
 */
@RestController
@RequestMapping("/api/eval/runs")
public class EvalRunController {

    private final SaveEvalRunServicePort save;
    private final FindEvalRunServicePort find;

    public EvalRunController(SaveEvalRunServicePort save, FindEvalRunServicePort find) {
        this.save = save;
        this.find = find;
    }

    @GetMapping("/{runId}")
    public Mono<StoredRunResponse> find(
            @RequestHeader(name = EvalConstants.TOKEN_HEADER, required = false) String token,
            @PathVariable("runId") String runId) {
        return find.find(token, runId).map(EvalSettingsMapper::toResponse);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<EvalRunSavedResponse> save(
            @RequestHeader(name = EvalConstants.TOKEN_HEADER, required = false) String token,
            @RequestBody EvalRunRequest body) {
        return save.save(token, EvalRunRequestMapper.toRun(body)).map(EvalRunRequestMapper::toResponse);
    }
}

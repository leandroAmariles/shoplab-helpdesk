package com.shoplab.helpdesk.eval.infrastructure.in.rest;

import com.shoplab.helpdesk.eval.application.port.in.GetEvalRunServicePort;
import com.shoplab.helpdesk.eval.application.port.in.ListEvalRunsCommand;
import com.shoplab.helpdesk.eval.application.port.in.ListEvalRunsServicePort;
import com.shoplab.helpdesk.eval.application.usecase.TraceUrlBuilder;
import com.shoplab.helpdesk.eval.infrastructure.in.rest.dto.RunDetailResponse;
import com.shoplab.helpdesk.eval.infrastructure.in.rest.dto.RunListResponse;
import com.shoplab.helpdesk.eval.infrastructure.in.rest.util.mapper.EvalHistoryMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * The read side of the history of evaluation runs, for the admin-eval screen (specs/LAB-32, ADR-0029). It only reads
 * and asks for no token: the token of {@code /api/eval/runs} protects what writes, and it never reaches the browser.
 * The parameters go to the use case as they came, which checks them; errors are typed and mapped only by the
 * {@code GlobalExceptionHandler}.
 */
@RestController
@RequestMapping("/api/eval/history")
public class EvalHistoryController {

    private final ListEvalRunsServicePort list;
    private final GetEvalRunServicePort get;
    private final TraceUrlBuilder trace;

    public EvalHistoryController(ListEvalRunsServicePort list, GetEvalRunServicePort get, TraceUrlBuilder trace) {
        this.list = list;
        this.get = get;
        this.trace = trace;
    }

    @GetMapping("/runs")
    public Mono<RunListResponse> list(@RequestParam(name = "kind", required = false) String kind,
            @RequestParam(name = "origin", required = false) String origin,
            @RequestParam(name = "limit", required = false) String limit) {
        return list.list(new ListEvalRunsCommand(kind, origin, limit)).map(EvalHistoryMapper::toResponse);
    }

    @GetMapping("/runs/{runId}")
    public Mono<RunDetailResponse> get(@PathVariable("runId") String runId) {
        return get.get(runId).map(run -> EvalHistoryMapper.toResponse(run, trace));
    }
}

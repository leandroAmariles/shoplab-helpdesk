package com.shoplab.helpdesk.eval.infrastructure.in.rest;

import com.shoplab.helpdesk.eval.application.port.in.ReadAssistantSettingsServicePort;
import com.shoplab.helpdesk.eval.domain.model.constants.EvalConstants;
import com.shoplab.helpdesk.eval.infrastructure.in.rest.dto.EvalSettingsResponse;
import com.shoplab.helpdesk.eval.infrastructure.in.rest.util.mapper.EvalSettingsMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * What the assistant uses now, for the evaluation tool to record with each run and to notice a change while it
 * measures (specs/LAB-31, research R6). It needs the same token as the saving of runs.
 */
@RestController
@RequestMapping("/api/eval/settings")
public class EvalSettingsController {

    private final ReadAssistantSettingsServicePort settings;

    public EvalSettingsController(ReadAssistantSettingsServicePort settings) {
        this.settings = settings;
    }

    @GetMapping
    public Mono<EvalSettingsResponse> read(
            @RequestHeader(name = EvalConstants.TOKEN_HEADER, required = false) String token) {
        return settings.read(token).map(EvalSettingsMapper::toResponse);
    }
}

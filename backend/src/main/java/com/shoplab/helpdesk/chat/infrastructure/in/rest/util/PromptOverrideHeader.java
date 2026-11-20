package com.shoplab.helpdesk.chat.infrastructure.in.rest.util;

import com.shoplab.helpdesk.chat.config.PromptProperties;
import com.shoplab.helpdesk.chat.domain.exception.PromptOverrideException;
import com.shoplab.helpdesk.chat.domain.exception.PromptOverrideForbiddenException;
import com.shoplab.helpdesk.chat.domain.model.PromptOverrides;
import com.shoplab.helpdesk.chat.domain.model.enums.PromptName;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.EnumMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Reads the headers with which an evaluation fixes prompt versions in a request (LAB-85): {@code X-Prompt-Versions:
 * chat-system=3,chat-context=2} and {@code X-Prompt-Token}. It decides nothing about the chat, only whether the request
 * may fix versions and which ones.
 *
 * <p>A request without the versions header is a normal one. A request with it needs the capability on (the prompts are
 * managed and a token is configured) and the right token, or it is refused with 403 and never answered with other
 * prompts: an evaluation that is badly configured must fail, not measure with the wrong ones. The permission is checked
 * before the format, so a caller without the token learns nothing about the format. Neither the headers nor the token
 * appear in any message.
 */
@Component
public class PromptOverrideHeader {

    private static final Pattern VERSION = Pattern.compile("[0-9]{1,9}");

    private final PromptProperties properties;

    public PromptOverrideHeader(PromptProperties properties) {
        this.properties = properties;
    }

    /**
     * @param versions value of {@code X-Prompt-Versions}, or {@code null} when the request has no such header
     * @param token    value of {@code X-Prompt-Token}, or {@code null}
     * @return the versions the request fixes; none when it does not fix any
     * @throws PromptOverrideForbiddenException the request may not fix versions
     * @throws PromptOverrideException          the header is malformed
     */
    public PromptOverrides parse(String versions, String token) {
        if (versions == null) {
            return PromptOverrides.NONE;
        }
        requireAllowed(token);
        return parseVersions(versions);
    }

    private void requireAllowed(String token) {
        if (!properties.overrideEnabled() || token == null || !sameToken(token)) {
            throw new PromptOverrideForbiddenException();
        }
    }

    /** Compared in constant time, so the time it takes says nothing about how much of the token was right. */
    private boolean sameToken(String token) {
        return MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                properties.overrideToken().getBytes(StandardCharsets.UTF_8));
    }

    private static PromptOverrides parseVersions(String header) {
        Map<PromptName, Integer> versions = new EnumMap<>(PromptName.class);
        for (String entry : header.split(",", -1)) {
            String[] parts = entry.strip().split("=", -1);
            if (parts.length != 2) {
                throw PromptOverrideException.malformed();
            }
            PromptName name = PromptName.fromLangfuseName(parts[0].strip())
                    .orElseThrow(PromptOverrideException::malformed);
            String number = parts[1].strip();
            if (!VERSION.matcher(number).matches() || Integer.parseInt(number) < 1
                    || versions.put(name, Integer.parseInt(number)) != null) {
                throw PromptOverrideException.malformed();
            }
        }
        return new PromptOverrides(versions);
    }
}

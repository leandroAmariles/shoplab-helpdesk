package com.shoplab.helpdesk.chat.infrastructure.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shoplab.helpdesk.chat.config.PromptProperties;
import com.shoplab.helpdesk.chat.domain.exception.PromptOverrideException;
import com.shoplab.helpdesk.chat.domain.exception.PromptOverrideForbiddenException;
import com.shoplab.helpdesk.chat.domain.model.PromptOverrides;
import com.shoplab.helpdesk.chat.domain.model.enums.PromptName;
import com.shoplab.helpdesk.chat.infrastructure.in.rest.util.PromptOverrideHeader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PromptOverrideHeaderTests {

    private static final String TOKEN = "token-de-evaluacion";

    private static PromptOverrideHeader header(Boolean enabled, String token) {
        return new PromptOverrideHeader(new PromptProperties(enabled, null, "pk", "sk", null, null, null, null, null,
                token));
    }

    private final PromptOverrideHeader on = header(true, TOKEN);

    @Test
    void withoutTheVersionsHeaderTheRequestIsANormalOneWhateverTheTokenAndTheSwitch() {
        assertThat(on.parse(null, null)).isEqualTo(PromptOverrides.NONE);
        assertThat(on.parse(null, "cualquiera")).isEqualTo(PromptOverrides.NONE);
        assertThat(header(false, null).parse(null, null)).isEqualTo(PromptOverrides.NONE);
    }

    @Test
    void validVersionsWithTheRightTokenAreParsedWithSpacesAndInAnyOrder() {
        PromptOverrides overrides = on.parse(" chat-context = 2 ,chat-system=3,chat-small-talk=10", TOKEN);

        assertThat(overrides.versions()).containsOnly(
                java.util.Map.entry(PromptName.CHAT_SYSTEM, 3),
                java.util.Map.entry(PromptName.CONTEXT, 2),
                java.util.Map.entry(PromptName.SMALL_TALK, 10));
    }

    @Test
    void withoutAConfiguredTokenTheCapabilityIsOffAndEveryRequestWithVersionsIsForbidden() {
        PromptOverrideHeader off = header(true, "");

        assertThatThrownBy(() -> off.parse("chat-system=3", "")).isInstanceOf(PromptOverrideForbiddenException.class);
        assertThatThrownBy(() -> off.parse("chat-system=3", null)).isInstanceOf(PromptOverrideForbiddenException.class);
        assertThatThrownBy(() -> off.parse("chat-system=3", "algo"))
                .isInstanceOf(PromptOverrideForbiddenException.class);
    }

    @Test
    void withTheManagementOffAndATokenConfiguredTheCapabilityIsOffToo() {
        PromptOverrideHeader off = header(false, TOKEN);

        assertThatThrownBy(() -> off.parse("chat-system=3", TOKEN)).isInstanceOf(PromptOverrideForbiddenException.class);
    }

    @Test
    void aMissingOrDifferentTokenIsForbidden() {
        assertThatThrownBy(() -> on.parse("chat-system=3", null)).isInstanceOf(PromptOverrideForbiddenException.class);
        assertThatThrownBy(() -> on.parse("chat-system=3", "otro")).isInstanceOf(PromptOverrideForbiddenException.class);
        assertThatThrownBy(() -> on.parse("chat-system=3", TOKEN + " "))
                .isInstanceOf(PromptOverrideForbiddenException.class);
        assertThatThrownBy(() -> on.parse("chat-system=3", TOKEN.substring(1)))
                .isInstanceOf(PromptOverrideForbiddenException.class);
    }

    @Test
    void thePermissionIsCheckedBeforeTheFormatSoACallerWithoutTheTokenLearnsNothingAboutIt() {
        assertThatThrownBy(() -> on.parse("esto no es valido", null))
                .isInstanceOf(PromptOverrideForbiddenException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "chat-system", "chat-system=", "=3", "chat-system=x", "chat-system=0",
            "chat-system=-1", "chat-system=1.5", "chat-system=3,", ",chat-system=3", "desconocido=1",
            "chat-system=1,chat-system=2", "chat-system=3=4", "chat-system=1234567890", "chat-system=latest"})
    void aMalformedHeaderIsRejectedWithTheRightToken(String value) {
        assertThatThrownBy(() -> on.parse(value, TOKEN)).isInstanceOf(PromptOverrideException.class)
                .hasMessageNotContaining(value.isBlank() ? "\u0000" : value);
    }

    @Test
    void theMessagesOfTheRefusalsNeverCarryTheTokenOrTheHeader() {
        assertThatThrownBy(() -> on.parse("chat-system=3", "el-token-que-no-vale"))
                .hasMessageNotContaining("el-token-que-no-vale").hasMessageNotContaining("chat-system=3")
                .hasMessageNotContaining(TOKEN);
    }
}

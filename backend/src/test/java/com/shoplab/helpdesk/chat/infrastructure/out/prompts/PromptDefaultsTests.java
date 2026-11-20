package com.shoplab.helpdesk.chat.infrastructure.out.prompts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shoplab.helpdesk.chat.application.usecase.PromptValidator;
import com.shoplab.helpdesk.chat.domain.model.ResolvedPrompt;
import com.shoplab.helpdesk.chat.domain.model.enums.PromptName;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The texts are the ones the chat had before LAB-85 (FR-028): their hashes were taken before moving them. */
class PromptDefaultsTests {

    private static final Map<PromptName, String> ORIGINAL_SHA256 = Map.of(
            PromptName.CHAT_SYSTEM, "5c3f999d23aa1ffac66ab8f9877022230d2b0863f119142b45ff452f69dd345d",
            PromptName.SMALL_TALK, "cac17ad10eb782c478b8a5a79b946a42ab46f38709bca27145062ce9a81a4b8d",
            PromptName.CONTEXT, "9239e2e217b2c605835e9fcfda9fb2ef8239ae67845cb2eb56a0d01aeea18752",
            PromptName.EMPTY_CONTEXT, "0c0641448594adbc312b27d01ecaa465e1094759f9f19633b69da9159ecaaba5");

    private final PromptDefaults defaults = new PromptDefaults(new PromptValidator(20_000));

    @Test
    void theDefaultTextsAreByteForByteTheOriginalOnes() throws Exception {
        for (PromptName name : PromptName.values()) {
            byte[] bytes = defaults.get(name).text().getBytes(StandardCharsets.UTF_8);

            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                    .as(name.langfuseName())
                    .isEqualTo(ORIGINAL_SHA256.get(name));
        }
    }

    @Test
    void everyDefaultIsMarkedAsDefaultAndHasItsMarkers() {
        for (PromptName name : PromptName.values()) {
            ResolvedPrompt prompt = defaults.get(name);

            assertThat(prompt.reference().isDefault()).isTrue();
            assertThat(prompt.reference().name()).isEqualTo(name);
            name.requiredPlaceholders().forEach(marker -> assertThat(prompt.text()).contains(marker));
        }
    }

    @Test
    void theApplicationDoesNotStartWithAnInvalidDefault() {
        // A validator that rejects every text stands for a default that was edited into something unusable.
        PromptValidator impossible = new PromptValidator(1);

        assertThatThrownBy(() -> new PromptDefaults(impossible))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prompts/chat-system.txt")
                .hasMessageContaining("too-long");
    }
}

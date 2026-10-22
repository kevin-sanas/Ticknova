package com.ticknova.ai.model;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class ModelConfigurationTest {
    @Test void defaultMockNeedsNoProviderSecretsAndCanActuallyEmbed() {
        var model = new ModelConfiguration().aiModel(new MockEnvironment());
        assertThat(model.mode()).isEqualTo("mock");
        assertThat(model.embed(List.of("密码重置")).getFirst()).hasSize(1024);
    }
    @Test void unknownOrIncompleteRealProviderNeverSilentlyFallsBack() {
        var config = new ModelConfiguration();
        assertThatThrownBy(() -> config.aiModel(new MockEnvironment().withProperty("ticknova.ai.provider", "typo")))
                .isInstanceOfSatisfying(AiModelFailure.class, e -> assertThat(e.code()).isEqualTo("AI_CONFIGURATION_INVALID"));
        assertThatThrownBy(() -> config.aiModel(new MockEnvironment().withProperty("ticknova.ai.provider", "openai")))
                .isInstanceOfSatisfying(AiModelFailure.class, e -> assertThat(e.code()).isEqualTo("AI_CONFIGURATION_INVALID"));
    }
}

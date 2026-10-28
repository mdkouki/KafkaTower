package com.lynra.kafkatower.config;

import com.embabel.agent.config.models.openai.OpenAiModelDefinition;
import com.embabel.agent.config.models.openai.OpenAiModelDefinitions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConfiguredModelLoaderTest {

    private static OpenAiModelDefinitions shipped() {
        OpenAiModelDefinition known = new OpenAiModelDefinition("gpt-5.1", "gpt-5.1", null, null, null,
                16384, 1.0, null, null, com.embabel.agent.config.models.openai.OpenAiApiFormat.CHAT_COMPLETIONS, null);
        return new OpenAiModelDefinitions(List.of(known), List.of(), null);
    }

    @Test
    void aModelMissingFromTheYmlIsRegistered() {
        OpenAiModelDefinitions result = ConfiguredModelLoader.withModel(shipped(), "qwen2.5-7b-instruct-1m");

        assertThat(result.getModels()).extracting(OpenAiModelDefinition::getName)
                .containsExactly("gpt-5.1", "qwen2.5-7b-instruct-1m");
        assertThat(result.getModels().get(1).getModelId()).isEqualTo("qwen2.5-7b-instruct-1m");
    }

    @Test
    void aModelAlreadyInTheYmlIsNotDuplicated() {
        assertThat(ConfiguredModelLoader.withModel(shipped(), "gpt-5.1").getModels()).hasSize(1);
    }

    @Test
    void aBlankModelChangesNothing() {
        assertThat(ConfiguredModelLoader.withModel(shipped(), " ").getModels()).hasSize(1);
    }
}

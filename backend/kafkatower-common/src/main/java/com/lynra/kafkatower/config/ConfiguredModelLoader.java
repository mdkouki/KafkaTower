package com.lynra.kafkatower.config;

import com.embabel.agent.config.models.openai.OpenAiApiFormat;
import com.embabel.agent.config.models.openai.OpenAiModelDefinition;
import com.embabel.agent.config.models.openai.OpenAiModelDefinitions;
import com.embabel.agent.config.models.openai.OpenAiModelLoader;
import com.embabel.common.ai.autoconfig.LlmAutoConfigMetadataLoader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Registers the model saved in the LLM settings with Embabel, so any model name the endpoint
 * serves works without being listed in {@code models/openai-models.yml}. Embabel refuses to start
 * when {@code embabel.models.default-llm} is not a registered model; the yml stays the source for
 * the shipped models and the embedding model, and the configured model is added when missing.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnAssistantEnabled
public class ConfiguredModelLoader {

    @Bean
    LlmAutoConfigMetadataLoader<OpenAiModelDefinitions> openAiModelLoader(
            @Value("${embabel.models.default-llm:}") String configuredModel) {
        OpenAiModelLoader yml = new OpenAiModelLoader();
        return () -> withModel(yml.loadAutoConfigMetadata(), configuredModel);
    }

    static OpenAiModelDefinitions withModel(OpenAiModelDefinitions loaded, String model) {
        if (!StringUtils.hasText(model)
                || loaded.getModels().stream().anyMatch(m -> model.equals(m.getName()) || model.equals(m.getModelId()))) {
            return loaded;
        }
        List<OpenAiModelDefinition> models = new ArrayList<>(loaded.getModels());
        models.add(new OpenAiModelDefinition(model, model, model, null, null, 16384, 0.7, null, null,
                OpenAiApiFormat.CHAT_COMPLETIONS, null));
        return new OpenAiModelDefinitions(models, loaded.getEmbeddingModels(), loaded.getNativeSupportDefaults());
    }
}

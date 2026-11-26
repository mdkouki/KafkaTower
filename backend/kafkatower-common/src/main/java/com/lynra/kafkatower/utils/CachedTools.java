package com.lynra.kafkatower.utils;

import com.embabel.agent.api.annotation.LlmTool;
import com.embabel.agent.api.tool.Tool;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the tools of an {@code @LlmTool} object, serving those whose methods carry
 * {@link CachedTool} from {@link ToolResultCache}. Offer them with {@code PromptRunner.withTools}
 * instead of {@code withToolObject}.
 */
@Component
public class CachedTools {

    private final ToolResultCache cache;

    public CachedTools(ToolResultCache cache) {
        this.cache = cache;
    }

    public List<Tool> from(Object toolObject) {
        Class<?> type = ClassUtils.getUserClass(toolObject);
        Map<String, Duration> ttlByToolName = ttlByToolName(type);
        return Tool.fromInstance(toolObject).stream()
                .map(tool -> ttlByToolName.containsKey(tool.getDefinition().getName())
                        ? new CachingTool(tool, type.getName(), cache, ttlByToolName.get(tool.getDefinition().getName()))
                        : tool)
                .toList();
    }

    private static Map<String, Duration> ttlByToolName(Class<?> type) {
        Map<String, Duration> ttlByToolName = new HashMap<>();
        for (Method method : ReflectionUtils.getUniqueDeclaredMethods(type)) {
            CachedTool cachedTool = AnnotatedElementUtils.findMergedAnnotation(method, CachedTool.class);
            LlmTool llmTool = AnnotatedElementUtils.findMergedAnnotation(method, LlmTool.class);
            if (cachedTool != null && llmTool != null) {
                ttlByToolName.put(toolName(llmTool, method), Duration.ofSeconds(cachedTool.ttlSeconds()));
            }
        }
        return ttlByToolName;
    }

    private static String toolName(LlmTool llmTool, Method method) {
        return llmTool.name().isBlank() ? method.getName() : llmTool.name();
    }
}

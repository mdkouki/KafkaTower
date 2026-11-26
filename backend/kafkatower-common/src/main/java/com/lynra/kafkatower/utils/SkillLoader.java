package com.lynra.kafkatower.utils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Loads always-on prompt Markdown from the classpath: the shared guardrails in {@code skills/shared/},
 * the root orchestrator's templated prompt and the single-shot reviewer/classifier prompts. These
 * must reach the model on every call, so they are inlined into the system prompt.
 * <p>
 * Agent Skills (a {@code SKILL.md} directory the model loads on demand) are not read here —
 * see {@link AgentSkills}.
 */
public class SkillLoader {

    private static final Pattern HTML_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern TRAILING_LINE_WHITESPACE = Pattern.compile("[ \t]+\n");
    private static final Pattern MULTIPLE_BLANK_LINES = Pattern.compile("\n{3,}");

    public static String loadContent(String classpathPath) {
        try (InputStream is = SkillLoader.class.getResourceAsStream(classpathPath)) {
            if (is == null) throw new RuntimeException("Skill not found on classpath: " + classpathPath);
            String raw = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return stripForLlm(raw);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load skill: " + classpathPath, e);
        }
    }

    // Mechanical, meaning-preserving trims only (no reflow/paraphrase, markdown structure kept
    // intact) — the .md files on disk stay untouched for humans; this only shrinks what's
    // actually sent to the LLM as a system prompt, on every request, so the savings compound.
    static String stripForLlm(String raw) {
        String noComments = HTML_COMMENT.matcher(raw).replaceAll("");
        String noTrailingWs = TRAILING_LINE_WHITESPACE.matcher(noComments).replaceAll("\n");
        String collapsedBlankLines = MULTIPLE_BLANK_LINES.matcher(noTrailingWs).replaceAll("\n\n");
        return collapsedBlankLines.strip();
    }
}

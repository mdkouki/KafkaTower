package com.lynra.kafkatower.agents.core.guardrails;

import com.embabel.agent.api.validation.guardrails.AssistantMessageGuardRail;
import com.embabel.agent.core.Blackboard;
import com.embabel.common.core.thinking.ThinkingResponse;
import com.embabel.common.core.validation.ValidationError;
import com.embabel.common.core.validation.ValidationResult;
import com.embabel.common.core.validation.ValidationSeverity;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Fails validation if an agent's answer appears to contain a leaked credential — a bearer
 * token, an API key/secret/password assignment, or a PEM private key block. Agents whose
 * tools touch MCP servers or live Kafka state (which are configured with bearer tokens)
 * attach this via {@code PromptRunner.withGuardRails(INSTANCE)} as a last line of defense
 * against the LLM echoing a secret back into the chat.
 * <p>
 * {@code GuardRail} is a sealed interface permitting only {@link AssistantMessageGuardRail}
 * and {@code UserInputGuardRail}; this validates the generated answer, so it implements the
 * former.
 */
public final class NoSecretLeakageGuardRail implements AssistantMessageGuardRail {

    public static final NoSecretLeakageGuardRail INSTANCE = new NoSecretLeakageGuardRail();

    private static final List<Pattern> SECRET_PATTERNS = List.of(
            Pattern.compile("(?i)bearer\\s+[a-z0-9_\\-.]{16,}"),
            // The value must look like an actual secret (mixed-case/digit high-entropy run) —
            // not merely any 12+ char run — otherwise a legitimate answer mentioning e.g.
            // "SASL_TOKEN: enabled" or a topic named "auth-token: some-readable-name" trips a
            // CRITICAL validation failure.
            Pattern.compile("(?i)(api[_-]?key|secret|password|token)\\s*[:=]\\s*['\"]?" +
                    "(?=[a-z0-9_\\-.]{12,}['\"]?(?:\\s|$))(?=[a-z0-9_\\-.]*[0-9])(?=[a-z0-9_\\-.]*[A-Za-z])[a-z0-9_\\-.]{12,}['\"]?"),
            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----")
    );

    private NoSecretLeakageGuardRail() {
    }

    @Override
    public String getName() {
        return "no-secret-leakage";
    }

    @Override
    public String getDescription() {
        return "Fails validation if the response appears to contain a leaked credential, bearer token, or private key.";
    }

    @Override
    public ValidationResult validate(String content, Blackboard blackboard) {
        return check(content);
    }

    @Override
    public ValidationResult validate(ThinkingResponse<?> response, Blackboard blackboard) {
        Object result = response.getResult();
        return check(result == null ? null : result.toString());
    }

    private static ValidationResult check(String content) {
        if (content == null) {
            return ValidationResult.Companion.getVALID();
        }
        for (Pattern pattern : SECRET_PATTERNS) {
            if (pattern.matcher(content).find()) {
                return new ValidationResult(false, List.of(new ValidationError(
                        "SECRET_LEAKAGE",
                        "Response appears to contain a credential or token and was blocked.",
                        ValidationSeverity.CRITICAL)));
            }
        }
        return ValidationResult.Companion.getVALID();
    }
}

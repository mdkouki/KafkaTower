package com.lynra.kafkatower.agents.core.guardrails;

import com.embabel.common.core.thinking.ThinkingResponse;
import com.embabel.common.core.validation.ValidationResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NoSecretLeakageGuardRailTest {

    private final NoSecretLeakageGuardRail guardRail = NoSecretLeakageGuardRail.INSTANCE;

    @Test
    void validatesNormalTextAsValid() {
        ValidationResult result = guardRail.validate("Topic 'orders' is owned by team payments.", null);

        assertThat(result.isValid()).isTrue();
    }

    @Test
    void treatsNullContentAsValid() {
        ValidationResult result = guardRail.validate((String) null, null);

        assertThat(result.isValid()).isTrue();
    }

    @Test
    void rejectsABearerToken() {
        ValidationResult result = guardRail.validate("Use header Authorization: Bearer sk-abcdef0123456789", null);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).extracting("code").containsExactly("SECRET_LEAKAGE");
    }

    @Test
    void rejectsAnApiKeyAssignment() {
        ValidationResult result = guardRail.validate("api_key: sk-abcdef0123456789xyz", null);

        assertThat(result.isValid()).isFalse();
    }

    @Test
    void rejectsAPrivateKeyBlock() {
        ValidationResult result = guardRail.validate("-----BEGIN RSA PRIVATE KEY-----\nMIIB...", null);

        assertThat(result.isValid()).isFalse();
    }

    @Test
    void validatesThinkingResponseWrapperConsistentlyWithPlainString() {
        ValidationResult valid = guardRail.validate(new ThinkingResponse<>("all clear", List.of()), null);
        ValidationResult invalid = guardRail.validate(
                new ThinkingResponse<>("token: aXk9mQ2plmNop7z", List.of()), null);

        assertThat(valid.isValid()).isTrue();
        assertThat(invalid.isValid()).isFalse();
    }

    @Test
    void doesNotFlagAPlainWordAfterTokenAsASecret() {
        // Regression for a false positive: any 12+ char run after "token:" used to trip this,
        // including ordinary readable text with no actual secret shape (no digit at all).
        ValidationResult result = guardRail.validate("SASL_TOKEN: enabled-for-this-cluster", null);

        assertThat(result.isValid()).isTrue();
    }
}

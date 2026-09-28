package com.lynra.kafkatower.utils;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillLoaderTest {

    @Test
    void loadsFileContentFromClasspath() {
        String content = SkillLoader.loadContent("/skilltest/sample.md");

        assertThat(content).contains("Sample skill");
        assertThat(content).contains("fixture file used by SkillLoaderTest");
    }

    @Test
    void throwsWhenFileIsMissingFromClasspath() {
        assertThatThrownBy(() -> SkillLoader.loadContent("/skilltest/does-not-exist.md"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Skill not found on classpath");
    }

    @Test
    void stripsHtmlComments() {
        String stripped = SkillLoader.stripForLlm("Before <!-- author note, not for the LLM --> After");

        assertThat(stripped).isEqualTo("Before  After");
    }

    @Test
    void stripsMultiLineHtmlComments() {
        String stripped = SkillLoader.stripForLlm("Before\n<!--\nnote spanning\nmultiple lines\n-->\nAfter");

        assertThat(stripped).isEqualTo("Before\n\nAfter");
    }

    @Test
    void collapsesMultipleBlankLinesToOne() {
        String stripped = SkillLoader.stripForLlm("Line one\n\n\n\n\nLine two");

        assertThat(stripped).isEqualTo("Line one\n\nLine two");
    }

    @Test
    void trimsTrailingWhitespacePerLine() {
        String stripped = SkillLoader.stripForLlm("Line one   \nLine two\t\t\n");

        assertThat(stripped).isEqualTo("Line one\nLine two");
    }

    @Test
    void trimsLeadingAndTrailingWhitespaceOfWholeDocument() {
        String stripped = SkillLoader.stripForLlm("\n\n  Content  \n\n");

        assertThat(stripped).isEqualTo("Content");
    }

    @Test
    void preservesMarkdownStructureOfRealSkillFile() {
        String content = SkillLoader.loadContent("/skilltest/sample.md");

        // headers/lists are meaningful structure for the LLM — must not be stripped
        assertThat(content).contains("#");
    }
}

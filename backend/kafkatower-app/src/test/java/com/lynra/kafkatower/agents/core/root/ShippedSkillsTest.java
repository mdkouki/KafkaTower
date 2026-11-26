package com.lynra.kafkatower.agents.core.root;

import com.lynra.kafkatower.utils.AgentSkills;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Holds every skill shipped in the application to the Agent Skills authoring guidance
 * (https://agentskills.io/specification, https://agentskills.io/skill-creation/best-practices):
 * a lean core body, a description that says when to use the skill, and no orphaned reference files.
 */
class ShippedSkillsTest {

    /** Embabel budgets about 50-100 tokens of metadata per skill; the spec allows up to 1024 characters. */
    private static final int MAX_DESCRIPTION_CHARS = 400;
    private static final int MAX_BODY_LINES = 500;
    private static final String DESCRIPTION_OPENING = "Use this skill";
    private static final Pattern SKILL_FILE_URL = Pattern.compile(".*/skills/([^/]+)/SKILL\\.md$");
    private static final Pattern FRONT_MATTER = Pattern.compile("(?s)\\A---\\n(.*?)\\n---\\n(.*)\\z");
    private static final Pattern DESCRIPTION = Pattern.compile("(?m)^description: \"(.*)\"$");
    private static final Pattern NAME = Pattern.compile("(?m)^name: (.+)$");

    private final PathMatchingResourcePatternResolver classpath = new PathMatchingResourcePatternResolver();

    @Test
    void everyExpectedSkillIsShipped() throws IOException {
        assertThat(shippedSkillNames()).containsExactlyInAnyOrder(
                "kafka-admin-reference", "kafka-investigation-playbooks", "kafka-investigation-report",
                "kafka-investigator", "kafka-log-inspector", "kafka-metrics-catalog", "kafka-metrics-inspector",
                "kafka-metrics-queries", "kafka-registry-inspector", "rich-output-formatting");
    }

    @Test
    void everyShippedSkillFollowsTheAuthoringGuidanceAndPassesEmbabelValidation() throws IOException {
        AgentSkills agentSkills = new AgentSkills();
        SoftAssertions softly = new SoftAssertions();
        try {
            for (String skill : shippedSkillNames()) {
                String content = read("skills/" + skill + "/SKILL.md");
                Matcher frontMatter = FRONT_MATTER.matcher(content);
                softly.assertThat(frontMatter.matches()).as("%s has front matter", skill).isTrue();
                if (!frontMatter.matches()) {
                    continue;
                }
                String header = frontMatter.group(1);
                String body = frontMatter.group(2);

                softly.assertThat(group(NAME, header)).as("%s: name matches its directory", skill).isEqualTo(skill);
                String description = group(DESCRIPTION, header);
                softly.assertThat(description).as("%s: description says when to use the skill", skill)
                        .startsWith(DESCRIPTION_OPENING);
                softly.assertThat(description.length()).as("%s: description length", skill)
                        .isLessThanOrEqualTo(MAX_DESCRIPTION_CHARS);
                softly.assertThat(body.lines().count()).as("%s: body lines", skill).isLessThanOrEqualTo(MAX_BODY_LINES);
                for (String reference : referenceFiles(skill)) {
                    softly.assertThat(body).as("%s: body says when to read %s", skill, reference)
                            .contains("references/" + reference);
                }
                softly.assertThatCode(() -> agentSkills.reference(skill)).as("%s loads", skill).doesNotThrowAnyException();
            }
        } finally {
            agentSkills.destroy();
        }
        softly.assertAll();
    }

    private List<String> shippedSkillNames() throws IOException {
        List<String> names = new ArrayList<>();
        for (Resource resource : classpath.getResources("classpath*:skills/*/SKILL.md")) {
            Matcher matcher = SKILL_FILE_URL.matcher(resource.getURL().toString());
            if (matcher.matches()) {
                names.add(matcher.group(1));
            }
        }
        return names;
    }

    private List<String> referenceFiles(String skill) throws IOException {
        List<String> files = new ArrayList<>();
        for (Resource resource : classpath.getResources("classpath*:skills/" + skill + "/references/*")) {
            String filename = resource.getFilename();
            if (filename != null && resource.isReadable()) {
                files.add(filename);
            }
        }
        return files;
    }

    private String read(String path) throws IOException {
        return new String(classpath.getResource("classpath:" + path).getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
    }

    private static String group(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : "";
    }
}

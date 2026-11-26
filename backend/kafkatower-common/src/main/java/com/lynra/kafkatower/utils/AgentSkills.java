package com.lynra.kafkatower.utils;

import com.embabel.agent.skills.Skills;
import com.embabel.agent.skills.support.DefaultDirectorySkillDefinitionLoader;
import com.embabel.agent.skills.support.DirectorySkillDefinitionLoader;
import com.embabel.agent.skills.support.LoadedSkill;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads Agent Skills (https://agentskills.io/specification) for the agents the way Embabel
 * documents it: an agent's role and core instructions go in its system prompt
 * ({@link #systemPrompt}), and optional skills are offered through {@code PromptRunner.withReference}
 * ({@link #reference}), which lists them by name and description and lets the model load a skill's
 * instructions and read its {@code references/} files on demand.
 * <p>
 * Embabel's loaders read skill directories from the file system only, so a skill packaged on the
 * classpath (<code>skills/&lt;name&gt;/SKILL.md</code>, possibly spread over several module jars) is
 * first copied to a temporary directory, once per skill.
 */
@Lazy
@Component
public class AgentSkills implements DisposableBean {

    private static final String CLASSPATH_SKILLS_ROOT = "skills/";
    private static final String SKILLS_REFERENCE_NAME = "skills";
    private static final String SKILLS_REFERENCE_DESCRIPTION = "Optional instructions and reference material for this agent";
    private static final String TEMP_DIRECTORY_PREFIX = "kafkatower-skills-";

    private final PathMatchingResourcePatternResolver classpath = new PathMatchingResourcePatternResolver();
    private final DirectorySkillDefinitionLoader loader = new DefaultDirectorySkillDefinitionLoader(true);
    private final Map<String, LoadedSkill> loadedSkills = new ConcurrentHashMap<>();
    private final Map<List<String>, Skills> references = new ConcurrentHashMap<>();
    private final Path extractionRoot;

    public AgentSkills() {
        try {
            extractionRoot = Files.createTempDirectory(TEMP_DIRECTORY_PREFIX);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create a directory to extract skills into", e);
        }
    }

    /**
     * The optional skills for {@code PromptRunner.withReference}: the model sees each skill's name
     * and description and reads what it needs on demand.
     */
    public Skills reference(String... skillNames) {
        return references.computeIfAbsent(List.of(skillNames), this::buildReference);
    }

    /**
     * The system prompt for {@code skillName}: the skill's instructions, inlined so they reach the
     * model on every call, followed by the always-on {@code sharedGuidance} (null when there is none).
     */
    public String systemPrompt(String skillName, String sharedGuidance) {
        String instructions = load(skillName).getInstructions();
        if (instructions == null) {
            throw new IllegalStateException("Skill '" + skillName + "' has no instructions");
        }
        return sharedGuidance == null ? instructions : instructions + "\n\n" + sharedGuidance;
    }

    @Override
    public void destroy() {
        FileSystemUtils.deleteRecursively(extractionRoot.toFile());
    }

    private Skills buildReference(List<String> skillNames) {
        LoadedSkill[] skills = skillNames.stream().map(this::load).toArray(LoadedSkill[]::new);
        return new Skills(SKILLS_REFERENCE_NAME, SKILLS_REFERENCE_DESCRIPTION).withSkills(skills);
    }

    private LoadedSkill load(String skillName) {
        return loadedSkills.computeIfAbsent(skillName, this::extractAndLoad);
    }

    private LoadedSkill extractAndLoad(String skillName) {
        Path directory = extractionRoot.resolve(skillName);
        extract(skillName, directory);
        return loader.load(directory);
    }

    private void extract(String skillName, Path directory) {
        String skillPrefix = "/" + CLASSPATH_SKILLS_ROOT + skillName + "/";
        boolean found = false;
        try {
            for (Resource resource : classpath.getResources("classpath*:" + CLASSPATH_SKILLS_ROOT + skillName + "/**")) {
                String url = resource.getURL().toString();
                if (url.endsWith("/")) {
                    continue;
                }
                Path target = directory.resolve(url.substring(url.lastIndexOf(skillPrefix) + skillPrefix.length()));
                Files.createDirectories(target.getParent());
                try (InputStream content = resource.getInputStream()) {
                    Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
                }
                found = true;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to extract skill: " + skillName, e);
        }
        if (!found) {
            throw new IllegalArgumentException("Skill not found on classpath: " + skillName);
        }
    }
}

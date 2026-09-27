package com.cloud.alibaba.ai.example.skills.skillsagentexample.workflow;

import com.cloud.alibaba.ai.example.skills.skillsagentexample.workflow.model.WorkflowDefinition;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 扫描/读写 workflows/*.json，持久化前端画布生成的工作流定义。
 */
@Component
public class WorkflowRepository {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRepository.class);

    private final WorkflowProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public WorkflowRepository(WorkflowProperties properties) {
        this.properties = properties;
    }

    /** 解析 workflows 目录：优先项目根，回退当前工作目录。 */
    private Path resolveDir() {
        Path projectDir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path dir = projectDir.resolve(properties.getWorkflowsDir());
        if (!Files.isDirectory(dir)) {
            dir = Path.of(properties.getWorkflowsDir()).toAbsolutePath();
        }
        return dir;
    }

    public List<WorkflowDefinition> loadAll() {
        List<WorkflowDefinition> result = new ArrayList<>();
        Path dir = resolveDir();
        if (!Files.isDirectory(dir)) {
            log.warn("Workflows directory not found: {}", dir);
            return result;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            for (Path p : stream.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList()) {
                try {
                    WorkflowDefinition wf = objectMapper.readValue(p.toFile(), WorkflowDefinition.class);
                    if (wf.name() == null || wf.name().isBlank()) {
                        log.warn("Skip workflow without name: {}", p);
                        continue;
                    }
                    result.add(wf);
                } catch (IOException e) {
                    log.error("Failed to parse workflow: {}", p, e);
                }
            }
        } catch (IOException e) {
            log.error("Failed to list workflows directory: {}", dir, e);
        }
        return result;
    }

    public Optional<WorkflowDefinition> find(String name) {
        return loadAll().stream().filter(w -> w.name().equals(name)).findFirst();
    }

    public void save(WorkflowDefinition wf) {
        try {
            Path dir = resolveDir();
            Files.createDirectories(dir);
            Path file = dir.resolve(sanitize(wf.name()) + ".json");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), wf);
            log.info("Workflow saved: {}", file);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save workflow: " + wf.name(), e);
        }
    }

    public void delete(String name) {
        try {
            Path file = resolveDir().resolve(sanitize(name) + ".json");
            Files.deleteIfExists(file);
            log.info("Workflow deleted: {}", file);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to delete workflow: " + name, e);
        }
    }

    private String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9\\-_]", "_");
    }
}
package com.cloud.alibaba.ai.example.skills.skillsagentexample.evaluation;

import com.cloud.alibaba.ai.example.skills.skillsagentexample.agent.SkillsAgent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 评测服务：加载 golden-set.json，逐条调用 Agent 并校验输出。
 * <p>
 * 评测维度：
 * <ul>
 *   <li>expectedKeywords：回答中必须包含的关键词</li>
 *   <li>mustNotContain：回答中不能出现的关键词</li>
 *   <li>followUp：多轮对话验证（可选）</li>
 * </ul>
 */
@Service
public class EvaluationService {

    private static final Logger log = LoggerFactory.getLogger(EvaluationService.class);

    private final SkillsAgent skillsAgent;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private JsonNode goldenSet;

    public EvaluationService(SkillsAgent skillsAgent) {
        this.skillsAgent = skillsAgent;
    }

    @PostConstruct
    public void init() {
        loadGoldenSet();
    }

    private void loadGoldenSet() {
        try {
            ClassPathResource resource = new ClassPathResource("evaluation/golden-set.json");
            try (InputStream is = resource.getInputStream()) {
                goldenSet = objectMapper.readTree(is);
                log.info("Golden set loaded, version={}, cases={}",
                        goldenSet.path("version").asText("unknown"),
                        goldenSet.path("cases").size());
            }
        } catch (Exception e) {
            log.error("Failed to load golden set", e);
            goldenSet = objectMapper.createObjectNode();
        }
    }

    /**
     * 运行完整评测集。
     */
    public EvaluationReport runAll() {
        if (goldenSet == null || !goldenSet.has("cases")) {
            return new EvaluationReport("unknown", 0, 0, 0, 0.0, 0, List.of());
        }

        String version = goldenSet.path("version").asText("unknown");
        JsonNode cases = goldenSet.get("cases");
        List<EvaluationCaseResult> results = new ArrayList<>();
        long totalStart = System.currentTimeMillis();

        for (JsonNode caseNode : cases) {
            EvaluationCaseResult result = runSingleCase(caseNode);
            results.add(result);
        }

        long totalDuration = System.currentTimeMillis() - totalStart;
        int passed = (int) results.stream().filter(EvaluationCaseResult::passed).count();
        int total = results.size();
        double passRate = total > 0 ? (passed * 100.0 / total) : 0.0;

        log.info("Evaluation completed: {}/{} passed ({:.1f}%), duration={}ms"
                .formatted(passed, total, passRate, totalDuration));

        return new EvaluationReport(version, total, passed, total - passed, passRate, totalDuration, results);
    }

    /**
     * 运行单个评测用例。
     */
    private EvaluationCaseResult runSingleCase(JsonNode caseNode) {
        String caseId = caseNode.path("id").asText();
        String category = caseNode.path("category").asText();
        String description = caseNode.path("description").asText();
        String input = caseNode.path("input").asText();

        List<String> expectedKeywords = toStringList(caseNode.path("expectedKeywords"));
        List<String> mustNotContain = toStringList(caseNode.path("mustNotContain"));

        long start = System.currentTimeMillis();
        String output = callAgent(input);
        long duration = System.currentTimeMillis() - start;

        // 校验关键词
        List<String> matched = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String kw : expectedKeywords) {
            if (output.contains(kw)) {
                matched.add(kw);
            } else {
                missing.add(kw);
            }
        }

        List<String> violated = new ArrayList<>();
        for (String kw : mustNotContain) {
            if (output.contains(kw)) {
                violated.add(kw);
            }
        }

        boolean passed = missing.isEmpty() && violated.isEmpty();

        // 处理多轮追问（如果有）
        JsonNode followUp = caseNode.get("followUp");
        if (followUp != null && passed) {
            String followUpInput = followUp.path("input").asText();
            List<String> followUpExpected = toStringList(followUp.path("expectedKeywords"));
            String followUpOutput = callAgentWithHistory(input, output, followUpInput);
            for (String kw : followUpExpected) {
                if (followUpOutput.contains(kw)) {
                    matched.add(kw);
                } else {
                    missing.add(kw);
                    passed = false;
                }
            }
            output = output + "\n--- FOLLOW UP ---\n" + followUpOutput;
        }

        return new EvaluationCaseResult(
                caseId, category, description, input, output,
                passed, matched, missing, violated, duration
        );
    }

    /**
     * 单轮调用 Agent。
     */
    private String callAgent(String input) {
        try {
            List<Message> messages = List.of(new UserMessage(input));
            AssistantMessage reply = skillsAgent.chat(messages);
            return reply.getText() == null ? "" : reply.getText();
        } catch (Exception e) {
            log.error("Agent call failed for eval case", e);
            return "ERROR: " + e.getMessage();
        }
    }

    /**
     * 多轮调用 Agent（带历史）。
     */
    private String callAgentWithHistory(String user1, String assistant1, String user2) {
        try {
            List<Message> messages = List.of(
                    new UserMessage(user1),
                    new AssistantMessage(assistant1),
                    new UserMessage(user2)
            );
            AssistantMessage reply = skillsAgent.chat(messages);
            return reply.getText() == null ? "" : reply.getText();
        } catch (Exception e) {
            log.error("Agent follow-up call failed", e);
            return "ERROR: " + e.getMessage();
        }
    }

    private List<String> toStringList(JsonNode node) {
        List<String> list = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                list.add(item.asText());
            }
        }
        return list;
    }
}

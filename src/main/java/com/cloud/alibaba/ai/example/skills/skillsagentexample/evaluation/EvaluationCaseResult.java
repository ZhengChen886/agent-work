package com.cloud.alibaba.ai.example.skills.skillsagentexample.evaluation;

import java.util.List;

/**
 * 单个评测用例的执行结果。
 */
public record EvaluationCaseResult(
        String caseId,
        String category,
        String description,
        String input,
        String actualOutput,
        boolean passed,
        List<String> matchedKeywords,
        List<String> missingKeywords,
        List<String> violatedKeywords,
        long durationMs
) {
}

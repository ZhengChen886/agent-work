package com.cloud.alibaba.ai.example.skills.skillsagentexample.dto;

import java.util.List;

/**
 * 小模型语义解读结果：意图分类、领域识别、槽位提取。
 */
public record SemanticInterpretation(
        String intent,
        String domain,
        List<String> slots,
        boolean success,
        String raw) {

    public static SemanticInterpretation failed() {
        return new SemanticInterpretation("other", "general", List.of(), false, null);
    }

    /** 转成可注入大模型的系统上下文片段。 */
    public String contextText() {
        StringBuilder sb = new StringBuilder("[SYSTEM] 语义解读 - 意图: ").append(intent)
                .append(", 领域: ").append(domain);
        if (slots != null && !slots.isEmpty()) {
            sb.append(", 槽位: ").append(String.join(", ", slots));
        }
        return sb.toString();
    }
}
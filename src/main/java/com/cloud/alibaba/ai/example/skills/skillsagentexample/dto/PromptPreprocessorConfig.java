package com.cloud.alibaba.ai.example.skills.skillsagentexample.dto;

import java.util.List;
import java.util.Map;

public record PromptPreprocessorConfig(
        List<String> bannedWords,
        String bannedWordsMessage,
        Map<String, String> commands,
        boolean enabled
) {
}

package com.cloud.alibaba.ai.example.skills.skillsagentexample.agent;

import org.springframework.stereotype.Component;

@Component
public class BannedWordsRule implements PromptPreprocessor {

    private static final java.util.regex.Pattern CACHE = java.util.regex.Pattern.compile("", java.util.regex.Pattern.CASE_INSENSITIVE);

    private final BannedWordsConfig config;
    private volatile java.util.regex.Pattern cache;

    public BannedWordsRule(BannedWordsConfig config) {
        this.config = config;
    }

    @Override
    public int getOrder() {
        return 0;
    }

    @Override
    public ProcessResult process(String originalPrompt) {
        if (originalPrompt == null) {
            return ProcessResult.continueWith(java.util.List.of());
        }
        String lower = originalPrompt.toLowerCase();
        java.util.regex.Pattern pattern = getPattern();
        for (String word : config.getWords()) {
            if (pattern.matcher(lower).find()) {
                org.slf4j.LoggerFactory.getLogger(BannedWordsRule.class)
                        .warn("Banned word matched: '{}' in prompt", word);
                return ProcessResult.blocked(config.getErrorMessage());
            }
        }
        return ProcessResult.continueWith(java.util.List.of());
    }

    private synchronized java.util.regex.Pattern getPattern() {
        if (cache == null) {
            cache = java.util.regex.Pattern.compile(
                    "\\b(" + String.join("|", config.getWords()) + ")\\b",
                    java.util.regex.Pattern.CASE_INSENSITIVE);
        }
        return cache;
    }
}

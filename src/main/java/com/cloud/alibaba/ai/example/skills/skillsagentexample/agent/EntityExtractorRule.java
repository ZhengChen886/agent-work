package com.cloud.alibaba.ai.example.skills.skillsagentexample.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

@Component
class EntityExtractorRule implements PromptPreprocessor {

    private static final Logger log = LoggerFactory.getLogger(EntityExtractorRule.class);

    private static final Pattern DATE_PATTERN =
            Pattern.compile("\\d{4}[-/年]\\d{1,2}[-/月]\\d{1,2}[日]?",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern NAME_PATTERN =
            Pattern.compile("[\\u4e00-\\u9fff]{2,4}(?=[的，,、\\s]|$)");

    @Override
    public int getOrder() {
        return 20;
    }

    @Override
    public ProcessResult process(String originalPrompt) {
        return ProcessResult.continueWith(List.of());
    }

    @Override
    public List<String> appendSystemContext(String originalPrompt, ProcessResult result) {
        if (originalPrompt == null) {
            return List.of();
        }
        List<String> context = new ArrayList<>();
        extractDates(originalPrompt).ifPresent(dates ->
                context.add("[SYSTEM] Detected dates: " + dates));
        extractNames(originalPrompt).ifPresent(names ->
                context.add("[SYSTEM] Detected names: " + names));
        if (context.isEmpty()) {
            return List.of();
        }
        log.debug("Entity extraction context: {}", context);
        return context;
    }

    private Optional<String> extractDates(String prompt) {
        Matcher m = DATE_PATTERN.matcher(prompt);
        List<String> dates = new ArrayList<>();
        while (m.find()) {
            dates.add(m.group());
        }
        return dates.isEmpty() ? Optional.empty() : Optional.of(String.join(", ", dates));
    }

    private Optional<String> extractNames(String prompt) {
        Matcher m = NAME_PATTERN.matcher(prompt);
        List<String> names = new ArrayList<>();
        while (m.find() && names.size() < 5) {
            names.add(m.group());
        }
        return names.isEmpty() ? Optional.empty() : Optional.of(String.join(", ", names));
    }
}

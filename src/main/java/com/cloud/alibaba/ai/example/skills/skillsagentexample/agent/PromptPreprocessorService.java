package com.cloud.alibaba.ai.example.skills.skillsagentexample.agent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class PromptPreprocessorService {

    private static final Logger log = LoggerFactory.getLogger(PromptPreprocessorService.class);

    private final List<PromptPreprocessor> rules;

    @Autowired
    public PromptPreprocessorService(List<PromptPreprocessor> ruleBeans) {
        this.rules = ruleBeans.stream()
                .sorted(Comparator.comparingInt(PromptPreprocessor::getOrder))
                .toList();
        log.info("Loaded {} prompt preprocessor rules", rules.size());
    }

    public PromptPreprocessor.ProcessResult process(String originalPrompt) {
        if (originalPrompt == null || originalPrompt.isBlank()) {
            return PromptPreprocessor.ProcessResult.continueWith(List.of());
        }
        PromptPreprocessor.ProcessResult result = PromptPreprocessor.ProcessResult.continueWith(List.of());
        for (PromptPreprocessor rule : rules) {
            result = rule.process(originalPrompt);
            if (result.isBlocked()) {
                log.info("Prompt blocked by rule: {}", rule.getClass().getSimpleName());
                return result;
            }
            if (result.isCommand()) {
                log.info("Command matched by rule: {}", rule.getClass().getSimpleName());
                return result;
            }
        }
        return result;
    }

    public List<String> appendSystemContext(String originalPrompt,
                                             PromptPreprocessor.ProcessResult result) {
        List<String> context = new ArrayList<>();
        for (PromptPreprocessor rule : rules) {
            List<String> append = rule.appendSystemContext(originalPrompt, result);
            if (!append.isEmpty()) {
                context.addAll(append);
            }
        }
        return context;
    }
}

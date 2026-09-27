package com.cloud.alibaba.ai.example.skills.skillsagentexample.agent;

import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

@Component
class CommandRule implements PromptPreprocessor {

    private static final Logger log = LoggerFactory.getLogger(CommandRule.class);

    private final CommandsConfig config;
    private volatile String prefixCache = null;
    private volatile String actionCache = null;

    public CommandRule(CommandsConfig config) {
        this.config = config;
    }

    @Override
    public int getOrder() {
        return 10;
    }

    @Override
    public ProcessResult process(String originalPrompt) {
        if (originalPrompt == null || originalPrompt.isBlank()) {
            return ProcessResult.continueWith(List.of());
        }
        synchronized (this) {
            if (config.getPrefixes().keySet().stream().anyMatch(originalPrompt::startsWith)) {
                prefixCache = null;
                actionCache = null;
            }
        }
        String prefix = getPrefix(originalPrompt);
        if (prefix != null) {
            String action = config.getPrefixes().get(prefix);
            log.info("Command detected: {} -> {}", prefix, action);
            return ProcessResult.command(prefix, action);
        }
        return ProcessResult.continueWith(List.of());
    }

    @Override
    public List<String> appendSystemContext(String originalPrompt, ProcessResult result) {
        if (!result.isCommand() || result.commandAliases() == null) {
            return List.of();
        }
        String prefix = result.commandAliases()[0];
        String action = result.commandAliases()[1];
        return List.of(
                "[SYSTEM] Detected command prefix: " + prefix,
                "[SYSTEM] Task type: " + action,
                "[SYSTEM] Please handle this request using the appropriate specialized capability."
        );
    }

    private String getPrefix(String prompt) {
        for (String prefix : config.getPrefixes().keySet()) {
            if (prompt.startsWith(prefix)) {
                return prefix;
            }
        }
        return null;
    }
}

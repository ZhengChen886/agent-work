package com.cloud.alibaba.ai.example.skills.skillsagentexample.agent;

import java.util.List;
import org.springframework.core.Ordered;

public interface PromptPreprocessor extends Ordered {

    int ORDER = 100;

    record ProcessResult(boolean blocked, String blockReason, String[] commandAliases) {

        static ProcessResult blocked(String reason) {
            return new ProcessResult(true, reason, null);
        }

        static ProcessResult command(String... aliases) {
            return new ProcessResult(false, null, aliases);
        }

        static ProcessResult continueWith(List<String> systemContext) {
            return new ProcessResult(false, null, null);
        }

        public boolean isBlocked() {
            return blocked;
        }

        public boolean isCommand() {
            return !blocked && commandAliases != null;
        }
    }

    default int getOrder() {
        return ORDER;
    }

    ProcessResult process(String originalPrompt);

    default List<String> appendSystemContext(String originalPrompt, ProcessResult result) {
        return List.of();
    }
}

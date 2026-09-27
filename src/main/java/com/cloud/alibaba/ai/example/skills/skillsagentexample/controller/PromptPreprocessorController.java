package com.cloud.alibaba.ai.example.skills.skillsagentexample.controller;

import com.cloud.alibaba.ai.example.skills.skillsagentexample.agent.BannedWordsConfig;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.agent.CommandsConfig;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.dto.PromptPreprocessorConfig;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/preprocessor")
public class PromptPreprocessorController {

    private final BannedWordsConfig bannedWordsConfig;
    private final CommandsConfig commandsConfig;

    public PromptPreprocessorController(BannedWordsConfig bannedWordsConfig,
                                        CommandsConfig commandsConfig) {
        this.bannedWordsConfig = bannedWordsConfig;
        this.commandsConfig = commandsConfig;
    }

    @GetMapping
    public PromptPreprocessorConfig getConfig() {
        return new PromptPreprocessorConfig(
                bannedWordsConfig.getWords(),
                bannedWordsConfig.getErrorMessage(),
                commandsConfig.getPrefixes(),
                true
        );
    }

    @PutMapping
    public PromptPreprocessorConfig updateConfig(
            @RequestBody PromptPreprocessorConfig config) {
        if (config.bannedWords() != null) {
            bannedWordsConfig.setWords(config.bannedWords());
        }
        if (config.bannedWordsMessage() != null) {
            bannedWordsConfig.setErrorMessage(config.bannedWordsMessage());
        }
        if (config.commands() != null) {
            commandsConfig.setPrefixes(config.commands());
        }
        return getConfig();
    }
}

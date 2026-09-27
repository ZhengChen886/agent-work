package com.cloud.alibaba.ai.example.skills.skillsagentexample.agent;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "prompt.preprocessor.banned-words")
public class BannedWordsConfig {

    private List<String> words = List.of(
            "kill", "die", "murder", "suicide", "bomb", "weapon",
            "exploit", "hack", "steal", "fraud", "child abuse"
    );

    private String errorMessage = "您的输入包含敏感词汇，已被系统拦截。请修改后重试。";

    public List<String> getWords() {
        return words;
    }

    public void setWords(List<String> words) {
        this.words = words;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}

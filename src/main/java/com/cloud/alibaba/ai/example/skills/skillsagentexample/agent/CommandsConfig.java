package com.cloud.alibaba.ai.example.skills.skillsagentexample.agent;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "prompt.preprocessor.commands")
public class CommandsConfig {

    private Map<String, String> prefixes = Map.of(
            "/translate", "translate",
            "/code", "coding",
            "/summarize", "summarize"
    );

    public Map<String, String> getPrefixes() {
        return prefixes;
    }

    public void setPrefixes(Map<String, String> prefixes) {
        this.prefixes = prefixes;
    }
}

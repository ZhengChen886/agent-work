package com.cloud.alibaba.ai.example.skills.skillsagentexample.dto;

import java.util.List;

public record SkillInfo(String name, String description, String path, List<String> files) {
}
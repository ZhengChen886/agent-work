package com.cloud.alibaba.ai.example.skills.skillsagentexample.controller;

import com.cloud.alibaba.ai.example.skills.skillsagentexample.dto.SkillInfo;
import com.cloud.alibaba.ai.example.skills.skillsagentexample.service.SkillService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private final SkillService skillService;

    public SkillController(SkillService skillService) {
        this.skillService = skillService;
    }

    @GetMapping
    public ResponseEntity<List<SkillInfo>> list() {
        return ResponseEntity.ok(skillService.list());
    }

    @PostMapping("/upload")
    public ResponseEntity<SkillInfo> upload(@RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(skillService.upload(file));
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<Void> delete(@PathVariable String name) {
        skillService.delete(name);
        return ResponseEntity.noContent().build();
    }
}
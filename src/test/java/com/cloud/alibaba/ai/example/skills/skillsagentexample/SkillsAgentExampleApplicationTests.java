package com.cloud.alibaba.ai.example.skills.skillsagentexample;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// admin.port=0：Admin 后台改绑随机空闲端口，避免与运行中的应用（8088）冲突
@SpringBootTest(properties = "admin.port=0")
class SkillsAgentExampleApplicationTests {

    @Test
    void contextLoads() {
    }

}

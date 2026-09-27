package com.cloud.alibaba.ai.example.skills.skillsagentexample.dto;

/**
 * 供应商 / 模型切换请求。
 * providerId 为空时仅切换模型（需配合当前激活供应商使用）；
 * model 为空时切换为供应商默认模型。
 */
public record ModelSwitchRequest(String providerId, String model) {
}

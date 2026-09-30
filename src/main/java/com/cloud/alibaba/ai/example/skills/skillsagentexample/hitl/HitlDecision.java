package com.cloud.alibaba.ai.example.skills.skillsagentexample.hitl;

import java.time.LocalDateTime;

/**
 * 一次人工审批决策。
 *
 * @param action    批准 / 拒绝
 * @param responder 审批人标识（无鉴权环境下仅作展示）
 * @param note      备注 / 拒绝理由
 * @param decidedAt 决策时间
 */
public record HitlDecision(Action action, String responder, String note, LocalDateTime decidedAt) {

    public enum Action { APPROVE, REJECT }

    public static HitlDecision approve(String responder, String note) {
        return new HitlDecision(Action.APPROVE, responder, note, LocalDateTime.now());
    }

    public static HitlDecision reject(String responder, String note) {
        return new HitlDecision(Action.REJECT, responder, note, LocalDateTime.now());
    }
}

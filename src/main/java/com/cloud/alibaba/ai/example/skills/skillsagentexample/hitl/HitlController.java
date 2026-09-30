package com.cloud.alibaba.ai.example.skills.skillsagentexample.hitl;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * HITL 审批 REST 接口。
 *
 * <ul>
 *   <li>{@code GET  /api/hitl/requests?status=PENDING} 待审批列表（轮询兜底，SSE 为主通道）</li>
 *   <li>{@code GET  /api/hitl/requests/{id}} 请求详情</li>
 *   <li>{@code POST /api/hitl/requests/{id}/decide} 提交决策（批准 / 拒绝）</li>
 *   <li>{@code GET  /api/hitl/config} 当前审批配置</li>
 *   <li>{@code GET  /api/hitl/pending-count} 在途请求数（监控）</li>
 * </ul>
 *
 * <p>注意：主端口当前为 permitAll（见 SecurityConfig 审查结论），
 * 该接口同样未鉴权——对外部署前应先收敛主端口鉴权。</p>
 */
@RestController
@RequestMapping("/api/hitl")
public class HitlController {

    private final HitlManager hitlManager;
    private final HitlRequestRepository repository;
    private final HitlProperties properties;

    public HitlController(HitlManager hitlManager,
                          HitlRequestRepository repository,
                          HitlProperties properties) {
        this.hitlManager = hitlManager;
        this.repository = repository;
        this.properties = properties;
    }

    /** 审批请求列表；status 缺省 PENDING，传 status=ALL 查询全部。 */
    @GetMapping("/requests")
    public List<HitlRequest> list(@RequestParam(defaultValue = "PENDING") String status) {
        if ("ALL".equalsIgnoreCase(status)) {
            return repository.findAll();
        }
        return repository.findByStatusOrderByCreatedAtDesc(status.toUpperCase());
    }

    @GetMapping("/requests/{id}")
    public ResponseEntity<HitlRequest> detail(@PathVariable String id) {
        return repository.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 提交人工决策。
     *
     * @param body {"action":"APPROVE"|"REJECT","responder":"...","note":"..."}
     */
    @PostMapping("/requests/{id}/decide")
    public ResponseEntity<Map<String, Object>> decide(@PathVariable String id,
                                                      @RequestBody DecideRequest body) {
        HitlDecision.Action action = parseAction(body.action());
        if (action == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "非法 action: " + body.action() + "（仅支持 APPROVE / REJECT）"));
        }
        HitlDecision decision = action == HitlDecision.Action.APPROVE
                ? HitlDecision.approve(body.responder(), body.note())
                : HitlDecision.reject(body.responder(), body.note());

        Optional<HitlRequest> responded = hitlManager.respond(id, decision);
        if (responded.isPresent()) {
            HitlRequest updated = responded.get();
            return ResponseEntity.ok(Map.of(
                    "id", updated.getId(),
                    "status", updated.getStatus(),
                    "responder", updated.getResponder() == null ? "" : updated.getResponder()));
        }
        // 区分"不存在"与"已被处理"，便于前端给出准确提示
        if (repository.existsById(id)) {
            return ResponseEntity.status(409).body(Map.of(
                    "error", "该审批请求已被处理",
                    "id", id,
                    "status", repository.findById(id).map(HitlRequest::getStatus).orElse("?")));
        }
        return ResponseEntity.status(404).body(Map.of("error", "审批请求不存在: " + id));
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        return Map.of(
                "enabled", properties.isEnabled(),
                "timeoutSeconds", properties.getTimeoutSeconds(),
                "requiresApproval", properties.getRequiresApproval(),
                "autoApprove", properties.getAutoApprove());
    }

    @GetMapping("/pending-count")
    public Map<String, Object> pendingCount() {
        return Map.of("count", hitlManager.pendingCount());
    }

    /** 解析 action；非法值返回 null（由调用方转 400）。 */
    private static HitlDecision.Action parseAction(String action) {
        if (action == null) {
            return null;
        }
        try {
            return HitlDecision.Action.valueOf(action.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 决策请求体。 */
    public record DecideRequest(String action, String responder, String note) {}
}

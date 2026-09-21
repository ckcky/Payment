package com.payment.common.mq.dlq;

import com.payment.common.mq.MqTopics;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * DLQ 管理端点（spec 034 §10 / T20）：死信可见性 + 人工重放 + 清空。
 *
 * <p>端点清单（与 spec §10 目标一致）：</p>
 * <ul>
 *   <li>{@code GET /internal/mq/dlq} —— 全 topic 死信条数总览</li>
 *   <li>{@code GET /internal/mq/dlq?topic=X&limit=N} —— 单 topic 明细（XRANGE）</li>
 *   <li>{@code POST /internal/mq/dlq/{topic}/{entryId}/replay} —— 原样回放（不跳过消费端幂等）</li>
 *   <li>{@code DELETE /internal/mq/dlq/{topic}} —— 清空</li>
 * </ul>
 *
 * <p><b>守卫（复用 X-Admin-Token 模式）</b>：Bean 仅在
 * {@code payment.mq.dlq-admin.enabled=true} 且 classpath 有 spring-web 时注册
 * （类级 {@code @ConditionalOnClass/@ConditionalOnProperty}，auto-config {@code @Import} 以
 * ASM 元数据处理，spring-web 缺席时本类不被加载）；启用但未配置 {@code admin-token} → 503 锁死；
 * 令牌不匹配 → 403。与 {@code payment.resolve.admin-token}（ResolveAuthorizationInterceptor）
 * 同一先例（spec 034 §1.1#5）。</p>
 */
@RestController
@RequestMapping("/internal/mq/dlq")
@ConditionalOnClass(RestController.class)
@ConditionalOnProperty(prefix = "payment.mq.dlq-admin", name = "enabled", havingValue = "true")
public class MqDlqAdminController {

    private final MqDlqAdminService service;
    private final MqDlqAdminProperties properties;
    private final int listLimit;

    public MqDlqAdminController(MqDlqAdminService service, MqDlqAdminProperties properties) {
        this.service = service;
        this.properties = properties;
        this.listLimit = 100;
    }

    /** 全 topic 总览（不带 topic 参数时）。 */
    @GetMapping
    public ResponseEntity<?> overview(@RequestHeader(name = "X-Admin-Token", required = false) String token) {
        ResponseEntity<?> denied = guard(token);
        if (denied != null) {
            return denied;
        }
        List<TopicSize> topics = java.util.Arrays.stream(MqTopics.ALL)
                .map(t -> new TopicSize(t, service.size(t)))
                .toList();
        return ResponseEntity.ok(new DlqOverview(topics));
    }

    /** 单 topic 明细：死信条数 + 条目视图（含 dlqReason 与原始信封字段）。 */
    @GetMapping(params = "topic")
    public ResponseEntity<?> detail(@RequestHeader(name = "X-Admin-Token", required = false) String token,
                                    @RequestParam("topic") String topic,
                                    @RequestParam(name = "limit", defaultValue = "100") int limit) {
        ResponseEntity<?> denied = guard(token);
        if (denied != null) {
            return denied;
        }
        if (!MqTopics.isKnown(topic)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorView("unknown topic: " + topic));
        }
        int capped = Math.min(Math.max(1, limit), listLimit);
        return ResponseEntity.ok(new DlqTopicView(topic, service.size(topic), service.list(topic, capped)));
    }

    /** 原样回放单条死信。404=条目不存在；409=条目损坏（payload 不可反序列化），DLQ 原样保留。 */
    @PostMapping("/{topic}/{entryId}/replay")
    public ResponseEntity<?> replay(@RequestHeader(name = "X-Admin-Token", required = false) String token,
                                    @PathVariable String topic, @PathVariable String entryId) {
        ResponseEntity<?> denied = guard(token);
        if (denied != null) {
            return denied;
        }
        if (!MqTopics.isKnown(topic)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorView("unknown topic: " + topic));
        }
        try {
            boolean replayed = service.replay(topic, entryId);
            if (replayed) {
                return ResponseEntity.ok(new ReplayView(topic, entryId, "REPLAYED"));
            }
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorView("dlq entry not found: " + entryId));
        } catch (com.payment.common.mq.MqException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorView(ex.getMessage()));
        }
    }

    /** 清空单 topic 死信。 */
    @DeleteMapping("/{topic}")
    public ResponseEntity<?> clear(@RequestHeader(name = "X-Admin-Token", required = false) String token,
                                   @PathVariable String topic) {
        ResponseEntity<?> denied = guard(token);
        if (denied != null) {
            return denied;
        }
        if (!MqTopics.isKnown(topic)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorView("unknown topic: " + topic));
        }
        boolean deleted = service.clear(topic);
        return ResponseEntity.ok(new ReplayView(topic, null, deleted ? "CLEARED" : "EMPTY"));
    }

    /**
     * 端点守卫：null = 放行。
     * <ul>
     *   <li>启用但 {@code admin-token} 未配置（空）：503 锁死——「开了端点忘配令牌」必须是显式失败；</li>
     *   <li>请求头令牌不匹配：403。</li>
     * </ul>
     */
    private ResponseEntity<?> guard(String provided) {
        String configured = properties.getAdminToken();
        if (configured == null || configured.isBlank()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ErrorView("dlq-admin endpoint requires payment.mq.dlq-admin.admin-token"));
        }
        if (provided == null || !provided.equals(configured)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorView("admin token mismatch"));
        }
        return null;
    }

    public record TopicSize(String topic, long size) {
    }

    public record DlqOverview(List<TopicSize> topics) {
    }

    public record DlqTopicView(String topic, long size, List<MqDlqAdminService.DlqEntry> entries) {
    }

    public record ReplayView(String topic, String entryId, String result) {
    }

    public record ErrorView(String error) {
    }
}

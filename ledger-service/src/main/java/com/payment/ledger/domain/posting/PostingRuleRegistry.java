package com.payment.ledger.domain.posting;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.dto.rpc.AccountCode;
import com.payment.common.dto.rpc.AccountingEventType;
import jakarta.annotation.PostConstruct;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 记账规则注册表（spec 031 §7.7）：启动期自检——每个 eventType 恰一条规则，
 * 缺规则即启动失败（配错不静默，fail fast）。
 *
 * <p>注：definitionCode 的「可解析」自检依赖账户定义表（seed），在
 * {@code AccountDefinitionValidator} 启动期校验（缺行即失败），二者共同构成 §7.7 门禁。</p>
 */
public class PostingRuleRegistry {

    private final Map<AccountingEventType, PostingRule> rules = new EnumMap<>(AccountingEventType.class);
    private final List<PostingRule> all;

    public PostingRuleRegistry(List<PostingRule> all) {
        this.all = all;
    }

    @PostConstruct
    void selfCheck() {
        for (PostingRule rule : all) {
            PostingRule prev = rules.put(rule.eventType(), rule);
            if (prev != null) {
                throw new IllegalStateException(
                        "duplicate PostingRule for " + rule.eventType());
            }
        }
        for (AccountingEventType type : AccountingEventType.values()) {
            if (!rules.containsKey(type)) {
                throw new IllegalStateException("missing PostingRule for " + type
                        + " (§7.7: every eventType MUST have exactly one rule)");
            }
        }
    }

    public PostingRule require(AccountingEventType eventType) {
        PostingRule rule = rules.get(eventType);
        if (rule == null) {
            throw BizException.of(ErrorCodes.EVENT_TYPE_UNSUPPORTED, "no rule for " + eventType);
        }
        return rule;
    }

    /** 全部规则声明的科目码（供启动期 definitionCode 可解析自检）。 */
    public List<String> declaredDefinitionCodes() {
        return java.util.Arrays.stream(AccountCode.values()).map(AccountCode::name).toList();
    }
}

package com.payment.ledger.application;

import com.payment.ledger.domain.posting.PostingRule;
import com.payment.ledger.domain.posting.PostingRuleRegistry;
import com.payment.ledger.domain.posting.rules.AdjustmentRule;
import com.payment.ledger.domain.posting.rules.ChannelFeeRule;
import com.payment.ledger.domain.posting.rules.ChannelSettlementRule;
import com.payment.ledger.domain.posting.rules.MerchantSettlementRule;
import com.payment.ledger.domain.posting.rules.PaymentCaptureRule;
import com.payment.ledger.domain.posting.rules.RefundRule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 记账规则装配（spec 031 §7.7）：PostingRule / Registry 是纯领域逻辑（ADR-0029 要求
 * domain 层框架无关，不得出现 Spring 注解），Bean 化统一收口在本配置类。
 * 注册表启动期自检（每 eventType 恰一条规则）由 {@link PostingRuleRegistry} 的
 * {@code @PostConstruct} 触发——漏配即启动失败，不静默。
 */
@Configuration
public class LedgerPostingConfiguration {

    @Bean
    public PostingRule paymentCaptureRule() {
        return new PaymentCaptureRule();
    }

    @Bean
    public PostingRule refundRule() {
        return new RefundRule();
    }

    @Bean
    public PostingRule channelFeeRule() {
        return new ChannelFeeRule();
    }

    @Bean
    public PostingRule channelSettlementRule() {
        return new ChannelSettlementRule();
    }

    @Bean
    public PostingRule merchantSettlementRule() {
        return new MerchantSettlementRule();
    }

    @Bean
    public PostingRule adjustmentRule() {
        return new AdjustmentRule();
    }

    @Bean
    public PostingRuleRegistry postingRuleRegistry(List<PostingRule> rules) {
        return new PostingRuleRegistry(rules);
    }
}

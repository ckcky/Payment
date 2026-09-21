package com.payment.ledger.application;

import com.payment.common.dto.rpc.AccountCode;
import com.payment.ledger.domain.AccountDefinition;
import com.payment.ledger.domain.AccountInstance;
import com.payment.ledger.domain.AccountRepository;
import com.payment.ledger.domain.posting.PostingRuleRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 启动期 seed 完整性自检（spec 031 §5.4 / §7.7）：配错不静默、fail fast。
 *
 * <p>校验三件事：</p>
 * <ol>
 *   <li>规则声明的全部 definitionCode 在 {@code account_definitions} 有 seed，
 *       且 seed 的 owner 维度 / status 与契约枚举 {@link AccountCode} 一致；</li>
 *   <li>平台级科目与 LEGACY 哨兵的实例行已预置（缺行 = 启动失败）；</li>
 *   <li>渠道 seed 由 {@code AccountResolver} 在解析失败时报
 *       {@code LEDGER_CHANNEL_UNKNOWN}（渠道集合可增，不在启动期穷举）。</li>
 * </ol>
 */
@Component
public class AccountDefinitionValidator {

    private static final String CNY = "CNY";

    private final AccountRepository accountRepository;
    private final PostingRuleRegistry ruleRegistry;

    public AccountDefinitionValidator(AccountRepository accountRepository,
                                      PostingRuleRegistry ruleRegistry) {
        this.accountRepository = accountRepository;
        this.ruleRegistry = ruleRegistry;
    }

    @PostConstruct
    void selfCheck() {
        List<String> problems = new ArrayList<>();
        for (String code : ruleRegistry.declaredDefinitionCodes()) {
            AccountCode contract;
            try {
                contract = AccountCode.valueOf(code);
            } catch (IllegalArgumentException e) {
                problems.add("rule declares definition code absent from contract enum: " + code);
                continue;
            }
            AccountDefinition definition = accountRepository.findDefinition(code).orElse(null);
            if (definition == null) {
                problems.add("account_definitions seed missing: " + code);
                continue;
            }
            if (definition.getOwnerDimension() != contract.ownerDimension()) {
                problems.add(code + ": seed ownerDimension " + definition.getOwnerDimension()
                        + " != contract " + contract.ownerDimension());
            }
            if (definition.getStatus() != contract.status()) {
                problems.add(code + ": seed status " + definition.getStatus()
                        + " != contract " + contract.status());
            }
            if (contract.ownerDimension() == AccountCode.OwnerDimension.PLATFORM
                    && accountRepository.findInstance(code, contract.ownerDimension(),
                    AccountInstance.OWNER_PLATFORM, CNY).isEmpty()) {
                problems.add("platform singleton instance not seeded: " + code + "/" + CNY);
            }
        }
        // LEGACY 哨兵（spec §5.3 / 偏差登记）：商户维度科目的历史余额承接行
        for (String merchantScoped : List.of(AccountCode.MERCHANT_PAYABLE.name(),
                AccountCode.SETTLEMENT_PAYABLE.name())) {
            if (accountRepository.findInstance(merchantScoped, AccountCode.OwnerDimension.MERCHANT,
                    AccountInstance.OWNER_LEGACY, CNY).isEmpty()) {
                problems.add("LEGACY sentinel instance not seeded: " + merchantScoped);
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("ledger account seed integrity check failed:\n  - "
                    + String.join("\n  - ", problems));
        }
    }
}

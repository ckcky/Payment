package com.payment.ledger.application;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.dto.rpc.AccountCode;
import com.payment.ledger.domain.AccountDefinition;
import com.payment.ledger.domain.AccountInstance;
import com.payment.ledger.domain.AccountRepository;
import com.payment.ledger.domain.posting.PostingLine;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 账户解析器（spec 031 原则 7 / §5.4）：把抽象账户 {@code (definitionCode, ownerKey, currency)}
 * 解析为可记账的 Account Instance。
 *
 * <p>解析与开户策略（§5.4）：</p>
 * <ul>
 *   <li>PLATFORM 维度 → 单例实例（seed 预置；缺行由启动期自检失败兜住）；</li>
 *   <li>MERCHANT 维度 → ownerKey 缺省时落 LEGACY 哨兵实例（历史应付总口径，spec §5.3
 *       偏差登记：哨兵行 owner_type=MERCHANT 而非 §5.2 表格里的 PLATFORM，语义等价、
 *       查询侧合并更直）；带 ownerKey 缺户时<b>自动开立</b>（幂等 insert-if-absent）；</li>
 *   <li>CHANNEL 维度 → 实例必须已在 seed 注册（渠道注册表即 seed 行本身），缺户
 *       <b>fail fast 拒绝</b>（{@code LEDGER_CHANNEL_UNKNOWN}，配错不静默走默认）；</li>
 *   <li>LEGACY 科目被<b>新事实</b>引用 → {@code LEDGER_ACCOUNT_LEGACY} 拒绝；
 *       红冲行（fixedAccountId）是对既有事实的更正、不是新事实，允许触达 LEGACY 实例。</li>
 * </ul>
 */
@Service
public class AccountResolver {

    private final AccountRepository accountRepository;

    public AccountResolver(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    /** 解析记账行的目标账户实例 id。 */
    public long resolve(PostingLine line, String currency) {
        if (line.fixedAccountId() != null) {
            return accountRepository.findInstanceById(line.fixedAccountId())
                    .map(AccountInstance::getId)
                    .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND,
                            "account instance not found for reversal line: " + line.fixedAccountId()));
        }
        AccountDefinition definition = requireDefinition(line.definitionCode());
        if (definition.isLegacy()) {
            throw BizException.of(ErrorCodes.LEDGER_ACCOUNT_LEGACY,
                    line.definitionCode() + " is LEGACY: forbidden for new events");
        }
        return switch (definition.getOwnerDimension()) {
            case PLATFORM -> requireInstance(definition, AccountInstance.OWNER_PLATFORM, currency)
                    .orElseThrow(() -> new IllegalStateException(
                            "platform singleton instance not seeded: " + definition.getCode()))
                    .getId();
            case CHANNEL -> {
                if (line.ownerKey() == null || line.ownerKey().isBlank()) {
                    throw BizException.of(ErrorCodes.LEDGER_CHANNEL_UNKNOWN,
                            "channel ownerKey missing for " + definition.getCode());
                }
                yield requireInstance(definition, line.ownerKey(), currency)
                        .orElseThrow(() -> BizException.of(ErrorCodes.LEDGER_CHANNEL_UNKNOWN,
                                "channel instance not seeded: "
                                        + definition.getCode() + ":" + line.ownerKey()))
                        .getId();
            }
            case MERCHANT -> {
                String ownerId = line.ownerKey() == null || line.ownerKey().isBlank()
                        ? AccountInstance.OWNER_LEGACY
                        : line.ownerKey();
                if (AccountInstance.OWNER_LEGACY.equals(ownerId)) {
                    yield requireInstance(definition, ownerId, currency)
                            .orElseThrow(() -> new IllegalStateException(
                                    "LEGACY sentinel instance missing for " + definition.getCode()))
                            .getId();
                }
                yield accountRepository.openInstance(definition.getCode(),
                        AccountCode.OwnerDimension.MERCHANT, ownerId, currency).getId();
            }
        };
    }

    private AccountDefinition requireDefinition(String code) {
        return accountRepository.findDefinition(code)
                .orElseThrow(() -> new IllegalStateException(
                        "account definition not seeded: " + code + " (seed 完整性自检应已拦截)"));
    }

    private Optional<AccountInstance> requireInstance(AccountDefinition definition, String ownerId,
                                                      String currency) {
        return accountRepository.findInstance(definition.getCode(), definition.getOwnerDimension(),
                ownerId, currency);
    }
}

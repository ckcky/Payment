package com.payment.ledger.domain.posting.rules;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.dto.rpc.AccountingEventType;
import com.payment.ledger.domain.AccountingEvent;
import com.payment.ledger.domain.LedgerEntry;
import com.payment.ledger.domain.Posting;
import com.payment.ledger.domain.posting.OriginalPostingLookup;
import com.payment.ledger.domain.posting.PostingLine;
import com.payment.ledger.domain.posting.PostingRule;

import java.util.ArrayList;
import java.util.List;

/**
 * ADJUSTMENT 规则（spec 017 五类调账 / 挂账在 031 的事件化，§7.6）。
 *
 * <p>上游只声明「哪一类、多少、从哪个科目到哪个科目、冲销哪笔原交易」——
 * 借贷方向由本规则推导（原则 3，方向决策权在账务域）：</p>
 * <ul>
 *   <li><b>转账语义</b>（SUSPEND / SUPPLEMENT / TRANSFER）：{@code from A → to B 金额 X}
 *       展开为 {@code Dr B X / Cr A X}。017 五类模板经此推导与现行分录逐行一致
 *       （实现期对 §7.6 推导表的修正见 Implementation Report 冲突 1：纯按正常余额推导
 *       在「资产↔负债」组合下不平衡，「Dr to / Cr from」恒平且语义等价）。</li>
 *   <li><b>红冲</b>（REVERSE）：账本读原交易、分录逐行取反（同账户实例、方向取反），
 *       append-only，原分录不动。</li>
 *   <li><b>红蓝字</b>（CORRECT）：红冲 + 新正确金额的转账语义，落在同一笔 ADJUSTMENT 交易。</li>
 *   <li><b>WRITE_OFF</b>：维持否决（口径同 ADR-0065：默认禁用）。</li>
 * </ul>
 */
public class AdjustmentRule implements PostingRule {

    @Override
    public AccountingEventType eventType() {
        return AccountingEventType.ADJUSTMENT;
    }

    @Override
    public List<PostingLine> expand(AccountingEvent event, OriginalPostingLookup original) {
        String kind = event.requireAdjustmentKind();
        long amount = event.requireAmount();
        return switch (kind) {
            case "SUSPEND", "SUPPLEMENT", "TRANSFER" -> transfer(event, amount);
            case "REVERSE" -> reverse(event, original);
            case "CORRECT" -> {
                List<PostingLine> lines = new ArrayList<>(reverse(event, original));
                lines.addAll(transfer(event, amount));
                yield lines;
            }
            case "WRITE_OFF" -> throw BizException.of(ErrorCodes.INVALID_ARGUMENT,
                    "WRITE_OFF disabled (ADR-0065 口径)");
            default -> throw BizException.of(ErrorCodes.INVALID_ARGUMENT, "unsupported kind: " + kind);
        };
    }

    /** 转账语义：Dr to / Cr from（恒平衡；两科目相同亦按两行对冲展开）。 */
    private List<PostingLine> transfer(AccountingEvent event, long amount) {
        String from = event.requireFromAccountCode();
        String to = event.requireToAccountCode();
        if (from.equals(to)) {
            throw BizException.of(ErrorCodes.EVENT_FIELD_MISSING, "ADJUSTMENT from/to must differ");
        }
        return List.of(
                PostingLine.debit(to, ownerKeyOf(event, to), amount),
                PostingLine.credit(from, ownerKeyOf(event, from), amount));
    }

    /** 红冲：原交易分录逐行取反，锚定原账户实例（合法触达 LEGACY 科目）。 */
    private List<PostingLine> reverse(AccountingEvent event, OriginalPostingLookup original) {
        AccountingEventType target = event.reversesEventType();
        String targetSource = event.reversesSourceId();
        if (target == null || targetSource == null || targetSource.isBlank()) {
            throw BizException.of(ErrorCodes.EVENT_FIELD_MISSING,
                    kind(event) + " requires reversesEventType / reversesSourceId");
        }
        Posting posting = original.findByEvent(target, targetSource)
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND,
                        "posting to reverse not found: " + target + ":" + targetSource));
        List<PostingLine> lines = new ArrayList<>();
        for (LedgerEntry entry : posting.getEntries()) {
            LedgerEntry.Direction flipped = entry.getDirection() == LedgerEntry.Direction.DEBIT
                    ? LedgerEntry.Direction.CREDIT
                    : LedgerEntry.Direction.DEBIT;
            lines.add(PostingLine.ofInstance(entry.getAccountId(), flipped, entry.getAmountMinor()));
        }
        return lines;
    }

    private String kind(AccountingEvent event) {
        return event.adjustmentKind();
    }

    /**
     * 转账行的 owner 解析键：商户维度科目取 merchantId（缺省由 AccountResolver 落 LEGACY
     * 哨兵——017 调账面向的是应付总口径）；其余维度（渠道/平台）此处给 null，由 resolver
     * 按科目 owner 维度落 PLATFORM / 拒绝。
     */
    private String ownerKeyOf(AccountingEvent event, String accountCode) {
        return "MERCHANT_PAYABLE".equals(accountCode) || "SETTLEMENT_PAYABLE".equals(accountCode)
                ? event.merchantId()
                : null;
    }
}

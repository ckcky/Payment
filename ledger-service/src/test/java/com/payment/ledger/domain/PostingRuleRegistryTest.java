package com.payment.ledger.domain;

import static com.payment.ledger.LedgerTestSupport.wiring;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.dto.rpc.AccountCode;
import com.payment.common.dto.rpc.AccountingEventType;
import com.payment.ledger.LedgerTestSupport.Wiring;
import com.payment.ledger.domain.posting.OriginalPostingLookup;
import com.payment.ledger.domain.posting.PostingLine;
import com.payment.ledger.domain.posting.PostingRule;
import com.payment.ledger.domain.posting.PostingRuleRegistry;
import com.payment.ledger.domain.posting.rules.PaymentCaptureRule;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PostingRuleRegistry（spec 031 §7.7）：每个 eventType 恰一条规则，缺/重即启动失败——
 * 配错不静默。
 */
class PostingRuleRegistryTest {

    @Test
    @DisplayName("六条规则全覆盖六个事件类型 ⇒ 自检通过、require 全部可解析")
    void fullCoverageSelfCheckPasses() {
        Wiring w = wiring();
        for (AccountingEventType type : AccountingEventType.values()) {
            assertThat(w.registry().require(type)).isNotNull();
        }
    }

    @Test
    @DisplayName("缺规则（如只有 PaymentCapture）⇒ selfCheck 抛错（启动失败）")
    void missingRuleFailsStartup() {
        PostingRuleRegistry registry = new PostingRuleRegistry(List.of(new PaymentCaptureRule()));
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> invokeSelfCheck(registry))
                .withMessageContaining("missing PostingRule");
    }

    @Test
    @DisplayName("同 eventType 双规则 ⇒ selfCheck 抛错")
    void duplicateRuleFailsStartup() {
        PostingRuleRegistry registry = new PostingRuleRegistry(List.of(
                new PaymentCaptureRule(), fakeRule(AccountingEventType.PAYMENT_CAPTURE)));
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> invokeSelfCheck(registry))
                .withMessageContaining("duplicate PostingRule");
    }

    @Test
    @DisplayName("未注册类型 require ⇒ EVENT_TYPE_UNSUPPORTED（运行期兜底）")
    void requireUnsupportedFailsFast() {
        PostingRuleRegistry bare = new PostingRuleRegistry(List.of());
        assertThatExceptionOfType(BizException.class)
                .isThrownBy(() -> bare.require(AccountingEventType.REFUND))
                .matches(ex -> ErrorCodes.EVENT_TYPE_UNSUPPORTED.equals(ex.getCode()));
    }

    @Test
    @DisplayName("declaredDefinitionCodes 与契约枚举逐一对应（科目码自检输入完整）")
    void declaredCodesMirrorContract() {
        assertThat(wiring().registry().declaredDefinitionCodes())
                .containsExactlyElementsOf(
                        java.util.Arrays.stream(AccountCode.values()).map(Enum::name).toList());
    }

    private static void invokeSelfCheck(PostingRuleRegistry registry) {
        try {
            Method m = PostingRuleRegistry.class.getDeclaredMethod("selfCheck");
            m.setAccessible(true);
            m.invoke(registry);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof IllegalStateException ise) {
                throw ise;
            }
            throw new IllegalStateException(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static PostingRule fakeRule(AccountingEventType type) {
        return new PostingRule() {
            @Override
            public AccountingEventType eventType() {
                return type;
            }

            @Override
            public List<PostingLine> expand(AccountingEvent event, OriginalPostingLookup original) {
                return List.of();
            }
        };
    }
}

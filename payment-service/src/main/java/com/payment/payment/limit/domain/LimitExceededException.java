package com.payment.payment.limit.domain;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;

/**
 * 额度超限领域异常（spec 027 / FR-019 / FR-010，ADR-0071 D5）。
 *
 * <p>承载「哪个周期超限、当前额度多少、本笔要多少、还差多少」——错误消息必须是<b>可执行</b>的：
 * 调用方看到 409 后应当知道「等额度重置」或「调低本笔金额」，而不是只能猜。这与 ADR-0049
 * 「给出合法取值清单」同一条纪律。</p>
 *
 * <p><b>三周期各自独立判定</b>（FR-010）：本异常一次只报<b>第一个</b>撞线的周期（按
 * DAY → MONTH → YEAR 顺序），因为任一超限即整笔拒绝、不会继续判后面的周期——
 * 报「全部超限周期」会暗示调用方存在部分扣减，与 FR-010 明确禁止的行为相反。</p>
 */
public class LimitExceededException extends BizException {

    private final LimitPeriod period;
    private final long limitMinor;
    private final long occupiedMinor;
    private final long requestedMinor;

    public LimitExceededException(String userId, LimitPeriod period, long limitMinor,
                                  long occupiedMinor, long requestedMinor) {
        super(ErrorCodes.LIMIT_EXCEEDED, message(userId, period, limitMinor, occupiedMinor, requestedMinor));
        this.period = period;
        this.limitMinor = limitMinor;
        this.occupiedMinor = occupiedMinor;
        this.requestedMinor = requestedMinor;
    }

    private static String message(String userId, LimitPeriod period, long limitMinor,
                                  long occupiedMinor, long requestedMinor) {
        long available = limitMinor - occupiedMinor;
        long shortfall = Math.max(0L, requestedMinor - available);
        return "user '" + userId + "' exceeded " + period + " payment limit: limit=" + limitMinor
                + ", occupied(used+pending)=" + occupiedMinor
                + ", available=" + available
                + ", requested=" + requestedMinor
                + ", shortfall=" + shortfall
                + "; the payment was NOT created. Retry after the period resets, raise the limit,"
                + " or reduce the amount.";
    }

    public LimitPeriod getPeriod() {
        return period;
    }

    public long getLimitMinor() {
        return limitMinor;
    }

    public long getOccupiedMinor() {
        return occupiedMinor;
    }

    public long getRequestedMinor() {
        return requestedMinor;
    }
}

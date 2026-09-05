package com.payment.refund.infra.persistence.refund;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.payment.refund.domain.RefundPostProcessAttempt;
import com.payment.refund.domain.RefundPostProcessAttemptRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 退款后处理尝试仓储 MyBatis 实现（ADR-0017）：追加式记录，终态不可变。
 */
@Repository
public class MybatisRefundPostProcessAttemptRepository implements RefundPostProcessAttemptRepository {

    private final RefundPostProcessAttemptMapper mapper;

    public MybatisRefundPostProcessAttemptRepository(RefundPostProcessAttemptMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void save(RefundPostProcessAttempt attempt) {
        RefundPostProcessAttemptEntity entity = new RefundPostProcessAttemptEntity();
        entity.setRefundNo(attempt.getRefundNo());
        entity.setTarget(attempt.getTarget());
        entity.setStatus(attempt.getStatus());
        entity.setDetail(attempt.getDetail());
        entity.setAttemptCount(attempt.getAttemptCount());
        mapper.insert(entity);
        attempt.setId(entity.getId());
        attempt.setVersion(entity.getVersion());
    }

    @Override
    public List<RefundPostProcessAttempt> findByRefundNo(String refundNo) {
        return mapper.selectList(
                        Wrappers.<RefundPostProcessAttemptEntity>lambdaQuery()
                                .eq(RefundPostProcessAttemptEntity::getRefundNo, refundNo))
                .stream()
                .map(e -> new RefundPostProcessAttempt(e.getRefundNo(), e.getTarget(), e.getStatus(),
                        e.getDetail(), e.getAttemptCount() == null ? 0 : e.getAttemptCount()))
                .toList();
    }
}

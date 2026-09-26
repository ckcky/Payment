package com.payment.channelgateway.infra.persistence;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.channelgateway.domain.ChannelOrder;
import com.payment.channelgateway.domain.ChannelOrderErrorType;
import com.payment.channelgateway.domain.ChannelOrderRepository;
import com.payment.channelgateway.domain.ChannelOrderStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 支付尝试仓储 MyBatis 实现：每次渠道交互独立落表（渠道引用唯一兜底），领域对象与 PO 双向映射。
 * 更新走乐观锁，冲突抛 {@link ErrorCodes#CONFLICT}。
 */
@Repository
public class MybatisChannelOrderRepository implements ChannelOrderRepository {

    private final ChannelOrderMapper attemptMapper;

    public MybatisChannelOrderRepository(ChannelOrderMapper attemptMapper) {
        this.attemptMapper = attemptMapper;
    }

    @Override
    public Optional<ChannelOrder> findById(Long id) {
        ChannelOrderEntity entity = attemptMapper.selectById(id);
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    @Override
    public Optional<ChannelOrder> findByChannelNo(String channelNo) {
        if (channelNo == null || channelNo.isBlank()) {
            return Optional.empty();
        }
        ChannelOrderEntity entity = attemptMapper.selectOne(
                Wrappers.<ChannelOrderEntity>lambdaQuery()
                        .eq(ChannelOrderEntity::getChannelNo, channelNo));
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    @Override
    public List<ChannelOrder> findByPaymentNo(String paymentNo) {
        return attemptMapper.selectList(
                        Wrappers.<ChannelOrderEntity>lambdaQuery()
                                .eq(ChannelOrderEntity::getPaymentNo, paymentNo))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public ChannelOrder save(ChannelOrder attempt) {
        if (attempt.getId() == null) {
            ChannelOrderEntity entity = toEntity(attempt);
            attemptMapper.insert(entity);
            attempt.setId(entity.getId());
            attempt.setVersion(entity.getVersion());
            return attempt;
        }
        ChannelOrderEntity entity = toEntity(attempt);
        if (attemptMapper.updateById(entity) == 0) {
            throw BizException.of(ErrorCodes.CONFLICT, "payment attempt concurrent update: " + attempt.getId());
        }
        attempt.setVersion(attempt.getVersion() + 1);
        return attempt;
    }

    private ChannelOrder toDomain(ChannelOrderEntity entity) {
        // spec 037 / FR-001：回读路径走显式 channelNo 重载——原样还原持久化的网关单号，不重新铸造。
        return ChannelOrder.rehydrate(entity.getId(), entity.getPaymentNo(), entity.getChannelNo(),
                entity.getChannelCode(),
                entity.getRetryCount(), entity.getRequestedAt(), entity.getRespondedAt(),
                entity.getChannelReference(), ChannelOrderStatus.valueOf(entity.getStatus()),
                entity.getFailureReason(),
                entity.getErrorType() == null ? null : ChannelOrderErrorType.valueOf(entity.getErrorType()),
                entity.getVersion(), entity.getAttemptType(),
                entity.getAmountMinor() == null ? 0L : entity.getAmountMinor(), entity.getCurrencyCode(),
                // spec 030 / FR-302：extra_json → Map（fail-safe：坏数据 ⇒ null ⇒ 模态判 MOCK）
                AttemptExtraCodec.decode(entity.getExtraJson()));
    }

    private ChannelOrderEntity toEntity(ChannelOrder attempt) {
        ChannelOrderEntity entity = new ChannelOrderEntity();
        entity.setId(attempt.getId());
        entity.setPaymentNo(attempt.getPaymentNo());
        // spec 037 / FR-002：channel_no 列 NOT NULL，INSERT / UPDATE 都必须带上（漏了插入即失败）
        entity.setChannelNo(attempt.getChannelNo());
        entity.setChannelCode(attempt.getChannelCode());
        entity.setAttemptType(attempt.getAttemptType());
        entity.setAmountMinor(attempt.getAmountMinor());
        entity.setCurrencyCode(attempt.getCurrencyCode());
        entity.setRequestedAt(attempt.getRequestedAt());
        entity.setRespondedAt(attempt.getRespondedAt());
        entity.setChannelReference(attempt.getChannelReference());
        entity.setStatus(attempt.getStatus().name());
        entity.setFailureReason(attempt.getFailureReason());
        entity.setRetryCount(attempt.getRetryCount());
        entity.setErrorType(attempt.getErrorType() == null ? null : attempt.getErrorType().name());
        // spec 030 / FR-302：Map → extra_json（null / 空 ⇒ 落 NULL 列值）
        entity.setExtraJson(AttemptExtraCodec.encode(attempt.getExtra()));
        entity.setVersion(attempt.getVersion());
        return entity;
    }
}

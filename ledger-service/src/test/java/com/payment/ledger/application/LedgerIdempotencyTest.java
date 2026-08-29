package com.payment.ledger.application;

import com.payment.common.core.observability.NoopBusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.ledger.LedgerTestSupport;
import com.payment.ledger.domain.LedgerEntry;
import com.payment.ledger.domain.LedgerRepository;
import com.payment.ledger.domain.LedgerSourceType;
import com.payment.ledger.domain.Posting;
import com.payment.ledger.infra.InMemoryLedgerRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 记账幂等测试（spec US1~US3 / FR-003 / T020）：三种来源的重复记账都只产生一组分录。
 *
 * <p>并发场景由数据库唯一约束 {@code uk_postings_idempotency_key} 兜底：本测试用
 * 「首次 save 抛 DuplicateKeyException」的仓储桩**确定性**地模拟撞键，
 * 断言服务回查返回首次结果而不是抛错或重复入账。</p>
 */
class LedgerIdempotencyTest {

    private final InMemoryLedgerRepository repository = new InMemoryLedgerRepository();
    private final LedgerPostingService service = new LedgerPostingService(repository,
            new NoopBusinessMetrics(), new StructuredAuditLogger());

    @Test
    void refundPostingIsIdempotent() {
        service.post("REFUND:r1", LedgerSourceType.REFUND, "r1", "CNY",
                LedgerTestSupport.refund(LedgerSourceType.REFUND, "r1", 3_000));
        int afterFirst = repository.findAllEntries().size();

        Posting again = service.post("REFUND:r1", LedgerSourceType.REFUND, "r1", "CNY",
                LedgerTestSupport.refund(LedgerSourceType.REFUND, "r1", 3_000));

        assertThat(again.getId()).isNotNull();
        assertThat(repository.findAllEntries()).hasSize(afterFirst);
    }

    @Test
    void settlementPostingIsIdempotent() {
        service.post("SETTLEMENT:s1", LedgerSourceType.SETTLEMENT, "s1", "CNY",
                LedgerTestSupport.settlement(LedgerSourceType.SETTLEMENT, "s1", 4_800));
        int afterFirst = repository.findAllEntries().size();

        service.post("SETTLEMENT:s1", LedgerSourceType.SETTLEMENT, "s1", "CNY",
                LedgerTestSupport.settlement(LedgerSourceType.SETTLEMENT, "s1", 4_800));

        assertThat(repository.findAllEntries()).hasSize(afterFirst);
    }

    @Test
    void duplicateKeyOnInsertFallsBackToFirstPosting() {
        InMemoryLedgerRepository inner = new InMemoryLedgerRepository();
        LedgerRepository collisionOnce = new CollisionOnceRepository(inner);
        LedgerPostingService serviceUnderCollision = new LedgerPostingService(collisionOnce,
                new NoopBusinessMetrics(), new StructuredAuditLogger());
        List<LedgerEntry> entries =
                LedgerTestSupport.paymentCapture(LedgerSourceType.PAYMENT, "p9", 1_000, 0);

        // 首次插入撞键（并发窗口的确定性模拟）→ 服务 MUST 回查返回既有的那条
        Posting first = inner.save(new Posting("PAYMENT:p9", LedgerSourceType.PAYMENT, "p9",
                "CNY", entries));
        Posting resolved = serviceUnderCollision.post("PAYMENT:p9", LedgerSourceType.PAYMENT, "p9",
                "CNY", entries);

        assertThat(resolved.getId()).isEqualTo(first.getId());
        assertThat(repository.findAllEntries()).isEmpty();
        assertThat(inner.findAllEntries()).hasSize(first.getEntries().size());
    }

    /** 首次 save 抛 {@link DuplicateKeyException}，之后透传：模拟并发撞唯一约束。 */
    private static final class CollisionOnceRepository implements LedgerRepository {

        private final LedgerRepository delegate;
        private boolean collided;

        private CollisionOnceRepository(LedgerRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<Posting> findById(Long id) {
            return delegate.findById(id);
        }

        @Override
        public Optional<Posting> findByIdempotencyKey(String idempotencyKey) {
            return delegate.findByIdempotencyKey(idempotencyKey);
        }

        @Override
        public List<Posting> findBySource(LedgerSourceType sourceType, String sourceId) {
            return delegate.findBySource(sourceType, sourceId);
        }

        @Override
        public List<LedgerEntry> findAllEntries() {
            return delegate.findAllEntries();
        }

        @Override
        public List<LedgerEntry> findEntriesBySource(LedgerSourceType sourceType, String sourceId) {
            return delegate.findEntriesBySource(sourceType, sourceId);
        }

        @Override
        public Posting save(Posting posting) {
            if (!collided) {
                collided = true;
                throw new DuplicateKeyException("uk_postings_idempotency_key");
            }
            return delegate.save(posting);
        }
    }
}

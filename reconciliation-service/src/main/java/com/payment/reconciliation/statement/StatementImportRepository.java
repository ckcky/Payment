package com.payment.reconciliation.statement;

import java.util.List;
import java.util.Optional;

/**
 * 账单导入仓储边界（领域接口，不依赖持久化实现）。
 *
 * <p>导入层幂等（spec 032 §8.3 第一层）由 {@code uk_import_identity(channel_code, period,
 * content_fingerprint)} 承接：同指纹重放回查即得首次导入，零新增差异。</p>
 */
public interface StatementImportRepository {

    StatementImport save(StatementImport imprt);

    /** 保存标准化行（uk(import_id, line_no) 保证同批次行号唯一；重复导入重放不重复写行）。 */
    void saveLines(Long importId, String channelCode, List<StatementLine> lines);

    Optional<StatementImport> findById(Long id);

    Optional<StatementImport> findByNo(String importNo);

    /** 导入层幂等键回查：(channelCode, period, fingerprint)。 */
    Optional<StatementImport> findByIdentity(String channelCode, String period, String contentFingerprint);

    /** 执行核对取数：该渠道该周期最新 NORMALIZED 导入（spec §10.1 run 缺省口径）。 */
    Optional<StatementImport> findLatestNormalized(String period, String channelCode);

    /** 导入台账查询（spec §10.1 GET statement-imports）：按 id 倒序。 */
    List<StatementImport> list(String period, String channelCode);

    List<StatementLine> findLines(Long importId);
}

package com.payment.common.mybatis;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.payment.common.core.trace.TraceContext;
import org.apache.ibatis.reflection.MetaObject;

import java.time.LocalDateTime;

/**
 * 审计字段自动填充：创建/更新时间、创建/更新人、乐观锁版本号初值。
 *
 * <p>创建/更新人当前以关联 ID 兜底（本 MVP 无认证主体）；接入认证后应替换为真实操作者。</p>
 */
public class AuditMetaObjectHandler implements MetaObjectHandler {

    private static final String CREATED_AT = "createdAt";
    private static final String UPDATED_AT = "updatedAt";
    private static final String CREATED_BY = "createdBy";
    private static final String UPDATED_BY = "updatedBy";
    private static final String VERSION = "version";

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        strictInsertFill(metaObject, CREATED_AT, LocalDateTime.class, now);
        strictInsertFill(metaObject, UPDATED_AT, LocalDateTime.class, now);
        strictInsertFill(metaObject, VERSION, Integer.class, 1);
        strictInsertFill(metaObject, CREATED_BY, String.class, currentActor());
        strictInsertFill(metaObject, UPDATED_BY, String.class, currentActor());
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        strictUpdateFill(metaObject, UPDATED_AT, LocalDateTime.class, LocalDateTime.now());
        strictUpdateFill(metaObject, UPDATED_BY, String.class, currentActor());
    }

    private String currentActor() {
        String traceId = TraceContext.getTraceId();
        return traceId != null ? traceId : "system";
    }
}

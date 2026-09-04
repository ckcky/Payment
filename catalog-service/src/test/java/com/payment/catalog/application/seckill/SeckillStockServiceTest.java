package com.payment.catalog.application.seckill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.payment.common.core.observability.BusinessMetrics;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 秒杀预扣服务单元测试（014）：验证 Lua 三态（allow/bypass/deny）与 Redis 不可用时的 fail-closed。
 */
@ExtendWith(MockitoExtension.class)
class SeckillStockServiceTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private RedisScript<Long> script;
    @Mock
    private BusinessMetrics metrics;

    private SeckillProperties props;
    private SeckillStockService service;

    @BeforeEach
    void setUp() {
        props = new SeckillProperties();
        props.setEnabled(true);
        service = new SeckillStockService(redis, script, props, metrics);
    }

    private void stubExecute(Long result) {
        doReturn(result).when(redis).execute(any(RedisScript.class), any(), any());
    }

    @Test
    void bypassWhenSkuNotSeeded() {
        stubExecute(-2L);

        SeckillResult r = service.tryPreDeduct(103L, 2);

        assertThat(r.allowed()).isTrue();
        assertThat(r.bypassed()).isTrue();
    }

    @Test
    void denyWhenInsufficient() {
        stubExecute(-1L);

        SeckillResult r = service.tryPreDeduct(103L, 2);

        assertThat(r.allowed()).isFalse();
        verify(metrics, times(1)).counter(anyString(), anyDouble(), any(), any());
    }

    @Test
    void allowedWhenSufficient() {
        stubExecute(8L);

        SeckillResult r = service.tryPreDeduct(103L, 2);

        assertThat(r.allowed()).isTrue();
        assertThat(r.bypassed()).isFalse();
        assertThat(r.remaining()).isEqualTo(8L);
    }

    @Test
    void denyWhenRedisDownProtectsStock() {
        doThrow(new RuntimeException("down")).when(redis).execute(any(RedisScript.class), any(), any());

        SeckillResult r = service.tryPreDeduct(103L, 2);

        assertThat(r.allowed()).isFalse();
        verify(metrics, times(1)).counter(anyString(), anyDouble(), any(), any());
    }

    @Test
    void rollbackIncrementsOnlyWhenQuotaKeyExists() {
        when(redis.hasKey("seckill:sku:103")).thenReturn(true);

        service.rollback(103L, 2);

        verify(redis, times(1)).opsForValue();
    }

    @Test
    void rollbackMustNotFabricateQuotaKeyForNormalSku() {
        // 普通品从未播种（键不存在）：回补绝不能 INCR 凭空造键，否则后续下单被误判秒杀售罄
        when(redis.hasKey("seckill:sku:101")).thenReturn(false);

        service.rollback(101L, 1);

        verify(redis, times(0)).opsForValue();
    }
}

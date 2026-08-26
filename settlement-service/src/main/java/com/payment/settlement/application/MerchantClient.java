package com.payment.settlement.application;

/**
 * settlement-service → merchant-service 的出站同步 RPC 端口：查询商户状态与结算资格。
 * 生产用 Feign 实现，测试用 fake。
 */
public interface MerchantClient {

    MerchantView getMerchant(Long merchantId);
}

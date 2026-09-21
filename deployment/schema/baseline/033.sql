
/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

CREATE DATABASE /*!32312 IF NOT EXISTS*/ `catalog` /*!40100 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci */ /*!80016 DEFAULT ENCRYPTION='N' */;

USE `catalog`;
DROP TABLE IF EXISTS `products`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `products` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `product_code` varchar(64) NOT NULL,
  `name` varchar(128) NOT NULL,
  `type` varchar(32) NOT NULL,
  `status` varchar(32) NOT NULL,
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  `created_by` varchar(64) DEFAULT NULL,
  `updated_by` varchar(64) DEFAULT NULL,
  `version` int NOT NULL DEFAULT '1',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_products_product_code` (`product_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `skus`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `skus` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `sku_code` varchar(64) NOT NULL,
  `product_id` bigint NOT NULL,
  `name` varchar(128) NOT NULL,
  `price_minor` bigint NOT NULL,
  `currency_code` varchar(8) NOT NULL,
  `delivery_definition` varchar(255) NOT NULL,
  `status` varchar(32) NOT NULL,
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  `created_by` varchar(64) DEFAULT NULL,
  `updated_by` varchar(64) DEFAULT NULL,
  `version` int NOT NULL DEFAULT '1',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_skus_sku_code` (`sku_code`),
  KEY `idx_skus_product_id` (`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `stock`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `stock` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `sku_id` bigint NOT NULL,
  `total` bigint NOT NULL,
  `available` bigint NOT NULL,
  `reserved` bigint NOT NULL,
  `sold` bigint NOT NULL,
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  `created_by` varchar(64) DEFAULT NULL,
  `updated_by` varchar(64) DEFAULT NULL,
  `version` int NOT NULL DEFAULT '1',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_stock_sku_id` (`sku_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `stock_reservation`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `stock_reservation` (
  `reservation_id` varchar(64) NOT NULL,
  `sku_id` bigint NOT NULL,
  `quantity` bigint NOT NULL,
  `status` varchar(32) NOT NULL,
  `deduct_id` varchar(64) DEFAULT NULL,
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  PRIMARY KEY (`reservation_id`),
  KEY `idx_stock_reservation_sku_id` (`sku_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

CREATE DATABASE /*!32312 IF NOT EXISTS*/ `order` /*!40100 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci */ /*!80016 DEFAULT ENCRYPTION='N' */;

USE `order`;
DROP TABLE IF EXISTS `order_event_log`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `order_event_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_no` varchar(32) NOT NULL COMMENT '所属订单（OR+雪花）',
  `event_type` varchar(64) NOT NULL COMMENT '事件类型（topic 名，如 order.paid）',
  `topic` varchar(64) NOT NULL COMMENT '消息主题',
  `msg_id` varchar(64) NOT NULL COMMENT '信封 msgId（重复投递去重）',
  `trace_id` varchar(64) DEFAULT NULL COMMENT '链路 traceId（跨异步边界连续）',
  `producer` varchar(64) DEFAULT NULL COMMENT '生产方服务名',
  `payload_json` text COMMENT '事件负载 JSON',
  `occurred_at` datetime NOT NULL COMMENT '事件发生时刻（生产端 occurredAt）',
  `created_at` datetime NOT NULL COMMENT '轨迹落表时刻',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_event_log_msg_id` (`msg_id`),
  KEY `idx_order_event_log_order_occurred` (`order_no`,`occurred_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `order_items`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `order_items` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_item_no` varchar(32) NOT NULL,
  `order_no` varchar(32) NOT NULL COMMENT '所属订单（业务单号 OR+雪花，ADR-0063）',
  `sku_id` varchar(64) NOT NULL,
  `sku_code` varchar(64) NOT NULL,
  `name` varchar(128) NOT NULL,
  `quantity` int NOT NULL,
  `price_minor` bigint NOT NULL,
  `currency_code` varchar(8) NOT NULL,
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  `created_by` varchar(64) DEFAULT NULL,
  `updated_by` varchar(64) DEFAULT NULL,
  `version` int NOT NULL DEFAULT '1',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_items_order_item_no` (`order_item_no`),
  KEY `idx_order_items_order_no` (`order_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `orders`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `orders` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_no` varchar(32) NOT NULL COMMENT '业务单号 OR+雪花（ADR-0062）',
  `user_id` varchar(64) NOT NULL,
  `merchant_id` varchar(64) NOT NULL,
  `payment_no` varchar(32) DEFAULT NULL COMMENT '关联支付单（业务单号 PM+雪花，ADR-0063）',
  `status` varchar(32) NOT NULL,
  `currency_code` varchar(8) NOT NULL,
  `total_minor` bigint NOT NULL,
  `paid_minor` bigint NOT NULL DEFAULT '0',
  `refunded_minor` bigint NOT NULL DEFAULT '0',
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  `created_by` varchar(64) DEFAULT NULL,
  `updated_by` varchar(64) DEFAULT NULL,
  `version` int NOT NULL DEFAULT '1',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_orders_order_no` (`order_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `transaction_refunds`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `transaction_refunds` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `refund_no` varchar(32) NOT NULL COMMENT '交易层退款单号 TXRF+雪花（ADR-0062/0067）',
  `payment_refund_no` varchar(32) DEFAULT NULL COMMENT '支付层退款执行单号 PMRF+雪花（payment 响应回填，ADR-0067）',
  `transaction_no` varchar(32) NOT NULL COMMENT '所属交易（TX+雪花，ADR-0062）',
  `order_no` varchar(32) NOT NULL COMMENT '所属订单（OR+雪花，ADR-0063）',
  `payment_no` varchar(32) NOT NULL COMMENT '被退支付单（PM+雪花，ADR-0063）',
  `user_id` varchar(64) NOT NULL,
  `amount_minor` bigint NOT NULL,
  `currency_code` varchar(8) NOT NULL,
  `status` varchar(32) NOT NULL COMMENT 'REQUESTED/PROCESSING/SUCCEEDED/FAILED/REJECTED',
  `reason` varchar(255) NOT NULL,
  `failure_reason` varchar(255) DEFAULT NULL,
  `idempotency_key` varchar(128) NOT NULL COMMENT '幂等键=TXRF（同号重试可重入回放）',
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  `created_by` varchar(64) DEFAULT NULL,
  `updated_by` varchar(64) DEFAULT NULL,
  `version` int NOT NULL DEFAULT '1',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_transaction_refunds_refund_no` (`refund_no`),
  UNIQUE KEY `uk_transaction_refunds_idempotency_key` (`idempotency_key`),
  KEY `idx_transaction_refunds_transaction_no` (`transaction_no`),
  KEY `idx_transaction_refunds_payment_refund_no` (`payment_refund_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `transactions`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `transactions` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `transaction_no` varchar(32) NOT NULL COMMENT '业务单号 TX+雪花（ADR-0062）',
  `order_no` varchar(32) NOT NULL COMMENT '所属订单（业务单号 OR+雪花，ADR-0063）',
  `amount_minor` bigint NOT NULL,
  `currency_code` varchar(8) NOT NULL,
  `purpose` varchar(32) NOT NULL,
  `status` varchar(32) NOT NULL,
  `payment_no` varchar(32) DEFAULT NULL COMMENT '生效支付单：首张成功支付（spec 019 / ADR-0067；surplus 被退单不覆盖）',
  `refunded_minor` bigint NOT NULL DEFAULT '0' COMMENT '累计已退金额（spec 019 / ADR-0067）',
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  `created_by` varchar(64) DEFAULT NULL,
  `updated_by` varchar(64) DEFAULT NULL,
  `version` int NOT NULL DEFAULT '1',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_transactions_order_no` (`order_no`),
  UNIQUE KEY `uk_transactions_transaction_no` (`transaction_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

CREATE DATABASE /*!32312 IF NOT EXISTS*/ `payment` /*!40100 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci */ /*!80016 DEFAULT ENCRYPTION='N' */;

USE `payment`;
DROP TABLE IF EXISTS `limit_operations`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `limit_operations` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `operation_no` varchar(32) NOT NULL COMMENT '业务单号 LO+雪花（ADR-0062）',
  `biz_no` varchar(32) NOT NULL COMMENT '关联支付单 paymentNo（ADR-0063）',
  `op_type` varchar(16) NOT NULL COMMENT 'RESERVE | CONFIRM | RELEASE | EXPIRED',
  `user_id` varchar(64) NOT NULL,
  `currency_code` varchar(8) NOT NULL,
  `period` varchar(8) NOT NULL,
  `amount_minor` bigint NOT NULL COMMENT '操作金额（分）',
  `expires_at` datetime DEFAULT NULL COMMENT '仅 RESERVE：在途到期时刻（审计兜底），日常判定走 Redis',
  `created_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_limitop_biz_type` (`biz_no`,`op_type`,`period`),
  KEY `idx_limitop_user_type` (`user_id`,`op_type`),
  KEY `idx_limitop_expiry` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='额度操作流水（spec 027 / ADR-0071）；UK(biz_no,op_type,period) 是幂等的数据库级兜底（INV-4）';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `payment_attempts`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `payment_attempts` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `payment_no` varchar(32) NOT NULL COMMENT '所属支付单（业务单号 PM+雪花，ADR-0063）',
  `channel_code` varchar(32) NOT NULL,
  `attempt_type` varchar(16) NOT NULL DEFAULT 'PAYMENT' COMMENT '尝试类型 PAYMENT/REFUND（Feature 016 / FR-017）',
  `amount_minor` bigint NOT NULL COMMENT '资金口径：PAYMENT=支付金额；REFUND=所属支付单金额（spec 018 / FR-002）',
  `currency_code` varchar(8) NOT NULL,
  `channel_reference` varchar(128) DEFAULT NULL,
  `status` varchar(32) NOT NULL,
  `failure_reason` varchar(255) DEFAULT NULL,
  `retry_count` int NOT NULL DEFAULT '0',
  `error_type` varchar(16) DEFAULT NULL,
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  `created_by` varchar(64) DEFAULT NULL,
  `updated_by` varchar(64) DEFAULT NULL,
  `requested_at` datetime NOT NULL,
  `responded_at` datetime DEFAULT NULL,
  `version` int NOT NULL DEFAULT '1',
  `extra_json` text COMMENT '渠道扩展属性 JSON（spec 030；当前键 channelMode=MOCK|SANDBOX）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_attempts_channel_reference` (`channel_reference`),
  KEY `idx_attempts_payment_no` (`payment_no`),
  KEY `idx_attempts_payment_type` (`payment_no`,`attempt_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `payments`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `payments` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `payment_no` varchar(32) NOT NULL COMMENT '业务单号 PM+雪花（ADR-0062）',
  `idempotency_key` varchar(128) NOT NULL,
  `transaction_id` varchar(64) NOT NULL,
  `order_no` varchar(32) NOT NULL COMMENT '所属订单（业务单号 OR+雪花，ADR-0063）',
  `user_id` varchar(64) NOT NULL,
  `merchant_id` varchar(64) DEFAULT NULL COMMENT '商户号（spec 031 / §13，H11 前置收编）：PAYMENT_CAPTURE 事实锚；历史行 NULL',
  `amount_minor` bigint NOT NULL,
  `currency_code` varchar(8) NOT NULL,
  `attempt_seq` int NOT NULL DEFAULT '1',
  `status` varchar(32) NOT NULL,
  `current_attempt_id` bigint DEFAULT NULL,
  `failure_reason` varchar(255) DEFAULT NULL,
  `query_attempts` int NOT NULL DEFAULT '0',
  `entered_unknown_at` datetime DEFAULT NULL,
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  `created_by` varchar(64) DEFAULT NULL,
  `updated_by` varchar(64) DEFAULT NULL,
  `version` int NOT NULL DEFAULT '1',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_payments_idempotency_key` (`idempotency_key`),
  UNIQUE KEY `uk_payments_payment_no` (`payment_no`),
  KEY `idx_payments_transaction_id` (`transaction_id`),
  KEY `idx_payments_txn_seq` (`transaction_id`,`attempt_seq`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `refund_intake_locks`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `refund_intake_locks` (
  `payment_no` varchar(32) NOT NULL COMMENT '所属支付单（业务单号 PM+雪花，ADR-0063）',
  PRIMARY KEY (`payment_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `refund_items`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `refund_items` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `refund_no` varchar(32) NOT NULL COMMENT '所属退款（业务单号 RF+雪花，ADR-0062/0063）',
  `order_item_id` varchar(64) NOT NULL,
  `amount_minor` bigint NOT NULL,
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  `created_by` varchar(64) DEFAULT NULL,
  `updated_by` varchar(64) DEFAULT NULL,
  `version` int NOT NULL DEFAULT '1',
  PRIMARY KEY (`id`),
  KEY `idx_refund_items_refund_no` (`refund_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `refund_post_process_attempts`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `refund_post_process_attempts` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `refund_no` varchar(32) NOT NULL COMMENT '所属退款（业务单号 RF+雪花，ADR-0062/0063）',
  `target` varchar(32) NOT NULL,
  `status` varchar(16) NOT NULL,
  `detail` varchar(512) DEFAULT NULL,
  `attempt_count` int NOT NULL,
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  `created_by` varchar(64) DEFAULT NULL,
  `updated_by` varchar(64) DEFAULT NULL,
  `version` int NOT NULL DEFAULT '1',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_rppa_refund_target` (`refund_no`,`target`),
  KEY `idx_rppa_refund_no` (`refund_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `refunds`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `refunds` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `refund_no` varchar(32) NOT NULL COMMENT '业务单号 PMRF+雪花（ADR-0062/0067；存量 RF 保留不改写）',
  `idempotency_key` varchar(128) NOT NULL,
  `transaction_refund_no` varchar(32) DEFAULT NULL COMMENT '上层交易退款单 TXRF（spec 019 / ADR-0067；幂等键载体）',
  `transaction_no` varchar(32) DEFAULT NULL COMMENT '所属交易单 TX（spec 019 / ADR-0067；回调通知 order 时回传）',
  `order_no` varchar(32) NOT NULL COMMENT '所属订单（业务单号 OR+雪花，ADR-0063）',
  `payment_no` varchar(32) NOT NULL COMMENT '所属支付单（业务单号 PM+雪花，ADR-0063）',
  `user_id` varchar(64) NOT NULL,
  `amount_minor` bigint NOT NULL,
  `currency_code` varchar(8) NOT NULL,
  `reason` varchar(255) NOT NULL,
  `status` varchar(32) NOT NULL,
  `failure_reason` varchar(255) DEFAULT NULL,
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  `created_by` varchar(64) DEFAULT NULL,
  `updated_by` varchar(64) DEFAULT NULL,
  `version` int NOT NULL DEFAULT '1',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_refunds_idempotency_key` (`idempotency_key`),
  UNIQUE KEY `uk_refunds_refund_no` (`refund_no`),
  KEY `idx_refunds_payment_no` (`payment_no`),
  KEY `idx_refunds_order_no` (`order_no`),
  KEY `idx_refunds_transaction_refund_no` (`transaction_refund_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `user_limit_usage`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_limit_usage` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` varchar(64) NOT NULL,
  `currency_code` varchar(8) NOT NULL,
  `period` varchar(8) NOT NULL COMMENT 'DAY | MONTH | YEAR',
  `period_start` date NOT NULL COMMENT '该行所属周期起始日；跨周期现算建新行，旧行自然闲置',
  `used_minor` bigint NOT NULL DEFAULT '0' COMMENT '已确认（支付 SUCCEEDED）',
  `pending_minor` bigint NOT NULL DEFAULT '0' COMMENT '在途占用（已预占未终态）',
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  `created_by` varchar(64) DEFAULT NULL,
  `updated_by` varchar(64) DEFAULT NULL,
  `version` int NOT NULL DEFAULT '1',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ulusage_user_ccy_period` (`user_id`,`currency_code`,`period`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户周期额度占用（spec 027 / ADR-0071）；周期重置靠 period_start 现算（D10）';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `user_payment_limits`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_payment_limits` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` varchar(64) NOT NULL COMMENT '用户标识（与 payments.user_id 同口径）',
  `currency_code` varchar(8) NOT NULL COMMENT '币种（ADR-0010 最小货币单位）',
  `daily_limit_minor` bigint NOT NULL DEFAULT '0' COMMENT '日限额（分）；0=不限',
  `monthly_limit_minor` bigint NOT NULL DEFAULT '0' COMMENT '月限额（分）；0=不限',
  `yearly_limit_minor` bigint NOT NULL DEFAULT '0' COMMENT '年限额（分）；0=不限',
  `status` varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE | DISABLED',
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  `created_by` varchar(64) DEFAULT NULL,
  `updated_by` varchar(64) DEFAULT NULL,
  `version` int NOT NULL DEFAULT '1',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_uplimit_user_ccy` (`user_id`,`currency_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户支付限额配置（spec 027 / ADR-0071）；无行=不限额（FR-012）';
/*!40101 SET character_set_client = @saved_cs_client */;

CREATE DATABASE /*!32312 IF NOT EXISTS*/ `fulfillment` /*!40100 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci */ /*!80016 DEFAULT ENCRYPTION='N' */;

USE `fulfillment`;
DROP TABLE IF EXISTS `fulfillments`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `fulfillments` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `source_payment_no` varchar(32) NOT NULL COMMENT '来源支付单（业务单号 PM+雪花，ADR-0063）',
  `order_no` varchar(32) NOT NULL COMMENT '所属订单（业务单号 OR+雪花，ADR-0063）',
  `order_item_id` varchar(64) NOT NULL COMMENT '订单明细业务单号 OI+雪花（spec 018 / ADR-0066）',
  `delivery_content` varchar(255) NOT NULL,
  `status` varchar(32) NOT NULL,
  `failure_reason` varchar(255) DEFAULT NULL,
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  `created_by` varchar(64) DEFAULT NULL,
  `updated_by` varchar(64) DEFAULT NULL,
  `version` int NOT NULL DEFAULT '1',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_fulfillments_source_payment_item` (`source_payment_no`,`order_item_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

CREATE DATABASE /*!32312 IF NOT EXISTS*/ `entitlement` /*!40100 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci */ /*!80016 DEFAULT ENCRYPTION='N' */;

USE `entitlement`;
DROP TABLE IF EXISTS `entitlements`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `entitlements` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `source_fulfillment_id` varchar(64) NOT NULL,
  `user_id` varchar(64) NOT NULL,
  `order_no` varchar(32) NOT NULL COMMENT '所属订单（业务单号 OR+雪花，ADR-0063）',
  `grant_ref` varchar(64) DEFAULT NULL,
  `available_quantity` int NOT NULL,
  `scope` varchar(64) NOT NULL,
  `expiry_at` datetime DEFAULT NULL,
  `status` varchar(32) NOT NULL,
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  `created_by` varchar(64) DEFAULT NULL,
  `updated_by` varchar(64) DEFAULT NULL,
  `version` int NOT NULL DEFAULT '1',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_entitlements_source_fulfillment_id` (`source_fulfillment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

CREATE DATABASE /*!32312 IF NOT EXISTS*/ `reconciliation` /*!40100 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci */ /*!80016 DEFAULT ENCRYPTION='N' */;

USE `reconciliation`;
DROP TABLE IF EXISTS `audit_adjustments`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `audit_adjustments` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `adjust_no` varchar(32) NOT NULL COMMENT '业务单号 AD+雪花（ADR-0062）',
  `batch_id` bigint NOT NULL,
  `difference_id` bigint NOT NULL,
  `kind` varchar(16) NOT NULL,
  `debit_account_code` varchar(32) NOT NULL,
  `credit_account_code` varchar(32) NOT NULL,
  `amount_minor` bigint NOT NULL,
  `currency` varchar(8) NOT NULL,
  `posting_no` varchar(32) DEFAULT NULL COMMENT 'ledger 侧 ADJUSTMENT posting 单号',
  `status` varchar(16) NOT NULL DEFAULT 'POSTED' COMMENT 'POSTED|REVERSED',
  `operator` varchar(64) NOT NULL,
  `reviewer` varchar(64) DEFAULT NULL,
  `reason` varchar(255) NOT NULL,
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  `created_by` varchar(64) DEFAULT NULL,
  `updated_by` varchar(64) DEFAULT NULL,
  `version` int NOT NULL DEFAULT '1',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_adjustments_adjust_no` (`adjust_no`),
  KEY `idx_adj_diff` (`difference_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `audit_batches`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `audit_batches` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `batch_no` varchar(32) NOT NULL COMMENT '业务单号 AB+雪花（ADR-0062）',
  `period` varchar(32) NOT NULL,
  `scope` varchar(16) NOT NULL COMMENT 'CERTIFICATE|LEDGER|REAL|REPORT|ALL',
  `status` varchar(16) NOT NULL COMMENT 'PROCESSING|BALANCED|HAS_DIFFERENCE|RECHECKING|CLOSED',
  `checked_count` int NOT NULL DEFAULT '0',
  `difference_count` int NOT NULL DEFAULT '0',
  `suspended_amount_minor` bigint NOT NULL DEFAULT '0',
  `adjusted_amount_minor` bigint NOT NULL DEFAULT '0',
  `triggered_by` varchar(64) DEFAULT NULL,
  `started_at` datetime DEFAULT NULL,
  `finished_at` datetime DEFAULT NULL,
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  `created_by` varchar(64) DEFAULT NULL,
  `updated_by` varchar(64) DEFAULT NULL,
  `version` int NOT NULL DEFAULT '1',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_audit_batches_batch_no` (`batch_no`),
  UNIQUE KEY `uk_audit_batches_period_scope` (`period`,`scope`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `audit_differences`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `audit_differences` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `batch_id` bigint NOT NULL,
  `kind` varchar(32) NOT NULL,
  `severity` varchar(8) NOT NULL COMMENT 'BLOCKER|MAJOR|MINOR',
  `source_type` varchar(16) NOT NULL,
  `source_id` varchar(64) NOT NULL,
  `reference` varchar(128) DEFAULT NULL,
  `expected_amount_minor` bigint DEFAULT NULL,
  `actual_amount_minor` bigint DEFAULT NULL,
  `currency` varchar(8) DEFAULT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'PENDING',
  `suspended_amount_minor` bigint NOT NULL DEFAULT '0',
  `adjusted_amount_minor` bigint NOT NULL DEFAULT '0',
  `transferred_out_minor` bigint NOT NULL DEFAULT '0' COMMENT '累计从 SUSPENSE 转出（TRANSFER）',
  `detail` varchar(512) DEFAULT NULL,
  `resolution_note` varchar(255) DEFAULT NULL,
  `resolved_by` varchar(64) DEFAULT NULL,
  `resolved_at` datetime DEFAULT NULL,
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  `created_by` varchar(64) DEFAULT NULL,
  `updated_by` varchar(64) DEFAULT NULL,
  `version` int NOT NULL DEFAULT '1',
  PRIMARY KEY (`id`),
  KEY `idx_audit_diff_batch` (`batch_id`),
  KEY `idx_audit_diff_source` (`source_type`,`source_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `reconciliation_batches`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `reconciliation_batches` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `batch_no` varchar(32) NOT NULL COMMENT '业务单号 RB+雪花（ADR-0062）',
  `period` varchar(32) NOT NULL,
  `source` varchar(32) NOT NULL,
  `status` varchar(32) NOT NULL,
  `matches_json` text,
  `differences_json` text,
  `closed_at` datetime DEFAULT NULL,
  `closed_by` varchar(64) DEFAULT NULL,
  `statement_source` varchar(255) DEFAULT NULL,
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  `created_by` varchar(64) DEFAULT NULL,
  `updated_by` varchar(64) DEFAULT NULL,
  `version` int NOT NULL DEFAULT '1',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_reconciliation_batches_period` (`period`),
  UNIQUE KEY `uk_reconciliation_batches_batch_no` (`batch_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

CREATE DATABASE /*!32312 IF NOT EXISTS*/ `settlement` /*!40100 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci */ /*!80016 DEFAULT ENCRYPTION='N' */;

USE `settlement`;
DROP TABLE IF EXISTS `settlement_adjustments`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `settlement_adjustments` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `idempotency_key` varchar(128) NOT NULL,
  `merchant_id` varchar(32) NOT NULL,
  `period` varchar(32) NOT NULL,
  `amount_minor` bigint NOT NULL,
  `direction` varchar(16) NOT NULL,
  `currency_code` varchar(8) NOT NULL,
  `reason` varchar(255) NOT NULL,
  `operator` varchar(64) NOT NULL,
  `status` varchar(16) NOT NULL,
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  `created_by` varchar(64) DEFAULT NULL,
  `updated_by` varchar(64) DEFAULT NULL,
  `version` int NOT NULL DEFAULT '1',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_settlement_adjustments_idem` (`idempotency_key`),
  KEY `idx_settlement_adjustments_scope` (`merchant_id`,`period`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `settlement_batches`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `settlement_batches` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `batch_no` varchar(32) NOT NULL COMMENT '业务单号 SB+雪花（ADR-0062）',
  `merchant_id` varchar(32) NOT NULL,
  `period` varchar(32) NOT NULL,
  `idempotency_key` varchar(128) NOT NULL,
  `currency_code` varchar(8) NOT NULL,
  `income_minor` bigint NOT NULL,
  `refund_minor` bigint NOT NULL,
  `adjustment_minor` bigint NOT NULL,
  `net_minor` bigint NOT NULL,
  `status` varchar(32) NOT NULL,
  `fact_count` int NOT NULL DEFAULT '0',
  `source_period` varchar(32) DEFAULT NULL,
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  `created_by` varchar(64) DEFAULT NULL,
  `updated_by` varchar(64) DEFAULT NULL,
  `version` int NOT NULL DEFAULT '1',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_settlement_batches_batch_no` (`batch_no`),
  UNIQUE KEY `uk_settlement_batches_merchant_period` (`merchant_id`,`period`),
  UNIQUE KEY `uk_settlement_batches_idempotency_key` (`idempotency_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `settlement_items`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `settlement_items` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `batch_id` bigint NOT NULL,
  `reference` varchar(64) NOT NULL,
  `type` varchar(16) NOT NULL,
  `amount_minor` bigint NOT NULL,
  `currency_code` varchar(8) NOT NULL,
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  `created_by` varchar(64) DEFAULT NULL,
  `updated_by` varchar(64) DEFAULT NULL,
  `version` int NOT NULL DEFAULT '1',
  PRIMARY KEY (`id`),
  KEY `idx_settlement_items_batch_id` (`batch_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

CREATE DATABASE /*!32312 IF NOT EXISTS*/ `ledger` /*!40100 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci */ /*!80016 DEFAULT ENCRYPTION='N' */;

USE `ledger`;
DROP TABLE IF EXISTS `account_balances`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `account_balances` (
  `account_instance_id` bigint NOT NULL,
  `currency` varchar(8) NOT NULL,
  `debit_total` bigint NOT NULL DEFAULT '0',
  `credit_total` bigint NOT NULL DEFAULT '0',
  `entry_count` bigint NOT NULL DEFAULT '0',
  `last_entry_id` bigint NOT NULL DEFAULT '0',
  `updated_at` datetime NOT NULL,
  PRIMARY KEY (`account_instance_id`,`currency`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `account_definitions`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `account_definitions` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `code` varchar(32) NOT NULL COMMENT '科目码（契约枚举 AccountCode，ArchUnit 收口）',
  `name` varchar(64) NOT NULL,
  `type` varchar(16) NOT NULL COMMENT 'ASSET/LIABILITY/REVENUE/EXPENSE/EQUITY',
  `normal_balance` varchar(8) NOT NULL COMMENT 'DEBIT/CREDIT（规则方向推导锚）',
  `owner_dimension` varchar(16) NOT NULL COMMENT 'PLATFORM/CHANNEL/MERCHANT',
  `status` varchar(8) NOT NULL DEFAULT 'ACTIVE' COMMENT 'LEGACY 科目禁止新事件引用',
  `created_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_def_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `accounts`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `accounts` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `definition_code` varchar(32) NOT NULL,
  `owner_type` varchar(16) NOT NULL COMMENT 'PLATFORM/CHANNEL/MERCHANT',
  `owner_id` varchar(64) NOT NULL COMMENT 'PLATFORM 单例=PLATFORM；哨兵=LEGACY；否则渠道码/商户号',
  `currency` varchar(8) NOT NULL,
  `code` varchar(32) NOT NULL,
  `name` varchar(64) NOT NULL,
  `type` varchar(16) NOT NULL,
  `created_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_instance` (`definition_code`,`owner_type`,`owner_id`,`currency`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `ledger_entries`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ledger_entries` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `posting_id` bigint NOT NULL,
  `account_id` bigint NOT NULL COMMENT '031 起指向账户实例（uk_instance.id）',
  `direction` varchar(8) NOT NULL,
  `amount_minor` bigint NOT NULL,
  `currency` varchar(8) NOT NULL,
  `entry_type` varchar(32) DEFAULT NULL,
  `source_type` varchar(16) DEFAULT NULL,
  `source_id` varchar(64) DEFAULT NULL,
  `created_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_entries_posting` (`posting_id`),
  KEY `idx_entries_source` (`source_type`,`source_id`),
  KEY `idx_entries_account` (`account_id`,`currency`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `ledger_periods`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ledger_periods` (
  `period` char(7) NOT NULL,
  `currency` varchar(8) NOT NULL,
  `status` varchar(8) NOT NULL DEFAULT 'OPEN',
  `closed_at` datetime DEFAULT NULL,
  `closed_by` varchar(64) DEFAULT NULL,
  PRIMARY KEY (`period`,`currency`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `postings`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `postings` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `posting_no` varchar(32) NOT NULL COMMENT '业务单号 LP+雪花（ADR-0062）',
  `event_type` varchar(32) NOT NULL COMMENT '事件语义（AccountingEventType，取代分录 entry_type）',
  `idempotency_key` varchar(128) NOT NULL COMMENT '031 起由 Ledger 派生 {eventType}:{sourceId}（原则 10）',
  `source_type` varchar(16) NOT NULL COMMENT '来源域 PAYMENT/REFUND/SETTLEMENT/RECONCILIATION',
  `source_id` varchar(64) NOT NULL COMMENT '业务单号（ADR-0063，禁数值 ID）',
  `status` varchar(16) NOT NULL,
  `currency` varchar(8) NOT NULL,
  `period` char(7) NOT NULL COMMENT '会计期间 YYYY-MM（G2，落库按 posted_at 派生）',
  `posted_at` datetime NOT NULL,
  `created_at` datetime NOT NULL,
  `created_by` varchar(64) DEFAULT NULL,
  `updated_at` datetime NOT NULL,
  `updated_by` varchar(64) DEFAULT NULL,
  `version` int NOT NULL DEFAULT '1',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_postings_idempotency_key` (`idempotency_key`),
  UNIQUE KEY `uk_event_source` (`event_type`,`source_id`),
  UNIQUE KEY `uk_postings_posting_no` (`posting_no`),
  KEY `idx_postings_source` (`source_type`,`source_id`),
  KEY `idx_postings_period` (`period`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

CREATE DATABASE /*!32312 IF NOT EXISTS*/ `refund` /*!40100 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci */ /*!80016 DEFAULT ENCRYPTION='N' */;

USE `refund`;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;


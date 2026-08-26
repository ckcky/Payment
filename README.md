# PaymentArch

面向生产环境的 Commerce & Payment Platform 学习项目，采用 Java 21、Spring Boot 3.x、Spring Cloud 和 Maven，重点实践订单、支付、履约、权益、账本、对账与结算的边界和一致性。

## 从哪里开始

- 开发流程：[docs/development-guide.md](docs/development-guide.md)
- 文档规则：[docs/documentation.md](docs/documentation.md)
- 项目宪法：[.specify/memory/constitution.md](.specify/memory/constitution.md)
- 架构决策：[docs/adr/](docs/adr/)
- 特性设计：`specs/<feature>/`

当前仓库处于文档与架构设计阶段，尚未创建业务服务代码。后续从一个可运行的纵向切片开始，而不是一次生成全部微服务。
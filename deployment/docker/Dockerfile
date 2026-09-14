# PaymentArch 通用应用镜像（spec 026 / ADR-0070）
#
# 设计约束（ADR-0070 D3）：**宿主打 jar，镜像只 COPY**。
# 不在镜像内执行 Maven 构建——10 个服务共享 3 个 common 模块，若在 Docker 内多阶段构建，
# 每个服务都要重跑整个 reactor 并把公共模块重编译一遍 ×10，首次构建时间极其糟糕。
# 父 POM 已把 repackage 产物统一输出到 deployment/output/jars/，此处直接 COPY。
#
# 一份 Dockerfile 服务 10 个模块，通过 build args 参数化：
#   JAR_FILE      相对于构建上下文（仓库根）的 fat jar 路径
#   SERVICE_PORT  应用监听端口（仅用于 EXPOSE 声明与文档化，实际端口由 compose 指定）
#
# 构建（由 docker-compose.yml 传入 args，勿手工构建）：
#   docker compose --profile full build

ARG JRE_IMAGE=eclipse-temurin:21-jre-jammy
FROM ${JRE_IMAGE}

ARG JAR_FILE
ARG SERVICE_PORT

# JVM 堆上限：与宿主模式 deployment/start-all.sh 的默认值保持一致，
# 使两种模式的资源占用与性能表现可比（ADR-0070 D2/D5）。
# 容器配额建议 16GiB 承载 10 个应用 + 7 个中间件（宿主 32GB）。
ENV JAVA_TOOL_OPTIONS="-Xmx384m -Xms128m -XX:MaxMetaspaceSize=160m"

WORKDIR /app
COPY ${JAR_FILE} /app/app.jar

EXPOSE ${SERVICE_PORT}

ENTRYPOINT ["java", "-jar", "/app/app.jar"]

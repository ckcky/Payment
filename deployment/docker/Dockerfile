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

# JVM 堆上限。**容器模式刻意低于宿主模式**（宿主 -Xmx384m/160m metaspace），原因见下。
#
# 实测（2026-09-15）：Docker Desktop 默认配额 7.75GiB，7 个中间件已占约 2.1GiB
# （nacos 947MB + mysql 448MB + grafana 270MB + 其余约 400MB），留给 10 个 JVM 的只有约 5.6GiB。
# 按宿主同规格（堆 384m + metaspace 160m）时，每个 JVM 实际 RSS 约 500MB × 10 = 5GB，
# 叠加中间件的瞬时峰值会顶穿 7.75GiB，导致 Docker VM 被杀、全部容器 Exited(255)。
# 故容器模式收敛为「堆 256m + metaspace 128m」，实测每个 JVM RSS 约 330MB，全栈约 5.3GiB 稳定运行。
# 另在 compose 侧对每个应用加 mem_limit: 512m 硬兜底，避免单个 JVM 失控拖垮 VM。
ENV JAVA_TOOL_OPTIONS="-Xmx256m -Xms96m -XX:MaxMetaspaceSize=128m"

# 时区对齐宿主（ADR-0070 D2「双模等价」）：基础镜像默认 UTC，宿主模式 JVM 是 Asia/Shanghai。
# 不统一会让两种模式在「按本地日期分区 / 日期窗口查询」上产生分叉，日志时间也差 8 小时。
ENV TZ=Asia/Shanghai

WORKDIR /app
COPY ${JAR_FILE} /app/app.jar

EXPOSE ${SERVICE_PORT}

# 日志双写：stdout（保留 docker logs / docker compose logs）+ 挂载卷文件（供 Promtail 采集）。
#
# 为什么不能在 compose 里直接设 LOGGING_FILE_NAME 就了事：
# 项目用自定义 logback-spring.xml（common/common-core），其中**只声明了 CONSOLE appender、
# 没有 FILE appender**，Spring Boot 的 logging.file.name 因此完全不生效（2026-09-15 实测：
# 环境变量已注入容器，但 /var/log/payment-arch 始终为空）。产品侧不改日志配置的前提下，
# 由容器入口把 stdout 分流到文件是唯一最小改动。
#
# - `exec` 保证 java 仍是 PID 1，可正常接收 SIGTERM 优雅停机（管道写法下 sh 若不 exec 会吞信号）。
# - 进程替换 `> >(tee)` 需要 bash，基础镜像 eclipse-temurin:21-jre-jammy 自带。
# - LOGGING_FILE_NAME 由 compose 注入（/var/log/payment-arch/<service>.log），与宿主模式同名，
#   两种模式共用 deployment/logs/，Promtail 采集路径零改动（ADR-0070 D5）。
ENTRYPOINT ["bash", "-c", "exec java -jar /app/app.jar > >(tee -a ${LOGGING_FILE_NAME:-/var/log/payment-arch/app.log}) 2>&1"]

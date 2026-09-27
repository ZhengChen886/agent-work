# Skills Agent Example — Dockerfile
# 多阶段构建:先编译,再打成最小运行时镜像。
# 用法:
#   mvn -DskipTests=true package
#   docker build -t skills-agent-example .
#   docker run -p 8080:8080 -p 8088:8088 \
#     -v $(pwd)/runtime/data:/app/runtime/data \
#     -v $(pwd)/runtime/skills:/app/runtime/skills \
#     -v $(pwd)/runtime/agents:/app/runtime/agents \
#     -v $(pwd)/runtime/workflows:/app/runtime/workflows \
#     -e MODELSCOPE_API_KEY=xxx \
#     -e ADMIN_USERS=admin:yourpassword \
#     skills-agent-example

# ============ Build stage ============
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /build

# 先复制 pom,利用 Docker 缓存只重下依赖
COPY pom.xml ./
COPY maven-settings.xml* ./
RUN if [ -f maven-settings.xml ]; then \
      mvn -B -q -DskipTests -s maven-settings.xml dependency:go-offline; \
    else \
      mvn -B -q -DskipTests dependency:go-offline; \
    fi

COPY src ./src
RUN mvn -B -DskipTests package \
 && cp target/skills-agent-example-*.jar /build/app.jar

# ============ Runtime stage ============
FROM eclipse-temurin:21-jre
WORKDIR /app

# 端口:主应用 8080,Admin 后台 8088
EXPOSE 8080 8088

# 数据目录(SQLite / 日志 / 工作流产物)
VOLUME ["/app/runtime/data", "/app/runtime/output"]

# 启动脚本
COPY --from=builder /build/app.jar /app/app.jar
COPY start.sh /app/start.sh
RUN chmod +x /app/start.sh

# 健康检查
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
    CMD wget -q -O- http://localhost:8080/actuator/health || exit 1

ENV JAVA_OPTS="-Xms256m -Xmx1g"
ENV ADMIN_PORT=8088
ENV APP_PORT=8080

ENTRYPOINT ["/app/start.sh"]
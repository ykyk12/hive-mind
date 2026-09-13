# 多阶段构建：builder 里出 jar，runtime 只带 JRE
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build
COPY pom.xml .
# 先只拉依赖，源码变化时仍能命中依赖层缓存
RUN mvn -B -ntp -q dependency:go-offline
COPY src ./src
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=builder /build/target/hive-mind-*.jar /app/hive-mind.jar

# 制品与审计轨迹挂出去，容器重建不丢历史
VOLUME ["/app/hive"]
ENV HIVE_EVOLVE_ROOT=/app/hive

EXPOSE 8101
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","/app/hive-mind.jar"]

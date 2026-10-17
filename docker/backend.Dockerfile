# syntax=docker/dockerfile:1

# ---- 构建阶段：Maven + JDK21 编译打包 ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# 使用国内镜像加速（阿里云），如需官方源可删除此文件
COPY docker/maven-settings.xml /root/.m2/settings.xml
COPY backend/pom.xml backend/pom.xml
RUN mvn -q -f backend/pom.xml -B dependency:go-offline
COPY backend/src backend/src
RUN mvn -q -f backend/pom.xml -B clean package -DskipTests

# ---- 运行阶段：仅 JRE，体积更小 ----
FROM eclipse-temurin:21-jre
WORKDIR /app
ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-Xms256m -Xmx1g" \
    RISK_UPLOAD_DIR=/app/uploads
RUN mkdir -p /app/uploads
COPY --from=build /build/backend/target/*.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]

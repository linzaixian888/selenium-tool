# Spring Boot JAR 与 CPU 架构无关，使用构建机架构可避免在 ARM64 目标构建时通过 QEMU 运行 Maven。
FROM --platform=$BUILDPLATFORM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /build

COPY pom.xml .
COPY src ./src

RUN mvn -q -DskipTests package

# Google Chrome 没有 Linux/ARM64 版本；Chromium 镜像同时支持 AMD64 和 ARM64。
FROM selenium/standalone-chromium:latest

USER root
WORKDIR /app

ENV TZ=Asia/Shanghai

COPY --from=build /build/target/selenium-tool-0.0.1-SNAPSHOT.jar /app/app.jar

EXPOSE 7900

ENTRYPOINT ["/bin/bash", "-lc", "/opt/bin/entry_point.sh >/tmp/selenium-base.log 2>&1 & exec java -jar /app/app.jar"]

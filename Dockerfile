# 构建阶段
FROM maven:3.9-eclipse-temurin-17 AS build

# 配置阿里云 Maven 镜像
RUN mkdir -p /root/.m2 && \
    echo '<?xml version="1.0" encoding="UTF-8"?> \
    <settings xmlns="http://maven.apache.org/SETTINGS/1.2.0" \
              xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" \
              xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.2.0 https://maven.apache.org/xsd/settings-1.2.0.xsd"> \
      <mirrors> \
        <mirror> \
          <id>aliyun</id> \
          <mirrorOf>central</mirrorOf> \
          <name>Aliyun Maven</name> \
          <url>https://maven.aliyun.com/repository/public</url> \
        </mirror> \
      </mirrors> \
    </settings>' > /root/.m2/settings.xml

WORKDIR /build
COPY pom.xml .
# 先单独拷贝 pom 下载依赖，源码变更时利用层缓存
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

# 运行阶段
FROM eclipse-temurin:17-jre
WORKDIR /app
ENV TZ=Asia/Shanghai \
    H2_DATA_DIR=/app/data \
    JAVA_OPTS=""
COPY --from=build /build/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]

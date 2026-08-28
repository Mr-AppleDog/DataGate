# DataGate 后端多阶段构建（参考 DocLoom；docs/09 §2.1 试用/生产制品）
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
# 阿里云 Maven 镜像，加速依赖拉取
COPY deploy/maven-settings.xml /root/.m2/settings.xml
# 拷全部源码（.dockerignore 已排除 target/.git/.idea/deploy 等）
COPY . .
# 只构建 ruoyi-admin 及其依赖模块（-am），跳测试加速
RUN mvn -B -q clean package -pl ruoyi-admin -am -DskipTests

# 运行阶段：精简 JRE
FROM eclipse-temurin:17-jre
WORKDIR /app
RUN mkdir -p /ruoyi/server/logs /ruoyi/server/temp
COPY --from=build /build/ruoyi-admin/target/ruoyi-admin.jar /app/app.jar
# prod profile；KEK 文件、DATAGATE_* 环境变量由 docker-compose 注入
ENV SERVER_PORT=8080 SPRING_PROFILES_ACTIVE=prod JAVA_OPTS=""
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java -Djava.security.egd=file:/dev/./urandom -XX:+HeapDumpOnOutOfMemoryError -XX:+UseZGC $JAVA_OPTS -jar /app/app.jar"]

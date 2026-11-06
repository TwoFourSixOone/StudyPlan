# 二开推荐阅读[如何提高项目构建效率](https://developers.weixin.qq.com/miniprogram/dev/wxcloudrun/src/scene/build/speed.html)
# 选择构建用基础镜像。如需更换，请到[dockerhub官方仓库](https://hub.docker.com/_/java?tab=tags)自行选择后替换。
FROM maven:3.9-eclipse-temurin-17 as build

# 指定构建过程中的工作目录
WORKDIR /app

# 将src目录下所有文件，拷贝到工作目录中src目录下（.gitignore/.dockerignore中文件除外）
COPY src /app/src

# 将pom.xml文件，拷贝到工作目录下
COPY settings.xml pom.xml /app/

# 执行代码编译命令
# 自定义settings.xml, 选用国内镜像源以提高下载速度
RUN mvn -s /app/settings.xml -f /app/pom.xml clean package -DskipTests


# 选择运行时基础镜像（使用 Debian 基础以支持完整 CA 证书链）
FROM eclipse-temurin:17-jre

# 安装 ca-certificates、Java 证书工具和 Redis
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
       ca-certificates \
       ca-certificates-java \
       redis-server \
    && update-ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# 配置 Redis：绑定所有网卡、关闭保护模式（容器内安全）、后台运行
RUN sed -i 's/^bind .*/bind 0.0.0.0/' /etc/redis/redis.conf \
    && sed -i 's/^protected-mode yes/protected-mode no/' /etc/redis/redis.conf \
    && sed -i 's/^daemonize yes/daemonize no/' /etc/redis/redis.conf

# 设置 JVM 使用系统级证书库（包含平台可能注入的代理 CA）
ENV JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts"

# 容器默认时区为UTC，如需使用上海时间请启用以下时区设置命令
# RUN ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && echo "Asia/Shanghai" > /etc/timezone

# 指定运行时的工作目录
WORKDIR /app

# 将构建产物jar包拷贝到运行时目录中
COPY --from=build /app/target/*.jar .

# 拷贝启动脚本
COPY start.sh /app/start.sh
RUN chmod +x /app/start.sh

# 暴露端口
# 此处端口必须与「服务设置」-「流水线」以及「手动上传代码包」部署时填写的端口一致，否则会部署失败。
EXPOSE 80
EXPOSE 6379

# 执行启动命令
CMD ["/app/start.sh"]

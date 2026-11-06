#!/bin/bash
# 启动脚本：先启动 Redis，再启动 Spring Boot 应用

# 启动 Redis（后台运行）
redis-server /etc/redis/redis.conf &

# 等待 Redis 就绪
sleep 2

# 启动 Spring Boot 应用
java -jar /app/StudyPlan-1.0-SNAPSHOT.jar

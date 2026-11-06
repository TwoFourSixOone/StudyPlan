#!/bin/bash
# 启动脚本：拉起 Redis + Spring Boot
# 关键点：任一进程退出，整个容器都要退出，避免「假活」

# set -e：任何命令失败立即退出，避免带病运行
set -e

# 后台启动 Redis（daemonize no 时它会前台运行，这里用 & 放后台）
redis-server /etc/redis/redis.conf &
REDIS_PID=$!

# 【改进1】用 redis-cli ping 轮询代替 sleep 2
# sleep 2 是「盲等」：慢机器可能没起来，快机器又浪费时间。
# 轮询直到返回 PONG 才继续，既快又可靠。
until redis-cli ping 2>/dev/null | grep -q PONG; do
  sleep 0.5
done

# 后台启动 Spring Boot（用 app.jar 与版本号解耦）
java -jar /app/app.jar &
JAVA_PID=$!

# 【改进2】wait -n：等待「任意一个」子进程退出
# 作用：Redis 或 Java 任一挂掉，脚本就继续往下走并退出容器。
# 这样云平台的健康检查/重启策略才能生效；
# 否则 start.sh 会一直挂着，容器表面活着、实际不可用。
wait -n

# 清理另一个进程，然后以非 0 退出，触发平台重启
kill "$REDIS_PID" "$JAVA_PID" 2>/dev/null || true
exit 1
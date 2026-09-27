#!/usr/bin/env bash
# Linux / macOS 启动脚本(Docker 镜像内使用,也可直接在 Linux 上运行)
set -e

cd "$(dirname "$0")"

APP_PORT="${APP_PORT:-8080}"
ADMIN_PORT="${ADMIN_PORT:-8088}"

echo "[INFO] APP_PORT    = $APP_PORT"
echo "[INFO] ADMIN_PORT  = $ADMIN_PORT"
echo "[INFO] ADMIN_USERS = ${ADMIN_USERS:-<unset, default admin/admin123>}"

# 杀掉占用端口的旧进程(Linux)
for p in "$APP_PORT" "$ADMIN_PORT"; do
  if command -v fuser >/dev/null 2>&1; then
    fuser -k "${p}/tcp" 2>/dev/null || true
  fi
done

exec java $JAVA_OPTS \
  -Dserver.port="$APP_PORT" \
  -Dadmin.port="$ADMIN_PORT" \
  -jar app.jar "$@"
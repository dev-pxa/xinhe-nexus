#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROJECT_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)
cd "$PROJECT_DIR"

[ -f .env ] || cp .env.example .env

if grep -Eq 'change_root_me|change_redis_me' .env; then
  echo "请先修改 .env 中的数据库和 Redis 密码。" >&2
  exit 1
fi

docker compose --env-file .env config --quiet
docker compose --env-file .env up -d --build --remove-orphans
docker compose --env-file .env ps


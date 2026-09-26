#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROJECT_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)
BACKUP_DIR="$PROJECT_DIR/backups"
mkdir -p "$BACKUP_DIR"
cd "$PROJECT_DIR"

OUTPUT_FILE="$BACKUP_DIR/xinhe-nexus-$(date '+%Y%m%d-%H%M%S').sql.gz"
docker compose --env-file .env exec -T mysql sh -c \
  'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction "$MYSQL_DATABASE"' | gzip > "$OUTPUT_FILE"
chmod 600 "$OUTPUT_FILE"
echo "备份完成：$OUTPUT_FILE"


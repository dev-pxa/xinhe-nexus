#!/usr/bin/env bash
# Deploy the Xinhe Nexus backend to the same host used by training, while
# keeping its Docker project, containers, volumes, networks and host ports isolated.
set -euo pipefail

SSH_HOST="${SSH_HOST:-49.232.34.105}"
SSH_USER="${SSH_USER:-root}"
SSH_PORT="${SSH_PORT:-22}"
SSH_KEY_PATH="${SSH_KEY_PATH:-}"
DEPLOY_PATH="${DEPLOY_PATH:-/opt/xinhe-nexus}"
ARCHIVE_NAME="xinhe-nexus-release.tgz"
LOCAL_ARCHIVE="/tmp/${ARCHIVE_NAME}"
REMOTE_ARCHIVE="/tmp/${ARCHIVE_NAME}"
REMOTE_ENV_FILE="/tmp/xinhe-nexus-release.env"
MODE="partial"
SHOW_LOGS="false"
STATUS_ONLY="false"

SCP_CMD=(scp -P "$SSH_PORT")
SSH_CMD=(ssh -p "$SSH_PORT")
if [[ -n "$SSH_KEY_PATH" ]]; then
  SCP_CMD=(scp -i "$SSH_KEY_PATH" -P "$SSH_PORT")
  SSH_CMD=(ssh -i "$SSH_KEY_PATH" -p "$SSH_PORT")
fi

usage() {
  cat <<'EOF'
Usage:
  deploy/scripts/deploy-ssh.sh          Rebuild management and app services
  deploy/scripts/deploy-ssh.sh --full   Rebuild all services, including MySQL and Redis
  deploy/scripts/deploy-ssh.sh --status Show remote compose status only
  deploy/scripts/deploy-ssh.sh --logs   Follow management logs after deployment

Optional environment variables: SSH_HOST, SSH_USER, SSH_PORT, SSH_KEY_PATH,
DEPLOY_PATH (default: /opt/xinhe-nexus).
EOF
}

for arg in "$@"; do
  case "$arg" in
    --full) MODE="full" ;;
    --logs) SHOW_LOGS="true" ;;
    --status) STATUS_ONLY="true" ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $arg" >&2; usage >&2; exit 1 ;;
  esac
done

if [[ -z "$DEPLOY_PATH" || "$DEPLOY_PATH" == "/" ]]; then
  echo "Invalid DEPLOY_PATH: $DEPLOY_PATH" >&2
  exit 1
fi

PROJECT_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$PROJECT_ROOT"

if [[ ! -f docker-compose.yml || ! -f .env ]]; then
  echo "docker-compose.yml and .env are required in $PROJECT_ROOT" >&2
  exit 1
fi
if grep -Eq '^[[:space:]]*(MYSQL_ROOT_PASSWORD|REDIS_PASSWORD)=[[:space:]]*(change_root_me|change_redis_me)[[:space:]]*$' .env; then
  echo ".env still contains the placeholder database or Redis password" >&2
  exit 1
fi

if [[ "$STATUS_ONLY" == "true" ]]; then
  "${SSH_CMD[@]}" "${SSH_USER}@${SSH_HOST}" "cd '$DEPLOY_PATH' && (docker compose ps || docker-compose ps)"
  exit 0
fi

echo "Validating isolated Xinhe Nexus port mappings..."
grep -q 'MANAGEMENT_PORT:-18081' docker-compose.yml
grep -q 'APP_PORT:-18083' docker-compose.yml
grep -q 'MYSQL_PORT:-13306' docker-compose.yml
grep -q 'REDIS_PORT:-16379' docker-compose.yml
docker compose --env-file .env config --quiet

echo "Packing backend release..."
rm -f "$LOCAL_ARCHIVE"
tar \
  --exclude='.git' \
  --exclude='.github' \
  --exclude='.codex' \
  --exclude='.idea' \
  --exclude='.DS_Store' \
  --exclude='.env' \
  --exclude='.env.*' \
  --exclude='**/target' \
  --exclude='logs' \
  --exclude='*.log' \
  -czf "$LOCAL_ARCHIVE" .

cleanup() { rm -f "$LOCAL_ARCHIVE"; }
trap cleanup EXIT

echo "Uploading release to ${SSH_USER}@${SSH_HOST}:${DEPLOY_PATH}..."
"${SCP_CMD[@]}" "$LOCAL_ARCHIVE" "${SSH_USER}@${SSH_HOST}:${REMOTE_ARCHIVE}"
"${SCP_CMD[@]}" .env "${SSH_USER}@${SSH_HOST}:${REMOTE_ENV_FILE}"

echo "Deploying backend services..."
"${SSH_CMD[@]}" "${SSH_USER}@${SSH_HOST}" \
  "DEPLOY_PATH='$DEPLOY_PATH' REMOTE_ARCHIVE='$REMOTE_ARCHIVE' REMOTE_ENV_FILE='$REMOTE_ENV_FILE' MODE='$MODE' bash -s" <<'REMOTE'
set -euo pipefail
mkdir -p "$DEPLOY_PATH"
cd "$DEPLOY_PATH"

if docker compose version >/dev/null 2>&1; then
  COMPOSE=(docker compose)
elif command -v docker-compose >/dev/null 2>&1; then
  COMPOSE=(docker-compose)
else
  echo "docker compose or docker-compose is required on the server" >&2
  exit 1
fi

[[ -s "$REMOTE_ENV_FILE" ]] || { echo "Uploaded .env is missing" >&2; exit 1; }
find "$DEPLOY_PATH" -mindepth 1 -maxdepth 1 ! -name '.env' -exec rm -rf {} +
tar -xzf "$REMOTE_ARCHIVE" -C "$DEPLOY_PATH"
cp "$REMOTE_ENV_FILE" .env
chmod 600 .env
rm -f "$REMOTE_ARCHIVE" "$REMOTE_ENV_FILE"

# These ports deliberately differ from the training stack on this server.
grep -q 'MANAGEMENT_PORT:-18081' docker-compose.yml
grep -q 'APP_PORT:-18083' docker-compose.yml
grep -q 'MYSQL_PORT:-13306' docker-compose.yml
grep -q 'REDIS_PORT:-16379' docker-compose.yml
"${COMPOSE[@]}" --env-file .env config --quiet

if [[ "$MODE" == "full" ]]; then
  "${COMPOSE[@]}" --env-file .env up -d --build --remove-orphans
else
  "${COMPOSE[@]}" --env-file .env up -d --build management app
fi
docker image prune -f >/dev/null 2>&1 || true
"${COMPOSE[@]}" --env-file .env ps
REMOTE

echo "Waiting for APIs..."
for attempt in $(seq 1 24); do
  MANAGEMENT_CODE="$("${SSH_CMD[@]}" "${SSH_USER}@${SSH_HOST}" "curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:18081/api/auth/checkLogin" 2>/dev/null || echo 000)"
  APP_CODE="$("${SSH_CMD[@]}" "${SSH_USER}@${SSH_HOST}" "curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:18083/api/app/system/ping" 2>/dev/null || echo 000)"
  if [[ "$MANAGEMENT_CODE" =~ ^(200|401|404)$ && "$APP_CODE" =~ ^(200|401|404)$ ]]; then
    echo "APIs are reachable: management=${MANAGEMENT_CODE}, app=${APP_CODE}"
    break
  fi
  if [[ "$attempt" -eq 24 ]]; then
    echo "APIs were not ready after 120 seconds: management=${MANAGEMENT_CODE}, app=${APP_CODE}" >&2
    exit 1
  fi
  sleep 5
done

if [[ "$SHOW_LOGS" == "true" ]]; then
  "${SSH_CMD[@]}" "${SSH_USER}@${SSH_HOST}" "docker logs -f xinhe-nexus-management"
fi

echo "Deploy completed: management=http://${SSH_HOST}:18081, app=http://${SSH_HOST}:18083"

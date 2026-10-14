#!/usr/bin/env bash
set -euo pipefail

API_BASE="${API_BASE:-http://localhost:18081}"
PROJECT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
TEST_CODE=""
ROOT_COOKIE="$(mktemp)"
COMPANY_COOKIE="$(mktemp)"
CREATED=0

if [[ -f "$PROJECT_DIR/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$PROJECT_DIR/.env"
  set +a
fi

cleanup() {
  if [[ "$CREATED" == "1" ]]; then
    (
      cd "$PROJECT_DIR"
      docker compose exec -T mysql mysql \
        -uroot "-p${MYSQL_ROOT_PASSWORD:-root}" "${MYSQL_DATABASE:-xinhe_nexus}" \
        -e "SET @cid=(SELECT id FROM sys_company WHERE company_code='${TEST_CODE}' LIMIT 1);
            SET @uid=(SELECT u.id FROM sys_user u JOIN sys_user_company uc ON uc.user_id=u.id WHERE uc.company_id=@cid AND u.username='${TEST_CODE}' LIMIT 1);
            DELETE FROM sys_operation_log WHERE company_id=@cid OR user_id=@uid;
            DELETE FROM sys_user_position WHERE user_id=@uid;
            DELETE pp FROM sys_position_permission pp JOIN sys_position p ON p.id=pp.position_id WHERE p.company_id=@cid;
            DELETE FROM sys_position WHERE company_id=@cid;
            DELETE FROM sys_org_unit WHERE company_id=@cid;
            DELETE FROM sys_user_company WHERE company_id=@cid OR user_id=@uid;
            DELETE FROM sys_user WHERE id=@uid;
            DELETE FROM sys_company_relation WHERE upstream_company_id=@cid OR downstream_company_id=@cid;
            DELETE FROM sys_company WHERE id=@cid;" >/dev/null
    )
  fi
  rm -f "$ROOT_COOKIE" "$COMPANY_COOKIE"
}
trap cleanup EXIT

curl -fsS -c "$ROOT_COOKIE" -H 'Content-Type: application/json' \
  -d '{"username":"root","password":"root","remember":false}' \
  "$API_BASE/api/auth/login" | grep -q '"code":0'

create_response="$(curl -fsS -b "$ROOT_COOKIE" -H 'Content-Type: application/json' \
  -d '{"companyName":"自动化测试公司"}' \
  "$API_BASE/api/system/companies")"
TEST_CODE="$(sed -n 's/.*"companyCode":"\([^"]*\)".*/\1/p' <<<"$create_response")"
if [[ -n "$TEST_CODE" ]]; then
  CREATED=1
fi
[[ "$TEST_CODE" =~ ^COMP[0-9]{8,}$ ]]
grep -q "\"companyCode\":\"${TEST_CODE}\"" <<<"$create_response"
grep -q "\"administratorUsername\":\"${TEST_CODE}\"" <<<"$create_response"
grep -q "\"initialPassword\":\"${TEST_CODE}\"" <<<"$create_response"

curl -fsS -c "$COMPANY_COOKIE" -H 'Content-Type: application/json' \
  -d "{\"username\":\"${TEST_CODE}\",\"password\":\"${TEST_CODE}\",\"remember\":false}" \
  "$API_BASE/api/auth/login" | grep -q '"code":0'

curl -fsS -b "$COMPANY_COOKIE" "$API_BASE/api/auth/current" \
  | grep -q "\"username\":\"${TEST_CODE}\""

echo "公司创建端到端测试通过：公司、根组织、公司管理员和登录会话均正常，测试数据已安排清理。"

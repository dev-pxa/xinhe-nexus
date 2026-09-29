#!/usr/bin/env bash
set -euo pipefail

base_url="${MANAGEMENT_BASE_URL:-http://127.0.0.1:18081}"
cookie_file="$(mktemp)"
admin_cookie_file="$(mktemp)"
trap 'rm -f "$cookie_file" "$admin_cookie_file"' EXIT

assert_contains() {
  local body="$1"
  local expected="$2"
  if [[ "$body" != *"$expected"* ]]; then
    echo "断言失败：响应中未找到 $expected" >&2
    echo "$body" >&2
    exit 1
  fi
}

status="$(curl -sS -o /dev/null -w '%{http_code}' "$base_url/api/auth/current")"
[[ "$status" == "401" ]] || { echo "未登录请求应返回401，实际为$status" >&2; exit 1; }

login_body="$(curl -sS -c "$cookie_file" -H 'Content-Type: application/json' \
  -d '{"username":"root","password":"root","remember":false}' \
  "$base_url/api/auth/login")"
assert_contains "$login_body" '"code":0'
assert_contains "$login_body" '"PLATFORM_ADMIN"'

current_body="$(curl -sS -b "$cookie_file" "$base_url/api/auth/current")"
assert_contains "$current_body" '"username":"root"'

company_body="$(curl -sS -b "$cookie_file" "$base_url/api/system/companies")"
assert_contains "$company_body" '"companyCode":"XINHE"'
if [[ "$company_body" == *'shortName'* ]]; then
  echo '公司接口不应返回公司简称字段' >&2
  exit 1
fi

admin_login_body="$(curl -sS -c "$admin_cookie_file" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin","remember":false}' \
  "$base_url/api/auth/login")"
assert_contains "$admin_login_body" '"code":0'
assert_contains "$admin_login_body" '"COMPANY_ADMIN"'
assert_contains "$admin_login_body" '"companyName":"芯河智枢总公司"'

admin_company_body="$(curl -sS -b "$admin_cookie_file" "$base_url/api/system/companies")"
assert_contains "$admin_company_body" '"companyCode":"XINHE"'

echo '管理端冒烟测试通过：root/admin认证、数据库账号、公司隔离查询、401保护均正常。'

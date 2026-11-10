#!/usr/bin/env bash
set -euo pipefail

base_url="${MANAGEMENT_BASE_URL:-http://127.0.0.1:18081}"
cookie_file="$(mktemp)"
trap 'rm -f "$cookie_file"' EXIT

login_body="$(curl -sS -c "$cookie_file" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin","remember":false}' \
  "$base_url/api/auth/login")"
echo "$login_body" | jq -e '.code == 0 and (.data.roles | index("COMPANY_ADMIN") != null)' >/dev/null

punch_body="$(curl -sS -b "$cookie_file" -H 'Content-Type: application/json' -H 'X-Tenant-Id: 1' \
  -d '{"latitude":30.2741,"longitude":120.1551,"accuracy":12.5,"locationSource":"BROWSER_GPS","capturedAt":"2026-11-09T09:00:00+08:00"}' \
  "$base_url/api/oa/attendance/punch")"
echo "$punch_body" | jq -e '.code == 0 and .data.punchCount >= 1' >/dev/null

records_body="$(curl -sS -b "$cookie_file" -H 'X-Tenant-Id: 1' \
  "$base_url/api/oa/attendance/records?from=2026-11-01&to=2026-11-30&page=1&pageSize=1")"
echo "$records_body" | jq -e '.code == 0 and (.data | has("data")) and (.data | has("total")) and .data.pageSize == 1' >/dev/null

admin_punch_body="$(curl -sS -b "$cookie_file" -H 'Content-Type: application/json' -H 'X-Tenant-Id: 1' \
  -d '{"companyId":1,"userId":2,"punchTime":"2026-11-09 09:05:00","latitude":30.2741,"longitude":120.1551,"accuracy":15,"locationSource":"ADMIN_ENTRY","remark":"自动化测试"}' \
  "$base_url/api/oa/attendance/records/punch")"
echo "$admin_punch_body" | jq -e '.code == 0' >/dev/null

corrections_body="$(curl -sS -b "$cookie_file" -H 'X-Tenant-Id: 1' \
  "$base_url/api/oa/attendance/correction-requests/mine")"
echo "$corrections_body" | jq -e '.code == 0 and (.data | type == "array")' >/dev/null

echo '考勤接口自动化冒烟测试通过：定位打卡、管理员补录、服务端分页和补打卡查询均正常。'

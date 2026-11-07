#!/usr/bin/env bash
# 不部署 Prometheus 时的最低检查（docs/ops/01-deploy.md 第 7 节）：放进 cron，失败时退出码非 0、输出原因。
# 配合 cron 的 MAILTO 或把输出发给自己的通知服务。
#   QF_CHECK_URL=https://quizforge.treenewbee.cloud \
#   QF_COMPOSE_ARGS="--env-file /etc/quizforge/prod.env -f deploy/docker-compose.yml -f deploy/docker-compose.prod.yml" \
#   deploy/ops/check.sh
set -uo pipefail
url="${QF_CHECK_URL:?请设置 QF_CHECK_URL}"
backups="${QF_BACKUP_DIR:-/var/backups/quizforge}"
max_backup_hours="${QF_CHECK_BACKUP_HOURS:-26}"
min_disk_percent="${QF_CHECK_DISK_PERCENT:-15}"
min_cert_days="${QF_CHECK_CERT_DAYS:-14}"
problems=0
problem() { echo "异常：$*"; problems=$((problems + 1)); }
# shellcheck disable=SC2086 # QF_COMPOSE_ARGS 是多个参数。
compose() { docker compose -p "${QF_COMPOSE_PROJECT:-quizforge}" ${QF_COMPOSE_ARGS:-} "$@"; }

# 1. 网站与数据库（经过 Nginx，从外部地址）。
body="$(curl -fsS --max-time 15 "$url/api/health" 2>&1)" || problem "健康检查失败：$body"
case "$body" in *'"database":"up"'*) ;; *) problem "数据库不可用：$body" ;; esac

# 2. 容器状态。
for service in nginx app mysql rule-runner; do
  state="$(compose ps --format '{{.Status}}' "$service" 2>/dev/null)"
  case "$state" in
    *healthy*) case "$state" in *unhealthy*) problem "$service 不健康：$state" ;; esac ;;
    *) problem "$service 状态异常：${state:-未运行}" ;;
  esac
done

# 3. 磁盘（Docker 数据目录与备份目录）。
docker_root="$(docker info --format '{{.DockerRootDir}}' 2>/dev/null || echo /)"
for path in "$docker_root" "$backups"; do
  [ -d "$path" ] || continue
  free="$(df -P "$path" | awk 'NR==2 {gsub("%","",$5); print 100-$5}')"
  [ "$free" -ge "$min_disk_percent" ] || problem "$path 剩余空间只有 ${free}%"
done

# 4. 备份是否新鲜。
latest="$(find "$backups" -mindepth 1 -maxdepth 1 -type d -name '[0-9]*-[0-9]*' -mmin "-$((max_backup_hours * 60))" 2>/dev/null | head -n 1)"
[ -n "$latest" ] || problem "$max_backup_hours 小时内没有成功的备份（$backups）"

# 5. 证书有效期。
host="$(printf '%s' "$url" | sed -E 's#^https?://([^/:]+).*#\1#')"
if end="$(echo | openssl s_client -servername "$host" -connect "$host:443" 2>/dev/null | openssl x509 -noout -enddate 2>/dev/null | cut -d= -f2)" && [ -n "$end" ]; then
  days=$(( ( $(date -d "$end" +%s) - $(date +%s) ) / 86400 ))
  [ "$days" -ge "$min_cert_days" ] || problem "证书还有 $days 天过期"
else
  problem "无法读取证书"
fi

# 6. 对账任务的告警（最近 2 小时的日志）。
recent_logs="$(compose logs app --no-color --since 2h 2>/dev/null)"
if printf '%s' "$recent_logs" | grep -qE 'AI 余额对账不一致|AI 流水不连续'; then
  problem "AI 余额对账发现不一致，运行 maintenance ai-reconcile 查看明细"
fi

[ "$problems" -eq 0 ] && { echo "正常"; exit 0; }
exit 1

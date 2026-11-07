#!/usr/bin/env bash
# 为 mysqld_exporter 创建只读账号（只需要执行一次）。密码取 prod.env 中的 QF_MYSQL_EXPORTER_PASSWORD。
#   QF_COMPOSE_ARGS="--env-file /etc/quizforge/prod.env -f deploy/docker-compose.yml -f deploy/docker-compose.prod.yml" \
#   QF_MYSQL_EXPORTER_PASSWORD=... deploy/monitoring/create-exporter-user.sh
set -euo pipefail
[ -n "${QF_MYSQL_EXPORTER_PASSWORD:-}" ] || { echo "请设置 QF_MYSQL_EXPORTER_PASSWORD" >&2; exit 1; }
case "$QF_MYSQL_EXPORTER_PASSWORD" in *\'*|*\\*) echo "密码不能包含单引号或反斜杠" >&2; exit 1 ;; esac
# 通过标准输入传 SQL，密码不出现在命令行。
# shellcheck disable=SC2086 # QF_COMPOSE_ARGS 是多个参数。
docker compose -p "${QF_COMPOSE_PROJECT:-quizforge}" ${QF_COMPOSE_ARGS:-} exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot' <<SQL
CREATE USER IF NOT EXISTS 'exporter'@'%' IDENTIFIED BY '$QF_MYSQL_EXPORTER_PASSWORD' WITH MAX_USER_CONNECTIONS 3;
ALTER USER 'exporter'@'%' IDENTIFIED BY '$QF_MYSQL_EXPORTER_PASSWORD';
GRANT PROCESS, REPLICATION CLIENT, SELECT ON *.* TO 'exporter'@'%';
SQL
echo "exporter 账号已就绪"

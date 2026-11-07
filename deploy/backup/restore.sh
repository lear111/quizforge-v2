#!/usr/bin/env bash
# 用一份备份覆盖当前的数据库和文件目录。会停止 app 与 nginx，恢复后重新启动。
#   deploy/backup/restore.sh /var/backups/quizforge/20261105-030000 --yes
# 没有 --yes 时只校验备份并说明会做什么，不改任何数据。
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=deploy/backup/common.sh
. "$here/common.sh"
require_stream_tools
[ $# -ge 1 ] || fail "用法：restore.sh <备份目录> --yes"
source_dir="$1"

log "校验备份：$source_dir"
verify_backup "$source_dir" full
cat "$source_dir/MANIFEST"
if [ "${2:-}" != "--yes" ]; then
  log "没有 --yes：未做任何修改。加上 --yes 后将覆盖项目 $QF_COMPOSE_PROJECT 的数据库和文件目录。"
  exit 0
fi

log "停止 app 与 nginx（恢复期间不接受写入）"
# 全新的服务器或删除了数据卷时，先由 Compose 创建容器、网络和数据卷。
compose up --no-start
compose stop nginx app

log "启动 MySQL"
compose up -d --wait mysql

log "恢复数据库（先删除现有数据库）"
compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -e "DROP DATABASE IF EXISTS \`$MYSQL_DATABASE\`"'
gzip -dc "$source_dir/mysql.sql.gz" \
  | compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot'
# 应用账号的权限针对数据库名保存在 mysql 系统库中，删除并重建数据库后仍然有效；空数据卷上新建的账号也已有权限。
compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -e "GRANT ALL ON \`$MYSQL_DATABASE\`.* TO \`$MYSQL_USER\`@\`%\`; FLUSH PRIVILEGES;"'

log "恢复文件目录（$BLOB_VOLUME）"
docker run --rm -i -v "$BLOB_VOLUME:/data" "$QF_HELPER_IMAGE" sh -c 'find /data -mindepth 1 -delete && tar -xf - -C /data && chown -R 10001:0 /data' < "$source_dir/blobs.tar"

log "启动全部服务（Flyway 只会执行备份之后新增的迁移）"
compose up -d --no-build --wait
log "恢复完成。按 docs/ops/01-deploy.md 第 6 节检查：登录、打开题库、作答与历史、广场下载。"

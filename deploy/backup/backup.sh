#!/usr/bin/env bash
# 备份 MySQL 与内容寻址文件目录（包含作者上传的原始 .qbank），校验后保留最近 N 份。可以在服务运行时执行。
# 用法与 cron 设置：docs/ops/01-deploy.md 第 6 节。
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=deploy/backup/common.sh
. "$here/common.sh"
require_stream_tools

stamp="$(date '+%Y%m%d-%H%M%S')"
target="$QF_BACKUP_DIR/$stamp"
work="$QF_BACKUP_DIR/.incomplete-$stamp"
mkdir -p "$work"
chmod 700 "$QF_BACKUP_DIR" "$work"
# 失败时不留下半份备份；保留策略只看完整的目录。
trap 'rm -rf "$work"' EXIT

# 先导出数据库，再打包文件：文件只增不改，这样数据库引用的文件一定都在备份中。
log "导出 MySQL（--single-transaction：不阻塞写入，得到一致快照）"
compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --quick --routines --triggers --hex-blob --no-tablespaces --set-gtid-purged=OFF --databases "$MYSQL_DATABASE"' \
  | gzip -c > "$work/mysql.sql.gz"
# 上面的管道在 pipefail 下已检查 mysqldump 的退出码。

log "打包文件目录（$BLOB_VOLUME，不含临时文件）"
docker run --rm -v "$BLOB_VOLUME:/data:ro" "$QF_HELPER_IMAGE" tar -cf - -C /data --exclude=./.tmp . > "$work/blobs.tar"

blob_files="$(count_blobs "$work/blobs.tar")"
users="$(compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -N -e "SELECT COUNT(*) FROM account_user" "$MYSQL_DATABASE"' | tr -d '\r')"
migration="$(compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -N -e "SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success = 1" "$MYSQL_DATABASE"' | tr -d '\r')"
{
  echo "created=$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  echo "project=$QF_COMPOSE_PROJECT"
  echo "flyway_version=$migration"
  echo "users=$users"
  echo "blob_files=$blob_files"
} > "$work/MANIFEST"
(cd "$work" && sha256sum mysql.sql.gz blobs.tar MANIFEST > SHA256SUMS)

log "校验备份"
verify_backup "$work"
mv "$work" "$target"
trap - EXIT
log "备份完成：$target（$(du -sh "$target" | cut -f1)，账号 $users，文件 $blob_files，迁移版本 $migration）"

# 保留最近 N 份：目录名按时间排序。只删除符合命名规则的目录。
count=0
for dir in $(find "$QF_BACKUP_DIR" -mindepth 1 -maxdepth 1 -type d -name '[0-9]*-[0-9]*' | sort -r); do
  count=$((count + 1))
  if [ "$count" -gt "$QF_BACKUP_KEEP" ]; then
    log "删除旧备份：$dir"
    rm -rf "$dir"
  fi
done

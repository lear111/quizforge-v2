# backup.sh、verify.sh、restore.sh 共用。配置来自环境变量（见 docs/ops/01-deploy.md 第 6 节）：
#   QF_COMPOSE_ARGS     docker compose 的参数，例如 "--env-file /etc/quizforge/prod.env -f deploy/docker-compose.yml -f deploy/docker-compose.prod.yml"
#   QF_COMPOSE_PROJECT  Compose 项目名（生产默认 quizforge；数据卷名为 <项目名>_blobs）
#   QF_BACKUP_DIR       备份目录
#   QF_BACKUP_KEEP      保留最近几份（默认 7）
# 所有文件都通过标准输入输出传给容器，不挂载宿主机目录；密码只在容器内使用，不出现在命令行和日志中。

QF_COMPOSE_PROJECT="${QF_COMPOSE_PROJECT:-quizforge}"
QF_BACKUP_DIR="${QF_BACKUP_DIR:-/var/backups/quizforge}"
QF_BACKUP_KEEP="${QF_BACKUP_KEEP:-7}"
QF_HELPER_IMAGE="${QF_HELPER_IMAGE:-alpine:3.20}"
BLOB_VOLUME="${QF_COMPOSE_PROJECT}_blobs"
# Windows 的 Git Bash 会改写以 / 开头的参数；Linux 上这个变量没有作用。
export MSYS_NO_PATHCONV=1

compose() {
  # shellcheck disable=SC2086 # QF_COMPOSE_ARGS 是多个参数。
  docker compose -p "$QF_COMPOSE_PROJECT" ${QF_COMPOSE_ARGS:-} "$@"
}

log() {
  printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"
}

fail() {
  printf '[%s] 失败：%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" >&2
  exit 1
}

require_stream_tools() {
  for tool in docker sha256sum gzip tar; do
    command -v "$tool" >/dev/null 2>&1 || fail "缺少命令 $tool"
  done
}

# 归档中内容寻址文件的数量。从标准输入读：Windows 路径中的冒号会被 tar 当成远程主机。
count_blobs() {
  tar -tf - < "$1" | grep -cE '^\./sha256/.+[0-9a-f]{64}$' || true
}

# 校验一份备份：文件校验和、gzip 完整性、SQL 导出完整、文件内容与文件名（SHA-256）一致。
verify_backup() {
  local dir="$1" full="${2:-sample}"
  [ -f "$dir/MANIFEST" ] || fail "$dir 不是一份完整的备份（没有 MANIFEST）"
  (cd "$dir" && sha256sum -c --quiet SHA256SUMS) || fail "校验和不符：$dir"
  gzip -t "$dir/mysql.sql.gz" || fail "mysql.sql.gz 已损坏"
  # 不用 grep -q：它提前退出时 gzip 收到 SIGPIPE，在 pipefail 下会把好的备份判为失败。
  local tables
  tables="$(gzip -dc "$dir/mysql.sql.gz" | grep -c '^CREATE TABLE `flyway_schema_history`' || true)"
  [ "$tables" = "1" ] || fail "SQL 导出中没有 flyway_schema_history"
  gzip -dc "$dir/mysql.sql.gz" | tail -n 1 | grep -q 'Dump completed' || fail "SQL 导出不完整（没有结束标记）"
  local expected actual
  expected="$(sed -n 's/^blob_files=//p' "$dir/MANIFEST")"
  actual="$(count_blobs "$dir/blobs.tar")"
  [ "$expected" = "$actual" ] || fail "文件数量不符：清单 $expected，归档 $actual"
  # 内容寻址：文件名就是内容的 SHA-256。sample 只抽查，full 全部检查。
  local limit=20
  [ "$full" = "full" ] && limit=100000000
  docker run --rm -i "$QF_HELPER_IMAGE" sh -c '
    limit="$1"; mkdir /restore && tar -xf - -C /restore || exit 1
    checked=0; bad=0
    for file in $(find /restore/sha256 -type f | head -n "$limit"); do
      name="$(basename "$file")"
      sum="$(sha256sum "$file" | cut -d" " -f1)"
      [ "$name" = "$sum" ] || { echo "内容与文件名不符：$name" >&2; bad=$((bad+1)); }
      checked=$((checked+1))
    done
    echo "已检查 $checked 个文件"
    [ "$bad" = 0 ]' sh "$limit" < "$dir/blobs.tar" || fail "文件内容校验失败：$dir"
}

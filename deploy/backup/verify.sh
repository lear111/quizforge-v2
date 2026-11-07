#!/usr/bin/env bash
# 校验一份备份。默认抽查文件内容，加 --full 检查全部文件（复制到异地后建议做一次）。
#   deploy/backup/verify.sh /var/backups/quizforge/20261105-030000 [--full]
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=deploy/backup/common.sh
. "$here/common.sh"
require_stream_tools
[ $# -ge 1 ] || fail "用法：verify.sh <备份目录> [--full]"
mode=sample
[ "${2:-}" = "--full" ] && mode=full
verify_backup "$1" "$mode"
log "校验通过：$1"
cat "$1/MANIFEST"

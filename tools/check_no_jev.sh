#!/usr/bin/env bash
# Sprint 5 门禁（D1）：Jev 判断模式已彻底删除，主源码集里不得再有它的引用。
# 检查项：JudgeClient / JevClient / JevQuestions 三个已删类的名字，以及 judge( 调用。
# 排除 core/Prefs.kt：那里按决策保留了 deprecated 的 judge* 兼容字段与 K_JUDGE_*
# 存储键（防老版本数据崩溃；effectiveReplyKey 仍以 judgeKey 兜底老数据）。
#
# 用法：bash tools/check_no_jev.sh   # 退出码 0 = PASS，1 = FAIL
set -u
cd "$(dirname "$0")/.."

hits="$(grep -rnE 'JudgeClient|JevClient|JevQuestions|judge\(' app/src/main/java \
  | grep -v 'core/Prefs\.kt' || true)"

if [ -n "$hits" ]; then
  echo "FAIL: 主源码集仍有 Jev 判断模式残留引用："
  echo "$hits"
  exit 1
fi

echo "PASS: app/src/main/java 无 JudgeClient/JevClient/JevQuestions/judge( 残留（Prefs.kt 兼容字段除外）"
exit 0

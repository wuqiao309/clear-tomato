#!/usr/bin/env bash
#
# 开发循环的快捷方式。省掉每次手敲 build / install / force-stop / 抓日志 那几条命令。
#
#   ./scripts/dev.sh deploy fq     构建 + 装模块 + 重启番茄 + 看日志
#   ./scripts/dev.sh deploy hg     同上，红果
#   ./scripts/dev.sh deploy yp     同上，智行
#   ./scripts/dev.sh deploy both   番茄和红果都来一遍
#   ./scripts/dev.sh build         只构建
#   ./scripts/dev.sh log           只看日志（不重启任何东西）
#   ./scripts/dev.sh ui            把当前页面上的文字按位置打出来
#   ./scripts/dev.sh shot          截图到 tools/work/shots/
#
# 需要 Git Bash。ADB 走 tools/platform-tools 里那份，不依赖 PATH。

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADB="$ROOT/tools/platform-tools/adb.exe"
export JAVA_HOME="${JAVA_HOME:-C:\\Program Files\\Java\\jdk-21}"

FANQIE="com.dragon.read"
HONGGUO="com.phoenix.read"
YIPIAO="com.yipiao"

# 模块在目标 app 里装的规则会打到 logcat，tag 固定是 ClearApp/<进程名>。
LOG_TAG="ClearApp/"

build() {
    echo "== 构建 =="
    (cd "$ROOT" && ./gradlew.bat --no-daemon assembleRelease -q)
}

install_module() {
    echo "== 安装模块 =="
    "$ADB" install -r "$ROOT/app/build/outputs/apk/release/app-release.apk" | tail -1
}

# 等规则挂上。实测从启动到打完「本轮共挂载」只要 1~2 秒（主界面 2.1 秒就绪），
# 所以别用固定 sleep —— 轮询日志，通常一两秒就返回。
wait_rules_mounted() {
    local deadline=$((SECONDS + 30))
    while [ $SECONDS -lt $deadline ]; do
        "$ADB" logcat -d 2>/dev/null | grep -q "本轮共挂载" && return 0
        sleep 0.3
    done
    echo "等 30 秒没等到「本轮共挂载」，看看 log 子命令的输出" >&2
}

# 装完模块后框架需要一点时间重新读；这里等一下再重启目标 app，省得踩「第一次冷启跑旧版本」的坑。
restart() {
    local pkg="$1"
    sleep 2
    "$ADB" shell am force-stop "$pkg"
    sleep 1
    "$ADB" logcat -c
    "$ADB" shell monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    wait_rules_mounted
}

# 只挑出有诊断价值的行：挂上了什么、哪条失效了、哪条触发了。
log() {
    "$ADB" logcat -d 2>&1 \
        | grep "$LOG_TAG" \
        | sed 's/.*ClearApp\/[^:]*: *//' \
        | grep -v '^com\.clearapp' \
        | sed 's/^io\.github\.wuqiao309\.clearapp: //' \
        | sort -u
}

ui() {
    local tmp
    tmp="$(mktemp -d)"
    # 界面起来后 uiautomator 要 8 秒左右才拿得到；这里试几次，别一上来就报错。
    local ok=0 i
    for i in $(seq 1 12); do
        if "$ADB" shell uiautomator dump /sdcard/ui.xml 2>&1 | grep -q "dumped to"; then ok=1; break; fi
        sleep 1
    done
    if [ $ok -eq 0 ]; then
        echo "抓取失败：如果正在播视频，先暂停再试" >&2
        exit 1
    fi
    "$ADB" pull /sdcard/ui.xml "$tmp/ui.xml" >/dev/null 2>&1
    # 每行一个「y 坐标 / x 坐标 / 文字」，按 y 排序 —— 调位置比翻 XML 快得多
    tr '>' '>\n' <"$tmp/ui.xml" \
        | grep -oE 'text="[^"]+"[^>]*bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' \
        | sed -E 's/text="([^"]+)".*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]"/\3\t\2\t\1/' \
        | sort -n
    rm -rf "$tmp"
}

shot() {
    local out="$ROOT/tools/work/shots"
    mkdir -p "$out"
    "$ADB" shell screencap -p /sdcard/dev-shot.png
    "$ADB" pull /sdcard/dev-shot.png "$out/" >/dev/null 2>&1
    echo "$out/dev-shot.png"
}

usage() {
    cat <<'EOF'
开发循环的快捷方式。省掉每次手敲 build / install / force-stop / 抓日志 那几条命令。

  ./scripts/dev.sh deploy fq     构建 + 装模块 + 重启番茄 + 看日志
  ./scripts/dev.sh deploy hg     同上，红果
  ./scripts/dev.sh deploy yp     同上，智行
  ./scripts/dev.sh deploy both   番茄和红果都来一遍
  ./scripts/dev.sh build         只构建
  ./scripts/dev.sh log           只看日志（不重启任何东西）
  ./scripts/dev.sh ui            把当前页面上的文字按位置打出来
  ./scripts/dev.sh shot          截图到 tools/work/shots/

需要 Git Bash。ADB 走 tools/platform-tools 里那份，不依赖 PATH。
EOF
}

case "${1:-help}" in
    build)  build ;;
    deploy)
        target="${2:-fq}"
        build
        install_module
        case "$target" in
            fq)   restart "$FANQIE";  log ;;
            hg)   restart "$HONGGUO"; log ;;
            yp)   restart "$YIPIAO";  log ;;
            both) restart "$FANQIE";  log; echo; restart "$HONGGUO"; log ;;
            *)    echo "用法: $0 deploy [fq|hg|yp|both]" >&2; exit 2 ;;
        esac
        ;;
    log)  log ;;
    ui)   ui ;;
    shot) shot ;;
    help|*) usage ;;
esac

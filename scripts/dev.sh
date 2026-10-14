#!/usr/bin/env bash
#
# 开发循环的快捷方式。省掉每次手敲 build / install / force-stop / 抓日志 那几条命令。
#
#   ./scripts/dev.sh deploy fq     构建 + 装模块 + 重启番茄 + 看日志
#   ./scripts/dev.sh deploy hg     同上，红果
#   ./scripts/dev.sh deploy both   两个都来一遍
#   ./scripts/dev.sh build         只构建
#   ./scripts/dev.sh log           只看日志（不重启任何东西）
#   ./scripts/dev.sh smoke fq      冒烟：断言番茄界面该在的在、该没的没了
#   ./scripts/dev.sh smoke hg      同上，红果
#   ./scripts/dev.sh ui            把当前页面上的文字按位置打出来
#   ./scripts/dev.sh shot          截图到 tools/work/shots/
#
# 需要 Git Bash。ADB 走 tools/platform-tools 里那份，不依赖 PATH。

set -euo pipefail

# 必须关掉 Git Bash 的路径自动转换：不然它会把手敲的 /sdcard/xxx 变成
# C:/Program Files/Git/sdcard/xxx 再传给 adb，一切涉及设备端路径的命令都会失败。
export MSYS_NO_PATHCONV=1

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADB="$ROOT/tools/platform-tools/adb.exe"
export JAVA_HOME="${JAVA_HOME:-C:\\Program Files\\Java\\jdk-21}"

# ---- 单实例锁 ----------------------------------------------------------------
#
# 血泪教训：有一次一个挂住的后台任务被反复调用，炸出 **1545 个** dev.sh 进程同时在跑，
# 每个都在 force-stop + 拉起目标 app —— 手机上就是「应用不停地自己切换」。
# mkdir 是原子的，拿它当锁；同一时间只允许一个实例。
LOCK_DIR="$ROOT/tools/work/.dev-running"
mkdir -p "$ROOT/tools/work"
if ! mkdir "$LOCK_DIR" 2>/dev/null; then
    # 锁超过 10 分钟当成陈旧的（上次被强杀之类的），清掉重来
    if [ -n "$(find "$LOCK_DIR" -maxdepth 0 -mmin +10 2>/dev/null)" ]; then
        echo "发现超过 10 分钟的旧锁，清掉重来" >&2
        rm -rf "$LOCK_DIR"
        mkdir "$LOCK_DIR" 2>/dev/null || { echo "抢锁失败，退出" >&2; exit 1; }
    else
        echo "已经有一个 dev.sh 在跑，这次直接退出（避免叠着 force-stop / 拉起同一个 app）" >&2
        exit 1
    fi
fi
trap 'rm -rf "$LOCK_DIR"' EXIT

# ---- adb 包装 ----------------------------------------------------------------
#
# 脚本绝不能挂死在一条 adb 上：设备掉线时 adb 会一直等，脚本就永远不退出。
# 需要更长的（比如 install）单独用 ADB_LONG_TIMEOUT。
# 注意用 "$ADB"（变量）而不是 adb —— 前者是路径，后者会被解析成这个函数自己，无限递归。
adb() { timeout "${ADB_TIMEOUT:-60}" "$ADB" "$@"; }
adb_long() { timeout "${ADB_LONG_TIMEOUT:-300}" "$ADB" "$@"; }

FANQIE="com.dragon.read"
HONGGUO="com.phoenix.read"

# 模块在目标 app 里装的规则会打到 logcat，tag 固定是 ClearApp/<进程名>。
LOG_TAG="ClearApp/"

build() {
    echo "== 构建 =="
    # 用 Gradle daemon：实测改一个文件后热 daemon 只要 2 秒，--no-daemon 要 10 秒。
    (cd "$ROOT" && ./gradlew.bat assembleRelease -q)
}

install_module() {
    echo "== 安装模块 =="
    adb_long install -r "$ROOT/app/build/outputs/apk/release/app-release.apk" | tail -1
}

# 等规则挂上。实测从启动到打完「本轮共挂载」只要 1~2 秒（主界面 2.1 秒就绪），
# 所以别用固定 sleep —— 轮询日志，通常一两秒就返回。
wait_rules_mounted() {
    local deadline=$((SECONDS + 30))
    while [ $SECONDS -lt $deadline ]; do
        adb logcat -d 2>/dev/null | grep -q "本轮共挂载" && return 0
        sleep 0.3
    done
    echo "等 30 秒没等到「本轮共挂载」，看看 log 子命令的输出" >&2
}

# 装完模块后框架需要一点时间重新读；这里等一下再重启目标 app，省得踩「第一次冷启跑旧版本」的坑。
restart() {
    local pkg="$1"
    sleep 2
    adb shell am force-stop "$pkg"
    sleep 1
    adb logcat -c
    adb shell monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    wait_rules_mounted
}

# 只挑出有诊断价值的行：挂上了什么、哪条失效了、哪条触发了。
log() {
    adb logcat -d 2>&1 \
        | grep "$LOG_TAG" \
        | sed 's/.*ClearApp\/[^:]*: *//' \
        | grep -v '^com\.clearapp' \
        | sed 's/^io\.github\.wuqiao309\.clearapp: //' \
        | sort -u
}

# 界面 XML 落盘的位置。放项目里：adb.exe 是 Windows 程序，既写不进 Git Bash 的 /tmp/...，
# 也要 cygpath 转过的形式才认。
UI_XML="$ROOT/tools/work/ui-dump.xml"

# 抓一次界面存下来。一次 dump 要 8 秒左右，所以只抓一次、后面都读同一份。
dump_ui() {
    local ok=0 i
    mkdir -p "$ROOT/tools/work"
    rm -f "$UI_XML"
    # uiautomator 在播放中的视频上拿不到 idle，会失败 —— 多试几次
    for i in $(seq 1 12); do
        if adb shell uiautomator dump /sdcard/ui.xml 2>&1 | grep -q "dumped to"; then ok=1; break; fi
        sleep 1
    done
    if [ $ok -eq 0 ]; then
        # 红果首页是自动播放的视频，uiautomator 要等界面 idle，播放中永远等不到。
        # 番茄的书城/书架是列表，没有这个问题。
        echo "抓不到界面：页面在播视频时 uiautomator 拿不到 idle。把它暂停了再跑。" >&2
        return 1
    fi
    if ! adb pull /sdcard/ui.xml "$(cygpath -w "$UI_XML")" >/dev/null 2>&1 || [ ! -f "$UI_XML" ]; then
        echo "拉取界面 XML 失败" >&2
        return 1
    fi
}

# 读上一次 dump 的结果，每行「y1 x1 x2 y2 文字 resource-id」，按 y 排序。
nodes() {
    grep -oE '<node[^>]*text="[^"]+"[^>]*>' "$UI_XML" \
        | sed -E -n 's/.*text="([^"]+)".*resource-id="([^"]*)".*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\4\t\3\t\6\t\5\t\1\t\2/p' \
        | sort -n
}

# dump 出去的是哪个 app —— 用来防止「目标 app 还没切到前台就断言」
dumped_package() {
    grep -oE 'package="[^"]+"' "$UI_XML" 2>/dev/null | head -1 | sed 's/package="//;s/"//'
}

ui() {
    # 调位置时看这个比翻 XML 快得多
    dump_ui && nodes | awk -F'\t' '{printf "y=%-5s x=%-5s %s\n", $1, $2, $5}'
}

# 底栏 tab 文字的 resource-id 后缀。
#
# 一开始按 y 坐标筛（底栏在最下面），但**内容能延伸到同一个 y**，分不干净；用 resource-id
# 才准 —— 底栏那几个 label 共用这一个 id。它是混淆名，升级后可能变，变了冒烟会报
# 「底栏一个 tab 都没读到」，改这里即可。
TAB_ID_SUFFIX=":id/g_r"

# 底栏上的文字，按从左到右排
bottom_tab_texts() {
    nodes | awk -F'\t' -v s="$TAB_ID_SUFFIX" '$6 ~ s {print $2 "\t" $5}' | sort -n | cut -f2 | tr '\n' ' ' | sed 's/ *$//'
}

# 点底栏上叫 $1 的那个 tab（用当前这份 dump 找坐标）
tap_bottom_tab() {
    local label="$1" coords
    coords="$(nodes | awk -F'\t' -v s="$TAB_ID_SUFFIX" -v l="$label" \
        '$6 ~ s && $5 == l {printf "%d %d", ($2+$3)/2, ($1+$4)/2; exit}')"
    if [ -z "$coords" ]; then
        echo "底栏上没找到「$label」" >&2
        return 1
    fi
    adb shell input tap $coords >/dev/null 2>&1
    sleep 5
}

# 断言底栏内容。读不到任何 tab 时单独报出来，免得和「tab 不对」混为一谈。
check_bottom_tabs() {
    local what="$1" expected="$2" actual
    actual="$(bottom_tab_texts)"
    if [ -z "$actual" ]; then
        echo "  ✗ $what：底栏一个 tab 都没读到 —— resource-id 后缀 $TAB_ID_SUFFIX 可能变了" >&2
        smoke_fail=1
        return
    fi
    check_equals "$what" "$expected" "$actual"
}

smoke_fail=0

check_equals() {
    local what="$1" expected="$2" actual="$3"
    if [ "$expected" = "$actual" ]; then
        echo "  ✓ $what: $actual"
    else
        echo "  ✗ $what" >&2
        echo "      期望: $expected" >&2
        echo "      实际: $actual" >&2
        smoke_fail=1
    fi
}

# 当前这份 dump 里不该出现这些文字
check_absent() {
    local what="$1" forbidden="$2" present
    present="$(nodes | awk -F'\t' -v f="$forbidden" 'BEGIN{n=split(f,a," ")} {for(i=1;i<=n;i++) if($5==a[i]) print $5}' | sort -u | tr '\n' ' ' | sed 's/ *$//')"
    if [ -z "$present" ]; then
        echo "  ✓ $what: 都不在（$forbidden）"
    else
        echo "  ✗ $what: 还在 —— $present" >&2
        smoke_fail=1
    fi
}

# 抓一次界面，并确认抓到的确实是目标 app —— 目标 app 有时候要好几秒才切到前台，
# 抓成别人的界面会让断言给出莫名其妙的结论。
dump_target() {
    local pkg="$1" name="$2" i
    for i in 1 2 3; do
        dump_ui >/dev/null 2>&1 || return 1
        [ "$(dumped_package)" = "$pkg" ] && return 0
        echo "  抓到的不是 $name，等 3 秒重抓（第 $i 次）" >&2
        sleep 3
    done
    echo "  ✗ 前台一直不是 $name（抓到的是 $(dumped_package)），冒烟中止" >&2
    return 1
}

# 冒烟：重启目标 app，断言界面上该在的在、该没的没了。
#
# 为什么需要：规则「挂上了但不生效」时日志一切正常（这次发帖球就踩过），只有看界面才发现。
# 断言抓的是模块最显眼、升级后也最先断的部分 —— 底栏和子 tab。
smoke() {
    local app="${1:-fq}" pkg name
    case "$app" in
        fq) pkg="$FANQIE";  name=番茄 ;;
        hg) pkg="$HONGGUO"; name=红果 ;;
        *)  echo "用法: $0 smoke [fq|hg]" >&2; exit 2 ;;
    esac

    restart "$pkg"
    echo "== $name 冒烟 =="

    dump_target "$pkg" "$name" || { smoke_fail=1; return 1; }

    # 注意：红果首页是自动播放的视频，uiautomator 抓不到 —— 跑之前先手动暂停一下。
    # 番茄的底栏/子 tab 都在列表页上，没有这个问题，开箱即用。
    if [ "$app" = "fq" ]; then
        check_bottom_tabs "$name 底部主 tab" "书城 书架 我的"
        tap_bottom_tab 书城 && dump_ui >/dev/null 2>&1 && check_absent "$name 书城子 tab" "看剧 视频 商城"
        tap_bottom_tab 书架 && dump_ui >/dev/null 2>&1 && check_absent "$name 书架子 tab" "收藏"
    else
        check_bottom_tabs "$name 底部主 tab" "首页 剧场 我的"
    fi

    if [ $smoke_fail -eq 0 ]; then
        echo "== 通过 =="
    else
        echo "== 有问题，看上面 ✗ ==" >&2
        return 1
    fi
}

shot() {
    local out="$ROOT/tools/work/shots"
    mkdir -p "$out"
    adb shell screencap -p /sdcard/dev-shot.png
    # 同样要 cygpath：adb 是 Windows 程序，写不进 Git Bash 的 /c/... 形式
    adb pull /sdcard/dev-shot.png "$(cygpath -w "$out")/" >/dev/null 2>&1
    echo "$out/dev-shot.png"
}

usage() {
    cat <<'EOF'
开发循环的快捷方式。省掉每次手敲 build / install / force-stop / 抓日志 那几条命令。

  ./scripts/dev.sh deploy fq     构建 + 装模块 + 重启番茄 + 看日志
  ./scripts/dev.sh deploy hg     同上，红果
  ./scripts/dev.sh deploy both   两个都来一遍
  ./scripts/dev.sh build         只构建
  ./scripts/dev.sh log           只看日志（不重启任何东西）
  ./scripts/dev.sh smoke fq      冒烟：断言番茄界面该在的在、该没的没了
  ./scripts/dev.sh smoke hg      同上，红果
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
            both) restart "$FANQIE";  log; echo; restart "$HONGGUO"; log ;;
            *)    echo "用法: $0 deploy [fq|hg|both]" >&2; exit 2 ;;
        esac
        ;;
    log)  log ;;
    smoke) smoke "${2:-fq}" ;;
    ui)   ui ;;
    shot) shot ;;
    help|*) usage ;;
esac

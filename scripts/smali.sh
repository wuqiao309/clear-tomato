#!/usr/bin/env bash
#
# 把单个 dex 反汇编成 smali。
#
#   ./scripts/smali.sh <dex 文件> [输出目录]
#   ./scripts/smali.sh tools/work/apk/dex/classes6.dex
#
# 为什么留着这个：jadx **会静默漏类** —— 反编译失败时不报错，产物里就是没有那个类，
# 而别处引用它的代码还在。这个项目里踩过至少 4 次（`t76.i`、`FloatViewShowManager`、
# `BaseHomeTab`、`MainFragmentActivity`），每次都要绕路。
#
# smali 是直接反汇编，不会「反编译失败」，所以只要 dex 里有就一定能看到。
# 而且字段名、方法名都明明白白写在里面 —— 比如 hook 要找的静态字段，不用再从别处引用来猜。
#
# 单个 dex 几秒到几十秒；别对整个 APK 跑（那还不如直接 apktool d）。

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LIB="$ROOT/tools/baklib"
JAVA="${JAVA_HOME:-C:\\Program Files\\Java\\jdk-21}/bin/java.exe"

usage() {
    cat <<'EOF'
把单个 dex 反汇编成 smali。

  ./scripts/smali.sh <dex 文件> [输出目录]
  ./scripts/smali.sh tools/work/apk/dex/classes6.dex

为什么留着这个：jadx **会静默漏类** —— 反编译失败时不报错，产物里就是没有那个类，
而别处引用它的代码还在。这个项目里踩过至少 4 次（`t76.i`、`FloatViewShowManager`、
`BaseHomeTab`、`MainFragmentActivity`），每次都要绕路。

smali 是直接反汇编，不会「反编译失败」，所以只要 dex 里有就一定能看到。而且字段名、
方法名都明明白白写在里面 —— 比如 hook 要找的静态字段，不用再从别处引用来猜。

单个 dex 几秒到几十秒；别对整个 APK 跑（那还不如直接 apktool d）。
EOF
}

if [ $# -lt 1 ]; then
    usage
    exit 2
fi

DEX="$1"
OUT="${2:-${DEX%.dex}-smali}"

# java.exe 是 Windows 程序，认不了 Git Bash 的 /c/... 路径，得转成 C:\...
win() { cygpath -w "$1"; }

# baksmali 的 Maven 包不是 fat jar，依赖要一起摆在 classpath 上。
CP="$(win "$LIB/baksmali-2.5.2.jar");$(win "$LIB/dexlib2-2.5.2.jar");$(win "$LIB/util-2.5.2.jar");$(win "$LIB/guava-27.1-android.jar");$(win "$LIB/jcommander-1.64.jar")"

"$JAVA" -cp "$CP" org.jf.baksmali.Main d "$(win "$DEX")" -o "$(win "$OUT")"
echo "smali 输出到: $OUT"

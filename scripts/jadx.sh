#!/usr/bin/env bash
#
# 只反编译某一（几）个类，不整个 dex 跑。
#
#   ./scripts/jadx.sh <dex 或 apk> <类全名> [类全名...]
#   ./scripts/jadx.sh tools/work/apk/fq2-dex/classes3.dex com.dragon.read.NsCommonDepend
#
# 为什么要这个：整个 dex 反编译要一两分钟（番茄单个 dex 就 50MB 源码），
# 而 **-​-single-class 是瞬间的**。RE 的时候通常只是想看一两个类，没必要等全量。
#
# 输出在 tools/work/jadx-one/，同名类会覆盖。

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JADX_JAR="$ROOT/tools/jadx/lib/jadx-gui-1.5.6-all.jar"
JAVA="${JAVA_HOME:-C:\Program Files\Java\jdk-21}/bin/java.exe"
OUT="$ROOT/tools/work/jadx-one"

usage() {
    cat <<'TXT'
只反编译指定的类，不跑整个 dex。

  ./scripts/jadx.sh <dex 或 apk> <类全名> [类全名...]

整个 dex 反编译要一两分钟，--single-class 是瞬间的。RE 时通常只想看一两个类。
输出在 tools/work/jadx-one/。
TXT
}

if [ $# -lt 2 ]; then usage; exit 2; fi

INPUT="$1"; shift
for cls in "$@"; do
    "$JAVA" -Xmx2g -cp "$JADX_JAR" jadx.cli.JadxCLI \
        --no-res --single-class "$cls" --single-class-output "$OUT" "$INPUT" 2>&1 \
        | grep -E "Saving class|Class not found|ERROR" | head -3
done
echo "输出目录: $OUT"

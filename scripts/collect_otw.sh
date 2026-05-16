#!/usr/bin/env bash
# ══════════════════════════════════════════════════════
# OnTheWay collect_otw v1 — 디바이스 데이터 회수 + ledger 분석
# ══════════════════════════════════════════════════════
#
# 사용법: scripts/collect_otw.sh
# 출력:   scripts/output/otw_YYYY_MM_DD_HHMM/
#           ├── ledger.db
#           ├── call_logs.db
#           ├── diagnostic.db
#           ├── prefs/  (SharedPreferences)
#           ├── ledger_report.md
#           └── pull_log.txt
#
# ⚠ READ-ONLY MODE ⚠
# 디바이스 데이터를 읽기만 함. 수정/삭제 없음.
# ══════════════════════════════════════════════════════

set -euo pipefail

echo "══════════════════════════════════════════════"
echo "  collect_otw v1 — READ-ONLY MODE"
echo "══════════════════════════════════════════════"
echo ""

# ── 경로 설정 ──
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
OUTPUT_BASE="$SCRIPT_DIR/output"

TIMESTAMP=$(date "+%Y_%m_%d_%H%M")
OUTPUT_DIR="$OUTPUT_BASE/otw_$TIMESTAMP"
mkdir -p "$OUTPUT_DIR/prefs"

PULL_LOG="$OUTPUT_DIR/pull_log.txt"
: > "$PULL_LOG"

log() {
    echo "$1"
    echo "$1" >> "$PULL_LOG"
}

# ── ADB 자동 감지 ──
detect_adb() {
    # 1. 환경변수 OTW_ADB_PATH
    if [ -n "${OTW_ADB_PATH:-}" ] && [ -f "$OTW_ADB_PATH" ]; then
        echo "$OTW_ADB_PATH"
        return
    fi
    # 2. PATH에서 adb
    if command -v adb &>/dev/null; then
        command -v adb
        return
    fi
    # 3. 노트북 (C:\platform-tools)
    if [ -f "C:/platform-tools/adb.exe" ]; then
        echo "C:/platform-tools/adb.exe"
        return
    fi
    # 4. 데스크탑 (Android SDK)
    if [ -f "C:/Users/$USER/AppData/Local/Android/Sdk/platform-tools/adb.exe" ]; then
        echo "C:/Users/$USER/AppData/Local/Android/Sdk/platform-tools/adb.exe"
        return
    fi
    # 5. ANDROID_HOME
    if [ -n "${ANDROID_HOME:-}" ] && [ -f "$ANDROID_HOME/platform-tools/adb.exe" ]; then
        echo "$ANDROID_HOME/platform-tools/adb.exe"
        return
    fi
    echo ""
}

ADB=$(detect_adb)
if [ -z "$ADB" ]; then
    log "[ERROR] ADB not found. Set OTW_ADB_PATH or install Android SDK."
    exit 1
fi
log "[ADB] $ADB"

# ── 디바이스 확인 ──
if ! "$ADB" devices 2>/dev/null | grep -q "device$"; then
    log "[ERROR] No Android device connected."
    exit 1
fi
DEVICE=$("$ADB" devices | grep "device$" | head -1 | awk '{print $1}')
log "[DEVICE] $DEVICE"

PKG="com.vita.ontheway"

# ── Python 자동 감지 ──
detect_python() {
    if command -v python3 &>/dev/null; then echo "python3"; return; fi
    if command -v python &>/dev/null; then echo "python"; return; fi
    if command -v py &>/dev/null; then echo "py"; return; fi
    echo ""
}
PYTHON=$(detect_python)

# ══════════════════════════════════════════════════════
# 1. DB 파일 회수
# ══════════════════════════════════════════════════════
log ""
log "[1/4] DB 파일 회수..."

pull_db() {
    local db_name="$1"
    local dest="$OUTPUT_DIR/$db_name"
    log "  pulling $db_name..."
    if "$ADB" exec-out run-as "$PKG" cat "databases/$db_name" > "$dest" 2>>"$PULL_LOG"; then
        local size
        size=$(stat -c%s "$dest" 2>/dev/null || wc -c < "$dest" | tr -d ' ')
        if [ "$size" -gt 0 ] 2>/dev/null; then
            log "  ✓ $db_name (${size} bytes)"
            return 0
        else
            log "  ✗ $db_name (empty)"
            rm -f "$dest"
            return 1
        fi
    else
        log "  ✗ $db_name (pull failed)"
        rm -f "$dest"
        return 1
    fi
}

LEDGER_OK=false
CALLLOG_OK=false
DIAG_OK=false

pull_db "ledger.db" && LEDGER_OK=true
pull_db "ledger.db-wal" || true
pull_db "ledger.db-shm" || true
pull_db "call_logs.db" && CALLLOG_OK=true
pull_db "call_logs.db-wal" || true
pull_db "diagnostic.db" && DIAG_OK=true

# ══════════════════════════════════════════════════════
# 2. SharedPreferences 회수
# ══════════════════════════════════════════════════════
log ""
log "[2/4] SharedPreferences 회수..."

pull_pref() {
    local pref_name="$1"
    local dest="$OUTPUT_DIR/prefs/$pref_name"
    if "$ADB" exec-out run-as "$PKG" cat "shared_prefs/$pref_name" > "$dest" 2>/dev/null; then
        local size
        size=$(stat -c%s "$dest" 2>/dev/null || wc -c < "$dest" | tr -d ' ')
        if [ "$size" -gt 0 ] 2>/dev/null; then
            log "  ✓ $pref_name"
            return 0
        fi
    fi
    rm -f "$dest"
    return 1
}

pull_pref "ontheway.xml" || true
pull_pref "earning.xml" || true
pull_pref "earnings_tracker.xml" || true
pull_pref "shadow_log.xml" || true
pull_pref "filter_log.xml" || true
pull_pref "advanced_prefs.xml" || true

# ══════════════════════════════════════════════════════
# 3. OtwFileLogger 로그 회수
# ══════════════════════════════════════════════════════
log ""
log "[3/4] 로그 파일 회수..."

# OtwFileLogger는 files/ 디렉토리에 저장
LOG_LIST=$("$ADB" exec-out run-as "$PKG" ls files/ 2>/dev/null || echo "")
if [ -n "$LOG_LIST" ]; then
    mkdir -p "$OUTPUT_DIR/logs"
    while IFS= read -r fname; do
        if [ -n "$fname" ]; then
            "$ADB" exec-out run-as "$PKG" cat "files/$fname" > "$OUTPUT_DIR/logs/$fname" 2>/dev/null || true
        fi
    done <<< "$LOG_LIST"
    FILE_COUNT=$(ls "$OUTPUT_DIR/logs/" 2>/dev/null | wc -l | tr -d ' ')
    log "  ✓ $FILE_COUNT log files"
else
    log "  (no log files)"
fi

# ══════════════════════════════════════════════════════
# 4. Ledger 분석
# ══════════════════════════════════════════════════════
log ""
log "[4/4] Ledger 분석..."

if [ "$LEDGER_OK" = true ] && [ -n "$PYTHON" ]; then
    ANALYZE_SCRIPT="$SCRIPT_DIR/analyze_ledger.py"
    if [ -f "$ANALYZE_SCRIPT" ]; then
        LEDGER_REPORT="$OUTPUT_DIR/ledger_report.md"
        "$PYTHON" "$ANALYZE_SCRIPT" "$OUTPUT_DIR/ledger.db" "$LEDGER_REPORT" 2>>"$PULL_LOG" || {
            log "  ✗ analyze_ledger.py 실패"
        }
        if [ -f "$LEDGER_REPORT" ]; then
            log "  ✓ ledger_report.md 생성"
        fi
    else
        log "  ✗ analyze_ledger.py not found"
    fi
elif [ "$LEDGER_OK" = false ]; then
    log "  (ledger.db 없음 — 분석 스킵)"
else
    log "  (Python 없음 — 분석 스킵)"
fi

# ══════════════════════════════════════════════════════
# 요약
# ══════════════════════════════════════════════════════
echo ""
echo "══════════════════════════════════════════════"
echo "  collect_otw v1 완료"
echo ""
echo "  출력: $OUTPUT_DIR"
echo ""
echo "  DB:"
echo "    ledger.db:     $([ "$LEDGER_OK" = true ] && echo "✓" || echo "✗")"
echo "    call_logs.db:  $([ "$CALLLOG_OK" = true ] && echo "✓" || echo "✗")"
echo "    diagnostic.db: $([ "$DIAG_OK" = true ] && echo "✓" || echo "✗")"
echo ""
if [ -f "$OUTPUT_DIR/ledger_report.md" ]; then
    echo "  Ledger Report: $OUTPUT_DIR/ledger_report.md"
fi
echo "══════════════════════════════════════════════"

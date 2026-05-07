#!/usr/bin/env bash
# ══════════════════════════════════════════════════════
# OnTheWay DevOps Agent v0 — 일일 마감 자동화
# ══════════════════════════════════════════════════════
#
# 사용법: scripts/devops_daily.sh
# 출력:   scripts/output/daily_report.md
#         scripts/output/codex_review_prompt.txt
#         scripts/output/build_result.txt
#         scripts/output/test_result.txt
#
# ⚠ READ-ONLY MODE ⚠
# 이 스크립트는 절대로:
#   - git commit / push 하지 않음
#   - APK 설치하지 않음
#   - 코드 수정하지 않음
#   - 배포하지 않음
# 모든 결정 = 대표 수동
# ══════════════════════════════════════════════════════

set -euo pipefail

echo "══════════════════════════════════════════════"
echo "  OnTheWay DevOps Agent v0 — READ-ONLY MODE"
echo "  자동 commit/push/설치/수정 = 절대 X"
echo "══════════════════════════════════════════════"
echo ""

# ── 경로 설정 ──
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
OUTPUT_DIR="$SCRIPT_DIR/output"
mkdir -p "$OUTPUT_DIR"

cd "$PROJECT_DIR"

# JAVA_HOME 자동 감지
if [ -z "${JAVA_HOME:-}" ]; then
    if [ -d "C:/Program Files/Android/Android Studio/jbr" ]; then
        export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
    fi
fi

TIMESTAMP=$(date "+%Y-%m-%d %H:%M")
DATE_SHORT=$(date "+%Y%m%d")

REPORT="$OUTPUT_DIR/daily_report.md"
CODEX_PROMPT="$OUTPUT_DIR/codex_review_prompt.txt"
BUILD_LOG="$OUTPUT_DIR/build_result.txt"
TEST_LOG="$OUTPUT_DIR/test_result.txt"

# ── 헬퍼 함수 ──
count_lines() {
    local file="$1"
    if [ -f "$file" ]; then
        wc -l < "$file" | tr -d ' '
    else
        echo "0"
    fi
}

# ══════════════════════════════════════════════════════
# 1. Git 정보 수집
# ══════════════════════════════════════════════════════
echo "[1/9] git status..."
GIT_STATUS=$(git status --short 2>/dev/null || echo "(git 없음)")
GIT_BRANCH=$(git branch --show-current 2>/dev/null || echo "unknown")

echo "[2/9] git log..."
GIT_LOG=$(git log --oneline -10 2>/dev/null || echo "(커밋 없음)")
COMMIT_COUNT=$(git log --oneline --since="midnight" 2>/dev/null | wc -l | tr -d ' ')

echo "[7/9] git diff..."
GIT_DIFF_STAT=$(git diff --stat HEAD~1 HEAD 2>/dev/null || echo "(diff 없음)")
CHANGED_FILES=$(git diff --name-only HEAD~1 HEAD 2>/dev/null || echo "")
CHANGED_COUNT=$(echo "$CHANGED_FILES" | grep -c "." 2>/dev/null || echo "0")

# LOC 계산
LOC_ADDED=$(git diff --numstat HEAD~1 HEAD 2>/dev/null | awk '{s+=$1} END {print s+0}')
LOC_DELETED=$(git diff --numstat HEAD~1 HEAD 2>/dev/null | awk '{s+=$2} END {print s+0}')

# ══════════════════════════════════════════════════════
# 3. 단위 테스트
# ══════════════════════════════════════════════════════
echo "[3/9] gradlew test..."
TEST_PASS=0
TEST_FAIL=0
TEST_TOTAL=0
TEST_STATUS="UNKNOWN"

if ./gradlew testDebugUnitTest > "$TEST_LOG" 2>&1; then
    TEST_STATUS="PASS"
else
    TEST_STATUS="FAIL"
fi

# 테스트 카운트 추출 (Gradle 출력 또는 XML 리포트)
TEST_SUMMARY=$(grep -E "[0-9]+ tests completed" "$TEST_LOG" 2>/dev/null | tail -1 || echo "")
if [ -n "$TEST_SUMMARY" ]; then
    TEST_TOTAL=$(echo "$TEST_SUMMARY" | grep -oE "^[0-9]+" || echo "0")
    TEST_FAIL=$(echo "$TEST_SUMMARY" | grep -oE "[0-9]+ failed" | grep -oE "^[0-9]+" || echo "0")
    TEST_PASS=$((TEST_TOTAL - TEST_FAIL))
fi
# fallback: XML 리포트에서 카운트 (UP-TO-DATE 캐시 시)
if [ "$TEST_TOTAL" -eq 0 ] 2>/dev/null; then
    XML_DIR="app/build/test-results/testDebugUnitTest"
    if [ -d "$XML_DIR" ]; then
        TEST_TOTAL=$(grep -h 'tests=' "$XML_DIR"/*.xml 2>/dev/null | grep -oE 'tests="[0-9]+"' | grep -oE '[0-9]+' | awk '{s+=$1} END {print s+0}')
        TEST_FAIL=$(grep -h 'failures=' "$XML_DIR"/*.xml 2>/dev/null | grep -oE 'failures="[0-9]+"' | grep -oE '[0-9]+' | awk '{s+=$1} END {print s+0}')
        TEST_PASS=$((TEST_TOTAL - TEST_FAIL))
    fi
fi

# 실패 시 핵심 라인
TEST_FAIL_LINES=""
if [ "$TEST_STATUS" = "FAIL" ]; then
    TEST_FAIL_LINES=$(grep -A3 "FAILED" "$TEST_LOG" | head -20 || echo "")
fi

# ══════════════════════════════════════════════════════
# 4. 빌드
# ══════════════════════════════════════════════════════
echo "[4/9] gradlew assembleDebug..."
BUILD_STATUS="UNKNOWN"

if ./gradlew assembleDebug > "$BUILD_LOG" 2>&1; then
    BUILD_STATUS="PASS"
else
    BUILD_STATUS="FAIL"
fi

# 실패 시 핵심
BUILD_FAIL_LINES=""
if [ "$BUILD_STATUS" = "FAIL" ]; then
    BUILD_FAIL_LINES=$(grep -B2 -A3 "BUILD FAILED\|error:" "$BUILD_LOG" | head -20 || echo "")
fi

# ══════════════════════════════════════════════════════
# 6. APK 정보
# ══════════════════════════════════════════════════════
echo "[6/9] APK 확인..."
APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
APK_SIZE="없음"
APK_EXISTS="NO"
if [ -f "$APK_PATH" ]; then
    APK_EXISTS="YES"
    APK_SIZE=$(du -h "$APK_PATH" | cut -f1)
fi

# ══════════════════════════════════════════════════════
# 8. 위험 파일 감지
# ══════════════════════════════════════════════════════
echo "[8/9] 위험 파일 스캔..."
RISK_FILES=""

# 1000줄+ 파일
while IFS= read -r f; do
    if [ -f "$f" ]; then
        lines=$(count_lines "$f")
        if [ "$lines" -gt 1000 ] 2>/dev/null; then
            RISK_FILES="${RISK_FILES}- 1000줄+ 파일: $f (${lines}줄)\n"
        fi
    fi
done <<< "$CHANGED_FILES"

# 오늘 변경된 src 파일 중 테스트 없는 파일
while IFS= read -r f; do
    if echo "$f" | grep -q "src/main.*\.kt$"; then
        basename=$(basename "$f" .kt)
        testfile=$(find app/src/test -name "${basename}Test.kt" 2>/dev/null | head -1)
        if [ -z "$testfile" ]; then
            RISK_FILES="${RISK_FILES}- 테스트 없음: $f\n"
        fi
    fi
done <<< "$CHANGED_FILES"

# TODO/FIXME 추가
TODO_COUNT=0
while IFS= read -r f; do
    if [ -f "$f" ]; then
        c=$(grep -c "TODO\|FIXME" "$f" 2>/dev/null || echo "0")
        if [ "$c" -gt 0 ] 2>/dev/null; then
            TODO_COUNT=$((TODO_COUNT + c))
            RISK_FILES="${RISK_FILES}- TODO/FIXME: $f (${c}건)\n"
        fi
    fi
done <<< "$CHANGED_FILES"

if [ -z "$RISK_FILES" ]; then
    RISK_FILES="없음"
fi

# ══════════════════════════════════════════════════════
# 9. Codex 검증 레벨
# ══════════════════════════════════════════════════════
echo "[9/9] Codex prompt 생성..."
CODEX_LEVEL="LOW"
CODEX_ITEMS=""

if [ "$BUILD_STATUS" = "FAIL" ]; then
    CODEX_LEVEL="HIGH"
    CODEX_ITEMS="${CODEX_ITEMS}- HIGH: 빌드 실패\n"
fi
if [ "$TEST_STATUS" = "FAIL" ]; then
    CODEX_LEVEL="HIGH"
    CODEX_ITEMS="${CODEX_ITEMS}- HIGH: 테스트 실패 (${TEST_FAIL}건)\n"
fi
if [ "$LOC_ADDED" -gt 200 ] 2>/dev/null; then
    if [ "$CODEX_LEVEL" != "HIGH" ]; then CODEX_LEVEL="MEDIUM"; fi
    CODEX_ITEMS="${CODEX_ITEMS}- MEDIUM: 큰 변경 (+${LOC_ADDED}줄)\n"
fi
if [ -z "$CODEX_ITEMS" ]; then
    CODEX_ITEMS="- LOW: 단순 변경"
fi

# ══════════════════════════════════════════════════════
# daily_report.md 생성
# ══════════════════════════════════════════════════════
echo ""
echo "리포트 생성 중..."

TEST_ICON="✅"
if [ "$TEST_STATUS" = "FAIL" ]; then TEST_ICON="❌"; fi
BUILD_ICON="✅"
if [ "$BUILD_STATUS" = "FAIL" ]; then BUILD_ICON="❌"; fi

cat > "$REPORT" << ENDREPORT
# OnTheWay 일일 개발 보고 — $TIMESTAMP

## [오늘 변경]
- 브랜치: $GIT_BRANCH
- 변경 파일: ${CHANGED_COUNT}개
- commit: ${COMMIT_COUNT}건 (오늘)
$(echo "$GIT_LOG" | head -10 | sed 's/^/  · /')
- LOC: +${LOC_ADDED} / -${LOC_DELETED}

## [빌드 결과]
- assembleDebug: $BUILD_ICON $BUILD_STATUS
- APK 존재: $APK_EXISTS
- APK 크기: $APK_SIZE
- APK 경로: $APK_PATH
$(if [ "$BUILD_STATUS" = "FAIL" ]; then echo -e "\n\`\`\`\n$BUILD_FAIL_LINES\n\`\`\`"; fi)

## [테스트 결과]
- 전체 ${TEST_TOTAL}건 / PASS ${TEST_PASS} / FAIL ${TEST_FAIL}
- 결과: $TEST_ICON $TEST_STATUS
$(if [ "$TEST_STATUS" = "FAIL" ]; then echo -e "\n\`\`\`\n$TEST_FAIL_LINES\n\`\`\`"; fi)

## [위험 파일]
$(echo -e "$RISK_FILES")

## [Codex 검증 필요 항목]
$(echo -e "$CODEX_ITEMS")

## [대표 결정 필요 사항]
- [ ] commit push?
- [ ] APK 설치?
- [ ] 다음 우선순위?
- [ ] Codex 검증 의뢰?

---
*Generated by DevOps Agent v0 — READ-ONLY MODE*
ENDREPORT

# ══════════════════════════════════════════════════════
# codex_review_prompt.txt 생성
# ══════════════════════════════════════════════════════
cat > "$CODEX_PROMPT" << ENDCODEX
[OnTheWay Codex Review Request — $TIMESTAMP]

== 오늘 변경 ==
commit ${COMMIT_COUNT}건, 파일 ${CHANGED_COUNT}개, LOC +${LOC_ADDED}/-${LOC_DELETED}

최근 10 commit:
$GIT_LOG

변경 파일:
$CHANGED_FILES

== 빌드/테스트 ==
- assembleDebug: $BUILD_STATUS
- test: $TEST_STATUS ($TEST_TOTAL건, FAIL $TEST_FAIL)

== 검증 요청 ==
1. 위 변경 파일 중 regression 위험 분석
2. 큰 변경 (200줄+) 파일 코드 리뷰
3. 테스트 커버리지 부족 파일 식별
4. 성능/메모리 영향 분석

== 위험 파일 ==
$(echo -e "$RISK_FILES")
ENDCODEX

# ══════════════════════════════════════════════════════
# 완료
# ══════════════════════════════════════════════════════
echo ""
echo "══════════════════════════════════════════════"
echo "  DevOps Agent v0 완료"
echo ""
echo "  출력 파일:"
echo "    $REPORT"
echo "    $CODEX_PROMPT"
echo "    $BUILD_LOG"
echo "    $TEST_LOG"
echo ""
echo "  빌드: $BUILD_ICON $BUILD_STATUS"
echo "  테스트: $TEST_ICON $TEST_STATUS ($TEST_TOTAL건)"
echo "══════════════════════════════════════════════"

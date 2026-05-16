#!/usr/bin/env python3
"""
OnTheWay Ledger Analyzer v1
ledger.db → ledger_report.md (섹션 8.1~8.7)

Usage: python analyze_ledger.py <ledger.db> [output.md]
"""

import sqlite3
import sys
import os
import json
from collections import Counter, defaultdict
from datetime import datetime

TABLE = "ledger_events"
# Fix W+: ACCEPT_CONFIRMED (정식) + DRIVER_ACCEPTED (legacy 호환)
ACCEPT_TYPES = "('ACCEPT_CONFIRMED', 'DRIVER_ACCEPTED')"

def _has_accept(types_set):
    """Fix W+: session에 수락 이벤트가 있는지 확인"""
    return "ACCEPT_CONFIRMED" in types_set or "DRIVER_ACCEPTED" in types_set


def connect(db_path):
    if not os.path.exists(db_path):
        print(f"[ERROR] {db_path} not found")
        sys.exit(1)
    conn = sqlite3.connect(db_path)
    conn.row_factory = sqlite3.Row
    return conn


# ── 8.1 Event Type 분포 ──

def section_8_1(conn):
    cur = conn.execute(
        f"SELECT event_type, COUNT(*) as cnt FROM {TABLE} GROUP BY event_type ORDER BY cnt DESC"
    )
    rows = cur.fetchall()
    total = sum(r["cnt"] for r in rows)

    lines = ["## 8.1 Event Type 분포", "", f"총 {total}건", "",
             "| event_type | count | % |", "|---|---|---|"]
    for r in rows:
        pct = r["cnt"] / total * 100 if total else 0
        # RAW_ACCESSIBILITY_SEEN 세분화
        extra = ""
        if r["event_type"] == "RAW_ACCESSIBILITY_SEEN":
            diag = conn.execute(
                f"SELECT COUNT(*) FROM {TABLE} WHERE event_type='RAW_ACCESSIBILITY_SEEN' "
                f"AND source_channel='accessibility_diagnostic'"
            ).fetchone()[0]
            event = r["cnt"] - diag
            extra = f" (event: {event}, diagnostic: {diag})"
        lines.append(f"| {r['event_type']}{extra} | {r['cnt']} | {pct:.1f}% |")

    return "\n".join(lines)


# ── 8.2 ACCEPT Source × Count (ACCEPT_CONFIRMED + legacy DRIVER_ACCEPTED) ──

def section_8_2(conn):
    # Fix analyze: session-distinct — ACCEPT_CONFIRMED 우선, DRIVER_ACCEPTED fallback
    cur = conn.execute(
        f"SELECT call_session_id, event_type, derived_payload_json "
        f"FROM {TABLE} WHERE event_type IN {ACCEPT_TYPES} "
        f"ORDER BY CASE event_type WHEN 'ACCEPT_CONFIRMED' THEN 0 ELSE 1 END"
    )
    seen_sessions = set()
    sources = Counter()
    for row in cur:
        sid = row["call_session_id"] or ""
        if sid in seen_sessions:
            continue  # double count 방지
        seen_sessions.add(sid)
        payload = row["derived_payload_json"] or "{}"
        try:
            j = json.loads(payload)
            sources[j.get("source", "UNKNOWN")] += 1
        except (json.JSONDecodeError, TypeError):
            sources["PARSE_ERROR"] += 1

    total = sum(sources.values())
    lines = ["## 8.2 ACCEPT Source 분포 (session-distinct)", "",
             f"총 ACCEPT (distinct session): {total}건", "",
             "| source | count | % |", "|---|---|---|"]
    for src, cnt in sources.most_common():
        pct = cnt / total * 100 if total else 0
        lines.append(f"| {src} | {cnt} | {pct:.1f}% |")

    return "\n".join(lines)


# ── 8.3 거품 검증 ──

def section_8_3(conn):
    # DRIVER_ACCEPTED sessions
    cur = conn.execute(
        f"SELECT call_session_id, COUNT(*) as cnt, derived_payload_json "
        f"FROM {TABLE} WHERE event_type IN {ACCEPT_TYPES} "
        f"GROUP BY call_session_id ORDER BY cnt DESC"
    )
    session_counts = []
    total_accepts = 0
    total_revenue = 0
    unique_revenue = 0
    for row in cur:
        cnt = row["cnt"]
        session_counts.append((row["call_session_id"], cnt))
        total_accepts += cnt

    unique_sessions = len(session_counts)
    dup_sessions = sum(1 for _, c in session_counts if c > 1)
    dup_accepts = sum(c - 1 for _, c in session_counts if c > 1)

    # 매출 계산: unique session별 첫 ACCEPT의 price
    for sid, _ in session_counts:
        row = conn.execute(
            f"SELECT derived_payload_json FROM {TABLE} "
            f"WHERE event_type IN {ACCEPT_TYPES} AND call_session_id=? "
            f"ORDER BY occurred_at_wall ASC LIMIT 1",
            (sid,)
        ).fetchone()
        if row and row["derived_payload_json"]:
            try:
                price = json.loads(row["derived_payload_json"]).get("price", 0)
                unique_revenue += price
                total_revenue += price
            except (json.JSONDecodeError, TypeError):
                pass

    # 총 매출 (중복 포함)
    gross_revenue = 0
    for row in conn.execute(
        f"SELECT derived_payload_json FROM {TABLE} WHERE event_type IN {ACCEPT_TYPES}"
    ):
        if row["derived_payload_json"]:
            try:
                gross_revenue += json.loads(row["derived_payload_json"]).get("price", 0)
            except (json.JSONDecodeError, TypeError):
                pass

    bubble_rate = (total_accepts - unique_sessions) / unique_sessions * 100 if unique_sessions else 0

    # DUPLICATE_ACCEPT_BLOCKED 건수 (패키지 49 이후)
    blocked = conn.execute(
        f"SELECT COUNT(*) FROM {TABLE} WHERE event_type='DUPLICATE_ACCEPT_BLOCKED'"
    ).fetchone()[0]

    lines = ["## 8.3 거품 검증", ""]
    lines.append(f"| 지표 | 값 |")
    lines.append(f"|---|---|")
    lines.append(f"| 총 ACCEPT 건수 | {total_accepts} |")
    lines.append(f"| Unique session 수 | {unique_sessions} |")
    lines.append(f"| 중복 session 수 | {dup_sessions} |")
    lines.append(f"| 중복 ACCEPT 건수 | {dup_accepts} |")
    lines.append(f"| 거품률 | {bubble_rate:.1f}% |")
    lines.append(f"| DUPLICATE_ACCEPT_BLOCKED | {blocked} |")
    lines.append(f"| 총 매출 (중복 포함) | {gross_revenue:,}원 |")
    lines.append(f"| 실제 매출 (unique) | {unique_revenue:,}원 |")

    # 5분 간격 다중 ACCEPT 상세
    if dup_sessions > 0:
        lines.append("")
        lines.append("### 중복 ACCEPT 상세 (상위 10)")
        lines.append("| session_id | ACCEPT 수 |")
        lines.append("|---|---|")
        for sid, cnt in session_counts[:10]:
            if cnt > 1:
                lines.append(f"| {sid[:8]}... | {cnt} |")

    return "\n".join(lines)


# ── 8.4 Lifecycle 분포 ──

def section_8_4(conn):
    # 세션별 event_type set
    cur = conn.execute(
        f"SELECT call_session_id, event_type FROM {TABLE} "
        f"WHERE call_session_id IS NOT NULL AND call_session_id != ''"
    )
    sessions = defaultdict(set)
    for row in cur:
        sessions[row["call_session_id"]].add(row["event_type"])

    total = len(sessions)
    detect_only = sum(1 for types in sessions.values()
                      if "CALL_DETECTED" in types and "JUDGMENT_ISSUED" not in types
                      and not _has_accept(types) and "TIMEOUT" not in types)
    with_judgment = sum(1 for types in sessions.values()
                        if "CALL_DETECTED" in types and "JUDGMENT_ISSUED" in types)
    with_timeout = sum(1 for types in sessions.values()
                       if "TIMEOUT" in types)
    with_accept = sum(1 for types in sessions.values()
                      if _has_accept(types))
    with_orphan = sum(1 for types in sessions.values()
                      if "ORPHAN_CLASSIFIED" in types)
    # orphan ACCEPT: DRIVER_ACCEPTED 있지만 CALL_DETECTED 없음
    orphan_accept = sum(1 for types in sessions.values()
                        if _has_accept(types) and "CALL_DETECTED" not in types)

    full_lifecycle = sum(1 for types in sessions.values()
                         if "CALL_DETECTED" in types and _has_accept(types))

    lifecycle_rate = full_lifecycle / with_accept * 100 if with_accept else 0

    lines = ["## 8.4 Lifecycle 분포", ""]
    lines.append(f"총 세션: {total}건")
    lines.append("")
    lines.append("| 단계 | 건수 | % |")
    lines.append("|---|---|---|")
    lines.append(f"| CALL_DETECTED만 | {detect_only} | {detect_only/total*100:.0f}% |" if total else "")
    lines.append(f"| + JUDGMENT_ISSUED | {with_judgment} | {with_judgment/total*100:.0f}% |" if total else "")
    lines.append(f"| + TIMEOUT | {with_timeout} | {with_timeout/total*100:.0f}% |" if total else "")
    lines.append(f"| + ACCEPTED (confirmed+legacy) | {with_accept} | {with_accept/total*100:.0f}% |" if total else "")
    lines.append(f"| + ORPHAN_CLASSIFIED | {with_orphan} | {with_orphan/total*100:.0f}% |" if total else "")
    lines.append(f"| Orphan ACCEPT (DETECT 없음) | {orphan_accept} | — | ⚠️" if orphan_accept else "")
    lines.append("")
    lines.append(f"Lifecycle integrity: {lifecycle_rate:.0f}% (ACCEPT 중 CALL_DETECTED 있는 비율)")

    return "\n".join(lines)


# ── 8.5 Raw 한글 보존 ──

def section_8_5(conn):
    total = conn.execute(f"SELECT COUNT(*) FROM {TABLE}").fetchone()[0]

    # raw_payload_json에 한글 존재
    korean_raw = conn.execute(
        f"SELECT COUNT(*) FROM {TABLE} WHERE raw_payload_json IS NOT NULL "
        f"AND (raw_payload_json LIKE '%원%' OR raw_payload_json LIKE '%동%' "
        f"OR raw_payload_json LIKE '%아파트%' OR raw_payload_json LIKE '%배달%')"
    ).fetchone()[0]

    # diagnostic_tree_walk 비율
    diag_walk = conn.execute(
        f"SELECT COUNT(*) FROM {TABLE} WHERE source_channel='accessibility_diagnostic'"
    ).fetchone()[0]

    # JSON 파싱 가능 비율 (raw_payload_json)
    raw_total = conn.execute(
        f"SELECT COUNT(*) FROM {TABLE} WHERE raw_payload_json IS NOT NULL AND raw_payload_json != ''"
    ).fetchone()[0]

    parseable = 0
    truncated = 0
    if raw_total > 0:
        for row in conn.execute(
            f"SELECT raw_payload_json FROM {TABLE} WHERE raw_payload_json IS NOT NULL AND raw_payload_json != ''"
        ):
            try:
                json.loads(row["raw_payload_json"])
                parseable += 1
            except (json.JSONDecodeError, TypeError):
                truncated += 1

    parse_rate = parseable / raw_total * 100 if raw_total else 0
    korean_rate = korean_raw / total * 100 if total else 0

    lines = ["## 8.5 Raw 한글 보존", ""]
    lines.append("| 지표 | 값 |")
    lines.append("|---|---|")
    lines.append(f"| 한글 포함 raw 건수 | {korean_raw}/{total} ({korean_rate:.0f}%) |")
    lines.append(f"| diagnostic_tree_walk 건수 | {diag_walk} |")
    lines.append(f"| JSON 파싱 가능 | {parseable}/{raw_total} ({parse_rate:.0f}%) |")
    lines.append(f"| truncate 깨짐 | {truncated} |")

    return "\n".join(lines)


# ── 8.6 ledger.db 사이즈 ──

def section_8_6(db_path, conn):
    file_size = os.path.getsize(db_path)
    total = conn.execute(f"SELECT COUNT(*) FROM {TABLE}").fetchone()[0]

    # 시간 범위
    time_range = conn.execute(
        f"SELECT MIN(occurred_at_wall), MAX(occurred_at_wall) FROM {TABLE}"
    ).fetchone()
    min_ts, max_ts = time_range[0] or 0, time_range[1] or 0

    hours = (max_ts - min_ts) / 3600000.0 if max_ts > min_ts else 0
    rows_per_hour = total / hours if hours > 0 else 0
    bytes_per_hour = file_size / hours if hours > 0 else 0

    # 100MB 도달 예상
    target = 100 * 1024 * 1024  # 100MB
    remaining = target - file_size
    if bytes_per_hour > 0 and remaining > 0:
        hours_to_100mb = remaining / bytes_per_hour
        days_to_100mb = hours_to_100mb / 24
    else:
        days_to_100mb = -1

    min_dt = datetime.fromtimestamp(min_ts / 1000).strftime("%Y-%m-%d %H:%M") if min_ts else "N/A"
    max_dt = datetime.fromtimestamp(max_ts / 1000).strftime("%Y-%m-%d %H:%M") if max_ts else "N/A"

    lines = ["## 8.6 ledger.db 사이즈", ""]
    lines.append("| 지표 | 값 |")
    lines.append("|---|---|")
    lines.append(f"| 파일 크기 | {file_size:,} bytes ({file_size/1024:.1f} KB) |")
    lines.append(f"| 총 건수 | {total:,} |")
    lines.append(f"| 시간 범위 | {min_dt} ~ {max_dt} |")
    lines.append(f"| 운행 시간 | {hours:.1f}h |")
    lines.append(f"| 행/시간 | {rows_per_hour:.0f} |")
    lines.append(f"| 바이트/시간 | {bytes_per_hour:.0f} |")
    if days_to_100mb > 0:
        lines.append(f"| 100MB 도달 예상 | ~{days_to_100mb:.0f}일 |")
    else:
        lines.append(f"| 100MB 도달 예상 | 계산 불가 |")

    return "\n".join(lines)


# ── 8.7 정체성 6단계 종합 ──

def section_8_7(conn, db_path):
    total = conn.execute(f"SELECT COUNT(*) FROM {TABLE}").fetchone()[0]
    if total == 0:
        return "## 8.7 정체성 6단계 종합\n\n데이터 없음"

    # 1. 원본 보존: 한글 비율
    korean_raw = conn.execute(
        f"SELECT COUNT(*) FROM {TABLE} WHERE raw_payload_json IS NOT NULL "
        f"AND (raw_payload_json LIKE '%원%' OR raw_payload_json LIKE '%동%' "
        f"OR raw_payload_json LIKE '%아파트%' OR raw_payload_json LIKE '%배달%')"
    ).fetchone()[0]
    raw_rate = korean_raw / total * 100

    # 2. Lifecycle integrity
    sessions = defaultdict(set)
    for row in conn.execute(
        f"SELECT call_session_id, event_type FROM {TABLE} "
        f"WHERE call_session_id IS NOT NULL AND call_session_id != ''"
    ):
        sessions[row["call_session_id"]].add(row["event_type"])

    with_accept = [s for s in sessions.values() if _has_accept(s)]
    full_lifecycle = sum(1 for types in with_accept if "CALL_DETECTED" in types)
    lifecycle_rate = full_lifecycle / len(with_accept) * 100 if with_accept else 0

    # 3. Identity confidence HIGH 비율
    high_conf = conn.execute(
        f"SELECT COUNT(*) FROM {TABLE} WHERE identity_confidence >= 0.8"
    ).fetchone()[0]
    high_rate = high_conf / total * 100

    # 4. Quarantine 비율
    quarantined = conn.execute(
        f"SELECT COUNT(*) FROM {TABLE} WHERE event_type='QUARANTINED'"
    ).fetchone()[0]
    quarantine_rate = quarantined / len(sessions) * 100 if sessions else 0

    # 5. join_eligible: confidence >= 0.5 비율
    eligible = conn.execute(
        f"SELECT COUNT(*) FROM {TABLE} WHERE identity_confidence >= 0.5"
    ).fetchone()[0]
    eligible_rate = eligible / total * 100

    # 6. 거품률 (session-distinct: ACCEPT_CONFIRMED 우선, double count 방지)
    accept_sessions = [(sid, cnt) for sid, cnt in
                       conn.execute(
                           f"SELECT call_session_id, COUNT(DISTINCT event_type) FROM {TABLE} "
                           f"WHERE event_type IN {ACCEPT_TYPES} GROUP BY call_session_id"
                       )]
    unique_accepts = len(accept_sessions)
    # DUPLICATE_ACCEPT_BLOCKED/SUSPECTED 건수로 실제 거품 측정
    dup_blocked = conn.execute(
        f"SELECT COUNT(*) FROM {TABLE} WHERE event_type IN ('DUPLICATE_ACCEPT_BLOCKED','DUPLICATE_ACCEPT_SUSPECTED')"
    ).fetchone()[0]
    bubble_rate = dup_blocked / unique_accepts * 100 if unique_accepts else 0

    def grade(value, green_threshold, yellow_threshold=None):
        if yellow_threshold is None:
            yellow_threshold = green_threshold * 0.8
        if value >= green_threshold:
            return "GREEN"
        elif value >= yellow_threshold:
            return "YELLOW"
        else:
            return "RED"

    def bubble_grade(rate):
        if rate <= 5:
            return "GREEN"
        elif rate <= 20:
            return "YELLOW"
        else:
            return "RED"

    grades = [
        ("1. 원본 보존 (한글 raw ≥80%)", f"{raw_rate:.0f}%", grade(raw_rate, 80)),
        ("2. Lifecycle integrity (≥80%)", f"{lifecycle_rate:.0f}%", grade(lifecycle_rate, 80)),
        ("3. Identity confidence HIGH (≥50%)", f"{high_rate:.0f}%", grade(high_rate, 50, 30)),
        ("4. Quarantine 적정", f"{quarantine_rate:.0f}%", "GREEN" if quarantine_rate < 30 else "YELLOW"),
        ("5. join_eligible (conf≥0.5, ≥60%)", f"{eligible_rate:.0f}%", grade(eligible_rate, 60, 40)),
        ("6. 거품률 (≤5%)", f"{bubble_rate:.0f}%", bubble_grade(bubble_rate)),
    ]

    overall = "GREEN"
    for _, _, g in grades:
        if g == "RED":
            overall = "RED"
            break
        if g == "YELLOW" and overall != "RED":
            overall = "YELLOW"

    lines = ["## 8.7 정체성 6단계 종합", "", f"**Overall: {overall}**", ""]
    lines.append("| # | 기준 | 값 | 판정 |")
    lines.append("|---|---|---|---|")
    for name, val, g in grades:
        icon = {"GREEN": "🟢", "YELLOW": "🟡", "RED": "🔴"}[g]
        lines.append(f"| {name} | {val} | {icon} {g} |")

    return "\n".join(lines)


def main():
    if len(sys.argv) < 2:
        print("Usage: python analyze_ledger.py <ledger.db> [output.md]")
        sys.exit(1)

    db_path = sys.argv[1]
    output_path = sys.argv[2] if len(sys.argv) > 2 else None

    conn = connect(db_path)

    # 테이블 존재 확인
    tables = [r[0] for r in conn.execute(
        "SELECT name FROM sqlite_master WHERE type='table'"
    )]
    if TABLE not in tables:
        print(f"[ERROR] Table '{TABLE}' not found in {db_path}")
        print(f"  Available tables: {tables}")
        sys.exit(1)

    total = conn.execute(f"SELECT COUNT(*) FROM {TABLE}").fetchone()[0]
    now = datetime.now().strftime("%Y-%m-%d %H:%M")

    sections = [
        f"# OnTheWay Ledger Report — {now}",
        f"",
        f"DB: {os.path.basename(db_path)} ({os.path.getsize(db_path):,} bytes, {total:,}건)",
        "",
        section_8_1(conn),
        "",
        section_8_2(conn),
        "",
        section_8_3(conn),
        "",
        section_8_4(conn),
        "",
        section_8_5(conn),
        "",
        section_8_6(db_path, conn),
        "",
        section_8_7(conn, db_path),
        "",
        "---",
        "*Generated by analyze_ledger.py v1*",
    ]

    report = "\n".join(sections)

    if output_path:
        with open(output_path, "w", encoding="utf-8") as f:
            f.write(report)
        print(f"[OK] Report saved: {output_path}")
    else:
        print(report)

    conn.close()


if __name__ == "__main__":
    main()

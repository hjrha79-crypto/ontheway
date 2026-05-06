package com.vita.ontheway

import android.util.Log

object CoupangParser {

    // ① "(조리완료) 3,000원 배달 거리 0.7km"
    // ② "멀티 3,525원 거리할증 지원금 포함 배달 거리 2.1km"
    // ③ "대량 주문, 멀티 11,948원 거리할증 지원금 포함 배달 거리 5.3km"
    // ④ "멀티 8,300원 거래할증 포함 주문 두 건 배달 거리 5.5km"
    private val PRICE_PATTERN = Regex("([\\d,]+)\\s*원")
    private val DISTANCE_PATTERN = Regex("배달\\s*거리\\s*(\\d+\\.?\\d*)\\s*km", RegexOption.IGNORE_CASE)
    private val MULTI_PATTERN = Regex("멀티|주문\\s*두\\s*건|대량", RegexOption.IGNORE_CASE)
    // FIX-COUPANG-MULTI: 명시적 묶음 키워드 (보수적)
    private val BUNDLE_EXPLICIT_PATTERN = Regex("(\\d+)\\s*건\\s*묶음|묶음\\s*(\\d+)\\s*건|\\[(\\d+)건\\s*묶음\\]|\\[(\\d+)건\\]")

    // v3.20: 특수문자 허용 + 길이 30으로 상향 (긴 가게명 대비)
    private val STORE_PATTERN = Regex("^[가-힣a-zA-Z0-9\\s.,\\-()'/&]{2,30}$")

    /**
     * 쿠팡 비콜 키워드.
     * Accessibility(parse()) + NotificationListener(DeliveryNotificationService) 공유.
     *
     * 2026-04-24 STEP 9-fix: "미션","리워드","프로모션","이벤트" 등 단독 키워드 제거.
     * 쿠팡이츠 정상 콜 화면에 상시 표시되어 정상 콜 파싱을 차단했음.
     * 프로모션 특유의 복합 키워드만 유지.
     */
    val NON_CALL_KEYWORDS = setOf(
        // UI 상태 화면
        "배달 현황", "출근하기", "퇴근하기", "배달 완료",
        "고객에게 전달", "픽업 완료", "가게 도착", "고객 도착",
        "배달 중", "픽업 중", "주문 현황", "정산", "공지사항",
        "배달 내역", "수입 현황", "내 정보", "설정",
        "주문을 기다리는 중", "대기 중",
        // 유령콜 필터
        "배달이 많은 곳으로",
        // 프로모션/미션 특유 복합 키워드
        "완료 시 최대",
        "깜짝 미션",
        "지역 한정",
        "첫 수락 필요",
        // 알림 프로모션 표기
        "(광고)", "수신거부", "알림 설정", "유의사항",
        // 한글 숫자 (프로모션 금액)
        "7천원", "7천 원"
    )

    // 가게명 오염 블랙리스트: UI 텍스트가 가게명으로 오인되는 패턴 (정확 일치)
    private val STORE_NAME_BLACKLIST = setOf(
        // 기존
        "추천거절", "추천배차", "자동배차", "오류",
        // 콜 화면 UI 버튼
        "거절", "주문 수락", "주문수락",
        // 할증/지원금 태그
        "거리할증 포함", "거리할증", "지원금 포함", "거리할증 · 지원금 포함",
        // 미션 영역
        "조건에 맞거나 진행중인 미션이 없습니다", "미션",
        // 메뉴/네비게이션
        "메뉴", "확인", "닫기",
        // 단일/멀티 태그
        "단일", "멀티"
    )

    // 콜 화면 필수 버튼 텍스트: 이 중 하나는 있어야 진짜 콜
    private val CALL_SCREEN_BUTTONS = setOf("거절", "주문 수락", "주문수락")

    fun parse(texts: List<String>): List<DeliveryCall> {
        val results = mutableListOf<DeliveryCall>()
        val joined = texts.joinToString(" ")

        // 비콜 필터링: 배달 진행/완료/메뉴 화면이면 빈 리스트 반환
        if (NON_CALL_KEYWORDS.any { joined.contains(it) }) {
            Log.d("CoupangParser", "비콜 화면 감지 - 스킵: ${joined.take(50)}")
            OtwFileLogger.log("CoupangParser", "비콜 화면 감지 - 스킵: ${joined.take(50)}")
            DropReason.recordDrop(DropReason.DROP_SCREEN_FILTER, "coupang non-call keyword", "coupang")
            return results
        }

        // v2.1: "거절" 또는 "주문 수락" 버튼이 없으면 콜 화면이 아님
        if (CALL_SCREEN_BUTTONS.none { joined.contains(it) }) {
            Log.d("CoupangParser", "콜 버튼 없음 - 비콜 스킵: ${joined.take(50)}")
            OtwFileLogger.log("CoupangParser", "콜 버튼 없음 - 비콜 스킵: ${joined.take(50)}")
            DropReason.recordDrop(DropReason.DROP_SCREEN_FILTER, "coupang no call button", "coupang")
            return results
        }

        // 가게명 추출: 금액/거리/키워드가 아닌 짧은 한글 텍스트
        val storeName = texts.firstOrNull { t ->
            val trimmed = t.trim()
            trimmed.length in 2..30 &&
            !trimmed.contains("\n") &&
            !PRICE_PATTERN.containsMatchIn(trimmed) &&
            !DISTANCE_PATTERN.containsMatchIn(trimmed) &&
            !MULTI_PATTERN.containsMatchIn(trimmed) &&
            !trimmed.contains("km") && !trimmed.contains("원") &&
            STORE_PATTERN.matches(trimmed) &&
            !STORE_NAME_BLACKLIST.contains(trimmed)
        }?.trim() ?: ""

        val priceMatch = PRICE_PATTERN.find(joined)
        if (priceMatch != null) {
            val price = priceMatch.groupValues[1].replace(",", "").toIntOrNull()
            if (price != null && price in 1000..100000) {
                val distance = DISTANCE_PATTERN.find(joined)
                    ?.groupValues?.get(1)?.toDoubleOrNull()
                var isMulti = MULTI_PATTERN.containsMatchIn(joined)

                // FIX-COUPANG-MULTI: 명시적 묶음 키워드 감지 (보수적)
                val bundleMatch = BUNDLE_EXPLICIT_PATTERN.find(joined)
                var bundleCount = 1
                if (bundleMatch != null) {
                    isMulti = true
                    bundleCount = (bundleMatch.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.toIntOrNull()) ?: 2
                    OtwFileLogger.log("CoupangParser", "MULTI_EXPLICIT: '${bundleMatch.value}' → bundleCount=$bundleCount")
                }

                // FIX-COUPANG-MULTI: 멀티 의심 진단 로그
                val multiHints = mutableListOf<String>()
                if (MULTI_PATTERN.containsMatchIn(joined)) multiHints.add("MULTI_KEYWORD")
                if (bundleMatch != null) multiHints.add("BUNDLE_EXPLICIT(${bundleMatch.value})")
                if (texts.any { it.contains("주문 두 건") || it.contains("두건") }) multiHints.add("TWO_ORDERS_TEXT")
                if (multiHints.isNotEmpty()) {
                    logMultiDiag(joined, price, multiHints)
                }

                results.add(DeliveryCall(
                    price = price,
                    distance = distance,
                    isMulti = isMulti,
                    platform = "coupang",
                    rawText = joined,
                    storeName = storeName,
                    parsingMethod = V2Event.PARSING_ACCESSIBILITY_TEXT,
                    bundleCount = bundleCount
                ))
                Log.d("CoupangParser", "파싱: ${price}원, ${distance}km, multi=$isMulti, bundle=$bundleCount, store='${storeName.ifEmpty { "(없음)" }}'")
                OtwFileLogger.log("CoupangParser", "파싱: ${price}원, ${distance}km, multi=$isMulti, bundle=$bundleCount, store='${storeName.ifEmpty { "(없음)" }}'")

            }
        }

        // 개별 텍스트에서도 추가 콜 탐색
        for (text in texts) {
            if (text.length < 5 || text.length > 80) continue
            val pm = PRICE_PATTERN.find(text) ?: continue
            val p = pm.groupValues[1].replace(",", "").toIntOrNull() ?: continue
            if (p !in 1000..100000) continue

            // joined에서 이미 잡힌 금액이면 스킵
            if (results.any { it.price == p }) continue

            val d = DISTANCE_PATTERN.find(text)?.groupValues?.get(1)?.toDoubleOrNull()
            val m = MULTI_PATTERN.containsMatchIn(text)
            results.add(DeliveryCall(price = p, distance = d, isMulti = m, platform = "coupang", rawText = text, storeName = storeName, parsingMethod = V2Event.PARSING_ACCESSIBILITY_TEXT))
            Log.d("CoupangParser", "추가 파싱: ${p}원, ${d}km, multi=$m")
            OtwFileLogger.log("CoupangParser", "추가 파싱: ${p}원, ${d}km, multi=$m")
        }

        return results
    }

    /**
     * 쿠팡 픽업 진행 화면에서 가게명 추출.
     * 화면 구조: "픽업" → 주문코드(6자 영숫자) → 가게명 → 주소
     */
    fun extractStoreFromProgress(texts: List<String>): String {
        val pickupIdx = texts.indexOfFirst { it.trim() == "픽업" }
        if (pickupIdx < 0) return ""
        // "픽업" 이후 텍스트에서 가게명 후보 탐색
        for (i in (pickupIdx + 1) until texts.size) {
            val t = texts[i].trim()
            if (t.isEmpty()) continue
            // 주문코드 스킵 (6자 영숫자)
            if (t.matches(Regex("^[A-Z0-9]{4,8}$"))) continue
            // UI 텍스트 스킵
            if (t in setOf("매장 도착", "매장 픽업", "배정 취소하기", "복사", "매장찾기 팁",
                           "배달목록", "신규 주문", "NAVER")) continue
            // 주소 패턴 스킵
            if (t.matches(Regex("^(경기|서울|부산|대구|인천|광주|대전|울산|세종|강원|충북|충남|전북|전남|경북|경남|제주).*"))) continue
            // 가게명 후보: 2~30자 한글 포함
            if (t.length in 2..30 && Regex("[가-힣]").containsMatchIn(t)) {
                return t
            }
        }
        return ""
    }

    /**
     * FIX-COUPANG-MULTI: 멀티 의심 사례 진단 로그.
     * coupang_multi_diag.jsonl에 raw 보존.
     */
    private fun logMultiDiag(rawText: String, price: Int, hints: List<String>) {
        try {
            val ctx = OtwFileLogger.appContext ?: return
            val entry = org.json.JSONObject().apply {
                put("ts", System.currentTimeMillis())
                put("price", price)
                put("hints", hints.joinToString(","))
                put("rawText", rawText.take(500))
            }.toString()
            val file = java.io.File(ctx.filesDir, "coupang_multi_diag.jsonl")
            // 100건 초과 시 rotate
            if (file.exists() && file.length() > 100 * 1024) {
                val rotated = java.io.File(ctx.filesDir, "coupang_multi_diag.jsonl.1")
                if (rotated.exists()) rotated.delete()
                file.renameTo(rotated)
            }
            java.io.FileOutputStream(java.io.File(ctx.filesDir, "coupang_multi_diag.jsonl"), true).use { fos ->
                fos.write((entry + "\n").toByteArray())
                fos.fd.sync()
            }
        } catch (_: Exception) {}
    }
}

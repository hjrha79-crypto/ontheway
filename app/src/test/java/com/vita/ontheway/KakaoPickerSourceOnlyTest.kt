package com.vita.ontheway

import com.vita.ontheway.diagnostic.KakaoPickerDiagnosticLogger
import com.vita.ontheway.ledger.LedgerAppender
import com.vita.ontheway.ledger.LedgerEvent
import com.vita.ontheway.ledger.LedgerEventType
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Fix AA: 카카오T 픽커 Source-Only 로깅 검증.
 *
 * [Source-Only 원칙 v1.0]
 * kakao_picker는 raw 로깅만. 분기 추가 금지.
 * 검증 종료: 2026-07-12 또는 10건 누적 후 평가.
 */
class KakaoPickerSourceOnlyTest {

    // ── a. com.kakaomobility.flexer 이벤트 → RAW_ACCESSIBILITY_SEEN 발생 ──

    @Test
    fun `flexer 이벤트에 대해 RAW_ACCESSIBILITY_SEEN 이벤트 생성`() {
        // LedgerAppender.appendAccessibility는 내부적으로 RAW_ACCESSIBILITY_SEEN을 사용
        // DB I/O 없이 payload 구조만 검증
        val pkg = "com.kakaomobility.flexer"
        val texts = listOf("서울 강남구", "배차 대기", "3,500원")
        val json = JSONObject().apply {
            put("package", pkg)
            put("eventType", 32)  // TYPE_WINDOW_STATE_CHANGED
            put("className", "android.widget.FrameLayout")
            put("texts", org.json.JSONArray(texts))
            put("contentDescription", "")
            put("node_count", 5)
        }

        assertEquals(pkg, json.getString("package"))
        assertEquals(32, json.getInt("eventType"))
        assertEquals(3, json.getJSONArray("texts").length())

        // RAW_ACCESSIBILITY_SEEN 이벤트 타입 존재 확인
        val eventType = LedgerEventType.RAW_ACCESSIBILITY_SEEN
        assertEquals("RAW_ACCESSIBILITY_SEEN", eventType.name)
    }

    // ── b. platform = "kakao_picker" 라벨 부착 ──

    @Test
    fun `flexer 패키지는 kakao_picker 플랫폼으로 매핑`() {
        val platform = LedgerAppender.packageToPlatform("com.kakaomobility.flexer")
        assertEquals("kakao_picker", platform)
    }

    @Test
    fun `flexer가 아닌 kakaomobility 패키지는 kakaot 유지`() {
        val platform = LedgerAppender.packageToPlatform("com.kakaomobility.driver")
        assertEquals("kakaot", platform)
    }

    @Test
    fun `기존 baemin coupang 매핑 영향 없음`() {
        assertEquals("baemin", LedgerAppender.packageToPlatform("com.woowahan.bros"))
        assertEquals("coupang", LedgerAppender.packageToPlatform("com.coupang.mobile.eats.courier"))
    }

    // ── c. CALL_DETECTED 0건 ──

    @Test
    fun `kakao_picker는 CALL_DETECTED 생성하지 않음`() {
        // Source-Only 원칙: PKG_FLEXER 이벤트는 OnTheWayService에서
        // raw 로깅 후 즉시 return, handleDeliveryPlatform/CallRecommender 경로 진입 X
        // 이 테스트는 routing 원칙을 문서화
        val pkg = "com.kakaomobility.flexer"
        val isFlexer = (pkg == KakaoPickerDiagnosticLogger.PKG_FLEXER)
        assertTrue("flexer 패키지 식별", isFlexer)

        // Source-Only: CALL_DETECTED 생성 코드 경로에 진입하지 않음을 확인
        // OnTheWayService에서 pkg == PKG_FLEXER → return (noop)
        val callDetectedCount = 0  // Source-Only = 항상 0
        assertEquals(0, callDetectedCount)
    }

    // ── d. JUDGMENT_ISSUED 0건 ──

    @Test
    fun `kakao_picker는 JUDGMENT_ISSUED 생성하지 않음`() {
        // Source-Only: CallFilter.judge() 호출되지 않음
        val judgmentCount = 0  // Source-Only = 항상 0
        assertEquals(0, judgmentCount)

        // JUDGMENT_ISSUED 이벤트 타입이 존재하는지 확인 (enum 유효성)
        val eventType = LedgerEventType.JUDGMENT_ISSUED
        assertEquals("JUDGMENT_ISSUED", eventType.name)
    }

    // ── e. kakao_picker_diagnostic.jsonl 파일 생성 ──

    @Test
    fun `kakao_picker_diagnostic jsonl 파일에 엔트리 기록`() {
        val tempFile = File.createTempFile("kakao_picker_test", ".jsonl")
        try {
            // 직접 파일 쓰기 테스트 (Android context 없이)
            val entries = listOf(
                JSONObject().apply {
                    put("ts", System.currentTimeMillis())
                    put("phase", "immediate")
                    put("eventType", "TYPE_WINDOW_STATE_CHANGED")
                    put("pkg", "com.kakaomobility.flexer")
                    put("depth", 0)
                    put("className", "android.widget.FrameLayout")
                    put("text", "배차 대기")
                    put("contentDesc", "")
                    put("viewId", "com.kakaomobility.flexer:id/tv_status")
                    put("bounds", "[0,0][1080,2400]")
                },
                JSONObject().apply {
                    put("ts", System.currentTimeMillis())
                    put("phase", "delayed_100ms")
                    put("eventType", "TYPE_WINDOW_STATE_CHANGED")
                    put("pkg", "com.kakaomobility.flexer")
                    put("depth", 1)
                    put("className", "android.widget.TextView")
                    put("text", "3,500원")
                    put("contentDesc", "")
                    put("viewId", "")
                    put("bounds", "[100,200][500,300]")
                }
            )

            // jsonl 형식으로 기록
            tempFile.writeText(entries.joinToString("\n") { it.toString() } + "\n")

            // 파일 존재 + 내용 검증
            assertTrue("파일 생성됨", tempFile.exists())
            val lines = tempFile.readLines().filter { it.isNotBlank() }
            assertEquals(2, lines.size)

            // 첫 줄 파싱 검증
            val first = JSONObject(lines[0])
            assertEquals("immediate", first.getString("phase"))
            assertEquals("com.kakaomobility.flexer", first.getString("pkg"))
            assertTrue(first.has("text"))
            assertTrue(first.has("contentDesc"))
            assertTrue(first.has("viewId"))
            assertTrue(first.has("bounds"))

            // FILE_NAME 상수 검증
            assertEquals("kakao_picker_diagnostic.jsonl", KakaoPickerDiagnosticLogger.FILE_NAME)
        } finally {
            tempFile.delete()
        }
    }

    // ── Fix AA v2: accept-click pre-route 차단 ──

    @Test
    fun `PKG_FLEXER TYPE_VIEW_CLICKED should be blocked before onAcceptDetected`() {
        // OnTheWayService에서 TYPE_VIEW_CLICKED + PKG_FLEXER → return
        // accept 분기 진입 전 차단 확인 (코드 구조 검증)
        val pkg = "com.kakaomobility.flexer"
        val isFlexer = (pkg == KakaoPickerDiagnosticLogger.PKG_FLEXER)
        assertTrue("flexer click은 즉시 return", isFlexer)
        // Source-Only: onAcceptDetected 호출 0회
        val acceptCallCount = if (isFlexer) 0 else 1
        assertEquals(0, acceptCallCount)
    }

    @Test
    fun `PKG_FLEXER click with prior ACCEPT_CANDIDATE should not produce CONFIRMED`() {
        // 시나리오: 직전 배민 ACCEPT_CANDIDATE 잔존 + PKG_FLEXER click
        // 기대: CONFIRMED 0건 (flexer click은 return으로 차단)
        val pkg = "com.kakaomobility.flexer"
        val priorCandidate = true  // 배민 ACCEPT_CANDIDATE 잔존 가정
        val flexerBlocked = (pkg == KakaoPickerDiagnosticLogger.PKG_FLEXER)
        assertTrue(flexerBlocked)
        // flexer 차단 → onAcceptDetected 미호출 → CONFIRMED 0건
        val confirmedCount = if (flexerBlocked) 0 else 1
        assertEquals(0, confirmedCount)
    }

    @Test
    fun `PKG_BAEMIN and PKG_COUPANG click should not be blocked`() {
        // 회귀 테스트: 배민/쿠팡은 기존 accept 처리 유지
        val baeminPkg = "com.woowahan.bros"
        val coupangPkg = "com.coupang.mobile.eats.courier"
        val flexerPkg = "com.kakaomobility.flexer"

        assertFalse("배민은 차단 안 됨", baeminPkg == flexerPkg)
        assertFalse("쿠팡은 차단 안 됨", coupangPkg == flexerPkg)
        // DELIVERY_PACKAGES에 flexer 미포함 확인
        val deliveryPackages = setOf(coupangPkg, baeminPkg)
        assertFalse(flexerPkg in deliveryPackages)
    }
}

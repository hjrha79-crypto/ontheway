package com.vita.ontheway

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Lite 1.0 Mini UI 검증 (소스 코드 기반 — Robolectric 불필요).
 * UserModeActivity.kt의 View.GONE 처리 + 운행 버튼 로직 확인.
 */
class UserModeActivityLiteTest {

    private val source = File("src/main/java/com/vita/ontheway/UserModeActivity.kt").readText()

    @Test
    fun `T1 hourly rate view is GONE`() {
        // hourlyText must have visibility = View.GONE
        assertTrue(
            "hourlyText should be View.GONE",
            source.contains("hourlyText = TextView") &&
                source.contains("visibility = View.GONE")
        )
        // The label "시간당 수익" view must also be GONE
        val labelSection = source.substringAfter("시간당 수익").substringBefore("hourlyText")
        assertTrue("시간당 수익 label should be GONE", labelSection.contains("View.GONE"))
    }

    @Test
    fun `T2 today call count view is GONE`() {
        // callCountText must have visibility = View.GONE
        val callCountSection = source.substringAfter("callCountText = TextView")
            .substringBefore("container.addView(callCountText)")
        assertTrue(
            "callCountText should be View.GONE",
            callCountSection.contains("visibility = View.GONE")
        )
    }

    @Test
    fun `T4 text sizes are increased for lite visibility`() {
        // 타이틀 "OnTheWay Lite" >= 26sp
        val titleSection = source.substringAfter("OnTheWay Lite").substringBefore("setTextColor")
        val titleSize = Regex("""textSize\s*=\s*(\d+)""").find(titleSection)?.groupValues?.get(1)?.toInt()
        assertNotNull("title textSize should be found", titleSize)
        assertTrue("title textSize >= 26sp (actual=$titleSize)", titleSize!! >= 26)

        // 안내문구 >= 18sp
        val guideSection = source.substringAfter("실시간 음성 안내합니다")
            .substringBefore("setTextColor")
        val guideSize = Regex("""textSize\s*=\s*(\d+)""").find(guideSection)?.groupValues?.get(1)?.toInt()
        assertNotNull("guide textSize should be found", guideSize)
        assertTrue("guide textSize >= 18sp (actual=$guideSize)", guideSize!! >= 18)
    }

    @Test
    fun `T5 permission status displayed when granted`() {
        // refreshUI sets permissionStatus to two-line status when granted
        assertTrue(
            "permissionStatus should show 접근성 연결됨",
            source.contains("접근성 연결됨")
        )
        assertTrue(
            "permissionStatus should show 음성 안내 준비됨",
            source.contains("음성 안내 준비됨")
        )
        // permissionStatus color = C_GREEN when granted
        val grantedSection = source.substringAfter("접근성 연결됨").substringBefore("else")
        assertTrue(
            "permissionStatus should use C_GREEN when granted",
            grantedSection.contains("C_GREEN")
        )
    }

    @Test
    fun `T6 permission status warning when denied`() {
        // accessibilityBanner visible when denied (VISIBLE in refreshUI)
        assertTrue(
            "accessibilityBanner should be VISIBLE when denied",
            source.contains("accessibilityBanner.visibility = if (permOk) View.GONE else View.VISIBLE")
        )
        // permissionStatus shows warning text
        assertTrue(
            "permissionStatus should show warning when denied",
            source.contains("접근성 권한 필요")
        )
        // toggleDrivingMode guards against starting without permission
        assertTrue(
            "toggleDrivingMode should block start without accessibility",
            source.contains("!isAccessibilityEnabled()") &&
                source.contains("접근성 권한이 필요합니다")
        )
    }

    // ── Lite 1.2 tests ──

    @Test
    fun `T7 button text not clipped - wrap content height`() {
        // Button uses WC (wrap_content) height instead of fixed dp
        val btnSection = source.substringAfter("═══ 4. 운행 버튼")
            .substringBefore("driveBtn.setOnClickListener")
        assertTrue(
            "driveBtn should use WC (wrap_content) height",
            btnSection.contains("MP, WC")
        )
        // Button uses AI 동승 copy
        assertTrue("driveBtn should show AI 동승 시작", source.contains("AI 동승 시작"))
        assertTrue("driveBtn should show AI 동승 중지", source.contains("AI 동승 중지"))
    }

    @Test
    fun `T8 identity copy displayed`() {
        assertTrue(
            "Guide should contain AI 운행 비서 준비 완료",
            source.contains("AI 운행 비서 준비 완료")
        )
        assertTrue(
            "Guide should contain 실시간 음성 안내",
            source.contains("실시간 음성 안내합니다")
        )
    }

    @Test
    fun `T9 permission status two line descriptive`() {
        // Two-line permission status when granted
        val permSection = source.substringAfter("if (permOk)")
            .substringBefore("} else")
        assertTrue(
            "Should have two-line permission status",
            permSection.contains("접근성 연결됨") && permSection.contains("음성 안내 준비됨")
        )
    }

    @Test
    fun `T10 lite 1_1_a text sizes preserved`() {
        // Title 28sp bold
        val titleSection = source.substringAfter("OnTheWay Lite").substringBefore("gravity")
        assertTrue("Title should be 28sp", titleSection.contains("textSize = 28f"))
        assertTrue("Title should be bold", titleSection.contains("Typeface.BOLD"))

        // Guide text >= 20sp
        val guideSection = source.substringAfter("AI 운행 비서").substringBefore("setTextColor")
        val guideSize = Regex("""textSize\s*=\s*(\d+)""").find(guideSection)?.groupValues?.get(1)?.toInt()
        assertNotNull("guide textSize found", guideSize)
        assertTrue("guide textSize >= 20sp", guideSize!! >= 20)
    }

    @Test
    fun `T3 start button still toggles driving mode`() {
        // driveBtn click listener calls toggleDrivingMode
        assertTrue(
            "driveBtn should call toggleDrivingMode",
            source.contains("driveBtn.setOnClickListener { toggleDrivingMode() }")
        )
        // toggleDrivingMode calls DrivingModeManager.setMode
        assertTrue(
            "toggleDrivingMode should call DrivingModeManager.setMode",
            source.contains("DrivingModeManager.setMode(this, newMode")
        )
    }
}

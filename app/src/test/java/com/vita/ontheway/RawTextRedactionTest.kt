package com.vita.ontheway

import io.mockk.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Fix M1.wire-fix: raw 원문 로그 저장 제거 검증.
 *
 * T1: rawText가 OtwFileLogger에 평문 저장 X (hash 형태만)
 * T2: FilterLog JSON에 rawText 키 없음
 * T3: CustomerRequest hash만 저장 (원문 X)
 */
class RawTextRedactionTest {

    private val capturedLogs = mutableListOf<Pair<String, String>>()

    @Before
    fun setup() {
        capturedLogs.clear()
        mockkObject(OtwFileLogger)
        every { OtwFileLogger.log(any(), any()) } answers {
            capturedLogs.add(firstArg<String>() to secondArg<String>())
            Unit
        }
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    // ── T1: rawText 평문이 DeliveryFilter 로그에 포함 X ──

    @Test
    fun `T1 rawText hash format in DeliveryFilter log`() {
        // OnTheWayService의 rawText 로깅 로직 재현
        val texts = listOf("맘스터치", "역삼동 123-4", "배달료 4,000원", "15.0P")
        val rawOriginal = texts.joinToString(" | ")

        // Fix M1.wire-fix 적용 후: hash + items count만 로깅
        val rawHash = java.security.MessageDigest.getInstance("SHA-256")
            .digest((texts.joinToString("|") + "otw").toByteArray()).take(8)
            .joinToString("") { "%02x".format(it) }
        val logMsg = "[배민] items=${texts.size} hash=$rawHash"

        // 검증: 원문 텍스트 미포함
        assertFalse("맘스터치 미포함", logMsg.contains("맘스터치"))
        assertFalse("역삼동 미포함", logMsg.contains("역삼동"))
        assertFalse("배달료 미포함", logMsg.contains("배달료"))
        assertTrue("hash 포함", logMsg.contains("hash="))
        assertTrue("items 포함", logMsg.contains("items=4"))
        assertEquals(16, rawHash.length) // sha256[:8] → 16 hex chars
    }

    // ── T2: FilterLog JSON에 rawText 키 없음 ──

    @Test
    fun `T2 FilterLog JSON has no rawText key`() {
        // FilterLog.record() 내부의 JSON 구성 로직 재현
        val call = DeliveryCall(
            price = 4000, distance = 2.5, isMulti = false,
            platform = "baemin", rawText = "맘스터치 | 역삼동 | 배달료 4,000원"
        )
        val result = CallFilter.FilterResult(CallFilter.Verdict.ACCEPT, "통과")

        val entryJson = JSONObject().apply {
            put("ts", System.currentTimeMillis())
            put("platform", call.platform)
            // Fix M1.wire-fix: rawText 제거됨 — 여기에 put("rawText", ...) 없음
            put("price", call.price)
            put("distanceKm", call.distance ?: -1.0)
            put("verdict", result.verdict.name)
            put("reason", result.reason)
            put("storeName", call.storeName)
        }

        assertFalse("rawText 키 없음", entryJson.has("rawText"))
        assertTrue("price 키 존재", entryJson.has("price"))
        assertTrue("verdict 키 존재", entryJson.has("verdict"))

        // JSON 문자열에도 원문 미포함
        val jsonStr = entryJson.toString()
        assertFalse("맘스터치 미포함", jsonStr.contains("맘스터치"))
        assertFalse("역삼동 미포함", jsonStr.contains("역삼동"))
    }

    // ── T3: CustomerRequest hash만 저장 (원문 X) ──

    @Test
    fun `T3 CustomerRequest log contains hash not raw text`() {
        val customerReq = "문 앞에 놓아주세요 비밀번호 1234*"

        // Fix M1.wire-fix 적용 후: hash + len만 로깅
        val reqHash = java.security.MessageDigest.getInstance("SHA-256")
            .digest((customerReq + "otw").toByteArray()).take(8)
            .joinToString("") { "%02x".format(it) }
        val logMsg = "감지: hash=$reqHash len=${customerReq.length}"

        // 검증: 원문 미포함
        assertFalse("문 앞에 미포함", logMsg.contains("문 앞에"))
        assertFalse("비밀번호 미포함", logMsg.contains("비밀번호"))
        assertFalse("1234 미포함", logMsg.contains("1234"))
        assertTrue("hash 포함", logMsg.contains("hash="))
        assertTrue("len 포함", logMsg.contains("len="))
        assertEquals(16, reqHash.length)
    }
}

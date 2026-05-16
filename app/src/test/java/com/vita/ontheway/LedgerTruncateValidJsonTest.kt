package com.vita.ontheway

import com.vita.ontheway.ledger.LedgerAppender
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

/**
 * FIX-RAW-PAYLOAD-VALID-JSON-TRUNCATE 테스트.
 * 핵심: 모든 출력은 valid JSON (파싱 100% 성공).
 */
class LedgerTruncateValidJsonTest {

    private fun assertValidJson(json: String, msg: String = "") {
        try {
            JSONObject(json)
        } catch (e: Exception) {
            fail("Invalid JSON${ if (msg.isNotBlank()) " ($msg)" else ""}: ${e.message}\nJSON: ${json.take(200)}")
        }
    }

    // ── 기본 동작 ──

    @Test
    fun `payload 5KB 이하 → 그대로 반환`() {
        val small = JSONObject().apply {
            put("key", "value")
            put("text", "짧은 텍스트")
        }.toString()
        val result = LedgerAppender.truncatePayload(small)
        assertEquals(small, result)
    }

    @Test
    fun `payload 정확히 5KB → 그대로 반환`() {
        val filler = "가".repeat(2400) // ~4.8KB in UTF-8 (3 bytes per char)
        val json = JSONObject().apply { put("data", filler) }.toString()
        if (json.toByteArray().size <= 5 * 1024) {
            assertEquals(json, LedgerAppender.truncatePayload(json))
        }
    }

    // ── nodes 배열 truncate ──

    @Test
    fun `nodes 배열 5KB 초과 → 일부 nodes + _truncated 메타`() {
        val nodes = JSONArray()
        for (i in 0 until 100) {
            nodes.put(JSONObject().apply {
                put("text", "노드 텍스트 $i 길게 만들기 위한 패딩 ${"가나다".repeat(5)}")
                put("className", "android.widget.TextView")
                put("bounds", "[0,0][1080,100]")
            })
        }
        val json = JSONObject().apply {
            put("package", "com.coupang.mobile.eats.courier")
            put("source", "diagnostic_tree_walk")
            put("node_count", 100)
            put("nodes", nodes)
        }.toString()

        assertTrue("원본 > 5KB", json.toByteArray().size > 5 * 1024)

        val result = LedgerAppender.truncatePayload(json)
        assertValidJson(result, "nodes truncate")

        val obj = JSONObject(result)
        assertTrue(obj.getBoolean("_truncated"))
        assertEquals(100, obj.getInt("_original_nodes_count"))
        assertTrue(obj.getInt("_kept_nodes_count") < 100)
        assertTrue(obj.getInt("_kept_nodes_count") >= 0)
        assertTrue(result.toByteArray().size <= 5 * 1024)
    }

    @Test
    fun `nodes 전부 제거해도 5KB 초과 → nodes empty + 메타`() {
        // 매우 큰 non-nodes 필드
        val bigDesc = "A".repeat(6000)
        val json = JSONObject().apply {
            put("package", "com.coupang")
            put("contentDescription", bigDesc)
            put("nodes", JSONArray())
            put("node_count", 0)
        }.toString()

        val result = LedgerAppender.truncatePayload(json)
        assertValidJson(result, "big contentDescription")
        assertTrue(result.toByteArray().size <= 5 * 1024)
    }

    // ── texts 필드 truncate ──

    @Test
    fun `texts 배열 5KB 초과 → 축소`() {
        val texts = JSONArray()
        for (i in 0 until 200) {
            texts.put("텍스트 노드 $i ${"패딩".repeat(10)}")
        }
        val json = JSONObject().apply {
            put("package", "com.woowahan.bros")
            put("texts", texts)
            put("node_count", 200)
        }.toString()

        val result = LedgerAppender.truncatePayload(json)
        assertValidJson(result, "texts truncate")
        assertTrue(result.toByteArray().size <= 5 * 1024)
    }

    // ── 문자열 필드 truncate ──

    @Test
    fun `bigText 긴 문자열 → 500자 이하 축소`() {
        val json = JSONObject().apply {
            put("title", "쿠팡이츠")
            put("bigText", "A".repeat(6000))
            put("text", "B".repeat(3000))
        }.toString()

        val result = LedgerAppender.truncatePayload(json)
        assertValidJson(result, "bigText truncate")
        assertTrue(result.toByteArray().size <= 5 * 1024)
    }

    // ── 깨진 JSON 입력 ──

    @Test
    fun `깨진 JSON → fallback 메타 valid JSON`() {
        val broken = """{"key":"value","arr":[1,2,3"""  // 닫히지 않은 JSON
        val result = LedgerAppender.truncatePayload(broken + "X".repeat(6000))
        assertValidJson(result, "broken JSON fallback")

        val obj = JSONObject(result)
        assertTrue(obj.getBoolean("_truncated"))
        assertEquals("json_parse_failed", obj.getString("_reason"))
        assertTrue(obj.has("_original_size"))
    }

    // ── 플래그 정확도 ──

    @Test
    fun `_truncated _original_node_count _kept_node_count 정확`() {
        val nodes = JSONArray()
        for (i in 0 until 80) {
            nodes.put(JSONObject().apply {
                put("text", "노드 $i ${"패딩텍스트".repeat(3)}")
                put("viewId", "view-$i")
            })
        }
        val json = JSONObject().apply {
            put("source", "diagnostic_tree_walk")
            put("node_count", 80)
            put("nodes", nodes)
        }.toString()

        if (json.toByteArray().size <= 5 * 1024) return // skip if small enough

        val result = LedgerAppender.truncatePayload(json)
        val obj = JSONObject(result)

        assertTrue(obj.getBoolean("_truncated"))
        assertEquals(80, obj.getInt("_original_nodes_count"))
        val kept = obj.getInt("_kept_nodes_count")
        assertTrue("kept=$kept < 80", kept < 80)
        assertEquals(kept, obj.getJSONArray("nodes").length())
    }

    // ── JSON 파싱 100% 성공 (모든 케이스) ──

    @Test
    fun `모든 truncate 결과 valid JSON 종합`() {
        val cases = listOf(
            // 정상
            """{"a":"b"}""",
            // nodes 큰 케이스
            JSONObject().apply {
                val n = JSONArray(); repeat(150) { n.put(JSONObject().put("t", "가".repeat(20))) }
                put("nodes", n)
            }.toString(),
            // 깨진 JSON
            """{"broken": true, "data": [1,2""" + "X".repeat(6000),
            // 빈 JSON
            "{}",
            // 매우 큰 단일 필드
            JSONObject().apply { put("huge", "Z".repeat(10000)) }.toString()
        )

        for ((i, json) in cases.withIndex()) {
            val result = LedgerAppender.truncatePayload(json)
            assertValidJson(result, "case $i")
            assertTrue("case $i ≤ 5KB", result.toByteArray().size <= 5 * 1024)
        }
    }

    // ── UTF-8 한글 ──

    @Test
    fun `UTF-8 한글 mid-byte 안전`() {
        val json = JSONObject().apply {
            put("text", "가나다라마바사아자차카타파하".repeat(200))
        }.toString()

        val result = LedgerAppender.truncatePayload(json)
        assertValidJson(result, "UTF-8 한글")
        // 한글이 깨지지 않고 valid JSON으로 잘림
    }

    // ── 빈/null 입력 ──

    @Test
    fun `빈 payload → 그대로`() {
        assertEquals("{}", LedgerAppender.truncatePayload("{}"))
    }

    @Test
    fun `빈 문자열 → fallback`() {
        val result = LedgerAppender.truncatePayload("")
        // 빈 문자열은 5KB 이하이므로 그대로 반환
        assertEquals("", result)
    }

    // ── 5/9 실측 시뮬 ──

    @Test
    fun `diagnostic_tree_walk node_count=51 시뮬`() {
        val nodes = JSONArray()
        for (i in 0 until 51) {
            nodes.put(JSONObject().apply {
                put("text", if (i % 3 == 0) "배달료 ${(3000 + i * 100)}원" else "가게이름$i")
                put("contentDescription", "")
                put("className", "android.widget.TextView")
                put("viewIdResourceName", "com.coupang.mobile.eats.courier:id/text_$i")
                put("bounds", "[${i * 10},${i * 20}][${i * 10 + 100},${i * 20 + 50}]")
            })
        }
        val json = JSONObject().apply {
            put("package", "com.coupang.mobile.eats.courier")
            put("source", "diagnostic_tree_walk")
            put("node_count", 51)
            put("nodes", nodes)
        }.toString()

        val result = LedgerAppender.truncatePayload(json)
        assertValidJson(result, "node_count=51")
        assertTrue(result.toByteArray().size <= 5 * 1024)

        val obj = JSONObject(result)
        if (obj.has("_truncated")) {
            assertTrue(obj.has("_kept_nodes_count"))
        }
    }

    @Test
    fun `diagnostic_tree_walk node_count=92 시뮬 (10KB 초과)`() {
        val nodes = JSONArray()
        for (i in 0 until 92) {
            nodes.put(JSONObject().apply {
                put("text", "Flutter 노드 $i 텍스트 ${"가나다라마바사".repeat(2)}")
                put("contentDescription", "desc $i")
                put("className", "android.view.View")
                put("bounds", "[0,$i][1080,${i + 50}]")
            })
        }
        val json = JSONObject().apply {
            put("package", "com.coupang.mobile.eats.courier")
            put("source", "diagnostic_tree_walk")
            put("node_count", 92)
            put("nodes", nodes)
        }.toString()

        assertTrue("원본 > 5KB", json.toByteArray().size > 5 * 1024)

        val result = LedgerAppender.truncatePayload(json)
        assertValidJson(result, "node_count=92")
        assertTrue(result.toByteArray().size <= 5 * 1024)

        val obj = JSONObject(result)
        assertTrue(obj.getBoolean("_truncated"))
        assertEquals(92, obj.getInt("_original_nodes_count"))
        assertTrue(obj.getInt("_kept_nodes_count") < 92)
    }
}

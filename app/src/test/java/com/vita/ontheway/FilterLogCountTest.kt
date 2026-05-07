package com.vita.ontheway

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class FilterLogCountTest {

    private fun makeEntry(verdict: String, price: Int = 3000, platform: String = "baemin"): JSONObject {
        return JSONObject().apply {
            put("ts", System.currentTimeMillis())
            put("platform", platform)
            put("price", price)
            put("verdict", verdict)
            put("reason", "test")
        }
    }

    @Test
    fun `감지 5건 + ACCEPTED 3건 = total 5`() {
        val arr = JSONArray()
        repeat(3) { arr.put(makeEntry("REJECT", 2000)) }
        repeat(2) { arr.put(makeEntry("PASS", 4000)) }
        repeat(3) { arr.put(makeEntry("ACCEPTED", 3500)) }

        val detail = FilterLog.countDetections(arr)
        assertEquals(5, detail.total)
        assertEquals(3, detail.reject)
        assertEquals(2, detail.accept)
    }

    @Test
    fun `감지 10건 ACCEPTED 0건 = total 10`() {
        val arr = JSONArray()
        repeat(6) { arr.put(makeEntry("REJECT", 2500)) }
        repeat(4) { arr.put(makeEntry("PASS", 5000)) }

        val detail = FilterLog.countDetections(arr)
        assertEquals(10, detail.total)
    }

    @Test
    fun `ACCEPTED only 5건 = total 0`() {
        val arr = JSONArray()
        repeat(5) { arr.put(makeEntry("ACCEPTED", 3000)) }

        val detail = FilterLog.countDetections(arr)
        assertEquals(0, detail.total)
        assertEquals(0, detail.reject)
        assertEquals(0, detail.accept)
    }

    @Test
    fun `혼합 verdict - ACCEPTED만 제외`() {
        val arr = JSONArray()
        arr.put(makeEntry("REJECT", 2000))
        arr.put(makeEntry("PASS", 4000))
        arr.put(makeEntry("ACCEPTED", 3500))
        arr.put(makeEntry("REJECT", 3000))
        arr.put(makeEntry("ACCEPTED", 2500))

        val detail = FilterLog.countDetections(arr)
        assertEquals(3, detail.total)       // REJECT 2 + PASS 1
        assertEquals(2, detail.reject)
        assertEquals(1, detail.accept)
        assertEquals(2500, detail.rejectAvgPrice) // (2000+3000)/2
        assertEquals(4000, detail.acceptAvgPrice)
    }

    @Test
    fun `빈 배열 = total 0`() {
        val detail = FilterLog.countDetections(JSONArray())
        assertEquals(0, detail.total)
        assertEquals(0, detail.reject)
        assertEquals(0, detail.accept)
    }

    @Test
    fun `verdict null = ACCEPTED 아님 (포함)`() {
        val arr = JSONArray()
        arr.put(JSONObject().apply {
            put("ts", System.currentTimeMillis())
            put("platform", "baemin")
            put("price", 3000)
            // verdict 없음
        })
        val detail = FilterLog.countDetections(arr)
        assertEquals(1, detail.total)
    }
}

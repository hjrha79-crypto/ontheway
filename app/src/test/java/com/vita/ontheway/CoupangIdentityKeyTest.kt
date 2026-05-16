package com.vita.ontheway

import org.junit.Assert.*
import org.junit.Test

class CoupangIdentityKeyTest {

    @org.junit.Before
    fun setup() { CoupangIdentityKey.resetForTest() }
    @org.junit.After
    fun teardown() { CoupangIdentityKey.resetForTest() }

    @Test
    fun `primary key confidence 0점9`() {
        val key = CoupangIdentityKey.primary("sbn-abc", 1234567890L)
        assertEquals("coupang:sbn-abc:1234567890", key.key)
        assertEquals(CoupangIdentityKey.KeyType.PRIMARY, key.type)
        assertEquals(0.9, key.confidence, 0.001)
    }

    @Test
    fun `fallback key confidence 0점5`() {
        val key = CoupangIdentityKey.fallback(3450, 2.0, 1, 1234567890L, "test raw")
        assertEquals(CoupangIdentityKey.KeyType.FALLBACK, key.type)
        assertEquals(0.5, key.confidence, 0.001)
        assertTrue(key.key.startsWith("coupang:3450:2.0:1:"))
    }

    @Test
    fun `fallback rawHash 충돌 분리`() {
        val key1 = CoupangIdentityKey.fallback(3000, 0.6, 1, 1234560000L, "BBQ 3000원")
        val key2 = CoupangIdentityKey.fallback(3000, 0.6, 1, 1234560000L, "맘스터치 3000원")
        // 같은 price/dist/bundle/bucket이지만 다른 rawText → 다른 rawHash
        assertNotEquals(key1.key, key2.key)
    }

    @Test
    fun `fallback rawHash 일치`() {
        val key1 = CoupangIdentityKey.fallback(3000, 0.6, 1, 1234560000L, "same text")
        val key2 = CoupangIdentityKey.fallback(3000, 0.6, 1, 1234560000L, "same text")
        assertEquals(key1.key, key2.key)
    }

    @Test
    fun `같은 timeBucket = 같은 bucket 부분`() {
        val key1 = CoupangIdentityKey.fallback(3450, 2.0, 1, 1234560000L, "a")
        val key2 = CoupangIdentityKey.fallback(3450, 2.0, 1, 1234565000L, "a")
        assertEquals(key1.key, key2.key) // 5초 차이 = 같은 10초 bucket
    }

    @Test
    fun `다른 timeBucket = 다른 key`() {
        val key1 = CoupangIdentityKey.fallback(3450, 2.0, 1, 1234560000L, "a")
        val key2 = CoupangIdentityKey.fallback(3450, 2.0, 1, 1234580000L, "a")
        assertNotEquals(key1.key, key2.key) // 20초 차이 = 다른 bucket
    }

    @Test
    fun `resolve sbnKey 있으면 primary`() {
        val key = CoupangIdentityKey.resolve("sbn-1", 1000L, 3000, 2.0, 1)
        assertEquals(CoupangIdentityKey.KeyType.PRIMARY, key.type)
        assertEquals(0.9, key.confidence, 0.001)
    }

    @Test
    fun `resolve sbnKey blank이면 fallback`() {
        val key = CoupangIdentityKey.resolve("", 1000L, 3000, 2.0, 1, "raw")
        assertEquals(CoupangIdentityKey.KeyType.FALLBACK, key.type)
        assertEquals(0.5, key.confidence, 0.001)
    }

    @Test
    fun `resolve sbnKey null이면 fallback`() {
        val key = CoupangIdentityKey.resolve(null, 1000L, 3000, 2.0, 1)
        assertEquals(CoupangIdentityKey.KeyType.FALLBACK, key.type)
    }

    @Test
    fun `distance null fallback`() {
        val key = CoupangIdentityKey.fallback(3000, null, 1, 1000L, "raw")
        assertTrue(key.key.contains("null"))
    }
}

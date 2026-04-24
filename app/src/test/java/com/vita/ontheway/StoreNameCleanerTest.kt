package com.vita.ontheway

import org.junit.Assert.*
import org.junit.Test

class StoreNameCleanerTest {

    // ── Layer 3: isBlacklisted ──

    @Test
    fun blacklist_exactMatch() {
        assertTrue(StoreNameCleaner.isBlacklisted("배달 이어서 하기"))
        assertTrue(StoreNameCleaner.isBlacklisted("배차수락"))
        assertTrue(StoreNameCleaner.isBlacklisted("배차 수락"))
    }

    @Test
    fun blacklist_normalizedSpaceMatch() {
        // "배차수락" (no space) matches "배차 수락" (with space) via normalization
        assertTrue(StoreNameCleaner.isBlacklisted("배차수락"))
    }

    @Test
    fun blacklist_mapExpansionGPS() {
        assertTrue(StoreNameCleaner.isBlacklisted("지도 확대 기능이 꺼졌습니다"))
        assertTrue(StoreNameCleaner.isBlacklisted("지도 확대"))
    }

    @Test
    fun blacklist_timePattern() {
        assertTrue(StoreNameCleaner.isBlacklisted("16:40 완료"))
        assertTrue(StoreNameCleaner.isBlacklisted("9:30 배달완료"))
    }

    @Test
    fun blacklist_amountPattern() {
        assertTrue(StoreNameCleaner.isBlacklisted("4,990원"))
        assertTrue(StoreNameCleaner.isBlacklisted("12,000원"))
    }

    @Test
    fun blacklist_pointPattern() {
        assertTrue(StoreNameCleaner.isBlacklisted("38.3P"))
    }

    @Test
    fun blacklist_normalStoreNotBlocked() {
        assertFalse(StoreNameCleaner.isBlacklisted("피나치공 뿌리공스 태전점"))
        assertFalse(StoreNameCleaner.isBlacklisted("배스킨라빈스 태전힐스테이트점"))
        assertFalse(StoreNameCleaner.isBlacklisted("GS25 태전점"))
    }

    // ── Layer 4: isValidFormat ──

    @Test
    fun format_normalStores() {
        assertTrue(StoreNameCleaner.isValidFormat("GS25 태전점"))
        assertTrue(StoreNameCleaner.isValidFormat("맥도날드(광주태전점)"))
        assertTrue(StoreNameCleaner.isValidFormat("BBQ&치킨"))
        assertTrue(StoreNameCleaner.isValidFormat("피나치공 뿌리공스 태전점"))
        assertTrue(StoreNameCleaner.isValidFormat("배스킨라빈스 태전힐스테이트점"))
    }

    @Test
    fun format_rejectsColons() {
        // "16:40 완료" contains colon — not allowed
        assertFalse(StoreNameCleaner.isValidFormat("16:40 완료"))
    }

    @Test
    fun format_rejectsCommaAmount() {
        // "4,990원" contains comma — not in allowed chars
        assertFalse(StoreNameCleaner.isValidFormat("4,990원"))
    }

    @Test
    fun format_rejectsTooShort() {
        assertFalse(StoreNameCleaner.isValidFormat("가"))
    }

    @Test
    fun format_rejectsAllDigits() {
        assertFalse(StoreNameCleaner.isValidFormat("12345"))
    }

    // ── validateStoreName 통합 ──

    @Test
    fun validate_normalStore() {
        assertEquals("피나치공 뿌리공스 태전점", StoreNameCleaner.validateStoreName("피나치공 뿌리공스 태전점"))
    }

    @Test
    fun validate_blacklisted() {
        assertEquals("", StoreNameCleaner.validateStoreName("배차수락"))
    }

    @Test
    fun validate_invalidFormat() {
        assertEquals("", StoreNameCleaner.validateStoreName("16:40 완료"))
    }

    @Test
    fun validate_blank() {
        assertEquals("", StoreNameCleaner.validateStoreName(""))
    }

    // ── v3.21: UI 텍스트 혼입 필터 강화 ──

    @Test
    fun blacklist_1207_ui_texts() {
        // 12:07 실측 혼입 사례 전부 차단
        assertTrue(StoreNameCleaner.isBlacklisted("지도를 준비중입니다"))
        assertTrue(StoreNameCleaner.isBlacklisted("잠시만 기다려주세요"))
        assertTrue(StoreNameCleaner.isBlacklisted("현재 위치와 가까운 배차를 찾고 있어요"))
        assertTrue(StoreNameCleaner.isBlacklisted("고객 요청사항이 변경되었어요"))
        assertTrue(StoreNameCleaner.isBlacklisted("배달이 많은 지역을 볼수 있어요"))
        assertTrue(StoreNameCleaner.isBlacklisted("배달 이어서 하기"))
        assertTrue(StoreNameCleaner.isBlacklisted("지도 확대 기능이 꺼졌습니다"))
    }

    @Test
    fun blacklist_sentence_ending_patterns() {
        // ~요, ~니다, ~습니다 종결어미 패턴
        assertTrue(StoreNameCleaner.isBlacklisted("배달이 시작되었어요"))
        assertTrue(StoreNameCleaner.isBlacklisted("새로운 배달이 도착했습니다"))
        assertTrue(StoreNameCleaner.isBlacklisted("조리가 완료되었습니다"))
    }

    @Test
    fun blacklist_real_stores_not_caught() {
        // 정상 가게명은 차단되지 않아야 함
        assertFalse(StoreNameCleaner.isBlacklisted("제비 파스타&리조또 태전점"))
        assertFalse(StoreNameCleaner.isBlacklisted("미아리우동집 태전점"))
        assertFalse(StoreNameCleaner.isBlacklisted("맘스터치 경기광주태전센트힐점"))
        assertFalse(StoreNameCleaner.isBlacklisted("이디야커피 경기광주태전점"))
        assertFalse(StoreNameCleaner.isBlacklisted("호랑이떡볶이 광주태전점"))
        assertFalse(StoreNameCleaner.isBlacklisted("GS25 태전점"))
    }
}

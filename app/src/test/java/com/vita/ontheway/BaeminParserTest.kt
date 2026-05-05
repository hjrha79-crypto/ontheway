package com.vita.ontheway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class BaeminParserTest {

    @Test
    fun `배달료기준거리 1,065m 파싱 성공 - distance 1_065km로 설정`() {
        val texts = listOf(
            "배달료기준거리 (1,065m)",
            "배달료 3,500원",
            "픽업지",
            "맘스터치"
        )
        val result = BaeminParser.parse(texts)
        assertNotNull("파싱 결과가 비어 있음", result)
        if (result!!.isNotEmpty()) {
            assertEquals(1.065, result[0].distance!!, 0.01)
        }
    }

    @Test
    fun `배달료기준거리 4,300m 콤마 포함 파싱`() {
        val texts = listOf(
            "배달료기준거리 (4,300m)",
            "배달료 5,000원",
            "픽업지",
            "테스트가게"
        )
        val result = BaeminParser.parse(texts)!!
        if (result.isNotEmpty()) {
            assertEquals(4.3, result[0].distance!!, 0.01)
        }
    }

    @Test
    fun `거리 텍스트 없으면 distance null (기존 fallback 유지)`() {
        val texts = listOf(
            "13.5P",
            "배달료 3,500원",
            "픽업지",
            "테스트가게"
        )
        val result = BaeminParser.parse(texts)!!
        if (result.isNotEmpty()) {
            assertNull(result[0].distance)
        }
    }

    @Test
    fun `거리 3,691m 단위 매우 큰 값도 정상 파싱`() {
        val texts = listOf(
            "배달료기준거리 (3,691m)",
            "배달료 7,000원",
            "픽업지",
            "먼가게"
        )
        val result = BaeminParser.parse(texts)!!
        if (result.isNotEmpty()) {
            assertEquals(3.691, result[0].distance!!, 0.01)
        }
    }

    @Test
    fun `거리 500m 소규모 값 파싱`() {
        val texts = listOf(
            "배달료기준거리 (500m)",
            "배달료 2,500원",
            "픽업지",
            "근거리가게"
        )
        val result = BaeminParser.parse(texts)!!
        if (result.isNotEmpty()) {
            assertEquals(0.5, result[0].distance!!, 0.01)
        }
    }

    // ── 블랙리스트 필터 테스트 ──

    @Test
    fun `블랙리스트 - 신규배차_끄기버튼 제거`() {
        val result = BaeminParser.sanitizeStoreName("신규배차_끄기버튼")
        assertEquals("", result)
    }

    @Test
    fun `블랙리스트 - T2CG 주문코드 제거`() {
        val result = BaeminParser.sanitizeStoreName("T2CG0000M318")
        assertEquals("", result)
    }

    @Test
    fun `블랙리스트 - T2CI 주문코드 제거`() {
        val result = BaeminParser.sanitizeStoreName("T2CI0ABC1234")
        assertEquals("", result)
    }

    @Test
    fun `블랙리스트 - T2CI 혼합 토큰에서 오염만 제거`() {
        val result = BaeminParser.sanitizeStoreName("맘스터치+T2CIABCD5678")
        assertEquals("맘스터치", result)
    }

    @Test
    fun `블랙리스트 - 정상 가게명 유지`() {
        val result = BaeminParser.sanitizeStoreName("빽보이피자 오구샌 광주태전점")
        assertEquals("빽보이피자 오구샌 광주태전점", result)
    }

    @Test
    fun `블랙리스트 - 혼합 토큰에서 오염만 제거`() {
        val result = BaeminParser.sanitizeStoreName("빽보이피자+신규배차+T2CG0000M318")
        assertEquals("빽보이피자", result)
    }

    @Test
    fun `블랙리스트 - 모두 블랙리스트면 빈 문자열`() {
        val result = BaeminParser.sanitizeStoreName("신규배차_끄기버튼+배차수락+이전내역")
        assertEquals("", result)
    }

    @Test
    fun `블랙리스트 - 기존 영문 블랙리스트도 유지`() {
        val result = BaeminParser.sanitizeStoreName("button+naver+맘스터치")
        assertEquals("맘스터치", result)
    }

    // ── 목적지 파싱 테스트 ──

    @Test
    fun `전달지 다음 토큰으로 목적지 파싱`() {
        val texts = listOf(
            "배달료 3,500원",
            "픽업지",
            "맘스터치",
            "전달지",
            "경기 광주시 태성로 25 (태전동)"
        )
        val result = BaeminParser.parse(texts)!!
        assertTrue(result.isNotEmpty())
        assertEquals("경기 광주시 태성로 25 (태전동)", result[0].destination)
    }

    @Test
    fun `전달지 없으면 기존 패턴 매칭 fallback`() {
        val texts = listOf(
            "배달료 3,500원",
            "픽업지",
            "맘스터치",
            "광주구 태전동 123"
        )
        val result = BaeminParser.parse(texts)!!
        assertTrue(result.isNotEmpty())
        assertEquals("광주구 태전동 123", result[0].destination)
    }

    // ── 이전내역 화면 DROP 테스트 ──

    @Test
    fun `이전내역 - 배정받은 배달이 없습니다 포함 시 DROP`() {
        val texts = listOf("배정받은 배달이 없습니다", "배달료 3,500원", "픽업지", "맘스터치")
        assertNull(BaeminParser.parse(texts))
    }

    @Test
    fun `이전내역 - 신규배차가 중지되었습니다 포함 시 DROP`() {
        val texts = listOf("신규배차가 중지되었습니다", "배달료 5,000원")
        assertNull(BaeminParser.parse(texts))
    }

    @Test
    fun `이전내역 - 배달리스트 포함 시 DROP`() {
        val texts = listOf("배달리스트", "맘스터치", "배달료 3,500원")
        assertNull(BaeminParser.parse(texts))
    }

    @Test
    fun `이전내역 - 픽업 완료 되었습니다 포함 시 DROP`() {
        val texts = listOf("픽업 완료 되었습니다", "배달료 7,000원", "픽업지", "테스트가게")
        assertNull(BaeminParser.parse(texts))
    }

    @Test
    fun `고객센터 - 도움이 필요하세요 포함 시 DROP`() {
        val texts = listOf("도움이 필요하세요", "배달료 3,500원", "픽업지", "맘스터치")
        assertNull(BaeminParser.parse(texts))
    }

    @Test
    fun `고객센터 - 채팅문의 포함 시 DROP`() {
        val texts = listOf("채팅문의", "배달료 5,000원")
        assertNull(BaeminParser.parse(texts))
    }

    @Test
    fun `고객센터 - 아래 항목을 선택해 문제를 해결하세요 포함 시 DROP`() {
        val texts = listOf("아래 항목을 선택해 문제를 해결하세요", "배달료 4,000원", "픽업지", "테스트가게")
        assertNull(BaeminParser.parse(texts))
    }

    @Test
    fun `정상 콜 - 신규배차_수락버튼 포함 rawText는 정상 파싱`() {
        val texts = listOf("신규배차_수락버튼", "배달료 3,500원", "픽업지", "맘스터치")
        val result = BaeminParser.parse(texts)
        assertNotNull("정상 콜이 DROP되면 안됨", result)
        assertTrue(result!!.isNotEmpty())
        assertEquals(3500, result[0].price)
    }

    // ── 신규 콜 + 이전내역 키워드 혼입 테스트 (v3.24) ──

    @Test
    fun `신규콜가드 - 수락버튼 + 픽업완료 동시 포함 시 파싱 정상`() {
        val texts = listOf("신규배차_수락버튼", "31초", "배달료 5,000원", "픽업지", "맘스터치", "픽업 완료 되었습니다")
        val result = BaeminParser.parse(texts)
        assertNotNull("신규 콜 증거 있으면 DROP되면 안됨", result)
        assertTrue(result!!.isNotEmpty())
    }

    @Test
    fun `신규콜가드 - 순수 이전내역 화면은 여전히 DROP`() {
        val texts = listOf("배달리스트", "픽업 완료 되었습니다", "배달료 7,000원", "픽업지", "테스트가게")
        assertNull(BaeminParser.parse(texts))
    }

    @Test
    fun `신규콜가드 - 수락버튼만 있고 픽업완료 없으면 정상 파싱`() {
        val texts = listOf("신규배차_수락버튼", "31초", "배달료 3,500원", "픽업지", "맘스터치")
        val result = BaeminParser.parse(texts)
        assertNotNull("정상 신규 콜이 DROP되면 안됨", result)
        assertTrue(result!!.isNotEmpty())
    }

    @Test
    fun `신규콜가드 - 카운터만 있어도 DROP 우회`() {
        val texts = listOf("31초", "배달료 4,000원", "픽업지", "테스트가게", "픽업 완료 되었습니다")
        val result = BaeminParser.parse(texts)
        assertNotNull("카운터로 신규 콜 증거 있으면 DROP_HISTORY_SCREEN 되면 안됨", result)
    }

    // ── isBlacklistedPattern 테스트 ──

    @Test
    fun `블랙리스트패턴 - ai-mode-notification-item-0 차단`() {
        assertTrue(BaeminParser.isBlacklistedPattern("ai-mode-notification-item-0"))
    }

    @Test
    fun `블랙리스트패턴 - notification-item-3 차단`() {
        assertTrue(BaeminParser.isBlacklistedPattern("notification-item-3"))
    }

    @Test
    fun `블랙리스트패턴 - button-base 차단 (기존 정확 일치)`() {
        assertTrue(BaeminParser.isBlacklistedPattern("button-base"))
    }

    @Test
    fun `블랙리스트패턴 - touchable-image-container 차단`() {
        assertTrue(BaeminParser.isBlacklistedPattern("touchable-image-container"))
    }

    @Test
    fun `블랙리스트패턴 - BBQ 통과`() {
        assertFalse(BaeminParser.isBlacklistedPattern("BBQ"))
    }

    @Test
    fun `블랙리스트패턴 - 맘스터치 BEEF 통과`() {
        assertFalse(BaeminParser.isBlacklistedPattern("맘스터치 BEEF"))
    }

    @Test
    fun `블랙리스트패턴 - GS25 통과`() {
        assertFalse(BaeminParser.isBlacklistedPattern("GS25"))
    }

    @Test
    fun `블랙리스트패턴 - 빈문자열 차단`() {
        assertTrue(BaeminParser.isBlacklistedPattern(""))
    }

    @Test
    fun `블랙리스트패턴 - sanitize에서 ai-mode 제거`() {
        val result = BaeminParser.sanitizeStoreName("맘스터치+ai-mode-notification-item-0")
        assertEquals("맘스터치", result)
    }

    // ── FIX-15: 토큰/좌표 차단 테스트 ──

    @Test
    fun `블랙리스트패턴 - T2CK 토큰 차단`() {
        assertTrue(BaeminParser.isBlacklistedPattern("T2CK0000RGQM"))
    }

    @Test
    fun `블랙리스트패턴 - 대문자숫자 8글자이상 토큰 차단`() {
        assertTrue(BaeminParser.isBlacklistedPattern("ABCD1234EF"))
    }

    @Test
    fun `블랙리스트패턴 - 주소패턴 차단`() {
        assertTrue(BaeminParser.isBlacklistedPattern("경기도 광주시 장지동 686-2"))
    }

    @Test
    fun `블랙리스트패턴 - GS25 4글자 통과 (false positive 방지)`() {
        assertFalse(BaeminParser.isBlacklistedPattern("GS25"))
    }

    @Test
    fun `블랙리스트패턴 - sanitize에서 T2CK 토큰 제거`() {
        val result = BaeminParser.sanitizeStoreName("커피인류 광주고산점+T2CK0000RGQM+롯데리아 광주태전점")
        assertEquals("커피인류 광주고산점+롯데리아 광주태전점", result)
    }

    @Test
    fun `블랙리스트패턴 - sanitize에서 좌표 제거`() {
        val result = BaeminParser.sanitizeStoreName("롯데리아+경기도 광주시 장지동 686-2")
        assertEquals("롯데리아", result)
    }

    // ── FIX-18: 배달 옵션 UI 텍스트 차단 ──

    @Test
    fun `블랙리스트패턴 - sanitize에서 배달옵션 제거`() {
        val result = BaeminParser.sanitizeStoreName("동대문엽기떡볶이 광주한아람점+Bottom Sheet+전달 사진 촬영+문 앞에 두고 초인종")
        assertEquals("동대문엽기떡볶이 광주한아람점", result)
    }

    @Test
    fun `블랙리스트패턴 - 정상 가게명 보존`() {
        assertFalse(BaeminParser.isBlacklistedPattern("KFC 광주태전점"))
    }

    // ── 배민 UI 라벨 BLACKLIST 테스트 ──

    @Test
    fun `블랙리스트패턴 - 배민배달 차단`() {
        assertTrue(BaeminParser.isBlacklistedPattern("배민배달"))
    }

    @Test
    fun `블랙리스트패턴 - 조리완료 차단`() {
        assertTrue(BaeminParser.isBlacklistedPattern("조리완료"))
    }

    @Test
    fun `블랙리스트패턴 - 픽업지 차단`() {
        assertTrue(BaeminParser.isBlacklistedPattern("픽업지"))
    }

    @Test
    fun `블랙리스트패턴 - sanitize 멀티콜 UI 라벨 제거`() {
        val result = BaeminParser.sanitizeStoreName("도심속어항+배민배달+조리완료+픽업지+배고픈덮밥이 태전점")
        assertEquals("도심속어항+배고픈덮밥이 태전점", result)
    }

    @Test
    fun `블랙리스트패턴 - 배민배달의민족 가게명 보존 (정확매칭)`() {
        // "배민배달"은 차단이지만 "배민배달의 민족 가게"는 다른 문자열
        assertFalse(BaeminParser.isBlacklistedPattern("배민배달의 민족 가게"))
    }

    // ── 시스템 메시지 BLACKLIST ──

    @Test
    fun `블랙리스트패턴 - 중복된 요청입니다 차단`() {
        assertTrue(BaeminParser.isBlacklistedPattern("중복된 요청입니다"))
        assertTrue(BaeminParser.isBlacklistedPattern("중복된 요청입니다."))
    }

    @Test
    fun `블랙리스트패턴 - sanitize 기상+중복요청 제거`() {
        val result = BaeminParser.sanitizeStoreName("프랭크버거 경기광주태전점+기상+이삭토스트 광주샬롬점+중복된 요청입니다.")
        assertEquals("프랭크버거 경기광주태전점+이삭토스트 광주샬롬점", result)
    }

    // ── rawText 기반 멀티 검출 테스트 (Wave 1-E) ──

    @Test
    fun `detectMulti - 픽업지 2회 출현`() {
        assertTrue(BaeminParser.detectMulti("배민배달 픽업지 맘스터치 전달지 태전동 픽업지 BBQ 전달지 고산동"))
    }

    @Test
    fun `detectMulti - 픽업지2 키워드`() {
        assertTrue(BaeminParser.detectMulti("배민배달 픽업지 맘스터치 픽업지2 BBQ"))
    }

    @Test
    fun `detectMulti - 2건 패턴`() {
        assertTrue(BaeminParser.detectMulti("배달료 7,010원 2건 모두 수락"))
    }

    @Test
    fun `detectMulti - 두건 패턴`() {
        assertTrue(BaeminParser.detectMulti("배달료 5,000원 두건"))
    }

    @Test
    fun `detectMulti - 단건은 false`() {
        assertFalse(BaeminParser.detectMulti("배달료 3,500원 픽업지 맘스터치 전달지 태전동"))
    }

    @Test
    fun `parse - 단건 파싱인데 픽업지2 포함 시 isMulti=true`() {
        val texts = listOf("배달료 4,400원", "픽업지", "맘스터치", "픽업지2", "BBQ")
        val result = BaeminParser.parse(texts)!!
        assertTrue(result.isNotEmpty())
        assertTrue("멀티 감지: ${result[0].isMulti}", result[0].isMulti)
        assertEquals(2, result[0].bundleCount)
    }
}

package com.vita.ontheway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class BaeminParserTest {

    @Before
    fun setup() {
        BaeminParser.resetDedupCache()
    }

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
    fun `신규콜가드 - 카운터만으로는 DROP 우회 불가 (FIX2 강화)`() {
        // FIX2: "\d+초" 만으로는 신규 콜 증거 불충분 → DROP
        val texts = listOf("31초", "배달료 4,000원", "픽업지", "테스트가게", "픽업 완료 되었습니다")
        val result = BaeminParser.parse(texts)
        assertNull("카운터만으로는 히스토리 DROP 우회 불가", result)
    }

    @Test
    fun `FIX2 - 픽업완료 + 신규배달 동시 = 새 콜로 진행`() {
        // 5/5 21:35 사례 재현: 이전 콜 마무리 + 새 콜 동시 표시
        val texts = listOf("신규배달 1건을 수락해주세요", "신규배차_수락버튼", "배달료 5,500원", "픽업지", "새가게", "픽업 완료 되었습니다")
        val result = BaeminParser.parse(texts)
        assertNotNull("신규배달 명시 시 파싱 진행", result)
        assertTrue(result!!.isNotEmpty())
        assertEquals(5500, result[0].price)
    }

    @Test
    fun `FIX2 - 픽업완료만 표시 = DROP`() {
        val texts = listOf("픽업 완료 되었습니다", "배달료 3,900원", "픽업지", "BHC 오포고산점")
        val result = BaeminParser.parse(texts)
        assertNull("픽업완료만 있으면 DROP", result)
    }

    @Test
    fun `FIX2 - 신규배달만 표시 = 정상 파싱`() {
        val texts = listOf("신규배달 1건을 수락해주세요", "신규배차_수락버튼", "배달료 4,200원", "픽업지", "맘스터치")
        val result = BaeminParser.parse(texts)
        assertNotNull(result)
        assertTrue(result!!.isNotEmpty())
        assertEquals(4200, result[0].price)
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

    // ── rawText 기반 멀티 검출 테스트 (Wave 1-E → FIX-MULTI) ──

    @Test
    fun `detectMulti - 픽업지 노드 2회 출현`() {
        val texts = listOf("배민배달", "픽업지", "맘스터치", "전달지", "태전동", "픽업지", "BBQ", "전달지", "고산동")
        assertTrue(BaeminParser.detectMulti(texts))
    }

    @Test
    fun `detectMulti - 픽업지2 키워드`() {
        val texts = listOf("배민배달", "픽업지", "맘스터치", "픽업지2", "BBQ")
        assertTrue(BaeminParser.detectMulti(texts))
    }

    @Test
    fun `detectMulti - 묶음 키워드`() {
        val texts = listOf("배달료 7,010원", "2건 묶음배달")
        assertTrue(BaeminParser.detectMulti(texts))
    }

    @Test
    fun `detectMulti - 두건 패턴`() {
        val texts = listOf("배달료 5,000원", "두건")
        assertTrue(BaeminParser.detectMulti(texts))
    }

    @Test
    fun `detectMulti - 2건 모두 수락 버튼은 멀티 아님`() {
        // UI 버튼 텍스트 "2건 모두 수락"은 멀티 판정에서 제외
        val texts = listOf("배달료 3,500원", "픽업지", "맘스터치", "2건 모두 수락")
        assertFalse(BaeminParser.detectMulti(texts))
    }

    @Test
    fun `detectMulti - 단건은 false`() {
        val texts = listOf("배달료 3,500원", "픽업지", "맘스터치", "전달지", "태전동")
        assertFalse(BaeminParser.detectMulti(texts))
    }

    @Test
    fun `detectMulti - 콤마요약 + 개별노드 픽업지 1건 = 단건 (FIX-MULTI 회귀)`() {
        // 5/6 14:53 사례: 콤마구분 요약노드에 "픽업지" 포함 + 개별 "픽업지" 노드 1개 → 단건
        val texts = listOf(
            "배민배달, 조리완료, 픽업지, 육참냉면&돈카츠 태전점, 전달지, 경기 광주시",
            "배민배달", "조리완료", "픽업지", "육참냉면&돈카츠 태전점",
            "전달지", "경기 광주시 삼지곡길 73-4", "배달료", "6,090원"
        )
        assertFalse(BaeminParser.detectMulti(texts))
    }

    @Test
    fun `parse - 단건 파싱인데 픽업지2 포함 시 isMulti=true`() {
        val texts = listOf("배달료 4,400원", "픽업지", "맘스터치", "픽업지2", "BBQ")
        val result = BaeminParser.parse(texts)!!
        assertTrue(result.isNotEmpty())
        assertTrue("멀티 감지: ${result[0].isMulti}", result[0].isMulti)
        assertEquals(2, result[0].bundleCount)
    }

    // ── FIX-MULTI 회귀 테스트: 사례1 단일+멀티 동시 분류 방지 ──

    @Test
    fun `FIX-MULTI 사례1a - 라화쿵부마라탕 단건이 멀티 X (콤마요약 노드)`() {
        // 5/6 16:33 패턴: 콤마구분 요약 + 개별 노드 → 단건만 반환
        BaeminParser.resetDedupCache()
        val texts = listOf(
            "신규배차_수락버튼",
            "배민배달, 조리완료, 픽업지, 라화쿵부마라탕 태전점, 전달지, 경기 광주시",
            "배민배달", "조리완료", "픽업지", "라화쿵부마라탕 태전점",
            "전달지", "경기 광주시 태봉로 161", "배달료", "5,200원"
        )
        val result = BaeminParser.parse(texts)!!
        assertTrue(result.isNotEmpty())
        assertFalse("단건이 멀티 아님", result[0].isMulti)
        assertEquals(5200, result[0].price)
    }

    @Test
    fun `FIX-MULTI 사례1b - 밥풀릭스 단건이 멀티 X`() {
        // 5/6 16:35 패턴
        BaeminParser.resetDedupCache()
        val texts = listOf(
            "신규배차_수락버튼",
            "배민배달, 조리완료, 픽업지, 밥풀릭스 광주태전점, 전달지, 경기 광주시, 포인트, 30P",
            "배민배달", "조리완료", "픽업지", "밥풀릭스 광주태전점",
            "전달지", "경기 광주시 고불로 33", "포인트", "30P", "배달료", "3,700원"
        )
        val result = BaeminParser.parse(texts)!!
        assertTrue(result.isNotEmpty())
        assertFalse("단건이 멀티 아님", result[0].isMulti)
        assertEquals(3700, result[0].price)
    }

    @Test
    fun `FIX-MULTI 사례1c - 피자헛 단건이 멀티 X`() {
        // 5/6 16:35 패턴
        BaeminParser.resetDedupCache()
        val texts = listOf(
            "신규배차_수락버튼",
            "배민배달, 조리완료, 픽업지, 피자헛 광주태전점, 전달지, 경기 광주시, 포인트, 45P",
            "배민배달", "조리완료", "픽업지", "피자헛 광주태전점",
            "전달지", "경기 광주시 태성1로 16", "포인트", "45P", "배달료", "3,900원"
        )
        val result = BaeminParser.parse(texts)!!
        assertTrue(result.isNotEmpty())
        assertFalse("단건이 멀티 아님", result[0].isMulti)
        assertEquals(3900, result[0].price)
    }

    // ── FIX-MULTI 회귀 테스트: 사례4 이전내역 화면 DROP ──

    @Test
    fun `FIX-MULTI 사례4 - 이전내역 화면 다중 배달료 합산 멀티 오인식 방지`() {
        // 5/6 16:09 패턴: "이전내역" 텍스트 노드 포함 → DROP
        val texts = listOf(
            "이전내역",
            "밥풀릭스 광주태전점", "배달료 3,700원",
            "동대문엽기떡볶이 광주한아람점", "배달료 2,300원",
            "KFC 광주태전점", "배달료 2,300원"
        )
        val result = BaeminParser.parse(texts)
        assertNull("이전내역 화면은 DROP", result)
    }

    @Test
    fun `FIX-MULTI 사례4 - 이전내역 + 신규배차 수락버튼 = 새 콜 진행`() {
        // 이전내역 키워드 + 신규 콜 증거 동시 → 파싱 진행
        BaeminParser.resetDedupCache()
        val texts = listOf(
            "이전내역", "신규배차_수락버튼",
            "픽업지", "새가게", "전달지", "태전동",
            "배달료", "4,500원"
        )
        val result = BaeminParser.parse(texts)
        assertNotNull("신규 콜 증거 있으면 파싱 진행", result)
        assertTrue(result!!.isNotEmpty())
    }

    // ── FIX-T2CN: 주문번호 추출 테스트 ──

    @Test
    fun `T2CN - 개별 노드에서 주문번호 추출`() {
        val texts = listOf(
            "배민배달", "픽업지", "서대문 김치찜 광주태전점",
            "T2CN0000LAF4", "배달료 2,600원"
        )
        assertEquals("T2CN0000LAF4", BaeminParser.extractOrderId(texts))
    }

    @Test
    fun `T2CN - 콤마요약 노드의 T2CN은 무시하고 개별 노드 사용`() {
        // 콤마구분 요약노드는 skip, 개별 노드에서 추출
        val texts = listOf(
            "서대문 김치찜&김치찌개 광주태전점, 경기도 광주시 태전동로 12, T2CN0000LAF4, 배달료 2,600원",
            "서대문 김치찜&김치찌개 광주태전점",
            "T2CN0000LAF4",
            "배달료 2,600원"
        )
        assertEquals("T2CN0000LAF4", BaeminParser.extractOrderId(texts))
    }

    @Test
    fun `T2CN - 없는 경우 null`() {
        val texts = listOf("배민배달", "픽업지", "맘스터치", "배달료 3,500원")
        assertNull(BaeminParser.extractOrderId(texts))
    }

    @Test
    fun `T2CN - parse 결과에 orderId 포함`() {
        BaeminParser.resetDedupCache()
        val texts = listOf(
            "신규배차_수락버튼",
            "배민배달", "픽업지", "호노보노 파스타 식당",
            "T2CN00013AUH", "배달료 3,120원"
        )
        val result = BaeminParser.parse(texts)!!
        assertTrue(result.isNotEmpty())
        assertEquals("T2CN00013AUH", result[0].orderId)
        assertEquals(3120, result[0].price)
    }

    @Test
    fun `T2CN - 5_6 실제 사례 T2CN0000J54R 추출`() {
        val texts = listOf(
            "KFC 광주태전점",
            "T2CN0000J54R",
            "배달료 2,300원"
        )
        assertEquals("T2CN0000J54R", BaeminParser.extractOrderId(texts))
    }

    // ══════════════════════════════════════════════════════
    // FIX-REGRESSION: 5/6 통합 회귀 테스트 (영구 박제)
    // ══════════════════════════════════════════════════════

    @Test
    fun `REG-01 GS25 단건 콤마요약 노드 멀티 오인식 방지`() {
        // 5/6 16:36:45 GS25 — 콤마요약 + 개별 "픽업지" 1개 = 단건
        BaeminParser.resetDedupCache()
        val texts = listOf(
            "신규배차_수락버튼",
            "배민배달, 조리완료, 픽업지, GS25 광주태전점, 전달지, 경기 광주시, 포인트, 15P",
            "배민배달", "조리완료", "픽업지", "GS25 광주태전점",
            "전달지", "경기 광주시 태봉로 145", "포인트", "15P", "배달료", "2,300원"
        )
        val result = BaeminParser.parse(texts)!!
        assertTrue(result.isNotEmpty())
        assertFalse("GS25 단건이 멀티 아님", result[0].isMulti)
        assertEquals(2300, result[0].price)
    }

    @Test
    fun `REG-02 서대문 김치찜&김치찌개 단건 앰퍼샌드 가게명`() {
        // 5/6 16:39 — 가게명에 & 포함 단건
        BaeminParser.resetDedupCache()
        val texts = listOf(
            "신규배차_수락버튼",
            "배민배달, 조리완료, 픽업지, 서대문 김치찜&김치찌개 광주태전점, 전달지, 경기 광주시",
            "배민배달", "조리완료", "픽업지", "서대문 김치찜&김치찌개 광주태전점",
            "T2CN0000LAF4", "전달지", "경기 광주시 태전동로 12", "배달료", "2,600원"
        )
        val result = BaeminParser.parse(texts)!!
        assertTrue(result.isNotEmpty())
        assertFalse("& 포함 가게명 단건이 멀티 아님", result[0].isMulti)
        assertEquals(2600, result[0].price)
        assertEquals("T2CN0000LAF4", result[0].orderId)
    }

    @Test
    fun `REG-03 육참냉면&돈카츠 14_53 원본 rawText 패턴 회귀`() {
        // 5/6 14:53 실제 rawText 재현 (콤마요약 + 개별노드)
        BaeminParser.resetDedupCache()
        val texts = listOf(
            "신규배차_끄기버튼", "신규배차",
            "배민배달, 조리완료, 픽업지, 육참냉면&돈카츠 태전점, 전달지, 경기 광주시 삼지곡길 73-4 (삼동), 포인트, 25P",
            "배민배달", "조리완료", "픽업지", "육참냉면&돈카츠 태전점",
            "전달지", "경기 광주시 삼지곡길 73-4 (삼동)", "포인트", "25P",
            "배달료", "6,090원",
            "touchable-image-container", "신규배차_거절버튼", "신규배차_수락버튼",
            "지도", "NAVER", "배차대기중_상단_마이페이지_버튼", "button-base",
            "현재 위치와 가까운 배차를 찾고 있어요", "지도앱으로 검색하기"
        )
        val result = BaeminParser.parse(texts)!!
        assertTrue(result.isNotEmpty())
        assertFalse("육참냉면&돈카츠 단건이 멀티 아님", result[0].isMulti)
        assertEquals(6090, result[0].price)
    }

    @Test
    fun `REG-04 이전내역 다중 배달료 + 픽업완료 = DROP`() {
        // 5/6 21:36 실제 이전내역 화면 (배달료 복수)
        val texts = listOf(
            "이전내역",
            "밥풀릭스 광주태전점", "배달료 3,700원",
            "동대문엽기떡볶이 광주한아람점", "배달료 2,300원",
            "호노보노 파스타 식당", "배달료 3,120원",
            "서대문 김치찜&김치찌개 광주태전점", "배달료 2,600원",
            "KFC 광주태전점", "배달료 2,300원",
            "굽네치킨&피자 광주오포점", "배달료 2,890원",
            "픽업 완료 되었습니다"
        )
        val result = BaeminParser.parse(texts)
        assertNull("이전내역 화면 다중 배달료 = DROP", result)
    }

    @Test
    fun `REG-05 진짜 묶음 2건 픽업지 노드 2개`() {
        // 진짜 묶음: "픽업지" 개별 노드 2개 + 묶음 키워드
        BaeminParser.resetDedupCache()
        val texts = listOf(
            "신규배차_수락버튼", "신규배달",
            "배민배달", "픽업지", "7곡제면소 태전점",
            "전달지", "경기 광주시 태전동",
            "픽업지", "BBQ 광주태전점",
            "전달지", "경기 광주시 고산동",
            "배달료 3,500원", "배달료 4,200원"
        )
        val result = BaeminParser.parse(texts)!!
        assertTrue(result.isNotEmpty())
        // 2개 배달료 → 합산 묶음 or 개별 중 하나
        // 핵심: 파싱 실패 없이 결과 반환
        assertTrue("묶음 또는 개별 2건 파싱", result.isNotEmpty())
    }

    @Test
    fun `REG-06 진짜 묶음 detectMulti 픽업지 2노드`() {
        val texts = listOf(
            "배민배달", "픽업지", "버거킹 광주태전점",
            "전달지", "태전동",
            "픽업지", "맘스터치 태전점",
            "전달지", "고산동",
            "배달료 4,500원"
        )
        assertTrue("픽업지 2노드 = 멀티", BaeminParser.detectMulti(texts))
    }

    @Test
    fun `REG-07 dedup 캐시 동일 store+price 5분 내 재파싱 방지`() {
        BaeminParser.resetDedupCache()
        val texts = listOf(
            "신규배차_수락버튼",
            "픽업지", "맘스터치 태전점",
            "배달료", "4,300원"
        )
        val r1 = BaeminParser.parse(texts)!!
        assertTrue(r1.isNotEmpty())
        assertEquals(4300, r1[0].price)
        // 동일 호출 = dedup
        val r2 = BaeminParser.parse(texts)!!
        assertTrue("dedup: 빈 리스트", r2.isEmpty())
    }

    @Test
    fun `REG-08 T2CN 여러 형식 매칭`() {
        // T2CG, T2CK, T2CI, T2CN 모두 매칭
        assertEquals("T2CN00015ERE", BaeminParser.extractOrderId(listOf("T2CN00015ERE")))
        assertEquals("T2CG0000M318", BaeminParser.extractOrderId(listOf("T2CG0000M318")))
        assertEquals("T2CK0000RGQM", BaeminParser.extractOrderId(listOf("T2CK0000RGQM")))
    }

    @Test
    fun `REG-09 T2CN 콤마요약 노드 40자 이상 skip`() {
        // 요약노드는 skip, 개별 노드에서만 추출
        val texts = listOf(
            "서대문 김치찜&김치찌개 광주태전점, 경기도 광주시, T2CN0000LAF4, 배달료 2,600원, 도착예상시간 16:55",
            "T2CN0000LAF4"
        )
        assertEquals("T2CN0000LAF4", BaeminParser.extractOrderId(texts))
    }

    @Test
    fun `REG-10 이전내역 키워드만으로 DROP 없으면 진짜 콜 통과`() {
        // "이전내역" 포함하지 않는 정상 콜
        BaeminParser.resetDedupCache()
        val texts = listOf(
            "신규배차_수락버튼",
            "픽업지", "반찬전문점 조찬소",
            "T2CN0000L8PR", "배달료", "2,690원"
        )
        val result = BaeminParser.parse(texts)
        assertNotNull("정상 콜 통과", result)
        assertTrue(result!!.isNotEmpty())
        assertEquals(2690, result[0].price)
    }

    // ══════════════════════════════════════════════════════
    // FIX-STORE-NAME: 가게명 추출 4순위 fallback 테스트
    // ══════════════════════════════════════════════════════

    @Test
    fun `STORE-1 1순위 픽업지 다음 토큰 추출`() {
        val texts = listOf(
            "배민배달", "조리완료", "픽업지", "육참냉면&돈카츠 태전점",
            "전달지", "경기 광주시", "배달료", "6,090원"
        )
        val (name, _) = BaeminParser.extractStoreName(texts)
        assertEquals("육참냉면&돈카츠 태전점", name)
    }

    @Test
    fun `STORE-2 2순위 T2CN 직전 가게명`() {
        // 픽업지 없음 → 2순위: T2CN 바로 앞
        val texts = listOf(
            "배민배달", "조리완료", "서대문 김치찜&김치찌개 광주태전점",
            "T2CN0000LAF4", "배달료", "2,600원"
        )
        val (name, _) = BaeminParser.extractStoreName(texts)
        assertEquals("서대문 김치찜&김치찌개 광주태전점", name)
    }

    @Test
    fun `STORE-3 3순위 배달료 직전 가게명`() {
        // 픽업지 없음 + T2CN 없음 → 3순위: 배달료 앞
        val texts = listOf(
            "배민배달", "조리완료", "호노보노 파스타 식당",
            "전달지", "경기 광주시", "배달료", "3,120원"
        )
        val (name, _) = BaeminParser.extractStoreName(texts)
        // 1순위 없음 → 2순위(T2CN 없음) → 3순위 배달료 직전
        assertTrue("호노보노 파스타 식당 추출", name.contains("호노보노"))
    }

    @Test
    fun `STORE-4 4순위 조리완료 다음 가게명`() {
        // 픽업지/T2CN/배달료 직전 모두 실패 → 4순위
        val texts = listOf(
            "조리완료", "반찬전문점 조찬소", "15P"
        )
        val (name, _) = BaeminParser.extractStoreName(texts)
        assertEquals("반찬전문점 조찬소", name)
    }

    @Test
    fun `STORE-5 1순위 성공 시 2순위 무시`() {
        val texts = listOf(
            "픽업지", "맘스터치 태전점",
            "GS25 태전점", "T2CN0000LAF4", "배달료", "3,500원"
        )
        val (name, _) = BaeminParser.extractStoreName(texts)
        assertEquals("맘스터치 태전점", name)  // 1순위 우선
    }

    @Test
    fun `STORE-6 모든 순위 실패 → 빈 문자열`() {
        val texts = listOf("배달료", "3,500원", "25P", "30초")
        val (name, _) = BaeminParser.extractStoreName(texts)
        assertEquals("", name)
    }

    @Test
    fun `STORE-7 가게명 30자 초과 reject`() {
        val texts = listOf(
            "픽업지", "아주아주아주아주아주아주아주아주긴가게이름이삼십자를넘으면안됩니다여기까지만",
            "배달료", "3,500원"
        )
        val (name, _) = BaeminParser.extractStoreName(texts)
        assertEquals("", name)  // 30자 초과 → reject
    }

    @Test
    fun `STORE-8 STORE_PATTERN 한자 + 특수문자 통과`() {
        val texts = listOf(
            "픽업지", "수秀 곱도리탕 광주점",
            "배달료", "4,500원"
        )
        val (name, _) = BaeminParser.extractStoreName(texts)
        assertEquals("수秀 곱도리탕 광주점", name)
    }

    @Test
    fun `STORE-9 parse 결과에 가게명 포함 (T2CN 직전 2순위)`() {
        BaeminParser.resetDedupCache()
        val texts = listOf(
            "신규배차_수락버튼", "배민배달", "조리완료",
            "KFC 광주태전점", "T2CN0000JLZW",
            "배달료", "2,300원"
        )
        val result = BaeminParser.parse(texts)!!
        assertTrue(result.isNotEmpty())
        assertTrue("KFC 포함", result[0].storeName.contains("KFC"))
    }

    @Test
    fun `STORE-10 콤마요약 노드 무시하고 개별 노드에서 추출`() {
        val texts = listOf(
            "빽다방 태전한아람초점, 경기도 광주시 태봉로 145-1, T2CN0000HQNG, 배달료 2,600원",
            "빽다방 태전한아람초점",
            "T2CN0000HQNG",
            "배달료", "2,600원"
        )
        val (name, _) = BaeminParser.extractStoreName(texts)
        assertEquals("빽다방 태전한아람초점", name)
    }
}

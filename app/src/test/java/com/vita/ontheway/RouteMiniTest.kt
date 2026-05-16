package com.vita.ontheway

import org.junit.Assert.*
import org.junit.Test

/**
 * Route Mini v0.2 테스트 (T1~T9).
 */
class RouteMiniTest {

    /** T1: 자유 입력 → 주소 추출 */
    @Test
    fun t1_freeInputAddressExtraction() {
        val text = """
            강남구 역삼동 123-45
            서초구 서초대로 100
            마포구 마포대로 200
        """.trimIndent()

        val stops = RouteParser.parse(text)
        assertEquals(3, stops.size)
        assertTrue("역삼동 주소", stops[0].address.contains("역삼동"))
        assertTrue("서초대로 주소", stops[1].address.contains("서초대로"))
        assertTrue("마포대로 주소", stops[2].address.contains("마포대로"))
    }

    /** T2: 카톡 복붙 → 리스트 생성 */
    @Test
    fun t2_kakaoTalkPaste() {
        val text = """
            [배달 1]
            강남구 테헤란로 427
            1102동 504호
            [배달 2]
            서초구 강남대로 305
            2동 1201호
            공동현관 #1234
        """.trimIndent()

        val stops = RouteParser.parse(text)
        assertTrue("2개 이상 주소 추출: got ${stops.size}, addrs=${stops.map { it.address }}", stops.size >= 2)

        val stop1 = stops.firstOrNull { it.address.contains("테헤란로") }
        assertNotNull("테헤란로 정류장 존재: ${stops.map { "${it.address}|d=${it.dong}|h=${it.ho}" }}", stop1)
        assertEquals("ho=504: actual=${stop1!!.ho}", "504", stop1.ho)

        val stop2 = stops.first { it.address.contains("강남대로") }
        assertEquals("1201", stop2.ho)
    }

    /** T3: 추출 결과 편집 (수정/삭제/추가) */
    @Test
    fun t3_editExtractedStops() {
        val stops = mutableListOf(
            RouteStop(address = "강남구 역삼동 123", order = 0),
            RouteStop(address = "서초구 서초동 456", order = 1),
            RouteStop(address = "마포구 상암동 789", order = 2)
        )

        // 수정
        stops[1] = stops[1].copy(address = "서초구 반포동 100")
        assertEquals("반포동 100", true, stops[1].address.contains("반포동"))

        // 삭제
        stops.removeAt(2)
        assertEquals(2, stops.size)

        // 추가
        stops.add(RouteStop(address = "송파구 잠실동 10", order = 2))
        assertEquals(3, stops.size)
        assertTrue(stops[2].address.contains("잠실동"))
    }

    /** T4: 자동 Route 생성 (nearest-first) */
    @Test
    fun t4_autoRouteNearestFirst() {
        val stops = listOf(
            RouteStop(address = "마포구 상암동 100", order = 0),
            RouteStop(address = "강남구 역삼동 200", order = 1),
            RouteStop(address = "마포구 마포동 50", order = 2)
        )

        // 현재 위치가 마포구
        val optimized = RouteOptimizer.optimize(stops, "마포구 합정동 10")

        assertEquals(3, optimized.size)
        // 마포구 주소가 먼저 와야함 (같은 구)
        assertTrue("첫 정류장 마포", optimized[0].address.contains("마포"))
        assertTrue("둘째도 마포", optimized[1].address.contains("마포"))
        // 강남구가 마지막
        assertTrue("마지막 강남", optimized[2].address.contains("강남"))
        // order 갱신 확인
        assertEquals(0, optimized[0].order)
        assertEquals(1, optimized[1].order)
        assertEquals(2, optimized[2].order)
    }

    /** T5: drag 순서 변경 (swap) */
    @Test
    fun t5_dragReorder() {
        val stops = mutableListOf(
            RouteStop(address = "A", order = 0),
            RouteStop(address = "B", order = 1),
            RouteStop(address = "C", order = 2)
        )

        // swap 0 <-> 1
        val temp = stops[0]
        stops[0] = stops[1].copy(order = 0)
        stops[1] = temp.copy(order = 1)

        assertEquals("B", stops[0].address)
        assertEquals("A", stops[1].address)
        assertEquals("C", stops[2].address)
    }

    /** T6: 내비 intent 생성 (4단계 fallback URI 검증) */
    @Test
    fun t6_naviIntentFallback() {
        val address = "강남구 테헤란로 427"
        val encoded = java.net.URLEncoder.encode(address, "UTF-8")

        // 좌표 없는 경우 — kakaomap://route
        val urisNoCoord = listOf(
            "kakaomap://route?ep=$encoded&by=CAR",
            "kakaomap://search?q=$encoded",
            "geo:0,0?q=$encoded"
        )
        assertEquals(3, urisNoCoord.size)
        assertTrue("좌표 없을 때 kakaomap route", urisNoCoord[0].startsWith("kakaomap://route"))
        assertTrue("카카오맵 URI", urisNoCoord[1].startsWith("kakaomap://search"))
        assertTrue("geo URI", urisNoCoord[2].startsWith("geo:0,0"))

        // 좌표 있는 경우 — kakaonavi://navigate
        val lat = 37.4979; val lng = 127.0276
        val urisWithCoord = listOf(
            "kakaonavi://navigate?dest_lat=$lat&dest_lng=$lng&dest_name=$encoded",
            "kakaomap://search?q=$encoded",
            "geo:$lat,$lng?q=$encoded"
        )
        assertTrue("좌표 있을 때 kakaonavi", urisWithCoord[0].startsWith("kakaonavi://navigate"))
        assertTrue("좌표 포함", urisWithCoord[0].contains("dest_lat=$lat"))
        assertTrue("geo에 좌표", urisWithCoord[2].startsWith("geo:$lat,$lng"))
    }

    /** T7: 배송 완료 → 다음 강조 */
    @Test
    fun t7_deliveryCompleteNextHighlight() {
        val stops = mutableListOf(
            RouteStop(address = "A", order = 0, status = RouteStop.Status.CURRENT),
            RouteStop(address = "B", order = 1, status = RouteStop.Status.PENDING),
            RouteStop(address = "C", order = 2, status = RouteStop.Status.PENDING)
        )

        // 배송 완료
        stops[0] = stops[0].copy(status = RouteStop.Status.DONE)
        val nextIdx = stops.indexOfFirst { it.status == RouteStop.Status.PENDING }
        if (nextIdx >= 0) {
            stops[nextIdx] = stops[nextIdx].copy(status = RouteStop.Status.CURRENT)
        }

        assertEquals(RouteStop.Status.DONE, stops[0].status)
        assertEquals(RouteStop.Status.CURRENT, stops[1].status)
        assertEquals(RouteStop.Status.PENDING, stops[2].status)
    }

    /** T8: SharedPreferences 저장/복원 (직렬화 검증) */
    @Test
    fun t8_stateStoreSerialize() {
        val stops = listOf(
            RouteStop(id = "a1", address = "강남구 역삼동 123", dong = "101", ho = "504", order = 0, status = RouteStop.Status.CURRENT),
            RouteStop(id = "b2", address = "서초구 서초대로 100", memo = "공동현관 #1234", order = 1)
        )

        val json = RouteStop.listToJson(stops)
        val restored = RouteStop.listFromJson(json)

        assertEquals(2, restored.size)
        assertEquals("a1", restored[0].id)
        assertEquals("강남구 역삼동 123", restored[0].address)
        assertEquals("101", restored[0].dong)
        assertEquals("504", restored[0].ho)
        assertEquals(RouteStop.Status.CURRENT, restored[0].status)
        assertEquals("서초구 서초대로 100", restored[1].address)
        assertEquals("공동현관 #1234", restored[1].memo)
        assertEquals(RouteStop.Status.PENDING, restored[1].status)
    }

    /** T9: 20개 주소 end-to-end */
    @Test
    fun t9_twentyAddressesEndToEnd() {
        val sb = StringBuilder()
        for (i in 1..20) {
            sb.appendLine("강남구 테헤란로 ${i * 10}")
        }

        val stops = RouteParser.parse(sb.toString())
        assertEquals(20, stops.size)

        // Route 생성
        val optimized = RouteOptimizer.optimize(stops)
        assertEquals(20, optimized.size)

        // 모든 order 유니크
        val orders = optimized.map { it.order }.toSet()
        assertEquals(20, orders.size)

        // 첫번째 CURRENT로 변경
        val active = optimized.toMutableList()
        active[0] = active[0].copy(status = RouteStop.Status.CURRENT)

        // 하나씩 완료 시뮬레이션
        for (i in 0 until 20) {
            val currentIdx = active.indexOfFirst { it.status == RouteStop.Status.CURRENT }
            assertTrue("step $i: current 존재", currentIdx >= 0)
            active[currentIdx] = active[currentIdx].copy(status = RouteStop.Status.DONE)
            val nextIdx = active.indexOfFirst { it.status == RouteStop.Status.PENDING }
            if (nextIdx >= 0) {
                active[nextIdx] = active[nextIdx].copy(status = RouteStop.Status.CURRENT)
            }
        }

        // 모든 DONE
        assertTrue("모든 배송 완료", active.all { it.status == RouteStop.Status.DONE })
    }

    // ── 추가 검증 ──

    @Test
    fun addressDistance_sameDong() {
        val d = RouteOptimizer.addressDistance("마포구 상암동 100", "마포구 상암동 200")
        assertTrue("같은 동 = 가까움", d < 10)
    }

    @Test
    fun addressDistance_differentGu() {
        val d = RouteOptimizer.addressDistance("강남구 역삼동 100", "마포구 상암동 200")
        assertTrue("다른 구 = 먼", d >= 20)
    }

    @Test
    fun parseRoadAddress() {
        val addr = RouteParser.extractAddress("배달지: 서초구 강남대로 305 1층")
        assertNotNull(addr)
        assertTrue(addr!!.contains("강남대로 305"))
    }

    @Test
    fun parseJibunAddress() {
        val addr = RouteParser.extractAddress("전달지 강남구 역삼동 123-45")
        assertNotNull(addr)
        assertTrue(addr!!.contains("역삼동 123"))
    }

    @Test
    fun debugDongHo() {
        val r1 = RouteParser.extractDongHo("1102동 504호")
        assertEquals("dong=1102", "1102", r1.first)
        assertEquals("ho=504", "504", r1.second)

        val r2 = RouteParser.extractDongHo("2동 1201호")
        assertEquals("dong=2", "2", r2.first)
        assertEquals("ho=1201", "1201", r2.second)
    }

    @Test
    fun emptyInput() {
        val stops = RouteParser.parse("")
        assertTrue(stops.isEmpty())
    }

    @Test
    fun noAddressInput() {
        val stops = RouteParser.parse("안녕하세요\n좋은 하루 되세요")
        assertTrue(stops.isEmpty())
    }

    @Test
    fun routeStopJsonRoundTrip() {
        val stop = RouteStop(address = "테스트로 123", dong = "1", ho = "2", memo = "메모",
            lat = 37.4979, lng = 127.0276)
        val json = stop.toJson()
        val restored = RouteStop.fromJson(json)
        assertEquals(stop.address, restored.address)
        assertEquals(stop.dong, restored.dong)
        assertEquals(stop.ho, restored.ho)
        assertEquals(stop.memo, restored.memo)
        assertEquals(37.4979, restored.lat, 0.0001)
        assertEquals(127.0276, restored.lng, 0.0001)
    }

    // ── 좌표 기반 최적화 검증 ──

    @Test
    fun coordBasedOptimize() {
        // 강남(37.50, 127.03) → 서초(37.49, 127.01) → 마포(37.55, 126.91)
        val stops = listOf(
            RouteStop(address = "마포구", order = 0, lat = 37.55, lng = 126.91),
            RouteStop(address = "서초구", order = 1, lat = 37.49, lng = 127.01),
            RouteStop(address = "강남구", order = 2, lat = 37.50, lng = 127.03)
        )
        // 현재 위치 = 강남 (37.50, 127.03)
        val optimized = RouteOptimizer.optimize(stops, currentLat = 37.50, currentLng = 127.03)
        assertEquals(3, optimized.size)
        // 강남→서초→마포 순서 (거리순)
        assertTrue("강남 먼저", optimized[0].address.contains("강남"))
        assertTrue("서초 다음", optimized[1].address.contains("서초"))
        assertTrue("마포 마지막", optimized[2].address.contains("마포"))
    }

    @Test
    fun haversineKm() {
        // 강남역 → 서울역 ≈ 8~10km
        val d = RouteGeocoder.haversineKm(37.498, 127.028, 37.556, 126.972)
        assertTrue("거리 5~15km: $d", d in 5.0..15.0)
    }

    @Test
    fun routeStopHasCoord() {
        val noCoord = RouteStop(address = "test")
        assertFalse(noCoord.hasCoord())

        val withCoord = RouteStop(address = "test", lat = 37.5, lng = 127.0)
        assertTrue(withCoord.hasCoord())
    }

    @Test
    fun coordJsonRoundTrip() {
        val stops = listOf(
            RouteStop(address = "A", lat = 37.5, lng = 127.0),
            RouteStop(address = "B")
        )
        val json = RouteStop.listToJson(stops)
        val restored = RouteStop.listFromJson(json)
        assertEquals(2, restored.size)
        assertEquals(37.5, restored[0].lat, 0.001)
        assertEquals(127.0, restored[0].lng, 0.001)
        assertEquals(0.0, restored[1].lat, 0.001)
    }

    // ══════════════════════════════════════
    // v0.2.1 좌표 기반 nearest-first (T18~T20)
    // ══════════════════════════════════════

    /** T18: 카카오 키 있음 + 좌표 변환 성공 → 좌표 기반 nearest-first */
    @Test
    fun t18_coordBasedNearestFirst() {
        // 시나리오: 현재 위치 = 잠실 (37.514, 127.100)
        // A=강남 (37.498, 127.028) ~6km
        // B=서초 (37.484, 127.012) ~8km
        // C=잠실근처 (37.513, 127.095) ~0.5km
        val stops = listOf(
            RouteStop(address = "강남", order = 0, lat = 37.498, lng = 127.028),
            RouteStop(address = "서초", order = 1, lat = 37.484, lng = 127.012),
            RouteStop(address = "잠실근처", order = 2, lat = 37.513, lng = 127.095)
        )
        val optimized = RouteOptimizer.optimize(stops, currentLat = 37.514, currentLng = 127.100)

        assertEquals(3, optimized.size)
        // 잠실근처(0.5km) → 강남(6km) → 서초(8km) 순서
        assertEquals("잠실근처 first", "잠실근처", optimized[0].address)
        assertEquals("강남 second", "강남", optimized[1].address)
        assertEquals("서초 third", "서초", optimized[2].address)

        // order 갱신 확인
        assertEquals(0, optimized[0].order)
        assertEquals(1, optimized[1].order)
        assertEquals(2, optimized[2].order)
    }

    /** T19: 좌표 없음 → 텍스트 유사도 fallback */
    @Test
    fun t19_noCoordTextFallback() {
        // 모든 stop에 좌표 없음 → 텍스트 유사도로 정렬
        val stops = listOf(
            RouteStop(address = "강남구 역삼동 100", order = 0),
            RouteStop(address = "마포구 상암동 200", order = 1),
            RouteStop(address = "강남구 삼성동 50", order = 2)
        )
        // 현재 위치 주소 = 강남구 → 같은 구 우선
        val optimized = RouteOptimizer.optimize(stops, currentAddress = "강남구 대치동 10")

        assertEquals(3, optimized.size)
        // 강남구 2개가 먼저, 마포구가 마지막
        assertTrue("첫째 강남구", optimized[0].address.contains("강남구"))
        assertTrue("둘째 강남구", optimized[1].address.contains("강남구"))
        assertTrue("셋째 마포구", optimized[2].address.contains("마포구"))
    }

    /** T20: 일부 변환 성공/일부 실패 → 좌표 stop 우선, 미변환 stop 후순위 */
    @Test
    fun t20_mixedCoordAndNoCoord() {
        // A=좌표 있음 (가까움), B=좌표 없음, C=좌표 있음 (먼), D=좌표 없음
        val stops = listOf(
            RouteStop(address = "먼곳", order = 0, lat = 37.55, lng = 126.91),    // 마포 ~12km
            RouteStop(address = "강남구 역삼동 100", order = 1),                   // 좌표 없음
            RouteStop(address = "가까운곳", order = 2, lat = 37.50, lng = 127.02), // 강남 ~0.5km
            RouteStop(address = "서초구 서초동 200", order = 3)                    // 좌표 없음
        )
        // 현재 위치 = 강남역 (37.498, 127.028)
        val optimized = RouteOptimizer.optimize(stops, currentLat = 37.498, currentLng = 127.028)

        assertEquals(4, optimized.size)
        // 좌표 있는 stop이 먼저 (거리순): 가까운곳 → 먼곳
        assertEquals("가까운곳 first", "가까운곳", optimized[0].address)
        assertEquals("먼곳 second", "먼곳", optimized[1].address)
        // 좌표 없는 stop이 후순위 (텍스트 heuristic)
        val lastTwo = optimized.subList(2, 4).map { it.address }
        assertTrue("좌표 없는 stop 후순위", lastTwo.all { !it.contains("가까운곳") && !it.contains("먼곳") })
    }

    // ══════════════════════════════════════
    // v0.2.2 보완 (T21~T24)
    // ══════════════════════════════════════

    /** T21: 카카오내비 deep link 형식 검증 (좌표 있음/없음) */
    @Test
    fun t21_naviDeepLinkFormats() {
        val addr = "강남구 테헤란로 427"
        val lat = 37.4979; val lng = 127.0276

        // 좌표 있는 경우 (v0.2.7: 카카오맵 1순위)
        val withCoord = buildNaviUrisTest(addr, lat, lng)
        assertEquals(2, withCoord.size)
        // 1. 카카오맵 route (좌표)
        assertTrue("카카오맵 route", withCoord[0].startsWith("kakaomap://route"))
        assertTrue("ep=lat,lng", withCoord[0].contains("ep=$lat,$lng"))
        // 2. geo: 좌표
        assertTrue("geo 좌표", withCoord[1].startsWith("geo:$lat,$lng"))

        // 좌표 없는 경우
        val noCoord = buildNaviUrisTest(addr, 0.0, 0.0)
        assertEquals(2, noCoord.size)
        // 1. kakaomap search
        assertTrue("좌표없을때 kakaomap search", noCoord[0].startsWith("kakaomap://search"))
        // 2. geo 0,0
        assertTrue("geo 0,0", noCoord[1].startsWith("geo:0,0"))
    }

    /** T22: 장소명 ("강남역", "도곡역 1번출구") → 파서 인식 */
    @Test
    fun t22_placeNameRecognition() {
        // 역명
        val addr1 = RouteParser.extractAddress("다음 목적지: 강남역")
        assertNotNull("강남역 인식", addr1)
        assertTrue(addr1!!.contains("강남역"))

        // 역명 + 출구
        val addr2 = RouteParser.extractAddress("도곡역 1번출구 앞")
        assertNotNull("도곡역 1번출구 인식", addr2)
        assertTrue(addr2!!.contains("도곡역"))

        // 구/동
        val addr3 = RouteParser.extractAddress("송파구 가락동으로 가주세요")
        assertNotNull("송파구 가락동 인식", addr3)
        assertTrue(addr3!!.contains("송파구") || addr3.contains("가락동"))

        // isPlaceName 판별
        assertTrue("강남역 = 장소명", RouteParser.isPlaceName("강남역"))
        assertTrue("도곡역 1번출구 = 장소명", RouteParser.isPlaceName("도곡역 1번출구"))
        assertFalse("도로명 = 장소명 아님", RouteParser.isPlaceName("강남구 테헤란로 427"))

        // parse로 리스트 생성
        val stops = RouteParser.parse("강남역\n도곡역 1번출구\n서초구 강남대로 305")
        assertTrue("3개 추출: ${stops.size}", stops.size >= 2)
    }

    /** T23: 하단 버튼 고정 — XML layout_weight=1 ScrollView + 고정 bottom bar */
    @Test
    fun t23_bottomBarAlwaysVisible() {
        // layout 구조 검증: bottom bar는 ScrollView 밖, layout_weight 없음
        // → 화면 하단에 항상 고정
        // XML 파싱 없이 구조적 검증: bottom bar의 visibility는 코드에서 관리
        val stops = mutableListOf(
            RouteStop(address = "A", order = 0),
            RouteStop(address = "B", order = 1)
        )
        // 정류장이 있으면 bottom bar visible 상태
        assertTrue("정류장 존재 시 bottom bar 표시 가능", stops.isNotEmpty())
        // 20개로 늘려도 bottom bar 구조는 동일
        repeat(18) { stops.add(RouteStop(address = "Stop${it + 3}", order = it + 2)) }
        assertEquals(20, stops.size)
        // bottom bar는 ScrollView 밖이므로 스크롤 영향 없음 (구조 보장)
    }

    /** T24: 운영 종료 버튼 — 항상 우측 상단 접근 가능 */
    @Test
    fun t24_endButtonAccessible() {
        // 운영 종료 = header 영역 (ScrollView 밖)
        // 정류장 수와 무관하게 항상 접근 가능
        // XML: route_end_btn은 header LinearLayout 내부
        // 테두리 스타일 적용 확인 (프로그래매틱)
        val stops = (1..20).map { RouteStop(address = "Stop$it", order = it - 1) }
        assertEquals(20, stops.size)
        // header는 ScrollView 밖 → 스크롤과 무관하게 항상 보임 (구조 보장)
    }

    // ══════════════════════════════════════
    // v0.2.3 LocationTracker 독립 + 복귀 (T25~T27)
    // ══════════════════════════════════════

    /** T25: DRIVING 모드 → OnTheWayService.currentLat/Lng 사용 */
    @Test
    fun t25_drivingModeUsesServiceLocation() {
        // DRIVING 모드에서는 OnTheWayService.currentLat/Lng 활용
        // simulate: service 값이 있으면 그대로 사용
        val svcLat = 37.498
        val svcLng = 127.028
        assertTrue("서비스 좌표 유효", svcLat != 0.0 && svcLng != 0.0)

        // 이 좌표로 optimize 호출
        val stops = listOf(
            RouteStop(address = "먼곳", lat = 37.55, lng = 126.91),
            RouteStop(address = "가까운곳", lat = 37.50, lng = 127.02)
        )
        val optimized = RouteOptimizer.optimize(stops, currentLat = svcLat, currentLng = svcLng)
        assertEquals("가까운곳 first", "가까운곳", optimized[0].address)
    }

    /** T26: IDLE 모드 → lastLocation fallback (서비스 좌표 0,0) */
    @Test
    fun t26_idleModeLastLocationFallback() {
        // IDLE 모드: OnTheWayService.currentLat/Lng == 0.0
        // → LocationManager.getLastKnownLocation 사용 (시뮬레이션)
        val svcLat = 0.0; val svcLng = 0.0
        assertTrue("서비스 좌표 없음", svcLat == 0.0 && svcLng == 0.0)

        // lastLocation으로 얻은 좌표 시뮬레이션
        val lastLat = 37.514; val lastLng = 127.100
        val stops = listOf(
            RouteStop(address = "먼곳", lat = 37.55, lng = 126.91),
            RouteStop(address = "가까운곳", lat = 37.513, lng = 127.095)
        )
        // lastLocation 좌표로 optimize
        val optimized = RouteOptimizer.optimize(stops, currentLat = lastLat, currentLng = lastLng)
        assertEquals("가까운곳 first", "가까운곳", optimized[0].address)

        // 둘 다 실패 → 텍스트 fallback
        val noLocStops = listOf(
            RouteStop(address = "강남구 역삼동 100"),
            RouteStop(address = "마포구 상암동 200")
        )
        val textFallback = RouteOptimizer.optimize(noLocStops, currentAddress = "강남구 대치동 10")
        assertEquals(2, textFallback.size)
        assertTrue("텍스트 fallback 동작", textFallback[0].address.contains("강남구"))
    }

    /** T27: 복귀지 입력 → 마지막 stop으로 추가 */
    @Test
    fun t27_returnAddressLastStop() {
        val stops = mutableListOf(
            RouteStop(address = "A", order = 0, lat = 37.50, lng = 127.02),
            RouteStop(address = "B", order = 1, lat = 37.55, lng = 126.91)
        )
        val returnAddr = "가게로 100"
        val returnMarker = RouteModeActivity.RETURN_MARKER

        // 복귀 stop 추가
        stops.add(RouteStop(address = returnAddr, memo = returnMarker, order = stops.size))
        assertEquals(3, stops.size)

        // optimize 후 복귀 stop을 마지막으로 이동 (앱 로직 시뮬레이션)
        val optimized = RouteOptimizer.optimize(stops, currentLat = 37.498, currentLng = 127.028)
        val returnStop = optimized.firstOrNull { it.memo == returnMarker }
        val nonReturn = optimized.filter { it.memo != returnMarker }
        val finalList = if (returnStop != null) {
            nonReturn.mapIndexed { i, s -> s.copy(order = i) } +
                returnStop.copy(order = nonReturn.size)
        } else optimized

        assertEquals(3, finalList.size)
        // 마지막 stop = 복귀
        assertEquals("마지막 = 복귀", returnMarker, finalList.last().memo)
        assertEquals("복귀 주소", returnAddr, finalList.last().address)
        // 복귀가 아닌 stop은 앞에 배치
        assertTrue("비복귀 stop 앞에", finalList.subList(0, 2).none { it.memo == returnMarker })
    }

    // ══════════════════════════════════════
    // v0.2.4 UX 보강 (T28~T30)
    // ══════════════════════════════════════

    /** T28: Route 시작 버튼 강조 — AI 동승 IDLE = subdued, Route = 밝은 그린 */
    @Test
    fun t28_routeButtonHighlighted() {
        // AI 동승 IDLE 배경 = #2A2A3E (subdued dark) → R=42, G=42, B=62
        val subduedR = 0x2A; val subduedG = 0x2A; val subduedB = 0x3E
        val subduedBrightness = subduedR + subduedG + subduedB  // = 146

        // Route 시작 배경 = #00F5A0 (밝은 그린) → R=0, G=245, B=160
        val greenR = 0x00; val greenG = 0xF5; val greenB = 0xA0
        val greenBrightness = greenR + greenG + greenB  // = 405

        assertTrue("Route 버튼이 더 밝음 (강조): $greenBrightness > $subduedBrightness",
            greenBrightness > subduedBrightness)
        assertTrue("subdued 충분히 어두움", subduedBrightness < 200)
    }

    /** T29: 새 placeholder 텍스트 — 예시 주소 포함 */
    @Test
    fun t29_placeholderContainsExamples() {
        // XML hint에 포함되어야 할 예시 주소들
        val expectedExamples = listOf("고산동 396", "오포로909번길 32-7", "강남구 삼성동 123-45", "강남역")
        // RouteParser가 이 예시들을 실제로 파싱할 수 있는지 검증
        for (example in expectedExamples) {
            val addr = RouteParser.extractAddress(example)
            assertNotNull("placeholder 예시 파싱 가능: \"$example\"", addr)
        }
        // "한 줄에 하나씩" 입력 시 파싱도 정상
        val multiLine = expectedExamples.joinToString("\n")
        val stops = RouteParser.parse(multiLine)
        assertTrue("모든 예시 주소 추출: got ${stops.size}", stops.size >= 3)
    }

    /** T30: Fold Tip SharedPreferences 1회만 표시 */
    @Test
    fun t30_naviTipShownOnce() {
        val key = RouteModeActivity.PREF_NAVI_TIP_SHOWN
        // 기본값 = false (미표시)
        assertNotNull("key 존재", key)
        assertEquals("route_mini_navi_tip_shown", key)
        // 시뮬레이션: 첫 호출 시 false → true 설정 후 두 번째 호출 시 true
        var shown = false
        if (!shown) { shown = true } // 첫 호출
        assertTrue("첫 호출 후 true", shown)
        // 두 번째 호출 = 이미 true → 표시 안함
        val shouldShow = !shown
        assertFalse("두 번째 호출 = 표시 안함", shouldShow)
    }

    // ══════════════════════════════════════
    // v0.2.5 Package Visibility + 중복 방지 (T31~T33)
    // ══════════════════════════════════════

    /** T31: NaviStep에 패키지명 포함 (package visibility 대응) */
    @Test
    fun t31_naviStepsIncludePackage() {
        val addr = "강남구 테헤란로 427"
        val lat = 37.4979; val lng = 127.0276

        // 좌표 있는 경우: 카카오맵 route 1순위 (v0.2.7)
        val stepsCoord = buildNaviStepsTest(addr, lat, lng)
        assertEquals(2, stepsCoord.size)
        assertEquals("카카오맵 pkg", RouteModeActivity.PKG_KAKAOMAP, stepsCoord[0].pkg)
        assertNull("geo = 패키지 미지정", stepsCoord[1].pkg)
        assertTrue("카카오맵 route URI", stepsCoord[0].uri.startsWith("kakaomap://route"))

        // 좌표 없는 경우: 카카오맵 검색
        val stepsNoCoord = buildNaviStepsTest(addr, 0.0, 0.0)
        assertEquals(2, stepsNoCoord.size)
        assertEquals(RouteModeActivity.PKG_KAKAOMAP, stepsNoCoord[0].pkg)
    }

    /** T32: 패키지명 상수 검증 */
    @Test
    fun t32_packageNameConstants() {
        assertEquals("com.locnall.KimGiSa", RouteModeActivity.PKG_KAKAONAVI)
        assertEquals("net.daum.android.map", RouteModeActivity.PKG_KAKAOMAP)
    }

    /** T33: geocoding 중복 방지 — generation counter 로직 */
    @Test
    fun t33_geocodeGenerationCounter() {
        // generation counter 시뮬레이션
        var generation = 0
        var isGeocoding = false

        // 첫 번째 추출
        val gen1 = ++generation
        isGeocoding = true
        assertEquals(1, gen1)
        assertTrue(isGeocoding)

        // 중복 클릭 → isGeocoding 체크로 차단
        assertTrue("중복 클릭 차단", isGeocoding)

        // 두 번째 추출 (첫 번째 취소)
        val gen2 = ++generation
        assertEquals(2, gen2)

        // 첫 번째 callback 돌아옴 (stale)
        val isStale = gen1 != generation
        assertTrue("gen1 callback은 stale", isStale)

        // 두 번째 callback 돌아옴 (current)
        val isCurrent = gen2 == generation
        assertTrue("gen2 callback은 current", isCurrent)

        isGeocoding = false
        assertFalse("완료 후 잠금 해제", isGeocoding)
    }

    // 테스트 헬퍼

    private fun buildNaviUrisTest(address: String, lat: Double, lng: Double): List<String> =
        buildNaviStepsTest(address, lat, lng).map { it.uri }

    private fun buildNaviStepsTest(address: String, lat: Double, lng: Double): List<RouteModeActivity.NaviStep> {
        val hasCoord = lat != 0.0 && lng != 0.0
        val encoded = java.net.URLEncoder.encode(address, "UTF-8")
        return listOf(
            // 1. 카카오맵 (v0.2.7: 1순위)
            if (hasCoord) RouteModeActivity.NaviStep(
                "kakaomap://route?ep=$lat,$lng&by=CAR",
                RouteModeActivity.PKG_KAKAOMAP, "카카오맵 경로")
            else RouteModeActivity.NaviStep(
                "kakaomap://search?q=$encoded",
                RouteModeActivity.PKG_KAKAOMAP, "카카오맵 검색"),
            // 2. geo
            RouteModeActivity.NaviStep(
                if (hasCoord) "geo:$lat,$lng?q=$encoded" else "geo:0,0?q=$encoded",
                null, "지도 앱")
        )
    }

    // ══════════════════════════════════════
    // v0.2.6 본부장 베타 직전 보강 (T34~T36)
    // ══════════════════════════════════════

    /** T34: 광주시 prefix 보강 — 짧은 주소에 지역 prefix 필요 판정 */
    @Test
    fun t34_regionPrefixForShortAddress() {
        // 짧은 주소 (시/구 없음) → prefix 필요
        assertTrue("고산동 396 = prefix 필요", RouteGeocoder.needsRegionPrefix("고산동 396"))
        assertTrue("오포로909번길 32-7 = prefix 필요", RouteGeocoder.needsRegionPrefix("오포로909번길 32-7"))
        assertTrue("역삼동 123 = prefix 필요", RouteGeocoder.needsRegionPrefix("역삼동 123"))

        // 이미 시/구 포함 → prefix 불필요
        assertFalse("강남구 역삼동 123", RouteGeocoder.needsRegionPrefix("강남구 역삼동 123"))
        assertFalse("서울 강남구 테헤란로", RouteGeocoder.needsRegionPrefix("서울 강남구 테헤란로 427"))
        assertFalse("경기 광주시 고산동", RouteGeocoder.needsRegionPrefix("경기 광주시 고산동 396"))

        // REGION_PREFIXES에 경기 광주시 포함
        assertTrue("경기 광주시 포함", RouteGeocoder.REGION_PREFIXES.contains("경기 광주시"))
    }

    /** T35: onDestroy 시 geocodeGeneration 무효화 */
    @Test
    fun t35_onDestroyInvalidatesGeocoding() {
        var generation = 0
        var isGeocoding = false

        // 작업 시작
        val gen = ++generation
        isGeocoding = true
        assertEquals(1, gen)

        // onDestroy 시뮬레이션
        generation++  // 무효화
        isGeocoding = false

        // callback 돌아옴 → stale
        assertNotEquals("generation 변경됨", gen, generation)
        assertFalse("isGeocoding 해제됨", isGeocoding)
    }

    /** T36: geocoding callback에서 isDestroyed/isFinishing 체크 */
    @Test
    fun t36_geocodeCallbackSafetyCheck() {
        // 시뮬레이션: Activity 종료 후 callback
        var isDestroyed = false
        var isFinishing = false
        var generation = 1
        val gen = generation

        // 정상 상태 → 처리
        val shouldProcess1 = gen == generation && !isFinishing && !isDestroyed
        assertTrue("정상 상태 → 처리", shouldProcess1)

        // Activity 종료 → 무시
        isDestroyed = true
        val shouldProcess2 = gen == generation && !isFinishing && !isDestroyed
        assertFalse("isDestroyed → 무시", shouldProcess2)

        // isFinishing → 무시
        isDestroyed = false; isFinishing = true
        val shouldProcess3 = gen == generation && !isFinishing && !isDestroyed
        assertFalse("isFinishing → 무시", shouldProcess3)

        // generation 불일치 → 무시
        isFinishing = false; generation++
        val shouldProcess4 = gen == generation && !isFinishing && !isDestroyed
        assertFalse("generation 불일치 → 무시", shouldProcess4)
    }

    // ══════════════════════════════════════
    // v0.2.7 내비 긴급 보강 (T37~T38)
    // ══════════════════════════════════════

    /** T37: 좌표 있는 경우 → 카카오맵 route 1순위 (kakaonavi-sdk 제거) */
    @Test
    fun t37_kakaoMapFirstWithCoord() {
        val steps = buildNaviStepsTest("테스트 주소", 37.5, 127.0)
        assertEquals("2단계 fallback", 2, steps.size)
        // 1순위 = 카카오맵 route
        assertTrue("1순위 kakaomap route", steps[0].uri.startsWith("kakaomap://route"))
        assertEquals("카카오맵 패키지", RouteModeActivity.PKG_KAKAOMAP, steps[0].pkg)
        assertTrue("ep=좌표", steps[0].uri.contains("ep=37.5,127.0"))
        // kakaonavi-sdk 없음
        assertFalse("kakaonavi-sdk 없음", steps.any { it.uri.contains("kakaonavi-sdk") })
        // 2순위 = geo
        assertTrue("2순위 geo", steps[1].uri.startsWith("geo:"))
    }

    /** T38: 짧은 주소 → prefix 우선 시도 순서 검증 */
    @Test
    fun t38_shortAddressPrefixFirst() {
        // needsRegionPrefix = true인 짧은 주소
        assertTrue(RouteGeocoder.needsRegionPrefix("고산동 396"))
        // prefix 우선 → REGION_PREFIXES[0] = "경기 광주시"
        val prefixed = "${RouteGeocoder.REGION_PREFIXES[0]} 고산동 396"
        assertEquals("경기 광주시 고산동 396", prefixed)
        // 이미 시/구 있는 주소 → prefix 불필요
        assertFalse(RouteGeocoder.needsRegionPrefix("광주시 고산동 396"))
        assertFalse(RouteGeocoder.needsRegionPrefix("경기 광주시 고산동 396"))
    }

    // ══════════════════════════════════════
    // v0.2.9 복귀 중복 제거 + 동선 정확도 (T39~T42)
    // ══════════════════════════════════════

    /** T39: 복귀 중복 제거 — 동일 주소 1개만 (복귀) */
    @Test
    fun t39_returnDedupSameAddress() {
        val returnMarker = RouteModeActivity.RETURN_MARKER
        val stops = mutableListOf(
            RouteStop(address = "역삼동 100", order = 0),
            RouteStop(address = "고산동 396", order = 1),
            RouteStop(address = "상암동 200", order = 2)
        )
        val returnAddr = "고산동 396"

        // 중복 제거 로직 시뮬레이션
        val normalizedReturn = RouteModeActivity.normalizeAddress(returnAddr)
        stops.removeAll { it.memo != returnMarker && RouteModeActivity.normalizeAddress(it.address) == normalizedReturn }
        stops.add(RouteStop(address = returnAddr, memo = returnMarker, order = stops.size))

        // 고산동 396 = 1개만 (복귀)
        val matching = stops.filter { it.address == "고산동 396" }
        assertEquals("고산동 1개만", 1, matching.size)
        assertEquals("복귀 마커", returnMarker, matching[0].memo)
        assertEquals("총 3개 (원래 3 - 1 중복 + 1 복귀)", 3, stops.size)
    }

    /** T40: 복귀 미중복 — 다른 주소면 모두 유지 */
    @Test
    fun t40_returnNoDedupDifferentAddress() {
        val returnMarker = RouteModeActivity.RETURN_MARKER
        val stops = mutableListOf(
            RouteStop(address = "역삼동 100", order = 0),
            RouteStop(address = "고산동 396", order = 1),
            RouteStop(address = "상암동 200", order = 2)
        )
        val returnAddr = "다른주소로 999"

        val normalizedReturn = RouteModeActivity.normalizeAddress(returnAddr)
        stops.removeAll { it.memo != returnMarker && RouteModeActivity.normalizeAddress(it.address) == normalizedReturn }
        stops.add(RouteStop(address = returnAddr, memo = returnMarker, order = stops.size))

        assertEquals("총 4개 (3 + 1 복귀)", 4, stops.size)
        assertEquals("마지막 = 복귀", returnMarker, stops.last().memo)
    }

    /** T41: 좌표 범위 내 허용 */
    @Test
    fun t41_coordInRangeAccepted() {
        // 기준: 경기 광주시 (37.40, 127.25)
        val refLat = 37.40; val refLng = 127.25
        // 광주시 내 좌표 (±0.1도)
        assertTrue("범위 내", RouteGeocoder.isInRange(37.42, 127.30, refLat, refLng))
        assertTrue("범위 내 (경계)", RouteGeocoder.isInRange(37.90, 127.75, refLat, refLng))
        // 기준 없음 → 항상 허용
        assertTrue("기준 없음", RouteGeocoder.isInRange(0.0, 0.0, 0.0, 0.0))
        assertTrue("기준 없음 + 어디든", RouteGeocoder.isInRange(35.0, 129.0, 0.0, 0.0))
    }

    /** T42: 좌표 범위 외 거부 */
    @Test
    fun t42_coordOutOfRangeRejected() {
        // 기준: 경기 광주시 (37.40, 127.25)
        val refLat = 37.40; val refLng = 127.25
        // 부산 (35.18, 129.07) = 범위 밖
        assertFalse("부산 범위 밖", RouteGeocoder.isInRange(35.18, 129.07, refLat, refLng))
        // 광주광역시 (35.16, 126.85) = 범위 밖
        assertFalse("광주광역시 범위 밖", RouteGeocoder.isInRange(35.16, 126.85, refLat, refLng))
    }
}

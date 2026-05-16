package com.vita.ontheway

/**
 * Route Mini v0.2: 자유 텍스트 → 주소 추출 (룰 기반).
 *
 * 카톡 복붙, 배민 주소 목록 등 자유 텍스트에서
 * 도로명/지번 주소를 추출하여 RouteStop 리스트로 변환.
 */
object RouteParser {

    // 도로명: "XX로 123" 또는 "XX길 45-6"
    private val ROAD_ADDR = Regex(
        """(?:(?:서울|경기|인천|부산|대구|대전|광주|울산|세종|강원|충북|충남|전북|전남|경북|경남|제주)\s*)?""" +
        """(?:\S{1,10}(?:시|군|구)\s+)?""" +
        """(?:\S{1,10}(?:구|동|읍|면)\s+)?""" +
        """\S{1,20}(?:로|길)\s*\d[\d\-]*"""
    )

    // 지번: "역삼동 123-45" 또는 "XX리 67" (한글+동, 순수 숫자동 제외)
    private val JIBUN_ADDR = Regex(
        """(?:(?:서울|경기|인천|부산|대구|대전|광주|울산|세종|강원|충북|충남|전북|전남|경북|경남|제주)\s*)?""" +
        """(?:\S{1,10}(?:시|군|구)\s+)?""" +
        """[가-힣]{1,10}(?:동|리|읍|면)\s+\d[\d\-]*"""
    )

    // 아파트/건물명 패턴 (주소 뒤에 오는 상세)
    private val DETAIL = Regex("""(?:\s+\S{1,20}(?:아파트|빌라|오피스텔|타워|맨션|하이츠|파크|단지|빌딩|상가))?\s*(?:(\d{1,4})동)?\s*(?:(\d{1,5})호)?""")

    // 동/호수 추출
    private val DONG_HO = Regex("""(\d{1,4})\s*동\s*(\d{1,5})\s*호""")
    private val HO_ONLY = Regex("""(\d{1,5})\s*호""")
    private val DONG_ONLY = Regex("""(\d{1,4})\s*동""")

    /**
     * 자유 텍스트에서 RouteStop 리스트 추출.
     * 줄 단위로 파싱하되, 주소가 없는 줄은 이전 주소의 memo로 처리.
     */
    fun parse(text: String): List<RouteStop> {
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        val stops = mutableListOf<RouteStop>()
        var pendingMemo = ""

        for (line in lines) {
            val addr = extractAddress(line)
            if (addr != null) {
                if (pendingMemo.isNotBlank() && stops.isNotEmpty()) {
                    val last = stops.last()
                    stops[stops.lastIndex] = last.copy(memo = (last.memo + " " + pendingMemo).trim())
                }
                pendingMemo = ""

                val dongHo = extractDongHo(line)
                val remainder = line.replace(addr, "").trim()
                    .replace(DONG_HO, "").replace(HO_ONLY, "").replace(DONG_ONLY, "").trim()

                stops.add(RouteStop(
                    address = addr,
                    dong = dongHo.first,
                    ho = dongHo.second,
                    memo = remainder.take(50),
                    order = stops.size
                ))
            } else {
                // 숫자만 있는 줄 → 동/호수일 수 있음
                val dongHo = extractDongHo(line)
                if (dongHo.first.isNotBlank() || dongHo.second.isNotBlank()) {
                    if (stops.isNotEmpty()) {
                        val last = stops.last()
                        stops[stops.lastIndex] = last.copy(
                            dong = last.dong.ifBlank { dongHo.first },
                            ho = last.ho.ifBlank { dongHo.second }
                        )
                    }
                } else if (line.length <= 60) {
                    pendingMemo = line
                }
            }
        }

        if (pendingMemo.isNotBlank() && stops.isNotEmpty()) {
            val last = stops.last()
            stops[stops.lastIndex] = last.copy(memo = (last.memo + " " + pendingMemo).trim())
        }

        return stops
    }

    // 장소명 패턴: "XX역", "XX역 N번출구", "XX광장", "XX공원", "XX마트", "XX센터" 등
    private val PLACE_NAME = Regex(
        """[가-힣]{1,10}(?:역|광장|공원|마트|센터|터미널|시장|백화점|병원|학교|대학|아파트|빌딩|타워)(?:\s*\d{1,2}번\s*출구)?"""
    )

    // 구/동 단위 장소: "송파구 가락동", "강남구 역삼동"
    private val GU_DONG = Regex(
        """[가-힣]{1,5}구\s+[가-힣]{1,10}동"""
    )

    /** 한 줄에서 주소 추출 (도로명 > 지번 > 장소명 > 구동 순서) */
    internal fun extractAddress(line: String): String? {
        ROAD_ADDR.find(line)?.let { return it.value.trim() }
        JIBUN_ADDR.find(line)?.let { return it.value.trim() }
        PLACE_NAME.find(line)?.let { return it.value.trim() }
        GU_DONG.find(line)?.let { return it.value.trim() }
        return null
    }

    /** 장소명 여부 (도로명/지번이 아닌 경우) — keyword search 대상 판별 */
    internal fun isPlaceName(address: String): Boolean {
        if (ROAD_ADDR.containsMatchIn(address)) return false
        if (JIBUN_ADDR.containsMatchIn(address)) return false
        return PLACE_NAME.containsMatchIn(address) || GU_DONG.containsMatchIn(address)
    }

    /** 동/호수 추출 */
    internal fun extractDongHo(line: String): Pair<String, String> {
        DONG_HO.find(line)?.let {
            return Pair(it.groupValues[1], it.groupValues[2])
        }
        val ho = HO_ONLY.find(line)?.groupValues?.get(1) ?: ""
        // "동" 앞 숫자가 주소의 일부일 수 있어서 주소가 없는 줄에서만 dong 추출
        val dong = if (extractAddress(line) == null) {
            DONG_ONLY.find(line)?.groupValues?.get(1) ?: ""
        } else ""
        return Pair(dong, ho)
    }
}

package com.vita.ontheway

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

/**
 * Route Mini v0.2: 이동 운영 인터페이스.
 *
 * 7단계 흐름:
 * 1. 진입 → 자유 입력
 * 2. 주소 자동 추출
 * 3. 원문 보존 (collapsible)
 * 4. 추출 결과 편집 (수정/삭제/추가)
 * 5. 자동 Route (nearest-first)
 * 6. drag reorder + 내비 시작
 * 7. 배송 완료 → 다음 강조
 */
class RouteModeActivity : AppCompatActivity() {

    private val C_BG = Color.parseColor("#0F0F1A")
    private val C_GREEN = Color.parseColor("#00F5A0")
    private val C_BLUE = Color.parseColor("#00C9FF")
    private val C_RED = Color.parseColor("#EF233C")
    private val C_SUB = Color.parseColor("#6C757D")
    private val C_CARD = Color.parseColor("#1A1A2E")
    private val C_CURRENT = Color.parseColor("#1B3A2D")
    private val C_DONE = Color.parseColor("#2A2A2A")
    private val C_NEXT = Color.parseColor("#1A2A3E")

    private lateinit var inputArea: View
    private lateinit var inputEdit: EditText
    private lateinit var extractBtn: View
    private lateinit var ocrBtn: View
    private lateinit var returnInput: EditText
    private lateinit var rawToggle: TextView
    private lateinit var rawText: TextView
    private lateinit var dragHint: View
    private lateinit var stopListContainer: LinearLayout
    private lateinit var bottomBar: View
    private lateinit var addStopBtn: View
    private lateinit var optimizeBtn: TextView
    private lateinit var endBtn: View

    private val stops = mutableListOf<RouteStop>()
    private var rawInput = ""
    private var isRouteActive = false

    // v0.2.3: 자체 위치 확보용
    private var routeLat: Double = 0.0
    private var routeLng: Double = 0.0

    // v0.2.5: geocoding 중복 방지
    @Volatile private var geocodeGeneration: Int = 0
    private var isGeocoding = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_route_mode)
        window.statusBarColor = C_BG
        window.navigationBarColor = C_BG

        inputArea = findViewById(R.id.route_input_area)
        inputEdit = findViewById(R.id.route_input)
        extractBtn = findViewById(R.id.route_extract_btn)
        ocrBtn = findViewById(R.id.route_ocr_btn)
        rawToggle = findViewById(R.id.route_raw_toggle)
        rawText = findViewById(R.id.route_raw_text)
        dragHint = findViewById(R.id.route_drag_hint)
        stopListContainer = findViewById(R.id.route_stop_list)
        bottomBar = findViewById(R.id.route_bottom_bar)
        addStopBtn = findViewById(R.id.route_add_stop_btn)
        optimizeBtn = findViewById(R.id.route_optimize_btn)
        returnInput = findViewById(R.id.route_return_input)
        endBtn = findViewById(R.id.route_end_btn)
        // 복귀지 복원
        val savedReturn = RouteStateStore.loadReturnAddress(this)
        if (savedReturn.isNotBlank()) returnInput.setText(savedReturn)
        // 운영 종료 버튼 스타일: 테두리 + 배경
        (endBtn as? TextView)?.let { btn ->
            btn.background = GradientDrawable().apply {
                setStroke(dp(1), C_RED)
                cornerRadius = dp(8).toFloat()
                setColor(Color.TRANSPARENT)
            }
        }

        extractBtn.setOnClickListener { onExtract() }
        ocrBtn.setOnClickListener {
            Toast.makeText(this, "OCR 기능은 P2에서 추가됩니다", Toast.LENGTH_SHORT).show()
        }
        addStopBtn.setOnClickListener { showAddStopDialog() }
        optimizeBtn.setOnClickListener { onOptimizeOrNaviNext() }
        endBtn.setOnClickListener { onEndRoute() }
        rawToggle.setOnClickListener { toggleRawText() }

        // v0.2.6: GPS 권한 런타임 요청
        requestLocationPermissionIfNeeded()

        // 복원
        val saved = RouteStateStore.loadStops(this)
        if (saved.isNotEmpty()) {
            stops.clear(); stops.addAll(saved)
            rawInput = RouteStateStore.loadRawText(this)
            isRouteActive = stops.any { it.status == RouteStop.Status.CURRENT || it.status == RouteStop.Status.DONE }
            showRouteView()
        }
    }

    private fun requestLocationPermissionIfNeeded() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1001)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                OtwFileLogger.log("RouteMini", "GPS 권한 허용")
            } else {
                Toast.makeText(this, "위치 권한 없이도 사용 가능 (텍스트 정렬)", Toast.LENGTH_SHORT).show()
                OtwFileLogger.log("RouteMini", "GPS 권한 거부 → 텍스트 fallback")
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (stops.isNotEmpty()) {
            RouteStateStore.saveStops(this, stops)
            RouteStateStore.saveRawText(this, rawInput)
        }
    }

    // ══════════════════════════════════════
    // Step 1-2: 자유 입력 → 추출
    // ══════════════════════════════════════

    private fun onExtract() {
        if (isGeocoding) return // 중복 클릭 방지

        val text = inputEdit.text.toString().trim()
        if (text.isBlank()) {
            Toast.makeText(this, "주소를 입력해주세요", Toast.LENGTH_SHORT).show()
            return
        }

        rawInput = text
        val parsed = RouteParser.parse(text)
        if (parsed.isEmpty()) {
            Toast.makeText(this, "주소를 찾지 못했습니다.\n도로명 또는 지번 주소를 포함해주세요.", Toast.LENGTH_LONG).show()
            return
        }

        stops.clear()
        stops.addAll(parsed)
        showRouteView()

        // v0.2.5: 중복 방지 + stale callback 무시
        val gen = ++geocodeGeneration
        isGeocoding = true
        extractBtn.isEnabled = false
        (extractBtn as? TextView)?.text = "변환 중..."

        acquireLocation() // 범위 검증용 현재 위치
        RouteGeocoder.geocodeAll(this, stops, refLat = routeLat, refLng = routeLng, callback = { geocoded ->
            runOnUiThread {
                if (gen != geocodeGeneration || isFinishing || isDestroyed) return@runOnUiThread
                isGeocoding = false
                extractBtn.isEnabled = true
                (extractBtn as? TextView)?.text = "주소 추출"
                val geocodedCount = geocoded.count { it.hasCoord() }
                stops.clear(); stops.addAll(geocoded)
                renderStopList()
                RouteStateStore.saveStops(this, stops)
                if (geocodedCount > 0) {
                    Toast.makeText(this, "$geocodedCount/${stops.size} 좌표 변환 완료", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    // ══════════════════════════════════════
    // Step 3-4: 추출 결과 표시 + 편집
    // ══════════════════════════════════════

    private fun showRouteView() {
        inputArea.visibility = View.GONE
        rawToggle.visibility = View.VISIBLE
        rawText.text = rawInput
        bottomBar.visibility = View.VISIBLE
        // drag 힌트: Route 미활성 + 2개 이상 stop일 때만 표시
        dragHint.visibility = if (!isRouteActive && stops.size >= 2) View.VISIBLE else View.GONE
        updateOptimizeButton()
        renderStopList()
        RouteStateStore.saveStops(this, stops)
    }

    private fun updateOptimizeButton() {
        if (!isRouteActive) {
            optimizeBtn.text = "Route 생성"
            optimizeBtn.setBackgroundColor(C_GREEN)
        } else {
            // 현재 정류장이 있으면 "내비 시작"
            val current = stops.firstOrNull { it.status == RouteStop.Status.CURRENT }
            if (current != null) {
                optimizeBtn.text = "내비 시작"
                optimizeBtn.setBackgroundColor(C_BLUE)
            } else {
                optimizeBtn.text = "모든 배송 완료"
                optimizeBtn.setBackgroundColor(C_SUB)
            }
        }
    }

    private fun renderStopList() {
        stopListContainer.removeAllViews()

        for ((idx, stop) in stops.withIndex()) {
            val card = createStopCard(idx, stop)
            stopListContainer.addView(card)
        }
    }

    private fun createStopCard(idx: Int, stop: RouteStop): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val bg = GradientDrawable().apply {
                setColor(when (stop.status) {
                    RouteStop.Status.CURRENT -> C_CURRENT
                    RouteStop.Status.DONE -> C_DONE
                    RouteStop.Status.PENDING -> {
                        if (isRouteActive && isNextStop(idx)) C_NEXT else C_CARD
                    }
                })
                cornerRadius = dp(12).toFloat()
            }
            background = bg
            setPadding(dp(12), dp(14), dp(12), dp(14))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            layoutParams = lp
        }

        // Drag handle
        val dragHandle = TextView(this).apply {
            text = "\u2630"
            textSize = 20f
            setTextColor(C_SUB)
            setPadding(0, 0, dp(8), 0)
        }
        setupDragHandle(dragHandle, idx)
        card.addView(dragHandle)

        // Order number
        val orderLabel = TextView(this).apply {
            text = "${idx + 1}"
            textSize = 20f
            setTextColor(when (stop.status) {
                RouteStop.Status.CURRENT -> C_GREEN
                RouteStop.Status.DONE -> C_SUB
                else -> Color.WHITE
            })
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(0, 0, dp(10), 0)
        }
        card.addView(orderLabel)

        // Address info
        val infoCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val addrText = TextView(this).apply {
            text = stop.address
            textSize = 17f
            setTextColor(if (stop.status == RouteStop.Status.DONE) C_SUB else Color.WHITE)
            if (stop.status == RouteStop.Status.CURRENT) {
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
        }
        infoCol.addView(addrText)

        val isReturn = stop.memo == RETURN_MARKER
        val detail = buildString {
            if (stop.dong.isNotBlank()) append("${stop.dong}동 ")
            if (stop.ho.isNotBlank()) append("${stop.ho}호 ")
            if (stop.memo.isNotBlank() && !isReturn) append(stop.memo)
        }.trim()
        if (detail.isNotBlank()) {
            infoCol.addView(TextView(this).apply {
                text = detail
                textSize = 13f
                setTextColor(C_SUB)
            })
        }
        if (isReturn) {
            infoCol.addView(TextView(this).apply {
                text = "복귀"
                textSize = 13f
                setTextColor(Color.parseColor("#FFA500"))
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            })
        }

        if (stop.status == RouteStop.Status.CURRENT) {
            infoCol.addView(TextView(this).apply {
                text = "현재 목적지"
                textSize = 13f
                setTextColor(C_GREEN)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            })
        } else if (stop.status == RouteStop.Status.DONE) {
            infoCol.addView(TextView(this).apply {
                text = "완료"
                textSize = 13f
                setTextColor(C_SUB)
            })
        }

        card.addView(infoCol)

        // Action buttons
        val actionCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        if (isRouteActive && stop.status == RouteStop.Status.CURRENT) {
            val doneBtn = TextView(this).apply {
                text = "완료"
                textSize = 16f
                setTextColor(Color.WHITE)
                val bg = GradientDrawable().apply {
                    setColor(C_GREEN); cornerRadius = dp(8).toFloat()
                }
                background = bg
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(8), dp(12), dp(8))
            }
            doneBtn.setOnClickListener { onDeliveryComplete(idx) }
            actionCol.addView(doneBtn)
        } else if (!isRouteActive) {
            // Edit mode: edit + delete
            val editBtn = TextView(this).apply {
                text = "\u270E"
                textSize = 18f
                setTextColor(C_SUB)
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
            editBtn.setOnClickListener { showEditStopDialog(idx) }
            actionCol.addView(editBtn)

            val delBtn = TextView(this).apply {
                text = "\u2716"
                textSize = 16f
                setTextColor(C_RED)
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
            delBtn.setOnClickListener {
                stops.removeAt(idx)
                reindex()
                renderStopList()
                RouteStateStore.saveStops(this, stops)
            }
            actionCol.addView(delBtn)
        }

        card.addView(actionCol)
        return card
    }

    private fun isNextStop(idx: Int): Boolean {
        val currentIdx = stops.indexOfFirst { it.status == RouteStop.Status.CURRENT }
        if (currentIdx < 0) return false
        val nextPending = stops.withIndex()
            .filter { it.value.status == RouteStop.Status.PENDING }
            .take(2)
            .map { it.index }
        return idx in nextPending
    }

    // ══════════════════════════════════════
    // Step 5: Route 생성 / 내비 시작
    // ══════════════════════════════════════

    private fun onOptimizeOrNaviNext() {
        if (!isRouteActive) {
            // 미변환 stop이 있으면 지오코딩 후 최적화, 없으면 즉시 최적화
            val needsGeocode = stops.any { !it.hasCoord() }
            if (needsGeocode) {
                optimizeBtn.text = "Route 생성 중..."
                optimizeBtn.setBackgroundColor(C_SUB)
                optimizeBtn.isEnabled = false
                acquireLocation()
                RouteGeocoder.geocodeAll(this, stops, refLat = routeLat, refLng = routeLng, callback = { geocoded ->
                    runOnUiThread {
                        stops.clear(); stops.addAll(geocoded)
                        executeOptimize()
                        optimizeBtn.isEnabled = true
                    }
                })
            } else {
                executeOptimize()
            }
        } else {
            // 내비 시작
            val current = stops.firstOrNull { it.status == RouteStop.Status.CURRENT }
            if (current != null) {
                startNavigation(current.address, current.lat, current.lng)
            } else {
                Toast.makeText(this, "모든 배송 완료!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun executeOptimize() {
        // v0.2.3: 자체 위치 확보 (옵션 C)
        acquireLocation()
        val curLat = routeLat
        val curLng = routeLng

        // v0.2.3: 복귀지 추가 (마지막 stop)
        // v0.2.9: 동일 주소 중복 제거
        val returnAddr = returnInput.text.toString().trim()
        if (returnAddr.isNotBlank()) {
            RouteStateStore.saveReturnAddress(this, returnAddr)
            stops.removeAll { it.memo == RETURN_MARKER }
            // 일반 stop 중 복귀 주소와 동일한 것 제거
            val normalizedReturn = normalizeAddress(returnAddr)
            stops.removeAll { it.memo != RETURN_MARKER && normalizeAddress(it.address) == normalizedReturn }
            stops.add(RouteStop(address = returnAddr, memo = RETURN_MARKER, order = stops.size))
        }

        val geocodedCount = stops.count { it.hasCoord() }
        val optimized = RouteOptimizer.optimize(stops, currentLat = curLat, currentLng = curLng)

        // 복귀 stop은 항상 마지막으로 이동
        val returnStop = optimized.firstOrNull { it.memo == RETURN_MARKER }
        val nonReturn = optimized.filter { it.memo != RETURN_MARKER }
        val finalList = if (returnStop != null) {
            nonReturn.mapIndexed { i, s -> s.copy(order = i) } +
                returnStop.copy(order = nonReturn.size)
        } else {
            optimized
        }

        stops.clear(); stops.addAll(finalList)
        if (stops.isNotEmpty()) {
            stops[0] = stops[0].copy(status = RouteStop.Status.CURRENT)
        }
        isRouteActive = true
        renderStopList()
        updateOptimizeButton()
        RouteStateStore.saveStops(this, stops)
        val locMode = if (curLat != 0.0) "현재 위치" else "텍스트"
        val geoMode = if (geocodedCount > 0) "좌표" else "텍스트"
        Toast.makeText(this, "Route 생성 ($locMode+$geoMode, ${stops.size}개)", Toast.LENGTH_SHORT).show()
    }

    companion object {
        internal const val RETURN_MARKER = "[복귀]"
        internal const val PREF_NAVI_TIP_SHOWN = "route_mini_navi_tip_shown"
        const val PKG_KAKAONAVI = "com.locnall.KimGiSa"
        const val PKG_KAKAOMAP = "net.daum.android.map"

        /** 주소 정규화 (중복 비교용): 공백 통일 + 시도/구 prefix 제거 */
        internal fun normalizeAddress(addr: String): String =
            addr.trim().replace(Regex("\\s+"), " ")
                .replace(Regex("^(?:서울|경기|인천|부산|대구|대전|광주|울산|세종|강원|충북|충남|전북|전남|경북|경남|제주)\\s*"), "")
                .replace(Regex("^\\S+(?:시|군)\\s+"), "")
                .trim()
    }

    private fun showNaviTipOnce() {
        val prefs = getSharedPreferences("route_mini", Context.MODE_PRIVATE)
        if (prefs.getBoolean(PREF_NAVI_TIP_SHOWN, false)) return
        prefs.edit().putBoolean(PREF_NAVI_TIP_SHOWN, true).apply()
        try {
            com.google.android.material.snackbar.Snackbar
                .make(findViewById(android.R.id.content),
                    "Tip: 우측 화면에 카카오내비를 미리 띄워두면 더 편리합니다",
                    com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                .show()
        } catch (_: Exception) {
            // Material 없으면 Toast fallback
            Toast.makeText(this, "Tip: 우측에 카카오내비를 미리 띄워두면 편리합니다", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * v0.2.3: 자체 위치 확보 (옵션 C).
     * 1. OnTheWayService.currentLat/Lng (DRIVING 모드)
     * 2. LocationManager.getLastKnownLocation (IDLE 모드 fallback)
     */
    internal fun acquireLocation() {
        // 1. OnTheWayService 값 (DRIVING 모드)
        val svcLat = OnTheWayService.currentLat
        val svcLng = OnTheWayService.currentLng
        if (svcLat != 0.0 && svcLng != 0.0) {
            routeLat = svcLat; routeLng = svcLng
            OtwFileLogger.log("RouteMini", "위치: Service ($routeLat, $routeLng)")
            return
        }

        // 2. LocationManager.getLastKnownLocation (IDLE fallback)
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {

                val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                if (loc != null) {
                    routeLat = loc.latitude; routeLng = loc.longitude
                    OtwFileLogger.log("RouteMini", "위치: LastKnown ($routeLat, $routeLng)")
                    return
                }
            }
        } catch (e: Exception) {
            OtwFileLogger.log("RouteMini", "위치 확보 실패: ${e.message}")
        }

        routeLat = 0.0; routeLng = 0.0
        OtwFileLogger.log("RouteMini", "위치 확보 실패 → 텍스트 정렬")
    }

    // ══════════════════════════════════════
    // Step 6: 내비 시작 (4단계 fallback)
    // ══════════════════════════════════════

    /**
     * 3단계 fallback (v0.2.7 — 카카오맵 우선, kakaonavi-sdk 제거):
     * 1. 카카오맵 (net.daum.android.map) — 좌표 route 또는 검색
     * 2. geo: URI (일반 지도 앱)
     * 3. 클립보드 복사
     */
    internal fun startNavigation(address: String, lat: Double = 0.0, lng: Double = 0.0) {
        // v0.2.4: Fold 멀티 윈도우 Tip (1회만)
        showNaviTipOnce()

        val hasCoord = lat != 0.0 && lng != 0.0
        val steps = buildNaviSteps(address, lat, lng)

        for (step in steps) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(step.uri))
                if (step.pkg != null) intent.setPackage(step.pkg)
                // Fold 멀티 윈도우: 우측 유지
                intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_NEW_TASK)
                if (intent.resolveActivity(packageManager) != null) {
                    startActivity(intent)
                    OtwFileLogger.log("RouteMini", "내비 시작: ${step.label} uri=${step.uri.take(80)}")
                    return
                }
            } catch (_: Exception) {}
            OtwFileLogger.log("RouteMini", "내비 fallback: ${step.label} 실패")
        }

        // 최종 fallback: 클립보드 복사
        val clipText = if (hasCoord) "$address ($lat,$lng)" else address
        val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clip.setPrimaryClip(ClipData.newPlainText("address", clipText))
        Toast.makeText(this, "주소가 클립보드에 복사되었습니다\n지도 앱에서 붙여넣기 해주세요", Toast.LENGTH_LONG).show()
        OtwFileLogger.log("RouteMini", "내비 fallback: 클립보드 복사")
    }

    /** 내비 fallback step (URI + 패키지명 + 라벨) */
    internal data class NaviStep(val uri: String, val pkg: String?, val label: String)

    /**
     * 내비 fallback 단계 생성 (테스트 가능).
     *
     * v0.2.7: kakaonavi-sdk 제거 (실폰에서 "유효하지 않은 URI" 확인).
     * 카카오맵 deep link 1순위.
     *
     * 좌표 있는 경우:
     *   1. kakaomap://route?sp=현재위치&ep=lat,lng&by=CAR (경로)
     *   2. geo:lat,lng?q=주소명
     *
     * 좌표 없는 경우:
     *   1. kakaomap://search?q=주소
     *   2. geo:0,0?q=주소
     */
    internal fun buildNaviSteps(address: String, lat: Double = 0.0, lng: Double = 0.0): List<NaviStep> {
        val hasCoord = lat != 0.0 && lng != 0.0
        val encoded = Uri.encode(address)
        return listOf(
            // 1. 카카오맵 (좌표 route 또는 검색)
            if (hasCoord)
                NaviStep("kakaomap://route?ep=$lat,$lng&by=CAR", PKG_KAKAOMAP, "카카오맵 경로")
            else
                NaviStep("kakaomap://search?q=$encoded", PKG_KAKAOMAP, "카카오맵 검색"),
            // 2. geo URI (패키지 미지정 → 시스템 선택)
            NaviStep(
                if (hasCoord) "geo:$lat,$lng?q=$encoded" else "geo:0,0?q=$encoded",
                null, "지도 앱"
            )
        )
    }

    /** 하위 호환: URI만 필요한 테스트용 */
    internal fun buildNaviUris(address: String, lat: Double = 0.0, lng: Double = 0.0): List<String> =
        buildNaviSteps(address, lat, lng).map { it.uri }

    // ══════════════════════════════════════
    // Step 7: 배송 완료
    // ══════════════════════════════════════

    internal fun onDeliveryComplete(idx: Int) {
        if (idx < 0 || idx >= stops.size) return
        stops[idx] = stops[idx].copy(status = RouteStop.Status.DONE)

        // 다음 PENDING → CURRENT
        val nextIdx = stops.indexOfFirst { it.status == RouteStop.Status.PENDING }
        if (nextIdx >= 0) {
            stops[nextIdx] = stops[nextIdx].copy(status = RouteStop.Status.CURRENT)
        }

        renderStopList()
        updateOptimizeButton()
        RouteStateStore.saveStops(this, stops)

        if (nextIdx < 0) {
            Toast.makeText(this, "모든 배송 완료!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "다음: ${stops[nextIdx].address}", Toast.LENGTH_SHORT).show()
        }
    }

    // ══════════════════════════════════════
    // Drag reorder
    // ══════════════════════════════════════

    private fun setupDragHandle(handle: View, fromIdx: Int) {
        if (isRouteActive) return // Route 활성 후 reorder 불가

        var startY = 0f
        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.rawY
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dy = event.rawY - startY
                    val threshold = dp(40)
                    if (kotlin.math.abs(dy) > threshold) {
                        val direction = if (dy > 0) 1 else -1
                        val toIdx = (fromIdx + direction).coerceIn(0, stops.size - 1)
                        if (toIdx != fromIdx) {
                            swapStops(fromIdx, toIdx)
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    internal fun swapStops(from: Int, to: Int) {
        if (from < 0 || from >= stops.size || to < 0 || to >= stops.size) return
        val temp = stops[from]
        stops[from] = stops[to]
        stops[to] = temp
        reindex()
        renderStopList()
        RouteStateStore.saveStops(this, stops)
    }

    private fun reindex() {
        for (i in stops.indices) {
            stops[i] = stops[i].copy(order = i)
        }
    }

    // ══════════════════════════════════════
    // Dialogs
    // ══════════════════════════════════════

    private fun showEditStopDialog(idx: Int) {
        val stop = stops.getOrNull(idx) ?: return
        val editText = EditText(this).apply {
            setText(stop.address)
            textSize = 16f
            setTextColor(Color.WHITE)
            setBackgroundColor(C_CARD)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("주소 수정")
            .setView(editText)
            .setPositiveButton("저장") { _, _ ->
                val newAddr = editText.text.toString().trim()
                if (newAddr.isNotBlank()) {
                    stops[idx] = stop.copy(address = newAddr)
                    renderStopList()
                    RouteStateStore.saveStops(this, stops)
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showAddStopDialog() {
        val editText = EditText(this).apply {
            hint = "새 주소 입력"
            textSize = 16f
            setTextColor(Color.WHITE)
            setBackgroundColor(C_CARD)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("정류장 추가")
            .setView(editText)
            .setPositiveButton("추가") { _, _ ->
                val addr = editText.text.toString().trim()
                if (addr.isNotBlank()) {
                    stops.add(RouteStop(address = addr, order = stops.size))
                    renderStopList()
                    RouteStateStore.saveStops(this, stops)
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ══════════════════════════════════════
    // Raw text toggle
    // ══════════════════════════════════════

    private var rawExpanded = false
    private fun toggleRawText() {
        rawExpanded = !rawExpanded
        rawText.visibility = if (rawExpanded) View.VISIBLE else View.GONE
        rawToggle.text = if (rawExpanded) "원문 접기" else "원문 보기"
    }

    // ══════════════════════════════════════
    // End route
    // ══════════════════════════════════════

    private fun onEndRoute() {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("운영 종료")
            .setMessage("경로를 초기화하시겠습니까?")
            .setPositiveButton("종료") { _, _ ->
                stops.clear()
                isRouteActive = false
                rawInput = ""
                RouteStateStore.clear(this)
                inputArea.visibility = View.VISIBLE
                rawToggle.visibility = View.GONE
                rawText.visibility = View.GONE
                bottomBar.visibility = View.GONE
                stopListContainer.removeAllViews()
                inputEdit.setText("")
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ══════════════════════════════════════
    // Lifecycle
    // ══════════════════════════════════════

    override fun onDestroy() {
        // v0.2.6: geocoding callback 무효화
        geocodeGeneration++
        isGeocoding = false
        super.onDestroy()
    }

    /** 테스트용: stops 접근 */
    internal fun getStops(): List<RouteStop> = stops.toList()
    internal fun setStopsForTest(newStops: List<RouteStop>) {
        stops.clear(); stops.addAll(newStops)
    }
}

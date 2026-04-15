package com.vita.ontheway

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.RecognizerIntent
import android.widget.*
import android.view.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.graphics.*
import android.graphics.drawable.*
import kotlinx.coroutines.*
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private lateinit var chatLayout: LinearLayout
    private lateinit var inputField: EditText
    private lateinit var scrollView: ScrollView
    private lateinit var placesRow: LinearLayout
    private lateinit var micBtn: TextView
    private lateinit var resultCard: LinearLayout
    private lateinit var resultRoute: TextView
    private lateinit var resultAmount: TextView
    private lateinit var resultTag: TextView
    private lateinit var acceptBtn: TextView
    private lateinit var skipBtn: TextView
    private lateinit var detailBtn: TextView
    private lateinit var earningText: TextView
    private lateinit var earningMeta: TextView
    private lateinit var progressFill: View
    private lateinit var voiceManager: VoiceManager
    private lateinit var filterStatusText: TextView
    private lateinit var filterCountText: TextView
    private lateinit var tabStatus: TextView
    private lateinit var tabChat: TextView
    private lateinit var tabIndicator: View
    private lateinit var statusPanel: ScrollView
    private lateinit var chatPanel: LinearLayout
    private lateinit var lastCallText: TextView
    private lateinit var recentCallList: LinearLayout
    private lateinit var inputBar: LinearLayout
    private var currentTab = "status"  // "status" or "chat"

    private var partialBubble: TextView? = null
    private val messages = mutableListOf<Pair<String, String>>()
    private var isSpeaking = false
    private var todayEarning = 0
    private var todayGoalAmt = 100000
    private val mainHandler = Handler(Looper.getMainLooper())
    private val VOICE_REQUEST = 100

    private val NOW_ALIASES = setOf("지금 바로", "지금", "바로", "즉시", "출발", "가자")
    private val DEPARTURE_ALIASES = setOf("30분 뒤", "1시간 뒤", "오늘 저녁")
    private val CANCEL_ALIASES = setOf("취소", "리셋", "다시", "초기화", "처음부터", "클리어")
    private val HOME_ALIASES = setOf("집으로", "집 가자", "귀가", "퇴근", "집에 가자", "집")
    private val STATS_ALIASES = setOf("수익", "통계", "얼마", "오늘 수익", "얼마 벌었어", "매출")
    private val HELP_ALIASES = setOf("도움말", "도와줘", "사용법", "뭐 할 수 있어", "명령어")
    private var decisionTimer: Runnable? = null
    private var timerSeconds = 0
    private var fontScale = 1.0f

    private enum class AiContext { DESTINATION, ORIGIN, DEPARTURE, NONE }
    private var aiContext: AiContext = AiContext.DESTINATION

    // ═══ v4.2 컬러 — 운전 중 가독성 최우선 ═══
    private val C_WHITE    = Color.WHITE  // 카드 배경
    private val C_BRIGHT   = Color.parseColor("#333333")  // 본문 텍스트
    private val C_BLUE     = Color.parseColor("#5B6ABF")  // 퍼플 액센트
    private val C_BLUE_LT  = Color.parseColor("#7B8AD0")  // 밝은 퍼플
    private val C_SUB      = Color.parseColor("#999999")  // 서브
    private val C_DIM      = Color.parseColor("#BBBBBB")  // 힌트
    private val C_CARD     = Color.parseColor("#FFFFFF")  // 카드/다이얼로그
    private val C_BUBBLE   = Color.parseColor("#E8E8E8")  // 에이전트 버블

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val MP = ViewGroup.LayoutParams.MATCH_PARENT
    private val WC = ViewGroup.LayoutParams.WRAP_CONTENT
    private fun lp(w: Int, h: Int, wt: Float = 0f) = LinearLayout.LayoutParams(w, h, wt)
    private fun fmt(n: Int) = String.format("%,d", n)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // v3.5: 온보딩 체크
        if (OnboardingActivity.isFirstRun(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE
        // 라이트 테마: 상태바 아이콘 어둡게
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        setContentView(R.layout.activity_main)
        tts = TextToSpeech(this, this)

        chatLayout   = findViewById(R.id.chatLayout)
        inputField   = findViewById(R.id.inputField)
        scrollView   = findViewById(R.id.scrollView)
        placesRow    = findViewById(R.id.placesRow)
        micBtn       = findViewById(R.id.micBtn)
        resultCard   = findViewById(R.id.resultCard)
        resultRoute  = findViewById(R.id.resultRoute)
        resultAmount = findViewById(R.id.resultAmount)
        resultTag    = findViewById(R.id.resultTag)
        acceptBtn    = findViewById(R.id.acceptBtn)
        skipBtn      = findViewById(R.id.skipBtn)
        detailBtn    = findViewById(R.id.detailBtn)
        earningText  = findViewById(R.id.earningText)
        earningMeta  = findViewById(R.id.earningMeta)
        progressFill = findViewById(R.id.progressFill)
        filterStatusText = findViewById(R.id.filterStatusText)
        filterCountText = findViewById(R.id.filterCountText)
        tabStatus = findViewById(R.id.tabStatus)
        tabChat = findViewById(R.id.tabChat)
        tabIndicator = findViewById(R.id.tabIndicator)
        statusPanel = findViewById(R.id.statusPanel)
        chatPanel = findViewById(R.id.chatPanel)
        lastCallText = findViewById(R.id.lastCallText)
        recentCallList = findViewById(R.id.recentCallList)

        val statsBtn = findViewById<TextView>(R.id.statsBtn)
        val favBtn   = findViewById<TextView>(R.id.favBtn)
        val sendBtn  = findViewById<TextView>(R.id.sendBtn)
        val svcBtn   = findViewById<TextView>(R.id.svcBtn)
        val settingsBtn = findViewById<TextView>(R.id.settingsBtn)

        // 글자 크기 스케일
        val fs = FontSizeManager.getScale(this)
        fontScale = fs

        // ★ 글자 크기를 모든 UI 요소에 적용
        statsBtn.textSize = 11f * fs
        favBtn.textSize = 11f * fs
        svcBtn.textSize = 11f * fs
        settingsBtn.textSize = 11f * fs
        earningText.textSize = 18f * fs
        earningMeta.textSize = 11f * fs
        inputField.textSize = 15f * fs

        voiceManager = VoiceManager(
            context = this,
            onReady  = { micBtn.text = "\uD83D\uDD34" },
            onPartial = { text -> showPartialBubble(text) },
            onResult  = { text ->
                clearPartialBubble()
                // 음성 수락 명령 감지
                if (OnTheWayService.instance?.tryVoiceAccept(text) == true) {
                    // 수락 처리됨 - 채팅에 보내지 않음
                } else {
                    sendMessage(text)
                }
            }
        )

        statsBtn.setOnClickListener { startActivity(Intent(this, StatsActivity::class.java)) }
        favBtn.setOnClickListener { showPlaceSettings() }
        settingsBtn.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        earningText.setOnClickListener { startActivity(Intent(this, StatsActivity::class.java)) }
        earningText.setOnLongClickListener { startActivity(Intent(this, SettingsActivity::class.java)); true }
        micBtn.setOnClickListener { toggleVoice() }
        sendBtn.setOnClickListener {
            val t = inputField.text.toString().trim()
            if (t.isNotEmpty()) { sendMessage(t); inputField.setText("") }
        }
        acceptBtn.setOnClickListener { if (acceptBtn.tag == "active") startVoiceRecognition() }
        skipBtn.setOnClickListener {
            OnTheWayService.activeSearchSessionId?.let { sid ->
                SearchSessionStore.incrementCallsRejected(this, sid)
            }
            speak("넘기겠습니다"); resetAccept()
        }
        svcBtn.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }

        // ── 탭 전환 ──
        tabStatus.setOnClickListener { switchTab("status") }
        tabChat.setOnClickListener { switchTab("chat") }
        switchTab("status")  // 기본값: 상태 탭

        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 200)
        }

        todayEarning = EarningManager.getTodayEarning(this)
        todayGoalAmt = EarningManager.getGoal(this)
        EarningManager.markStartTime(this)
        updateEarningDisplay()
        refreshPlacesRow()
        resultCard.visibility = View.GONE
        acceptBtn.visibility  = View.INVISIBLE
        addAgentMessage("어디로 가세요?")

        // ── 접근성 서비스 / 알림 리스너 경고 배너 (v2 2.0) ──
        checkServiceStatus()

        // v3.4: GPS 권한 요청
        if (AdvancedPrefs.isGpsEnabled(this) &&
            checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION), 300)
        }

        val searchSession = SearchSessionStore.ensureActiveSession(this)
        OnTheWayService.activeSearchSessionId = searchSession.sessionId

        OnTheWayService.resultCallback = { from, to, amount, reason ->
            runOnUiThread { showResult(from, to, amount, reason) }
        }

        // 필터 상태 5초마다 갱신
        updateFilterStatus()
        val filterRefresh = object : Runnable {
            override fun run() {
                updateFilterStatus()
                mainHandler.postDelayed(this, 5000)
            }
        }
        mainHandler.postDelayed(filterRefresh, 5000)
    }

    private fun checkServiceStatus() {
        val warnings = mutableListOf<String>()

        // 접근성 서비스 체크
        val accessibilityEnabled = try {
            val enabledServices = Settings.Secure.getString(
                contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            enabledServices.contains("com.vita.ontheway")
        } catch (e: Exception) { false }

        if (!accessibilityEnabled) {
            warnings.add("접근성 서비스가 꺼져 있습니다")
        }

        // 알림 리스너 체크
        val notifEnabled = try {
            val enabledListeners = Settings.Secure.getString(
                contentResolver, "enabled_notification_listeners"
            ) ?: ""
            enabledListeners.contains("com.vita.ontheway")
        } catch (e: Exception) { false }

        if (!notifEnabled) {
            warnings.add("알림 접근 권한이 꺼져 있습니다")
        }

        if (warnings.isNotEmpty()) {
            val banner = TextView(this).apply {
                text = "⚠ ${warnings.joinToString(" · ")} — 터치하여 설정"
                textSize = 13f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#E53935"))
                setPadding(dp(16), dp(10), dp(16), dp(10))
                gravity = Gravity.CENTER
                setOnClickListener {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }
            val root = findViewById<ViewGroup>(android.R.id.content)
            root.addView(banner, 0, ViewGroup.LayoutParams(MP, WC))
        }
    }

    private fun switchTab(tab: String) {
        currentTab = tab
        val isStatus = tab == "status"

        // 탭 스타일
        tabStatus.setTextColor(if (isStatus) Color.parseColor("#5B6ABF") else Color.parseColor("#999999"))
        tabStatus.setTypeface(null, if (isStatus) Typeface.BOLD else Typeface.NORMAL)
        tabChat.setTextColor(if (!isStatus) Color.parseColor("#5B6ABF") else Color.parseColor("#999999"))
        tabChat.setTypeface(null, if (!isStatus) Typeface.BOLD else Typeface.NORMAL)

        // 인디케이터 위치 (왼쪽 절반 / 오른쪽 절반)
        tabIndicator.post {
            val parent = tabIndicator.parent as? FrameLayout ?: return@post
            val totalWidth = parent.width
            val halfWidth = totalWidth / 2
            val lp = tabIndicator.layoutParams as FrameLayout.LayoutParams
            lp.width = halfWidth
            lp.marginStart = if (isStatus) 0 else halfWidth
            tabIndicator.layoutParams = lp
        }

        // 패널 전환
        statusPanel.visibility = if (isStatus) View.VISIBLE else View.GONE
        chatPanel.visibility = if (!isStatus) View.VISIBLE else View.GONE

        // 입력바: 상태탭에서는 숨김
        val inputBarView = findViewById<LinearLayout>(R.id.inputBar)
        inputBarView?.visibility = if (isStatus) View.GONE else View.VISIBLE

        if (isStatus) refreshDashboard()
    }

    private fun updateFilterStatus() {
        val lastDetect = OnTheWayService.instance?.lastCallDetectedTime ?: 0
        val ago = if (lastDetect > 0) {
            val sec = (System.currentTimeMillis() - lastDetect) / 1000
            when {
                sec < 60 -> "${sec}초 전"
                sec < 3600 -> "${sec / 60}분 전"
                else -> "${sec / 3600}시간 전"
            }
        } else null

        var statusMsg = if (ago != null) "필터 작동 중 · 마지막 감지 $ago" else "필터 대기 중"

        // v3.4: GPS 위치 표시
        if (AdvancedPrefs.isGpsEnabled(this) && OnTheWayService.gpsActive && OnTheWayService.currentLat != 0.0) {
            val nearArea = LocationTable.getNearestArea(OnTheWayService.currentLat, OnTheWayService.currentLng)
            if (nearArea != null) statusMsg += " · 위치: ${nearArea} 부근"
        }

        // v3.3: 피크 상태 표시
        val peakText = PeakDetector.getStatusText(this)
        if (peakText.isNotEmpty()) statusMsg += " · $peakText"

        // v3.3: 연속 넘김 카운터
        val rejectCount = OnTheWayService.instance?.consecutiveRejectCount ?: 0
        if (rejectCount >= 3) statusMsg += " · 연속 넘김: ${rejectCount}건"

        filterStatusText.text = statusMsg

        val detail = FilterLog.getTodayDetail(this)
        if (detail.total > 0) {
            filterCountText.text = "오늘 ${detail.total}건 (넘기세요 ${detail.reject} · 괜찮습니다/잡으세요 ${detail.accept})"
        } else {
            filterCountText.text = ""
        }

        // 마지막 콜 정보
        val recent = FilterLog.getRecent(this, 1)
        if (recent.isNotEmpty()) {
            val e = recent[0]
            val platform = when (e.optString("platform")) {
                "coupang" -> "쿠팡"; "baemin" -> "배민"; "kakaot" -> "카카오"; else -> e.optString("platform")
            }
            val price = e.optInt("price", 0)
            val verdict = e.optString("verdict", "")
            val verdictKr = when (verdict) {
                "REJECT" -> "넘기세요"; "ACCEPT" -> {
                    val up = e.optInt("unitPrice", 0)
                    val dist = e.optDouble("distanceKm", -1.0)
                    val pt = e.optDouble("point", -1.0)
                    if (price >= 10000 || (price >= 7000 && ((dist in 0.0..3.0) || (pt in 0.0..15.0))) || (up >= 2500 && dist in 0.0..3.0)) "잡으세요" else "괜찮습니다"
                }; else -> verdict
            }
            lastCallText.text = "$platform ${fmt(price)}원 $verdictKr"
        }

        if (currentTab == "status") refreshDashboard()
    }

    // v3.2: 필터 상태
    private var dashFilterPlatform = "전체"
    private var dashFilterVerdict = "전체"

    private fun refreshDashboard() {
        recentCallList.removeAllViews()

        // v3.2: 플랫폼 필터 탭
        val platRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(6), dp(16), dp(2))
        }
        listOf("전체", "배민", "쿠팡", "카카오T").forEach { label ->
            platRow.addView(TextView(this).apply {
                text = label; textSize = 11f; gravity = Gravity.CENTER
                val sel = label == dashFilterPlatform
                setTextColor(if (sel) Color.WHITE else Color.parseColor("#5B6ABF"))
                setBackgroundColor(if (sel) Color.parseColor("#5B6ABF") else Color.parseColor("#F0F0F0"))
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setOnClickListener { dashFilterPlatform = label; refreshDashboard() }
            }, lp(0, WC, 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
        }
        recentCallList.addView(platRow)

        // v3.2: 판정 필터 탭
        val verdRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(2), dp(16), dp(6))
        }
        listOf("전체", "잡으세요", "괜찮습니다", "넘기세요").forEach { label ->
            verdRow.addView(TextView(this).apply {
                text = label; textSize = 11f; gravity = Gravity.CENTER
                val sel = label == dashFilterVerdict
                setTextColor(if (sel) Color.WHITE else Color.parseColor("#666666"))
                setBackgroundColor(if (sel) Color.parseColor("#666666") else Color.parseColor("#F0F0F0"))
                setPadding(dp(8), dp(6), dp(8), dp(6))
                setOnClickListener { dashFilterVerdict = label; refreshDashboard() }
            }, lp(0, WC, 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
        }
        recentCallList.addView(verdRow)

        var logs = FilterLog.getRecent(this, 30)

        // v3.2: 플랫폼 필터
        if (dashFilterPlatform != "전체") {
            val platKey = when (dashFilterPlatform) {
                "배민" -> "baemin"; "쿠팡" -> "coupang"; "카카오T" -> "kakaot"; else -> ""
            }
            logs = logs.filter { it.optString("platform") == platKey }
        }

        // v3.2: 판정 필터
        if (dashFilterVerdict != "전체") {
            logs = logs.filter { entry ->
                val v = getVerdictKr(entry)
                v == dashFilterVerdict
            }
        }

        logs = logs.take(15)

        if (logs.isEmpty()) {
            recentCallList.addView(TextView(this).apply {
                text = "기록 없음"
                textSize = 13f; setTextColor(Color.parseColor("#999999"))
                setPadding(dp(20), dp(12), dp(20), dp(12))
            })
            return
        }

        val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        for (entry in logs) {
            val ts = sdf.format(java.util.Date(entry.getLong("ts")))
            val platform = when (entry.optString("platform")) {
                "coupang" -> "쿠팡"; "baemin" -> "배민"; "kakaot" -> "카카오"; else -> "?"
            }
            val price = entry.optInt("price", 0)
            val isMulti = entry.optBoolean("multi", false)

            val verdictKr = getVerdictKr(entry)
            val verdictColor: Int
            val bgColor: Int
            when (verdictKr) {
                "잡으세요" -> {
                    verdictColor = Color.parseColor("#1976D2")
                    bgColor = Color.parseColor("#E3F2FD")  // 연한 파란색
                }
                "괜찮습니다" -> {
                    verdictColor = Color.parseColor("#388E3C")
                    bgColor = Color.parseColor("#E8F5E9")  // 연한 초록색
                }
                "넘기세요" -> {
                    verdictColor = Color.parseColor("#E53935")
                    bgColor = Color.parseColor("#FFEBEE")  // 연한 빨간색
                }
                else -> {
                    verdictColor = Color.parseColor("#999999")
                    bgColor = Color.WHITE
                }
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(bgColor)
                setPadding(dp(if (isMulti) 24 else 20), dp(8), dp(20), dp(8))
            }

            // 묶음배달: 왼쪽 보라색 세로 바
            if (isMulti) {
                row.addView(View(this).apply {
                    setBackgroundColor(Color.parseColor("#7B1FA2"))
                }, lp(dp(3), dp(28)).apply { marginEnd = dp(6) })
            }

            row.addView(TextView(this).apply {
                text = ts; textSize = 12f; setTextColor(Color.parseColor("#999999"))
            }, lp(WC, WC).apply { marginEnd = dp(10) })
            row.addView(TextView(this).apply {
                text = platform; textSize = 12f; setTextColor(Color.parseColor("#5B6ABF"))
                setTypeface(null, Typeface.BOLD)
            }, lp(WC, WC).apply { marginEnd = dp(10) })
            row.addView(TextView(this).apply {
                text = "${fmt(price)}원"; textSize = 13f; setTextColor(Color.BLACK)
            }, lp(0, WC, 1f))
            row.addView(TextView(this).apply {
                text = verdictKr; textSize = 13f; setTextColor(verdictColor)
                setTypeface(null, Typeface.BOLD)
            })

            row.isClickable = true
            row.isFocusable = true
            row.setOnClickListener { showCallDetail(entry) }

            recentCallList.addView(row)

            recentCallList.addView(View(this).apply {
                setBackgroundColor(Color.parseColor("#F0F0F0"))
            }, lp(MP, dp(1)).apply { setMargins(dp(20), 0, dp(20), 0) })
        }
    }

    private fun getVerdictKr(entry: org.json.JSONObject): String {
        val verdict = entry.optString("verdict", "")
        if (verdict == "REJECT") return "넘기세요"
        if (verdict == "ACCEPTED") return "수락됨"
        val price = entry.optInt("price", 0)
        val unitPrice = entry.optInt("unitPrice", 0)
        val dist = entry.optDouble("distanceKm", -1.0)
        val pt = entry.optDouble("point", -1.0)
        val isGrab = price >= 10000 ||
            (price >= 7000 && ((dist in 0.0..3.0) || (pt in 0.0..15.0))) ||
            (unitPrice >= 2500 && dist in 0.0..3.0)
        return if (isGrab) "잡으세요" else "괜찮습니다"
    }

    private fun showCallDetail(entry: org.json.JSONObject) {
        val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        val nf = java.text.NumberFormat.getNumberInstance()
        val ts = sdf.format(java.util.Date(entry.getLong("ts")))
        val platform = when (entry.optString("platform")) {
            "coupang" -> "쿠팡이츠"; "baemin" -> "배민커넥트"; "kakaot" -> "카카오T"; else -> "?"
        }
        val price = entry.optInt("price", 0)
        val dist = entry.optDouble("distanceKm", -1.0)
        val unitPrice = entry.optInt("unitPrice", 0)
        val verdict = entry.optString("verdict", "")
        val reason = entry.optString("reason", "")
        val storeName = entry.optString("storeName", "")
        val destination = entry.optString("destination", "")
        val isMulti = entry.optBoolean("multi", false)

        val pointVal = entry.optDouble("point", -1.0)
        val verdictKr = if (verdict == "REJECT") "넘기세요"
        else if (price >= 10000) "잡으세요"
        else if (price >= 7000 && ((dist in 0.0..3.0) || (pointVal in 0.0..15.0))) "잡으세요"
        else if (unitPrice >= 2500 && dist in 0.0..3.0) "잡으세요"
        else "괜찮습니다"

        val point = entry.optDouble("point", -1.0)
        val bundleCount = entry.optInt("bundleCount", 0)
        val isMultiPickup = entry.optBoolean("multiPickup", false)

        val sb = StringBuilder()
        sb.appendLine("플랫폼: $platform")
        sb.appendLine("금액: ${nf.format(price)}원")
        if (dist >= 0) sb.appendLine("거리: ${"%.1f".format(dist)}km")
        if (unitPrice > 0) sb.appendLine("단가: ${nf.format(unitPrice)}원/km")
        // 포인트/환산거리 표시 (v2 2.0)
        if (point > 0) {
            val pointKm = point * 0.25
            sb.appendLine("포인트: ${"%.1f".format(point)}P (환산 ${"%.1f".format(pointKm)}km)")
            if (pointKm > 0) {
                val pointUnit = (price / pointKm).toInt()
                sb.appendLine("환산단가: ${nf.format(pointUnit)}원/km")
            }
        }
        // v3.4: 픽업 거리 / 총거리 / 총단가
        val pickupKm = entry.optDouble("pickupKm", -1.0)
        if (pickupKm > 0) {
            sb.appendLine("픽업 거리: ${"%.1f".format(pickupKm)}km")
            if (dist > 0) {
                val totalKm = pickupKm + dist
                val totalUnit = (price / totalKm).toInt()
                sb.appendLine("총 거리: ${"%.1f".format(totalKm)}km")
                sb.appendLine("총 단가: ${nf.format(totalUnit)}원/km")
            }
        }
        if (isMulti) {
            val countStr = if (bundleCount > 1) "${bundleCount}건" else "예"
            val pickupStr = if (isMultiPickup) " (다중 픽업)" else ""
            sb.appendLine("묶음배달: $countStr$pickupStr")
        }
        val platformNames = setOf("배민배달", "배민커넥트", "배민", "쿠팡이츠", "쿠팡", "카카오T")
        if (storeName.isNotEmpty() && storeName !in platformNames) sb.appendLine("가게: $storeName")
        if (destination.isNotEmpty() && !destination.contains("검색하기")) sb.appendLine("목적지: $destination")
        sb.appendLine()
        sb.appendLine("판정: $verdictKr")
        sb.appendLine("사유: $reason")
        sb.appendLine()
        sb.appendLine("감지 시각: $ts")

        val dlg = AlertDialog.Builder(this)
            .setTitle("콜 상세 정보")
            .setMessage(sb.toString())
            .setPositiveButton("확인", null)

        // v3.3: 즐겨찾기/블랙리스트 버튼 (가게명이 있을 때만)
        if (storeName.isNotEmpty()) {
            val platformNames = setOf("배민배달", "배민커넥트", "배민", "쿠팡이츠", "쿠팡", "카카오T")
            if (storeName !in platformNames) {
                if (StoreManager.isFavorite(this, storeName)) {
                    dlg.setNeutralButton("즐겨찾기 해제") { _, _ ->
                        StoreManager.removeFavorite(this, storeName)
                        android.widget.Toast.makeText(this, "$storeName 즐겨찾기 해제", android.widget.Toast.LENGTH_SHORT).show()
                    }
                } else if (StoreManager.isBlacklisted(this, storeName)) {
                    dlg.setNeutralButton("블랙리스트 해제") { _, _ ->
                        StoreManager.removeBlacklist(this, storeName)
                        android.widget.Toast.makeText(this, "$storeName 블랙리스트 해제", android.widget.Toast.LENGTH_SHORT).show()
                    }
                } else {
                    dlg.setNeutralButton("즐겨찾기") { _, _ ->
                        StoreManager.addFavorite(this, storeName, entry.optString("platform", ""))
                        android.widget.Toast.makeText(this, "$storeName 즐겨찾기 추가", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    dlg.setNegativeButton("블랙리스트") { _, _ ->
                        StoreManager.addBlacklist(this, storeName, entry.optString("platform", ""))
                        android.widget.Toast.makeText(this, "$storeName 블랙리스트 추가", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        dlg.show()
    }

    // ═══ 수익 (콤팩트 1줄 헤더 + 서브라인) ═══
    private fun updateEarningDisplay() {
        // v3.0: 수익 트래킹 활성화 시 실시간 매출 표시
        if (AdvancedPrefs.isEarningsTrackingEnabled(this)) {
            val tracked = EarningsTracker.getToday(this)
            if (tracked.acceptedCount > 0) {
                earningText.text = "${fmt(tracked.totalRevenue)}원"
                val hourlyStr = if (tracked.hourlyRate > 0) "${fmt(tracked.hourlyRate)}원/h" else "0원/h"
                earningMeta.text = "${tracked.acceptedCount}콜 · $hourlyStr · 목표 ${fmt(todayGoalAmt)}원"
                todayEarning = tracked.totalRevenue

                val progress = if (todayGoalAmt > 0) {
                    (todayEarning.toFloat() / todayGoalAmt).coerceIn(0f, 1f)
                } else 0f
                progressFill.post {
                    val parent = progressFill.parent as? FrameLayout ?: return@post
                    val totalWidth = parent.width
                    val fillWidth = (totalWidth * progress).toInt()
                    progressFill.layoutParams = progressFill.layoutParams.apply { width = fillWidth }
                }
                return
            }
        }

        val callCount = EarningManager.getTodayCallCount(this)
        val pace = EarningManager.getEarningPace(this)
        val paceStr = if (pace > 0) "${fmt(pace)}원/h" else "0원/h"

        earningText.text = "${fmt(todayEarning)}원"
        earningMeta.text = "${callCount}콜 · $paceStr · 목표 ${fmt(todayGoalAmt)}원"

        val progress = if (todayGoalAmt > 0) {
            (todayEarning.toFloat() / todayGoalAmt).coerceIn(0f, 1f)
        } else 0f

        progressFill.post {
            val parent = progressFill.parent as? FrameLayout ?: return@post
            val totalWidth = parent.width
            val fillWidth = (totalWidth * progress).toInt()
            progressFill.layoutParams = progressFill.layoutParams.apply { width = fillWidth }
        }
    }

    private fun showGoalSetting() {
        val dlgView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C_CARD)
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        dlgView.addView(TextView(this).apply {
            text = "목표 수익 설정  |  " + ShadowLog.getTodayStats(this@MainActivity)
            textSize = 14f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.BLACK); setPadding(0, 0, 0, dp(16))
        })
        val goalInput = EditText(this).apply {
            setText(todayGoalAmt.toString())
            textSize = 18f; setTextColor(Color.BLACK)
            setBackgroundColor(Color.parseColor("#EEEEEE"))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        dlgView.addView(TextView(this).apply { text = "목표 금액"; textSize = 12f; setTextColor(C_SUB); setPadding(0, 0, 0, dp(8)) })
        dlgView.addView(goalInput, lp(MP, WC))
        dlgView.addView(TextView(this).apply { text = "차량 종류"; textSize = 12f; setTextColor(C_SUB); setPadding(0, dp(16), 0, dp(8)) })
        val vehicleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val cur = EarningManager.getVehicleType(this)
        listOf("오토바이", "승용차", "승합차").forEach { v ->
            vehicleRow.addView(TextView(this).apply {
                text = v; textSize = 14f
                setTextColor(if (v == cur) Color.WHITE else C_BLUE)
                setBackgroundColor(if (v == cur) C_BLUE else Color.parseColor("#2A2A2A"))
                setPadding(dp(18), dp(12), dp(18), dp(12))
                setOnClickListener { EarningManager.setVehicleType(this@MainActivity, v) }
            }, lp(WC, WC).apply { marginEnd = dp(8) })
        }
        dlgView.addView(vehicleRow)
        android.app.AlertDialog.Builder(this).setView(dlgView)
            .setPositiveButton("저장") { d, _ ->
                todayGoalAmt = goalInput.text.toString().toIntOrNull() ?: 100000
                EarningManager.setGoal(this, todayGoalAmt)
                updateEarningDisplay(); d.dismiss()
            }
            .setNegativeButton("취소") { d, _ -> d.dismiss() }
            .show().window?.setBackgroundDrawable(ColorDrawable(C_CARD))
    }

    // ═══ 즐겨찾기 ═══
    private fun refreshPlacesRow() {
        placesRow.removeAllViews()
        val places = PlaceManager.getPlaces(this)
        if (places.isEmpty()) {
            placesRow.addView(TextView(this).apply {
                text = "+ 즐겨찾기 추가"
                textSize = 13f; setTextColor(C_SUB)
                setPadding(dp(4), dp(8), dp(4), dp(8))
            }); return
        }
        places.forEach { place ->
            placesRow.addView(TextView(this).apply {
                text = place.name
                textSize = 14f * fontScale; setTextColor(Color.parseColor("#333333"))
                setBackgroundResource(R.drawable.place_chip_bg)
                setPadding(dp(16), dp(9), dp(16), dp(9))
                gravity = Gravity.CENTER
                setOnClickListener {
                    when (aiContext) {
                        AiContext.ORIGIN -> { addRecentSearch(place.address); sendMessage("출발지 ${place.address}") }
                        else -> { addRecentSearch(place.address); sendMessage("목적지 ${place.address}") }
                    }
                }
            }, lp(WC, WC).apply { marginEnd = dp(6) })
        }
    }

    // 최근 검색 기록 저장
    private val PREF_RECENT = "recent_destinations"
    private val MAX_RECENT = 10

    private fun addRecentSearch(text: String) {
        if (text.isBlank()) return
        val prefs = getSharedPreferences("ontheway_prefs", MODE_PRIVATE)
        // ★ HashSet 복사 — Android SharedPreferences 참조 버그 방지
        val existing = HashSet(prefs.getStringSet(PREF_RECENT, HashSet<String>()) ?: HashSet())
        existing.add(text)
        val trimmed = if (existing.size > MAX_RECENT) {
            HashSet(existing.toList().takeLast(MAX_RECENT))
        } else existing
        prefs.edit().remove(PREF_RECENT).apply()  // 먼저 삭제
        prefs.edit().putStringSet(PREF_RECENT, trimmed).apply()
    }

    private fun getRecentSearches(): List<String> {
        val prefs = getSharedPreferences("ontheway_prefs", MODE_PRIVATE)
        return (prefs.getStringSet(PREF_RECENT, emptySet()) ?: emptySet()).toList().sorted()
    }

    private var currentDialog: android.app.AlertDialog? = null

    private fun showPlaceSettings() {
        currentDialog?.dismiss()

        val scrollContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }

        // ─── 즐겨찾기 섹션 ───
        scrollContent.addView(TextView(this).apply {
            text = "즐겨찾기"
            textSize = 16f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.BLACK); setPadding(0, 0, 0, dp(10))
        })

        val places = PlaceManager.getPlaces(this)
        if (places.isEmpty()) {
            scrollContent.addView(TextView(this).apply {
                text = "즐겨찾기가 없습니다"
                textSize = 13f; setTextColor(C_SUB); setPadding(0, dp(8), 0, dp(12))
            })
        } else {
            places.forEach { place ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setBackgroundColor(Color.parseColor("#F5F5F5"))
                    setPadding(dp(16), dp(12), dp(8), dp(12))
                }
                val displayText = if (place.name != place.address) {
                    "${place.name}  ·  ${place.address}"
                } else {
                    place.name
                }
                row.addView(TextView(this).apply {
                    text = displayText
                    textSize = 14f; setTextColor(Color.parseColor("#333333")); layoutParams = lp(0, WC, 1f)
                })
                row.addView(TextView(this).apply {
                    text = "✕"; textSize = 18f; setTextColor(Color.parseColor("#FF4444"))
                    setPadding(dp(14), dp(8), dp(14), dp(8))
                    setOnClickListener {
                        PlaceManager.removePlace(this@MainActivity, place.name)
                        refreshPlacesRow()
                        // 다이얼로그 다시 열어서 갱신
                        showPlaceSettings()
                    }
                })
                scrollContent.addView(row, lp(MP, WC).apply { setMargins(0, 0, 0, dp(4)) })
            }
        }

        // ─── 추가 입력 ───
        val nameInput = EditText(this).apply {
            hint = "이름 (집, 사무실)"; textSize = 14f
            setTextColor(Color.BLACK); setHintTextColor(C_DIM)
            setBackgroundColor(Color.parseColor("#F5F5F5"))
            setPadding(dp(16), dp(12), dp(16), dp(12)); setSingleLine(true)
        }
        val addrInput = EditText(this).apply {
            hint = "주소 / 지역명"; textSize = 14f
            setTextColor(Color.BLACK); setHintTextColor(C_DIM)
            setBackgroundColor(Color.parseColor("#F5F5F5"))
            setPadding(dp(16), dp(12), dp(16), dp(12)); setSingleLine(true)
        }
        scrollContent.addView(View(this), lp(MP, dp(12)))
        scrollContent.addView(nameInput, lp(MP, WC).apply { setMargins(0, 0, 0, dp(4)) })
        scrollContent.addView(addrInput, lp(MP, WC))
        scrollContent.addView(TextView(this).apply {
            text = "+ 즐겨찾기 추가"; textSize = 14f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.BLACK); gravity = Gravity.CENTER
            setBackgroundColor(C_BLUE)
            setPadding(0, dp(14), 0, dp(14))
            setOnClickListener {
                val n = nameInput.text.toString().trim(); val a = addrInput.text.toString().trim()
                if (n.isNotEmpty() && a.isNotEmpty()) {
                    PlaceManager.savePlace(this@MainActivity, n, a)
                    refreshPlacesRow()
                    showPlaceSettings()  // 갱신
                }
            }
        }, lp(MP, WC).apply { setMargins(0, dp(10), 0, 0) })

        // ─── 최근 검색 기록 섹션 ───
        val recentList = getRecentSearches()
        if (recentList.isNotEmpty()) {
            scrollContent.addView(View(this), lp(MP, dp(16)))
            scrollContent.addView(TextView(this).apply {
                text = "최근 검색"
                textSize = 16f; setTypeface(null, Typeface.BOLD)
                setTextColor(Color.BLACK); setPadding(0, 0, 0, dp(10))
            })
            recentList.forEach { recent ->
                val alreadySaved = places.any { it.address == recent || it.name == recent }
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setBackgroundColor(Color.parseColor("#F5F5F5"))
                    setPadding(dp(16), dp(12), dp(8), dp(12))
                }
                row.addView(TextView(this).apply {
                    text = recent
                    textSize = 14f; setTextColor(C_BRIGHT); layoutParams = lp(0, WC, 1f)
                })
                if (!alreadySaved) {
                    row.addView(TextView(this).apply {
                        text = "+ 추가"; textSize = 12f; setTextColor(C_BLUE)
                        setPadding(dp(10), dp(6), dp(10), dp(6))
                        setOnClickListener {
                            PlaceManager.savePlace(this@MainActivity, recent, recent)
                            refreshPlacesRow()
                            showPlaceSettings()
                        }
                    })
                } else {
                    row.addView(TextView(this).apply {
                        text = "저장됨"; textSize = 12f; setTextColor(C_SUB)
                        setPadding(dp(10), dp(6), dp(10), dp(6))
                    })
                }
                scrollContent.addView(row, lp(MP, WC).apply { setMargins(0, 0, 0, dp(4)) })
            }
        }

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(C_CARD)
            addView(scrollContent)
        }

        currentDialog = android.app.AlertDialog.Builder(this).setView(scrollView)
            .setPositiveButton("닫기") { d, _ -> d.dismiss(); refreshPlacesRow() }
            .show()
        currentDialog?.window?.setBackgroundDrawable(ColorDrawable(C_CARD))
    }

    private fun toggleVoice() {
        voiceManager.continuous = !voiceManager.continuous
        if (voiceManager.continuous) {
            micBtn.setBackgroundResource(R.drawable.mic_active_bg)
            micBtn.text = "\uD83D\uDD34"
            voiceManager.isSpeaking = false
            voiceManager.start()
        } else {
            micBtn.setBackgroundResource(R.drawable.mic_bg)
            micBtn.text = "\uD83C\uDFA4"
            voiceManager.stop(); clearPartialBubble()
        }
    }

    private fun showPartialBubble(text: String) {
        if (partialBubble == null) {
            val wrapper = LinearLayout(this).apply { gravity = Gravity.END; tag = "partial" }
            partialBubble = TextView(this).apply {
                textSize = 14f; setTextColor(Color.parseColor("#999999"))
                setBackgroundResource(R.drawable.user_bubble_bg)
                alpha = 0.5f
                setPadding(dp(14), dp(10), dp(14), dp(10))
            }
            wrapper.addView(partialBubble!!, lp(WC, WC).apply { setMargins(dp(80), dp(2), 0, dp(2)) })
            chatLayout.addView(wrapper)
        }
        partialBubble?.text = "🎤 $text"
        scrollToBottom()
    }

    private fun clearPartialBubble() {
        chatLayout.findViewWithTag<View>("partial")?.let { chatLayout.removeView(it) }
        partialBubble = null
    }

    private fun sendMessage(text: String) {
        val trimmed = text.trim()

        if (trimmed.count { it.isLetterOrDigit() } < 2) {
            addAgentMessage("다시 말씀해 주세요")
            return
        }

        if (trimmed in NOW_ALIASES) {
            addUserMessage(trimmed)
            messages.add(Pair("user", "지금 바로"))
            OnTheWayService.departureTime = "바로"
            addAgentMessage("출발: 지금 바로 ✔")
            speak("지금 바로 출발이시군요")
            messages.add(Pair("assistant", "출발: 지금 바로 ✔"))
            aiContext = AiContext.NONE
            removeTimeButtons()
            addAgentMessage("콜 추천 시작합니다")
            return
        }

        if (trimmed in DEPARTURE_ALIASES) {
            addUserMessage(trimmed)
            OnTheWayService.departureTime = trimmed
            addAgentMessage("출발: $trimmed ✔")
            speak("${trimmed} 출발이시군요")
            aiContext = AiContext.NONE
            removeTimeButtons()
            addAgentMessage("콜 추천 시작합니다")
            return
        }

        if (trimmed in CANCEL_ALIASES) {
            addUserMessage(trimmed)
            OnTheWayService.currentDir = ""
            OnTheWayService.currentDest = ""
            OnTheWayService.departureTime = ""
            messages.clear()
            addAgentMessage("초기화 완료. 어디로 가세요?")
            speak("초기화했습니다")
            messages.add(Pair("user", trimmed))
            messages.add(Pair("assistant", "초기화 완료. 어디로 가세요?"))
            return
        }

        if (trimmed in HOME_ALIASES) {
            val home = PlaceManager.getPlaces(this).firstOrNull { it.name == "집" }
            if (home != null) {
                addUserMessage(trimmed)
                OnTheWayService.currentDir = home.address
                OnTheWayService.currentDest = home.address
                addAgentMessage("목적지: ${home.address} (집) ✔")
                speak("집으로 가시는군요")
                messages.add(Pair("user", trimmed))
                messages.add(Pair("assistant", "목적지: ${home.address} (집) ✔"))
                return
            }
        }

        if (trimmed in STATS_ALIASES) {
            addUserMessage(trimmed)
            val pace = EarningManager.getEarningPace(this)
            val paceStr = if (pace > 0) "${fmt(pace)}원/h" else "측정 중"
            val msg = "오늘 ${fmt(todayEarning)}원 ($paceStr)\n목표 ${fmt(todayGoalAmt)}원"
            addAgentMessage(msg)
            speak("오늘 ${fmt(todayEarning)}원 벌었습니다")
            messages.add(Pair("user", trimmed))
            messages.add(Pair("assistant", msg))
            return
        }

        if (trimmed in HELP_ALIASES) {
            addUserMessage(trimmed)
            val msg = "• 목적지 말하기 (강남, 서초)\n• 지금 바로 → 즉시 출발\n• 집으로 → 귀가\n• 수익 → 오늘 수익\n• 취소 → 초기화"
            addAgentMessage(msg)
            speak("도움말 표시했습니다")
            messages.add(Pair("user", trimmed))
            messages.add(Pair("assistant", msg))
            return
        }

        addUserMessage(text)
        // ★ 목적지/출발지 입력 시 최근 검색에 저장
        val destMatch = Regex("목적지\\s*(.+)").find(trimmed)
        val originMatch = Regex("출발지\\s*(.+)").find(trimmed)
        destMatch?.groupValues?.get(1)?.trim()?.let { addRecentSearch(it) }
        originMatch?.groupValues?.get(1)?.trim()?.let { addRecentSearch(it) }
        // 일반 지역명도 저장 (2글자 이상 한글)
        if (destMatch == null && originMatch == null && trimmed.length >= 2) {
            addRecentSearch(trimmed)
        }
        CoroutineScope(Dispatchers.Main).launch {
            val savedPlaces = PlaceManager.getPlaces(this@MainActivity)
            val placesStr = savedPlaces.map { "${it.name}=${it.address}" }
            // ★ 컨텍스트에 따라 접두어 자동 추가 (AI 오인식 방지)
            val aiText = when {
                aiContext == AiContext.DESTINATION
                    && !trimmed.startsWith("목적지") && !trimmed.startsWith("출발지")
                    && !trimmed.contains("에서") && !trimmed.contains("으로")
                    && !trimmed.contains("방향") -> "목적지 $text"
                aiContext == AiContext.ORIGIN
                    && !trimmed.startsWith("출발지") && !trimmed.startsWith("목적지")
                    -> "출발지 $text"
                else -> text
            }
            val (response, state) = CallAgent.chat(messages, aiText, placesStr, false)
            messages.add(Pair("user", text))
            messages.add(Pair("assistant", response))
            addAgentMessage(response)
            detectAiContext(response)
            speak(response)
            state?.let {
                OnTheWayService.currentDir = it.destination
                OnTheWayService.currentDest = it.destination
                voiceManager.continuous = false; voiceManager.stop()
                runOnUiThread {
                    micBtn.setBackgroundResource(R.drawable.mic_bg)
                    micBtn.text = "\uD83C\uDFA4"
                }
                if (it.destination.isNotEmpty()) {
                    addRecentSearch(it.destination)
                    refreshPlacesRow()
                }
            }
        }
    }

    // ═══ 채팅 버블 (v4 — 밝은 에이전트, 운전 가독성) ═══
    private fun addUserMessage(text: String) {
        val wrapper = LinearLayout(this).apply { gravity = Gravity.END }
        wrapper.addView(TextView(this).apply {
            this.text = text; textSize = 15f * fontScale; setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.user_bubble_bg)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }, lp(WC, WC).apply { setMargins(dp(80), dp(3), 0, dp(3)) })
        chatLayout.addView(wrapper); scrollToBottom()
    }

    private fun addAgentMessage(text: String) {
        val wrapper = LinearLayout(this).apply { gravity = Gravity.START }
        wrapper.addView(TextView(this).apply {
            this.text = text; textSize = 15f * fontScale; setTextColor(Color.parseColor("#222222"))
            setBackgroundResource(R.drawable.agent_bubble_bg)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setLineSpacing(0f, 1.3f)
        }, lp(WC, WC).apply { setMargins(0, dp(3), dp(80), dp(3)) })
        chatLayout.addView(wrapper); scrollToBottom()
    }

    private fun scrollToBottom() { scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) } }

    private fun detectAiContext(response: String) {
        val r = response.lowercase()
        aiContext = when {
            // ★ ORIGIN을 먼저 체크 — "목적지 확인 + 어디에 계세요?" 응답에서 다음 질문이 중요
            r.contains("어디서") || r.contains("출발지") || r.contains("현재 위치") || r.contains("어디에") || r.contains("어디 계") || r.contains("계세요") || r.contains("어디있") -> AiContext.ORIGIN
            r.contains("언제") || r.contains("출발 시간") || r.contains("몇 시") || r.contains("시간") -> AiContext.DEPARTURE
            r.contains("어디로") || r.contains("목적지") || r.contains("방향") -> AiContext.DESTINATION
            else -> aiContext
        }
        refreshPlacesRow()
        removeTimeButtons()
        if (aiContext == AiContext.DEPARTURE) showTimeButtons()
    }

    private fun showTimeButtons() {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            tag = "time_buttons"
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(4))
        }
        data class TimeOption(val label: String, val value: String)
        val options = listOf(
            TimeOption("지금 바로", "바로"),
            TimeOption("30분 뒤", "30분 뒤"),
            TimeOption("1시간 뒤", "1시간 뒤"),
            TimeOption("오늘 저녁", "오늘 저녁")
        )
        options.forEach { opt ->
            row.addView(TextView(this).apply {
                text = opt.label; textSize = 13f; setTextColor(Color.parseColor("#5B6ABF"))
                setBackgroundResource(R.drawable.place_chip_bg)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                gravity = Gravity.CENTER
                setOnClickListener {
                    removeTimeButtons()
                    sendMessage(opt.value)
                }
            }, lp(WC, WC).apply { marginEnd = dp(6) })
        }
        chatLayout.addView(row)
        scrollToBottom()
    }

    private fun removeTimeButtons() {
        chatLayout.findViewWithTag<View>("time_buttons")?.let { chatLayout.removeView(it) }
    }

    // ═══ 콜 결과 카드 ═══
    private fun showResult(from: String, to: String, amount: Int, reason: String) {
        resultRoute.text = "$from  →  $to"
        resultAmount.text = "${fmt(amount)}원"

        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val hotspotHint = when {
            hour in 11..13 -> "점심 피크"
            hour in 17..19 -> "저녁 피크"
            hour in 22..23 || hour in 0..5 -> "심야"
            else -> ""
        }
        resultTag.text = if (hotspotHint.isNotEmpty()) "$reason · $hotspotHint" else reason

        detailBtn.visibility = View.VISIBLE
        detailBtn.setOnClickListener { showCallDetailSheet() }

        resultCard.visibility = View.VISIBLE
        acceptBtn.visibility  = View.VISIBLE

        acceptBtn.setTextColor(Color.BLACK)
        acceptBtn.setBackgroundResource(R.drawable.accept_btn_bg)
        acceptBtn.tag = "active"

        startDecisionTimer()
        speak("${fmt(amount)}원 잡기 좋습니다")
    }

    private fun showCallDetailSheet() {
        OnTheWayService.lastCallData?.let { call ->
            val detail = CallDetail(
                callId = "${call.from}_${call.to}_${call.amount}",
                rank = 1, score = 0,
                pickupAddress = call.from,
                pickupDistanceKm = call.pickupKm,
                dropoffAddress = call.to,
                dropoffDistanceKm = call.deliveryKm,
                deliveryDeadline = call.deliveryDeadline ?: "",
                itemSize = call.itemSize,
                notice = call.notice ?: "",
                price = call.amount,
                isReservation = call.isReservation,
                reservationTime = call.reservationTime ?: "",
                vehicleType = call.vehicleType ?: "",
                callType = call.callType,
                aiReason = OnTheWayService.lastReason
            )
            CallDetailSheet.show(this, detail,
                onExecute = { if (acceptBtn.tag == "active") startVoiceRecognition() },
                onSkip = {
                    OnTheWayService.activeSearchSessionId?.let { sid ->
                        SearchSessionStore.incrementCallsRejected(this, sid)
                    }
                    speak("넘기겠습니다"); resetAccept()
                }
            )
        }
    }

    private fun startDecisionTimer() {
        decisionTimer?.let { mainHandler.removeCallbacks(it) }
        timerSeconds = 15
        acceptBtn.text = "수락  ${timerSeconds}s"
        val tick = object : Runnable {
            override fun run() {
                timerSeconds--
                if (timerSeconds > 0) {
                    acceptBtn.text = "수락  ${timerSeconds}s"
                    mainHandler.postDelayed(this, 1000)
                } else {
                    acceptBtn.text = "시간 초과"
                    acceptBtn.setTextColor(Color.parseColor("#999999"))
                    acceptBtn.setBackgroundResource(R.drawable.accept_inactive_bg)
                    acceptBtn.tag = "inactive"
                    OnTheWayService.activeSearchSessionId?.let { sid ->
                        SearchSessionStore.incrementCallsTimeout(this@MainActivity, sid)
                    }
                }
            }
        }
        decisionTimer = tick
        mainHandler.postDelayed(tick, 1000)
    }

    private fun stopDecisionTimer() {
        decisionTimer?.let { mainHandler.removeCallbacks(it) }
        decisionTimer = null
    }

    private fun doAccept() {
        stopDecisionTimer()
        val amount = resultAmount.text.toString().replace(",","").replace("원","").trim().toIntOrNull() ?: 0
        if (amount > 0) {
            todayEarning = EarningManager.addEarning(this, amount)
            updateEarningDisplay()
            updateShadowAction("accept", amount)

            val recentSessions = SessionStore.loadRecent(this, 1)
            recentSessions.firstOrNull()?.let { session ->
                val result = ResultEvaluator.evaluate(amount, session.expectedWon)
                SessionStore.updateEarned(this, session.sessionId, amount, result)
            }

            OnTheWayService.activeSearchSessionId?.let { sid ->
                SearchSessionStore.complete(this, sid, todayEarning)
                val ss = SearchSessionStore.loadAll(this).lastOrNull { it.sessionId == sid }
                if (ss != null) {
                    ContextManager.updateAgent("ontheway", mapOf(
                        "sessionId" to sid,
                        "status" to ss.status.value,
                        "acceptedCallPrice" to (ss.acceptedCallPrice ?: 0),
                        "minutesToAccept" to (ss.minutesToAccept ?: 0),
                        "earnedPerHour" to ss.earnedPerHour
                    ))
                }
            }
        }
        try { OnTheWayService.instance?.acceptCurrentCall() } catch (e: Exception) {}
        speak("수락합니다"); resetAccept()
    }

    private fun resetAccept() {
        stopDecisionTimer()
        acceptBtn.text = "수락하기"
        acceptBtn.setTextColor(Color.parseColor("#999999"))
        acceptBtn.setBackgroundResource(R.drawable.accept_inactive_bg)
        acceptBtn.tag = "inactive"
        acceptBtn.visibility = View.INVISIBLE
        mainHandler.postDelayed({ resultCard.visibility = View.GONE }, 300)
    }

    private fun updateShadowAction(action: String, amount: Int) {
        val ts = OnTheWayService.lastShadowTs
        if (ts.isEmpty()) return
        val entries = ShadowLog.getAll(this).toMutableList()
        val idx = entries.indexOfLast { it.timestamp == ts }
        if (idx >= 0) {
            val entry = entries[idx]
            val selectedId = if (action == "accept") {
                entry.recommended.firstOrNull { it.rank == 1 }?.callId ?: entry.bestCallId
            } else entry.userSelected
            val updated = entry.copy(userSelected = selectedId)
            ShadowLog.clearAll(this)
            entries[idx] = updated
            entries.forEach { ShadowLog.save(this, it) }

            val sessionId = OnTheWayService.activeSearchSessionId ?: ""
            val mobilityEvent = MobilityEventBuilder.fromShadowLog(
                entry     = updated,
                userId    = "on_the_way",
                sessionId = sessionId,
                callId    = selectedId
            )
            android.util.Log.d("MobilityEvent",
                "UPDATE(행동): event=${mobilityEvent.eventId} session=$sessionId action=${mobilityEvent.driverAction} summary=${mobilityEvent.summary} tags=${mobilityEvent.tags}")
        }
    }

    private fun speak(text: String) {
        val clean = text.replace(Regex("[\\p{So}\\p{Cn}]+"), "").trim()
        isSpeaking = true; voiceManager.isSpeaking = true; voiceManager.stop()
        tts.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "utt")
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onDone(id: String?) {
                isSpeaking = false; voiceManager.isSpeaking = false
                if (voiceManager.continuous) mainHandler.postDelayed({ voiceManager.start() }, 500)
            }
            override fun onError(id: String?) { isSpeaking = false; voiceManager.isSpeaking = false }
            override fun onStart(id: String?) {}
        })
    }

    private fun startVoiceRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "잡아 / 넘겨")
        }
        try { startActivityForResult(intent, VOICE_REQUEST) } catch (e: Exception) { doAccept() }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VOICE_REQUEST && resultCode == RESULT_OK) {
            val spoken = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
            if (spoken.contains("잡아") || spoken.contains("수락") || spoken.contains("좋아") || spoken.contains("가자") || spoken.contains("이거")) doAccept()
            else if (spoken.contains("넘겨") || spoken.contains("패스") || spoken.contains("다음") || spoken.contains("아니") || spoken.contains("싫어")) {
                OnTheWayService.activeSearchSessionId?.let { sid ->
                    SearchSessionStore.incrementCallsRejected(this, sid)
                }
                speak("넘기겠습니다"); resetAccept()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        voiceManager.continuous = false; voiceManager.stop()
        micBtn.setBackgroundResource(R.drawable.mic_bg)
        micBtn.text = "\uD83C\uDFA4"
    }

    override fun onResume() {
        super.onResume()
        // 설정에서 변경된 값 반영
        fontScale = FontSizeManager.getScale(this)
        todayGoalAmt = EarningManager.getGoal(this)
        todayEarning = EarningManager.getTodayEarning(this)
        updateEarningDisplay()
    }

    override fun onDestroy() {
        voiceManager.stop(); tts.stop(); tts.shutdown(); super.onDestroy()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.KOREAN
            mainHandler.postDelayed({ speak("어디로 가세요?") }, 500)
        }
    }
}

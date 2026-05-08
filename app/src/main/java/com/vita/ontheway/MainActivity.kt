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
    private lateinit var hourlyRateCard: LinearLayout
    private lateinit var recentHourlyRate: TextView
    private lateinit var cumulativeHourlyRate: TextView
    private lateinit var simulationHourlyCard: LinearLayout
    private lateinit var simRecentHourlyRate: TextView
    private lateinit var simCumulativeHourlyRate: TextView
    private var hourlyRateOffText: TextView? = null
    private var recentHourlyCard: View? = null
    private lateinit var appCheckText: TextView
    private var drivingModeSwitch: Switch? = null
    private var drivingModeStatusTv: TextView? = null
    private var drivingModeDurationTv: TextView? = null
    private var currentTab = "status"  // "status" or "chat"

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

    // ═══ v5.0 컬러 — 딥 다크 터미널 ═══
    private val C_BG       = Color.parseColor("#0A0A0F")  // 배경
    private val C_WHITE    = Color.parseColor("#1A1A2E")  // 카드 배경
    private val C_BRIGHT   = Color.WHITE                  // 본문 텍스트
    private val C_BLUE     = Color.parseColor("#00FF88")  // 초록 액센트
    private val C_BLUE_LT  = Color.parseColor("#00FF88")  // 초록 액센트
    private val C_SUB      = Color.parseColor("#A0A0C0")  // 서브
    private val C_DIM      = Color.parseColor("#A0A0C0")  // 힌트
    private val C_CARD     = Color.parseColor("#1A1A2E")  // 카드/다이얼로그
    private val C_BUBBLE   = Color.parseColor("#1A1A2E")  // 에이전트 버블
    private val C_WARN     = Color.parseColor("#FF4D6D")  // 경고

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val MP = ViewGroup.LayoutParams.MATCH_PARENT
    private val WC = ViewGroup.LayoutParams.WRAP_CONTENT
    private fun lp(w: Int, h: Int, wt: Float = 0f) = LinearLayout.LayoutParams(w, h, wt)
    private fun fmt(n: Int) = String.format("%,d", n)

    private val sdfHms = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
    private val sdfHm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    private val sdfMdHm = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())

    /** 상대 날짜 시각 표시: 오늘→"오늘 HH:mm:ss", 어제→"어제 HH:mm", 이전→"MM-dd HH:mm" */
    private fun formatRelativeTime(ts: Long): String {
        val tsCal = java.util.Calendar.getInstance().apply { timeInMillis = ts }
        val today = java.util.Calendar.getInstance()
        if (tsCal.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR) &&
            tsCal.get(java.util.Calendar.DAY_OF_YEAR) == today.get(java.util.Calendar.DAY_OF_YEAR)) {
            return "오늘 ${sdfHms.format(java.util.Date(ts))}"
        }
        today.add(java.util.Calendar.DAY_OF_YEAR, -1)
        if (tsCal.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR) &&
            tsCal.get(java.util.Calendar.DAY_OF_YEAR) == today.get(java.util.Calendar.DAY_OF_YEAR)) {
            return "어제 ${sdfHm.format(java.util.Date(ts))}"
        }
        return sdfMdHm.format(java.util.Date(ts))
    }

    private var logoTapCount = 0
    private var logoLastTap = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // v3.5: 온보딩 체크
        if (OnboardingActivity.isFirstRun(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        // v3.22: 사용자 모드 분기
        try { FeatureFlags.load(this) } catch (_: Exception) {}
        if (!FeatureFlags.devMode) {
            startActivity(Intent(this, UserModeActivity::class.java))
            finish()
            return
        }

        // v3.8: 오염 데이터 정리 마이그레이션
        FilterLog.migrateV38Cleanup(this)

        window.statusBarColor = C_BG
        window.navigationBarColor = C_BG

        setContentView(R.layout.activity_main)
        tts = TextToSpeech(this, this)

        // v3.22: 로고 5탭 → 사용자 모드 전환
        findViewById<TextView>(R.id.logoText)?.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - logoLastTap > 2000) logoTapCount = 0
            logoLastTap = now
            logoTapCount++
            if (logoTapCount >= 5) {
                logoTapCount = 0
                FeatureFlags.devMode = false
                FeatureFlags.save(this)
                Toast.makeText(this, "사용자 모드로 전환", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, UserModeActivity::class.java))
                finish()
            }
        }

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
        hourlyRateCard = findViewById(R.id.hourlyRateCard)
        recentHourlyRate = findViewById(R.id.recentHourlyRate)
        cumulativeHourlyRate = findViewById(R.id.cumulativeHourlyRate)
        simulationHourlyCard = findViewById(R.id.simulationHourlyCard)
        simRecentHourlyRate = findViewById(R.id.simRecentHourlyRate)
        simCumulativeHourlyRate = findViewById(R.id.simCumulativeHourlyRate)
        appCheckText = findViewById(R.id.appCheckText)
        hourlyRateOffText = findViewById(R.id.hourlyRateOffText)
        recentHourlyCard = findViewById(R.id.recentHourlyCard)

        // ── FeatureFlag: 채팅 탭 숨김 ──
        if (!FeatureFlags.showChatTab) {
            findViewById<LinearLayout>(R.id.tabBar)?.visibility = View.GONE
            findViewById<FrameLayout>(R.id.tabIndicatorBar)?.visibility = View.GONE
            statusPanel.visibility = View.VISIBLE
            chatPanel.visibility = View.GONE
        }

        // Sprint 6: 운행 모드
        drivingModeSwitch = findViewById(R.id.drivingModeSwitch)
        drivingModeStatusTv = findViewById(R.id.drivingModeStatusTv)
        drivingModeDurationTv = findViewById(R.id.drivingModeDurationTv)
        setupDrivingToggleListener()

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


        statsBtn.setOnClickListener { startActivity(Intent(this, StatsActivity::class.java)) }
        favBtn.setOnClickListener { showPlaceSettings() }
        settingsBtn.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        earningText.setOnClickListener { startActivity(Intent(this, StatsActivity::class.java)) }
        earningText.setOnLongClickListener { startActivity(Intent(this, SettingsActivity::class.java)); true }

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


        todayEarning = EarningManager.getTodayEarning(this)
        todayGoalAmt = EarningManager.getGoal(this)
        EarningManager.markStartTime(this)
        updateEarningDisplay()
        updateHourlyRateDisplay()
        updateAppCheckDisplay()
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
        } else if (OnTheWayService.instance == null) {
            // FIX-SELFPING: 토글 ON인데 인스턴스 사망
            warnings.add("접근성이 멈춰 있습니다 — 껐다 켜 주세요")
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
        tabStatus.setTextColor(if (isStatus) C_BLUE else C_SUB)
        tabStatus.setTypeface(null, if (isStatus) Typeface.BOLD else Typeface.NORMAL)
        tabChat.setTextColor(if (!isStatus) C_BLUE else C_SUB)
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
            filterCountText.text = "오늘 ${detail.total}건 (주의 ${detail.reject} · 보통/우세 ${detail.accept})\n${SessionStats.getSummary(this)}"
        } else {
            filterCountText.text = SessionStats.getSummary(this)
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
                "REJECT" -> "주의"; "ACCEPT" -> {
                    val up = e.optInt("unitPrice", 0)
                    val dist = e.optDouble("distanceKm", -1.0)
                    val pt = e.optDouble("point", -1.0)
                    if (price >= 10000 || (price >= 7000 && ((dist in 0.0..3.0) || (pt in 0.0..15.0))) || (up >= 2500 && dist in 0.0..3.0)) "우세" else "보통"
                }; else -> verdict
            }
            lastCallText.text = "$platform ${fmt(price)}원 $verdictKr"
        }

        updateHourlyRateDisplay()
        updateAppCheckDisplay()

        if (currentTab == "status") refreshDashboard()
    }

    // v3.2: 필터 상태
    private var dashFilterPlatform = "전체"
    private var dashFilterVerdict = "전체"
    private var dashPageSize = 20

    private fun refreshDashboard() {
        recentCallList.removeAllViews()

        // 오늘 건수 헤더
        val allLogs = FilterLog.getRecent(this, 200)
        recentCallList.addView(TextView(this).apply {
            text = "오늘 ${allLogs.size}건"
            textSize = 12f; setTextColor(C_SUB)
            setPadding(dp(20), dp(8), dp(20), dp(4))
        })

        // v3.2: 플랫폼 필터 탭
        val platRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(6), dp(16), dp(2))
        }
        listOf("전체", "배민", "쿠팡", "카카오T").forEach { label ->
            platRow.addView(TextView(this).apply {
                text = label; textSize = 11f; gravity = Gravity.CENTER
                val sel = label == dashFilterPlatform
                setTextColor(if (sel) Color.parseColor("#0A0A0F") else C_BLUE)
                setBackgroundColor(if (sel) C_BLUE else C_WHITE)
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setOnClickListener { dashFilterPlatform = label; dashPageSize = 20; refreshDashboard() }
            }, lp(0, WC, 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
        }
        recentCallList.addView(platRow)

        // v3.2: 판정 필터 탭
        val verdRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(2), dp(16), dp(6))
        }
        listOf("전체", "우세", "보통", "주의").forEach { label ->
            verdRow.addView(TextView(this).apply {
                text = label; textSize = 11f; gravity = Gravity.CENTER
                val sel = label == dashFilterVerdict
                setTextColor(if (sel) Color.parseColor("#0A0A0F") else C_SUB)
                setBackgroundColor(if (sel) C_SUB else C_WHITE)
                setPadding(dp(8), dp(6), dp(8), dp(6))
                setOnClickListener { dashFilterVerdict = label; dashPageSize = 20; refreshDashboard() }
            }, lp(0, WC, 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
        }
        recentCallList.addView(verdRow)

        var logs = allLogs

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

        val totalFiltered = logs.size
        val pagedLogs = logs.take(dashPageSize)

        if (pagedLogs.isEmpty()) {
            recentCallList.addView(TextView(this).apply {
                text = "기록 없음"
                textSize = 13f; setTextColor(C_SUB)
                setPadding(dp(20), dp(12), dp(20), dp(12))
            })
            return
        }

        // 복기 상태 조회
        val completedTs = try { CallLogDb.get(this).getCompletedReviewTimestamps() } catch (_: Exception) { emptySet() }

        for (entry in pagedLogs) {
            recentCallList.addView(buildCallCard(entry, completedTs))
        }

        // 더보기 버튼
        if (dashPageSize < totalFiltered) {
            recentCallList.addView(TextView(this).apply {
                text = "더보기 (${totalFiltered - dashPageSize}건 남음)"
                textSize = 13f; setTextColor(C_BLUE); gravity = Gravity.CENTER
                setBackgroundColor(Color.parseColor("#1A1A2E"))
                setPadding(0, dp(12), 0, dp(12))
                setOnClickListener { dashPageSize += 20; refreshDashboard() }
            }, lp(MP, WC).apply { setMargins(dp(16), dp(8), dp(16), dp(8)) })
        }
    }

    private fun buildCallCard(entry: org.json.JSONObject, completedTs: Set<Long>): View {
        val callTs = entry.getLong("ts")
        val platformCode = entry.optString("platform", "")
        val platform = when (platformCode) {
            "coupang" -> "쿠팡"; "baemin" -> "배민"; "kakaot" -> "카카오"; else -> "?"
        }
        val price = entry.optInt("price", 0)
        val storeName = entry.optString("storeName", "")
        val verdictKr = getVerdictKr(entry)
        val verdictColor = when (verdictKr) {
            "우세" -> Color.parseColor("#00FF88")
            "보통" -> Color.parseColor("#4CC9F0")
            "주의" -> Color.parseColor("#FF4D6D")
            else -> C_SUB
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1A1A2E"))
            setPadding(dp(16), dp(10), dp(16), dp(10))
            layoutParams = LinearLayout.LayoutParams(MP, WC).apply {
                setMargins(dp(4), dp(2), dp(4), dp(2))
            }
        }

        // ── 헤더 행 ──
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val ts = formatRelativeTime(callTs)
        headerRow.addView(TextView(this).apply {
            text = ts; textSize = 11f; setTextColor(C_SUB)
        }, lp(WC, WC).apply { marginEnd = dp(6) })
        headerRow.addView(TextView(this).apply {
            text = platform; textSize = 11f; setTextColor(C_BLUE)
            setTypeface(null, Typeface.BOLD)
        }, lp(WC, WC).apply { marginEnd = dp(4) })

        // 단일/멀티 태그
        val isMulti = entry.optBoolean("multi", false)
        headerRow.addView(TextView(this).apply {
            text = if (isMulti) "멀티" else "단일"
            textSize = 9f
            setTextColor(if (isMulti) Color.parseColor("#00FF88") else Color.parseColor("#4CC9F0"))
            setTypeface(null, Typeface.BOLD)
        }, lp(WC, WC).apply { marginEnd = dp(6) })

        if (storeName.isNotBlank()) {
            headerRow.addView(TextView(this).apply {
                text = storeName; textSize = 11f; setTextColor(Color.parseColor("#CCCCCC"))
                maxLines = 1
            }, lp(0, WC, 1f).apply { marginEnd = dp(6) })
        } else {
            headerRow.addView(TextView(this).apply {
                text = "[가게명 없음]"; textSize = 10f; setTextColor(Color.parseColor("#888888"))
                maxLines = 1
            }, lp(0, WC, 1f).apply { marginEnd = dp(6) })
        }

        // FIX-PICKUP-DISTANCE: 픽업 거리 표시 (큰 글씨)
        val pickupKm = entry.optDouble("pickupKm", -1.0)
        if (pickupKm > 0) {
            headerRow.addView(TextView(this).apply {
                text = "${"%.1f".format(pickupKm)}km"
                textSize = 13f
                setTextColor(Color.parseColor("#FFD700"))
                setTypeface(null, Typeface.BOLD)
            }, lp(WC, WC).apply { marginEnd = dp(4) })
        }

        headerRow.addView(TextView(this).apply {
            text = "${fmt(price)}원"; textSize = 13f; setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
        }, lp(WC, WC).apply { marginEnd = dp(6) })

        // 복기 상태 점
        val fb = FeedbackLogger.findByCall(this, callTs, price)
        val isReviewed = callTs in completedTs
        val dotColor = getDotColor(fb, isReviewed)
        val dotView = View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(dotColor)
            }
        }
        headerRow.addView(dotView, lp(dp(8), dp(8)))

        card.addView(headerRow)

        // 판정 메시지 행
        val reason = entry.optString("reason", "")
        if (reason.isNotBlank() || verdictKr.isNotBlank()) {
            val subRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(2), 0, 0)
            }
            subRow.addView(TextView(this).apply {
                text = verdictKr; textSize = 11f; setTextColor(verdictColor)
                setTypeface(null, Typeface.BOLD)
            }, lp(WC, WC).apply { marginEnd = dp(8) })
            val shortReason = extractShortReason(reason, entry.optString("verdict", ""))
            if (shortReason.isNotBlank()) {
                subRow.addView(TextView(this).apply {
                    text = shortReason; textSize = 10f; setTextColor(C_SUB)
                    maxLines = 1
                }, lp(0, WC, 1f))
            } else {
                val unitPrice = entry.optInt("unitPrice", 0)
                if (unitPrice > 0) {
                    subRow.addView(TextView(this).apply {
                        text = "단가 ${fmt(unitPrice)}원/km"; textSize = 10f; setTextColor(C_SUB)
                    }, lp(WC, WC))
                }
            }
            card.addView(subRow)
        }

        // 카드 탭 → BottomSheet 다이얼로그
        card.setOnClickListener {
            showCallBottomSheet(entry, dotView)
        }

        // 롱클릭 → 기존 상세 다이얼로그
        card.setOnLongClickListener { showCallDetail(entry); true }

        return card
    }

    private fun getDotColor(fb: FeedbackEntry?, isReviewed: Boolean): Int {
        return when {
            fb?.feedback == "up" -> Color.parseColor("#2ECC71")
            fb?.feedback == "down" -> Color.parseColor("#E74C3C")
            isReviewed -> Color.parseColor("#2ECC71")
            else -> Color.parseColor("#444444")
        }
    }

    private fun updateDotColor(dotView: View, color: Int) {
        dotView.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(color)
        }
    }

    private fun showCallBottomSheet(entry: org.json.JSONObject, dotView: View) {
        val callTs = entry.getLong("ts")
        val platformCode = entry.optString("platform", "")
        val platform = when (platformCode) {
            "coupang" -> "쿠팡"; "baemin" -> "배민"; "kakaot" -> "카카오"; else -> "?"
        }
        val price = entry.optInt("price", 0)
        val storeName = entry.optString("storeName", "")
        val unitPrice = entry.optInt("unitPrice", 0)
        val reason = entry.optString("reason", "")
        val verdictKr = getVerdictKr(entry)
        val dist = entry.optDouble("distanceKm", -1.0)
        val verdict = entry.optString("verdict", "")
        val destination = entry.optString("destination", "")
        val point = entry.optDouble("point", -1.0)

        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1A1A2E"))
            setPadding(dp(20), dp(20), dp(20), dp(24))
        }

        // 플랫폼 + 시각
        container.addView(TextView(this).apply {
            text = "[$platform] ${formatRelativeTime(callTs)}"
            textSize = 13f; setTextColor(C_BLUE); setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        })

        // 가게명 (라벨 + 값, 없으면 공란)
        fun addInfoRow(label: String, value: String) {
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(2), 0, dp(2))
            }
            row.addView(TextView(this@MainActivity).apply {
                text = "$label:"; textSize = 13f; setTextColor(C_SUB)
                layoutParams = LinearLayout.LayoutParams(dp(60), WC)
            })
            row.addView(TextView(this@MainActivity).apply {
                text = value; textSize = 13f; setTextColor(Color.WHITE)
            })
            container.addView(row)
        }

        val cleanedStore = if (storeName.isNotBlank()) {
            val cleaned = StoreNameCleaner.clean(storeName)
            if (cleaned.isNotEmpty()) cleaned.joinToString(", ") else ""
        } else ""
        addInfoRow("가게명", cleanedStore)

        if (destination.isNotBlank() && !destination.contains("검색하기")) {
            addInfoRow("배달지", destination)
        }

        // FIX-PICKUP-DISTANCE: 픽업 거리 표시
        val pickupKm = entry.optDouble("pickupKm", -1.0)
        if (pickupKm > 0) {
            addInfoRow("픽업", "${"%.1f".format(pickupKm)}km")
        }

        // 거리 (배달 거리)
        if (platformCode == "baemin" && point > 0) {
            val pointKm = BaeminParser.convertPointToKm(point)
            addInfoRow("배달", "${"%.1f".format(pointKm)}km (${"%.1f".format(point)}P)")
        } else if (dist >= 0) {
            addInfoRow("배달", "${"%.1f".format(dist)}km")
        }

        // 총 거리 (픽업 + 배달)
        if (pickupKm > 0) {
            val deliveryKm = if (platformCode == "baemin" && point > 0) BaeminParser.convertPointToKm(point)
                             else if (dist >= 0) dist else 0.0
            if (deliveryKm > 0) {
                val totalKm = pickupKm + deliveryKm
                addInfoRow("총 거리", "${"%.1f".format(totalKm)}km")
            }
        }

        // 금액 + 단가
        container.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#333355"))
            layoutParams = LinearLayout.LayoutParams(MP, dp(1)).apply { topMargin = dp(8); bottomMargin = dp(8) }
        })

        val priceStr = StringBuilder("${fmt(price)}원")
        if (unitPrice > 0) priceStr.append(" · 단가 ${fmt(unitPrice)}원/km")
        container.addView(TextView(this).apply {
            text = priceStr; textSize = 18f; setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, dp(4))
        })

        // OTW 판정
        container.addView(TextView(this).apply {
            text = "OTW: $verdictKr"
            textSize = 12f
            setTextColor(when (verdictKr) {
                "우세" -> Color.parseColor("#00FF88")
                "주의" -> Color.parseColor("#FF4D6D")
                else -> Color.parseColor("#4CC9F0")
            })
            setPadding(0, 0, 0, dp(8))
        })

        // 구분선
        container.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#333355"))
            layoutParams = LinearLayout.LayoutParams(MP, dp(1)).apply { bottomMargin = dp(12) }
        })

        // 복기 버튼: 잡았어요 / 안잡았어요
        val reviewRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val existingReview = try {
            val cursor = CallLogDb.get(this).readableDatabase.rawQuery(
                "SELECT user_action FROM review_log WHERE call_ts=? AND price=?",
                arrayOf(callTs.toString(), price.toString()))
            cursor.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (_: Exception) { null }

        val acceptBtn = TextView(this).apply {
            text = "잡았어요"; textSize = 14f; setTextColor(Color.parseColor("#00F5A0"))
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#1A2E1A"))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, WC, 1f).apply { marginEnd = dp(4) }
            alpha = if (existingReview == "ACCEPTED") 1f else 0.6f
        }
        val rejectBtn = TextView(this).apply {
            text = "안잡았어요"; textSize = 14f; setTextColor(Color.parseColor("#FF4D6D"))
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#2E1A1A"))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, WC, 1f).apply { marginStart = dp(4) }
            alpha = if (existingReview == "REJECTED") 1f else 0.6f
        }
        reviewRow.addView(acceptBtn)
        reviewRow.addView(rejectBtn)
        container.addView(reviewRow)

        // 배민 표시거리 입력
        var distInput: android.widget.EditText? = null
        if (platformCode == "baemin") {
            distInput = android.widget.EditText(this).apply {
                hint = "배민 표시거리 km (선택)"
                textSize = 13f; setTextColor(Color.WHITE); setHintTextColor(Color.parseColor("#555577"))
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                setBackgroundColor(Color.parseColor("#15152A"))
                setPadding(dp(12), dp(8), dp(12), dp(8))
                layoutParams = LinearLayout.LayoutParams(MP, WC).apply { topMargin = dp(8) }
                visibility = if (existingReview == "ACCEPTED") View.VISIBLE else View.GONE
            }
            container.addView(distInput)
        }

        // GPS 거리 표시 라벨 (잡았어요 후 업데이트됨)
        val gpsLabel = TextView(this).apply {
            textSize = 11f; setTextColor(C_SUB)
            setPadding(0, dp(4), 0, 0)
            visibility = View.GONE
        }
        container.addView(gpsLabel)

        // 피드백 버튼: 👍 / 👎
        container.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#333355"))
            layoutParams = LinearLayout.LayoutParams(MP, dp(1)).apply { topMargin = dp(12); bottomMargin = dp(12) }
        })

        val fb = FeedbackLogger.findByCall(this, callTs, price)
        val fbRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val upBtn = TextView(this).apply {
            text = "\uD83D\uDC4D"; textSize = 22f; gravity = Gravity.CENTER
            setBackgroundColor(if (fb?.feedback == "up") Color.parseColor("#1A3E1A") else Color.parseColor("#222233"))
            setPadding(dp(20), dp(8), dp(20), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, WC, 1f).apply { marginEnd = dp(4) }
        }
        val downBtn = TextView(this).apply {
            text = "\uD83D\uDC4E"; textSize = 22f; gravity = Gravity.CENTER
            setBackgroundColor(if (fb?.feedback == "down") Color.parseColor("#3E1A1A") else Color.parseColor("#222233"))
            setPadding(dp(20), dp(8), dp(20), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, WC, 1f).apply { marginStart = dp(4) }
        }
        fbRow.addView(upBtn)
        fbRow.addView(downBtn)
        container.addView(fbRow)

        // 클릭 핸들러
        var currentAction = existingReview

        acceptBtn.setOnClickListener {
            currentAction = "ACCEPTED"
            val d = distInput?.text?.toString()?.toDoubleOrNull()
            CallLogDb.get(this).upsertReview(callTs, platformCode, price, verdict, reason, "ACCEPTED", d)
            acceptBtn.alpha = 1f; rejectBtn.alpha = 0.3f
            distInput?.visibility = View.VISIBLE
            updateDotColor(dotView, Color.parseColor("#2ECC71"))
            // GPS 거리 백그라운드 계산
            CoroutineScope(Dispatchers.IO).launch {
                val gpsDist = ReviewLogger.calculateGpsDistance(this@MainActivity, callTs)
                if (gpsDist != null) {
                    ReviewLogger.updateGpsDistance(this@MainActivity, callTs, price, gpsDist)
                }
                withContext(Dispatchers.Main) {
                    if (gpsDist != null) {
                        gpsLabel.text = "GPS 거리: ${"%.1f".format(gpsDist)}km"
                        gpsLabel.visibility = View.VISIBLE
                    }
                }
            }
        }
        rejectBtn.setOnClickListener {
            currentAction = "REJECTED"
            CallLogDb.get(this).upsertReview(callTs, platformCode, price, verdict, reason, "REJECTED", null)
            rejectBtn.alpha = 1f; acceptBtn.alpha = 0.3f
            distInput?.visibility = View.GONE
            gpsLabel.visibility = View.GONE
            updateDotColor(dotView, Color.parseColor("#2ECC71"))
        }

        upBtn.setOnClickListener {
            showFeedbackAndSave(true, "\uD83D\uDC4D", platformCode, storeName, price,
                dist, verdictKr, reason, "${callTs}_${price}")
            upBtn.setBackgroundColor(Color.parseColor("#1A3E1A"))
            downBtn.setBackgroundColor(Color.parseColor("#222233"))
            updateDotColor(dotView, Color.parseColor("#2ECC71"))
        }
        downBtn.setOnClickListener {
            showFeedbackAndSave(false, "\uD83D\uDC4E", platformCode, storeName, price,
                dist, verdictKr, reason, "${callTs}_${price}")
            downBtn.setBackgroundColor(Color.parseColor("#3E1A1A"))
            upBtn.setBackgroundColor(Color.parseColor("#222233"))
            updateDotColor(dotView, Color.parseColor("#E74C3C"))
        }

        // 닫기 버튼
        container.addView(TextView(this).apply {
            text = "닫기"; textSize = 15f; setTextColor(C_SUB); gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(4))
            setOnClickListener {
                if (currentAction == "ACCEPTED" && platformCode == "baemin") {
                    val d = distInput?.text?.toString()?.toDoubleOrNull()
                    if (d != null) CallLogDb.get(this@MainActivity).updateUserAction(callTs, price, "ACCEPTED", d)
                }
                sheet.dismiss()
            }
        })

        sheet.setContentView(container)
        sheet.window?.navigationBarColor = Color.parseColor("#1A1A2E")
        sheet.show()
    }

    private fun getVerdictKr(entry: org.json.JSONObject): String {
        val verdict = entry.optString("verdict", "")
        if (verdict == "REJECT") return "주의"
        if (verdict == "ACCEPTED") return "수락됨"
        val price = entry.optInt("price", 0)
        val unitPrice = entry.optInt("unitPrice", 0)
        val dist = entry.optDouble("distanceKm", -1.0)
        val pt = entry.optDouble("point", -1.0)
        val isGrab = price >= 10000 ||
            (price >= 7000 && ((dist in 0.0..3.0) || (pt in 0.0..15.0))) ||
            (unitPrice >= 2500 && dist in 0.0..3.0)
        return if (isGrab) "우세" else "보통"
    }

    private fun extractShortReason(reason: String, verdict: String): String {
        if (reason.isBlank()) return ""
        return when {
            reason.contains("묶음 효율") -> "묶음 효율"
            reason.contains("고단가 근거리") -> "고단가 근거리"
            reason.contains("단거리 고단가") -> "단거리 고단가"
            reason.contains("최소기준") || reason.contains("최소 기준") -> "최소 기준 미달"
            reason.contains("기준 미달") -> {
                Regex("""단가\s*([\d,]+)원/km""").find(reason)?.let { "단가 ${it.groupValues[1]}원/km 미달" }
                    ?: "기준 미달"
            }
            reason.contains("묶음 최소") -> "묶음 최소 미달"
            reason.contains("블랙리스트") -> "블랙리스트"
            reason.contains("구간 기준") -> "구간 기준 미달"
            else -> ""
        }
    }

    /** v3.15: 콜 상세 다이얼로그 — 판정 컬러 + 섹션 구조 + 사유 간결화 */
    /** v3.20: 피드백 이유 선택 → 저장 (신규 or 덮어쓰기) */
    private fun showFeedbackAndSave(
        isUp: Boolean, emoji: String, platformCode: String,
        storeName: String, price: Int, dist: Double,
        verdictKr: String, reason: String, feedbackSessionId: String
    ) {
        if (isUp) {
            // 👍 즉시 저장 (4축 생략)
            val existing = FeedbackLogger.findBySessionId(this, feedbackSessionId)
            if (existing != null) {
                val updated = existing.copy(feedback = "up", entryPoint = "thumbs_up")
                FeedbackLogger.updateBySessionId(this, feedbackSessionId, updated)
                android.widget.Toast.makeText(this, "$emoji 덮어쓰기 완료", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                FeedbackLogger.log(this, platform = platformCode, store = storeName,
                    price = price, distanceKm = if (dist >= 0) dist else 0.0,
                    verdict = verdictKr, reason = reason, sessionId = feedbackSessionId,
                    feedback = "up", reasons = emptyList(),
                    driverAction = "accepted", entryPoint = "thumbs_up")
                android.widget.Toast.makeText(this, "$emoji 기록됨", android.widget.Toast.LENGTH_SHORT).show()
            }
            refreshDashboard()
        } else {
            // 👎 → 4축 상세
            BidirectionalFeedbackDialog.show(this, "thumbs_down", platform = platformCode) { matrix ->
                val existing = FeedbackLogger.findBySessionId(this, feedbackSessionId)
                if (existing != null) {
                    val updated = existing.copy(
                        feedback = "down",
                        reasons = matrix.toReasonsList(),
                        pickupRating = matrix.pickupRating,
                        deliveryRating = matrix.deliveryRating,
                        priceRating = matrix.priceRating,
                        judgmentRating = matrix.judgmentRating,
                        entryPoint = matrix.entryPoint
                    )
                    FeedbackLogger.updateBySessionId(this, feedbackSessionId, updated)
                    android.widget.Toast.makeText(this, "$emoji 덮어쓰기 완료", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    FeedbackLogger.log(this, platform = platformCode, store = storeName,
                        price = price, distanceKm = if (dist >= 0) dist else 0.0,
                        verdict = verdictKr, reason = reason, sessionId = feedbackSessionId,
                        feedback = "down", reasons = matrix.toReasonsList(),
                        driverAction = "rejected",
                        pickupRating = matrix.pickupRating, deliveryRating = matrix.deliveryRating,
                        priceRating = matrix.priceRating, judgmentRating = matrix.judgmentRating,
                        entryPoint = matrix.entryPoint,
                        platformDistanceKm = matrix.platformDistanceKm,
                        onthewayDistanceKm = matrix.onthewayDistanceKm,
                        distanceDiffKm = matrix.distanceDiffKm, memo = matrix.memo)
                    android.widget.Toast.makeText(this, "$emoji 기록됨", android.widget.Toast.LENGTH_SHORT).show()
                }
                refreshDashboard()
            }
        }
    }

    private fun showCallDetail(entry: org.json.JSONObject) {
        val nf = java.text.NumberFormat.getNumberInstance()
        val ts = formatRelativeTime(entry.getLong("ts"))
        val platformCode = entry.optString("platform", "")
        val platformShort = when (platformCode) {
            "coupang" -> "쿠팡"; "baemin" -> "배민"; "kakaot" -> "카카오T"; else -> "?"
        }
        val price = entry.optInt("price", 0)
        val dist = entry.optDouble("distanceKm", -1.0)
        val unitPrice = entry.optInt("unitPrice", 0)
        val verdict = entry.optString("verdict", "")
        val reason = entry.optString("reason", "")
        val storeName = entry.optString("storeName", "")
        val point = entry.optDouble("point", -1.0)
        val bundleCount = entry.optInt("bundleCount", 0)
        val isMulti = entry.optBoolean("multi", false)
        val isMultiPickup = entry.optBoolean("multiPickup", false)
        val pickupKm = entry.optDouble("pickupKm", -1.0)

        // 판정 결정 (reason 기반으로 "우세" 감지)
        val verdictKr: String
        val verdictColor: Int
        if (verdict == "REJECT") {
            verdictKr = "주의"
            verdictColor = Color.parseColor("#E53935")
        } else if (reason.contains("우세:") || reason.contains("우세")) {
            verdictKr = "우세"
            verdictColor = Color.parseColor("#5B6ABF")
        } else {
            verdictKr = "보통"
            verdictColor = Color.parseColor("#4CAF50")
        }

        // ── 프로그래밍 방식 다이얼로그 뷰 구성 ──
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }

        // 판정 (큰 글씨 + 컬러)
        container.addView(TextView(this).apply {
            text = verdictKr
            textSize = 24f
            setTextColor(verdictColor)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })

        // 헤더: 플랫폼 · 금액
        container.addView(TextView(this).apply {
            text = "$platformShort · ${nf.format(price)}원"
            textSize = 16f
            setTextColor(Color.parseColor("#333333"))
            setPadding(0, dp(4), 0, 0)
        })

        // 구분선
        fun addDivider() {
            container.addView(View(this@MainActivity).apply {
                setBackgroundColor(Color.parseColor("#EEEEEE"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
                ).apply { topMargin = dp(12); bottomMargin = dp(12) }
            })
        }
        addDivider()

        // 섹션 행 추가 함수
        fun addSection(label: String, value: String) {
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(3), 0, dp(3))
            }
            row.addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 14f
                setTextColor(Color.parseColor("#888888"))
                layoutParams = LinearLayout.LayoutParams(dp(72), LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            row.addView(TextView(this@MainActivity).apply {
                text = value
                textSize = 14f
                setTextColor(Color.parseColor("#333333"))
            })
            container.addView(row)
        }

        // 거리 섹션
        if (platformCode == "baemin" && point > 0) {
            val pointKm = BaeminParser.convertPointToKm(point)
            addSection("거리", "약 ${"%.1f".format(pointKm)}km (${"%.1f".format(point)}P)")
            if (pointKm > 0) {
                addSection("단가", "${nf.format((price / pointKm).toInt())}원/km")
            }
        } else if (dist >= 0) {
            addSection("거리", "${"%.1f".format(dist)}km")
            if (unitPrice > 0) addSection("단가", "${nf.format(unitPrice)}원/km")
        }

        // 픽업/총거리
        if (pickupKm > 0) {
            addSection("픽업", "${"%.1f".format(pickupKm)}km")
            if (dist > 0) {
                val totalKm = pickupKm + dist
                addSection("총거리", "${"%.1f".format(totalKm)}km (${nf.format((price / totalKm).toInt())}원/km)")
            }
        }

        // 묶음
        if (isMulti && bundleCount > 1) {
            val perItem = price / bundleCount
            val pickupStr = if (isMultiPickup) " · 다중픽업" else ""
            addSection("묶음", "${bundleCount}건 (건당 ${nf.format(perItem)}원$pickupStr)")
        }

        // 가게명 (정제) + 목적지
        val destination = entry.optString("destination", "")
        if (storeName.isNotEmpty()) {
            val cleanedStores = StoreNameCleaner.clean(storeName)
            if (cleanedStores.isNotEmpty()) {
                addSection("가게", cleanedStores.joinToString("\n"))
            }
        }
        if (destination.isNotEmpty() && !destination.contains("검색하기")) {
            addSection("목적지", destination)
        }

        addDivider()

        // 사유 (간결화)
        val simplifiedReason = simplifyReason(reason, verdict)
        addSection("사유", simplifiedReason)

        addDivider()

        // 시각 (라벨 + 값)
        addSection("감지", ts)

        // v3.19: 👍 👎 피드백 버튼
        addDivider()
        val callTs = entry.getLong("ts")
        val feedbackSessionId = "s_${callTs}_${price}"
        val existingFb = FeedbackLogger.findByCall(this, callTs, price)

        if (existingFb != null) {
            // 기존 피드백 있음 → 수정하기 버튼
            val fbRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(8))
            }
            val fbEmoji = if (existingFb.feedback == "up") "\uD83D\uDC4D" else "\uD83D\uDC4E"
            fbRow.addView(TextView(this).apply {
                text = "$fbEmoji 피드백 완료"
                textSize = 14f; setTextColor(Color.parseColor("#999999"))
            })
            fbRow.addView(TextView(this).apply {
                text = "수정하기"; textSize = 13f; setTextColor(Color.parseColor("#4CC9F0"))
                setPadding(dp(12), 0, 0, 0)
                setOnClickListener {
                    BidirectionalFeedbackDialog.show(this@MainActivity,
                        existingFb.entryPoint ?: "thumbs_up",
                        platform = platformCode,
                        onthewayDistanceKm = if (dist >= 0) dist.toFloat() else null,
                        existing = existingFb) { matrix ->
                        val updated = existingFb.copy(
                            reasons = matrix.toReasonsList(),
                            pickupRating = matrix.pickupRating,
                            deliveryRating = matrix.deliveryRating,
                            priceRating = matrix.priceRating,
                            judgmentRating = matrix.judgmentRating,
                            entryPoint = matrix.entryPoint,
                            platformDistanceKm = matrix.platformDistanceKm,
                            onthewayDistanceKm = matrix.onthewayDistanceKm,
                            distanceDiffKm = matrix.distanceDiffKm
                        )
                        val sid = existingFb.sessionId ?: feedbackSessionId
                        FeedbackLogger.updateBySessionId(this@MainActivity, sid, updated)
                        android.widget.Toast.makeText(this@MainActivity, "피드백 수정됨", android.widget.Toast.LENGTH_SHORT).show()
                        refreshDashboard()
                    }
                }
            })
            container.addView(fbRow, LinearLayout.LayoutParams(MP, WC))
        } else {
            // 새 피드백
            val feedbackRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, dp(4))
            }
            fun makeFeedbackButton(emoji: String, isUp: Boolean): TextView {
                return TextView(this@MainActivity).apply {
                    text = emoji
                    textSize = 20f
                    gravity = Gravity.CENTER
                    setBackgroundColor(Color.parseColor("#F0F0F0"))
                    setPadding(dp(24), dp(8), dp(24), dp(8))
                    setOnClickListener {
                        showFeedbackAndSave(isUp, emoji, platformCode, storeName, price, dist, verdictKr, reason, feedbackSessionId)
                    }
                }
            }
            feedbackRow.addView(makeFeedbackButton("\uD83D\uDC4D", true), LinearLayout.LayoutParams(WC, WC).apply { marginEnd = dp(12) })
            feedbackRow.addView(makeFeedbackButton("\uD83D\uDC4E", false))
            container.addView(feedbackRow, LinearLayout.LayoutParams(MP, WC))
        }

        // 다이얼로그 빌드
        val dlg = AlertDialog.Builder(this)
            .setView(container)
            .setPositiveButton("확인", null)

        // v3.17: 즐겨찾기/블랙리스트 버튼 (가게명 > 목적지 > rawText 순 대체)
        val cleanedName = if (storeName.isNotEmpty()) StoreNameCleaner.cleanToString(storeName) else ""
        val rawText = entry.optString("rawText", "")
        val storeKey = when {
            cleanedName.isNotEmpty() -> storeName
            destination.isNotEmpty() && !destination.contains("검색하기") -> destination
            rawText.isNotEmpty() -> rawText.take(30)
            else -> ""
        }
        val displayName = when {
            cleanedName.isNotEmpty() -> cleanedName
            destination.isNotEmpty() && !destination.contains("검색하기") -> destination
            else -> "$platformShort 콜"
        }
        if (storeKey.isNotEmpty()) {
            if (StoreManager.isFavorite(this, storeKey)) {
                dlg.setNeutralButton("즐겨찾기 해제") { _, _ ->
                    AlertDialog.Builder(this)
                        .setTitle("즐겨찾기 해제")
                        .setMessage("$displayName 을(를) 즐겨찾기에서 해제하시겠습니까?")
                        .setPositiveButton("해제") { _, _ ->
                            StoreManager.removeFavorite(this, storeKey)
                            android.widget.Toast.makeText(this, "$displayName 즐겨찾기 해제", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("취소", null)
                        .show()
                }
            } else if (StoreManager.isBlacklisted(this, storeKey)) {
                dlg.setNeutralButton("블랙리스트 해제") { _, _ ->
                    AlertDialog.Builder(this)
                        .setTitle("블랙리스트 해제")
                        .setMessage("$displayName 을(를) 블랙리스트에서 해제하시겠습니까?")
                        .setPositiveButton("해제") { _, _ ->
                            StoreManager.removeBlacklist(this, storeKey)
                            android.widget.Toast.makeText(this, "$displayName 블랙리스트 해제", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("취소", null)
                        .show()
                }
            } else {
                dlg.setNeutralButton("즐겨찾기") { _, _ ->
                    AlertDialog.Builder(this)
                        .setTitle("즐겨찾기 추가")
                        .setMessage("$displayName 을(를) 즐겨찾기에 추가하시겠습니까?\n(최소배달료 기준 1,000원 완화)")
                        .setPositiveButton("추가") { _, _ ->
                            StoreManager.addFavorite(this, storeKey, platformCode)
                            android.widget.Toast.makeText(this, "$displayName 즐겨찾기 추가", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("취소", null)
                        .show()
                }
                dlg.setNegativeButton("블랙리스트") { _, _ ->
                    AlertDialog.Builder(this)
                        .setTitle("블랙리스트 추가")
                        .setMessage("$displayName 을(를) 블랙리스트에 추가하시겠습니까?\n(앞으로 이 가게 콜은 '주의'로 판정)")
                        .setPositiveButton("추가") { _, _ ->
                            StoreManager.addBlacklist(this, storeKey, platformCode)
                            android.widget.Toast.makeText(this, "$displayName 블랙리스트 추가", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("취소", null)
                        .show()
                }
            }
        }
        dlg.show()
    }

    /** v3.17: 판정 사유 간결화 */
    private fun simplifyReason(raw: String, verdict: String): String {
        return when {
            // --- REJECT ---
            verdict == "REJECT" && raw.contains("구간기준") -> {
                val match = Regex("""구간기준\s*([\d,]+)원""").find(raw)
                if (match != null) "구간 기준 ${match.groupValues[1]}원 미달" else raw
            }
            verdict == "REJECT" && raw.contains("묶음 최소") -> {
                val match = Regex("""묶음 최소\s*([\d,]+)원""").find(raw)
                if (match != null) "묶음 최소 ${match.groupValues[1]}원 미달" else raw
            }
            verdict == "REJECT" && raw.contains("최소기준") -> {
                val match = Regex("""최소기준\s*([\d,]+)원""").find(raw)
                if (match != null) "최소 기준 ${match.groupValues[1]}원 미달" else raw
            }
            verdict == "REJECT" && raw.contains("단가") && raw.contains("미달") -> {
                val match = Regex("""단가\s*([\d,]+)원/km""").find(raw)
                if (match != null) "단가 ${match.groupValues[1]}원/km 미달" else raw
            }
            verdict == "REJECT" && raw.contains("묶음") -> {
                val match = Regex("""기준\s*([\d,]+)원""").find(raw)
                if (match != null) "묶음 기준 ${match.groupValues[1]}원 미달" else raw
            }
            // --- ACCEPT: 우세 (한글 설명 추출) ---
            verdict != "REJECT" && (raw.contains("우세:") || raw.contains("우세")) -> {
                val match = Regex("""우세:\s*([가-힣]+\s*[가-힣]+)""").find(raw)
                if (match != null) "우세 · ${match.groupValues[1].trim()}" else "우세"
            }
            // --- ACCEPT: 쿠팡/단건 단가+거리 기반 ---
            verdict != "REJECT" && raw.contains("단가") && raw.contains("≥") && raw.contains("거리") -> {
                when {
                    raw.contains("≤ 3km") || raw.contains("≤ 3.0km") -> "고단가 근거리"
                    raw.contains("≤ 2km") || raw.contains("≤ 2.0km") -> "단거리 고단가"
                    else -> "단가 기준 통과"
                }
            }
            // --- ACCEPT: 구간 기준 통과 ---
            verdict != "REJECT" && raw.contains("구간기준") -> "구간 기준 통과"
            // --- ACCEPT: 묶음 통과/효율 ---
            verdict != "REJECT" && raw.contains("묶음 효율") -> {
                val match = Regex("""건당\s*([\d,]+)원""").find(raw)
                if (match != null) "묶음 효율 (건당 ${match.groupValues[1]}원)" else "묶음 효율"
            }
            verdict != "REJECT" && raw.contains("묶음 통과") -> {
                val match = Regex("""건당\s*([\d,]+)원""").find(raw)
                if (match != null) "묶음 통과 (건당 ${match.groupValues[1]}원)" else "묶음 통과"
            }
            // --- ACCEPT: 고액/고단가/일반 ---
            verdict != "REJECT" && raw.contains("고액") -> "고액 콜"
            verdict != "REJECT" && raw.contains("단가") && raw.contains("≥") -> "단가 기준 통과"
            verdict != "REJECT" && raw.contains("최소기준") -> "기준 통과"
            else -> raw
        }
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

    private fun getHourlyRateColor(rate: Int): Int {
        return when {
            rate <= 0 -> C_SUB
            rate >= 20000 -> Color.parseColor("#00FF88")
            rate >= 18000 -> Color.parseColor("#FFD700")
            rate >= 16000 -> Color.parseColor("#FFA500")
            else -> Color.parseColor("#FF4D6D")
        }
    }

    private fun updateHourlyRateDisplay() {
        hourlyRateCard.visibility = View.VISIBLE

        // 운행 OFF = 시급 미표시
        if (DrivingModeManager.getMode(this) != DrivingMode.DRIVING) {
            recentHourlyRate.text = "—원/h"
            cumulativeHourlyRate.text = "—원/h"
            simRecentHourlyRate.text = "—원/h"
            simCumulativeHourlyRate.text = "—원/h"
            updateHourlyCardDrivingState()
            return
        }

        val recent = EarningsTracker.getRecentHourlyRate(this)
        val cumulative = EarningsTracker.getCumulativeHourlyRate(this)

        // 메인: 체감 시급만 표시
        recentHourlyRate.text = if (recent >= 0) "${fmt(recent)}원/h" else "—원/h"
        if (recent > 0) recentHourlyRate.setTextColor(getHourlyRateColor(recent))
        // 누적/SIM 값은 계산만 (통계 화면에서 사용)
        cumulativeHourlyRate.text = if (cumulative >= 0) "${fmt(cumulative)}원/h" else "—원/h"

        val simRecent = SimulationEarnings.getRecentHourlyRate(this)
        val simCumulative = SimulationEarnings.getCumulativeHourlyRate(this)
        simRecentHourlyRate.text = if (simRecent > 0) "${fmt(simRecent)}원/h" else "—원/h"
        simCumulativeHourlyRate.text = if (simCumulative > 0) "${fmt(simCumulative)}원/h" else "—원/h"

        // 운행 OFF → 회색 처리
        updateHourlyCardDrivingState()
    }

    private fun updateHourlyCardDrivingState() {
        val isDriving = DrivingModeManager.getMode(this) == DrivingMode.DRIVING
        val card = recentHourlyCard as? androidx.cardview.widget.CardView
        if (isDriving) {
            card?.setCardBackgroundColor(C_WHITE)
            // 시급 색상은 getHourlyRateColor에서 설정한 값 유지
            hourlyRateOffText?.visibility = View.GONE
        } else {
            card?.setCardBackgroundColor(Color.parseColor("#0D0D15"))
            recentHourlyRate.setTextColor(C_SUB)
            hourlyRateOffText?.visibility = View.VISIBLE
        }
    }

    private fun updateAppCheckDisplay() {
        SessionStats.ensureLoaded(this)
        val count = SessionStats.appCheckCount
        appCheckText.text = if (count > 0) "오늘 ${count}회 확인" else ""
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
        if (!::tts.isInitialized) return
        val clean = text.replace(Regex("[\\p{So}\\p{Cn}]+"), "").trim()
        isSpeaking = true
        tts.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "utt")
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onDone(id: String?) { isSpeaking = false }
            override fun onError(id: String?) { isSpeaking = false }
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
        if (::micBtn.isInitialized) { micBtn.setBackgroundResource(R.drawable.mic_bg); micBtn.text = "\uD83C\uDFA4" }
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // 오버레이 클릭 → 피드백 다이얼로그
        if (intent?.getBooleanExtra("open_feedback", false) == true) {
            intent.removeExtra("open_feedback")
            val fbPlatform = intent.getStringExtra("fb_platform") ?: ""
            val fbPrice = intent.getIntExtra("fb_price", 0)
            val fbTs = intent.getLongExtra("fb_ts", 0)
            if (fbPrice > 0) {
                try {
                    val sessionId = "s_${fbTs}_${fbPrice}"
                    BidirectionalFeedbackDialog.showThumbsFirst(this,
                        platform = fbPlatform) { matrix ->
                        val fb = if (matrix.entryPoint == "thumbs_up") "up" else "down"
                        FeedbackLogger.log(this, platform = fbPlatform, store = "",
                            price = fbPrice, distanceKm = 0.0,
                            verdict = "", reason = "", sessionId = sessionId,
                            feedback = fb, reasons = matrix.toReasonsList(),
                            driverAction = if (fb == "up") "accepted" else "rejected",
                            pickupRating = matrix.pickupRating, deliveryRating = matrix.deliveryRating,
                            priceRating = matrix.priceRating, judgmentRating = matrix.judgmentRating,
                            entryPoint = matrix.entryPoint, memo = matrix.memo)
                    }
                } catch (_: Exception) {}
            }
        }
        // 설정에서 변경된 값 반영
        fontScale = FontSizeManager.getScale(this)
        todayGoalAmt = EarningManager.getGoal(this)
        todayEarning = EarningManager.getTodayEarning(this)
        updateEarningDisplay()
        SessionStats.onAppChecked(this)
        updateHourlyRateDisplay()
        updateAppCheckDisplay()
        updateDrivingModeUi()

        // FIX-AUDIT-2: 어제/그제 audit 미입력 자동 감지
        checkPendingAudit()

        // 첫 실행 온보딩
        if (!AdvancedPrefs.isOnboardingShown(this)) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("OnTheWay 권한 안내")
                .setMessage(
                    "이 앱은 다음만 읽습니다:\n" +
                    "• 배민커넥트 / 쿠팡이츠 알림\n" +
                    "• 픽업/배달 정보 (금액, 거리)\n\n" +
                    "이 앱은 접근하지 않습니다:\n" +
                    "• 카카오톡, 메시지, 사진\n" +
                    "• 연락처, 통화 기록\n\n" +
                    "작동 시점:\n" +
                    "• 운행 모드 ON 시에만\n" +
                    "• 운행 OFF = 모든 분석 중지\n\n" +
                    "데이터 처리:\n" +
                    "• 가게명, 주소 = 폰 안에서만\n" +
                    "• 서버 전송 = 익명 통계만\n\n" +
                    "권한 끄는 방법:\n" +
                    "• 안드로이드 설정 → 앱 → OnTheWay → 권한\n" +
                    "• 언제든 끄면 즉시 분석 중지\n\n" +
                    "데이터 보존:\n" +
                    "• 가게명/주소 = 폰 안에서만\n" +
                    "• 서버 = 익명 통계만\n" +
                    "• 앱 삭제 시 모든 데이터 제거"
                )
                .setPositiveButton("동의하고 시작") { _, _ ->
                    AdvancedPrefs.setOnboardingShown(this)
                }
                .setCancelable(false)
                .show()
        }
    }

    /** FIX-SELFPING: 운행 토글 리스너 (instance dead 차단 포함) */
    private fun setupDrivingToggleListener() {
        drivingModeSwitch?.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                val health = AccessibilityHealthMonitor.check(
                    instanceAlive = OnTheWayService.instance != null,
                    lastEventTimeMs = OnTheWayService.lastAccessibilityEventTime
                )
                if (health.status == AccessibilityHealthMonitor.Status.INSTANCE_DEAD) {
                    // 차단: 리스너 해제 → 되돌리기 → 리스너 재설정
                    drivingModeSwitch?.setOnCheckedChangeListener(null)
                    drivingModeSwitch?.isChecked = false
                    setupDrivingToggleListener()
                    Toast.makeText(this, "접근성이 멈춰 있습니다. 설정에서 OnTheWay를 껐다 켜 주세요", Toast.LENGTH_LONG).show()
                    startActivity(android.content.Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    return@setOnCheckedChangeListener
                }
            }
            val mode = if (isChecked) DrivingMode.DRIVING else DrivingMode.IDLE
            DrivingModeManager.setMode(this, mode, "user_toggle_main")
            updateDrivingModeUi()
            if (isChecked) {
                Toast.makeText(this, "운행 시작", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "운행 종료", Toast.LENGTH_SHORT).show()
                // FIX-AUDIT: 운행 종료 시 매출 진단 다이얼로그
                showDailyAuditDialog()
            }
        }
    }

    /**
     * FIX-AUDIT-2: 어제/그제 audit 미입력 자동 감지.
     * onResume 시 1회만 표시. Skip 시 오늘은 다시 안 보임.
     */
    private var pendingAuditCheckedToday = false
    private fun checkPendingAudit() {
        if (pendingAuditCheckedToday) return
        pendingAuditCheckedToday = true

        val sdf = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault())
        val cal = java.util.Calendar.getInstance()
        val today = sdf.format(cal.time)

        // 최근 2일 체크
        val datesToCheck = mutableListOf<String>()
        for (i in 1..2) {
            cal.time = java.util.Date()
            cal.add(java.util.Calendar.DAY_OF_YEAR, -i)
            datesToCheck.add(sdf.format(cal.time))
        }

        val auditDb = DailyAuditDb.get(this)
        val prefs = getSharedPreferences("audit_skip", MODE_PRIVATE)

        for (dateStr in datesToCheck) {
            // 이미 Skip 했으면 건너뜀
            if (prefs.getBoolean("skip_$dateStr", false)) continue
            // pending 확인
            if (auditDb.hasPendingAudit(dateStr)) {
                showPendingAuditDialog(dateStr)
                return
            }
        }
    }

    private fun showPendingAuditDialog(dateStr: String) {
        val displayDate = "${dateStr.substring(4, 6)}/${dateStr.substring(6, 8)}"
        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle("$displayDate 매출 미입력")
            .setMessage("$displayDate 운행 기록이 있지만 실제 매출이 입력되지 않았습니다.\n지금 입력하시겠어요?")
            .setPositiveButton("입력") { _, _ ->
                showDailyAuditDialogForDate(dateStr)
            }
            .setNegativeButton("건너뛰기") { _, _ ->
                getSharedPreferences("audit_skip", MODE_PRIVATE)
                    .edit().putBoolean("skip_$dateStr", true).apply()
            }
            .setCancelable(false)
            .show()
    }

    /** FIX-AUDIT-2: 특정 날짜의 audit 입력 (어제/그제용) */
    private fun showDailyAuditDialogForDate(dateStr: String) {
        val auditDb = DailyAuditDb.get(this)
        val existing = auditDb.getRecent(30).find { it.date == dateStr }
        val screenTotal = existing?.screenTotal ?: 0
        val screenCalls = existing?.screenCalls ?: 0
        val acceptCount = existing?.acceptLogsCount ?: 0
        val acceptAmount = existing?.acceptLogsAmount ?: 0

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }
        layout.addView(TextView(this).apply {
            text = "화면 매출: ${java.text.NumberFormat.getNumberInstance().format(screenTotal)}원 (${screenCalls}건)"
            textSize = 14f; setTextColor(Color.parseColor("#AAAAAA"))
        })
        layout.addView(TextView(this).apply {
            text = "수락 감지: ${acceptCount}건 ${java.text.NumberFormat.getNumberInstance().format(acceptAmount)}원"
            textSize = 14f; setTextColor(Color.parseColor("#AAAAAA")); setPadding(0, dp(4), 0, dp(12))
        })

        layout.addView(TextView(this).apply { text = "쿠팡 실제 매출 (원)"; textSize = 13f; setTextColor(Color.parseColor("#CCCCCC")) })
        val coupangInput = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER; hint = "0"
            setTextColor(Color.WHITE); setHintTextColor(Color.parseColor("#666666"))
        }
        layout.addView(coupangInput)

        layout.addView(TextView(this).apply {
            text = "배민 실제 매출 (원)"; textSize = 13f; setTextColor(Color.parseColor("#CCCCCC")); setPadding(0, dp(8), 0, 0)
        })
        val baeminInput = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER; hint = "0"
            setTextColor(Color.WHITE); setHintTextColor(Color.parseColor("#666666"))
        }
        layout.addView(baeminInput)

        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle("${dateStr.substring(4, 6)}/${dateStr.substring(6, 8)} 실제 매출 입력")
            .setView(layout)
            .setPositiveButton("저장") { _, _ ->
                val coupang = coupangInput.text.toString().toIntOrNull() ?: 0
                val baemin = baeminInput.text.toString().toIntOrNull() ?: 0
                val declaredTotal = coupang + baemin
                if (declaredTotal <= 0) {
                    Toast.makeText(this, "매출을 입력해 주세요", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val bubble = BubbleCalculator.calculate(screenTotal, declaredTotal)
                val reliability = BubbleCalculator.acceptReliability(acceptAmount, declaredTotal)
                auditDb.save(DailyAuditDb.AuditEntry(
                    date = dateStr, declaredCoupang = coupang, declaredBaemin = baemin,
                    declaredTotal = declaredTotal, screenTotal = screenTotal, screenCalls = screenCalls,
                    acceptLogsCount = acceptCount, acceptLogsAmount = acceptAmount, bubblePct = bubble.bubblePct
                ))
                showAuditResult(bubble, reliability, screenTotal, declaredTotal)
            }
            .setNegativeButton("Skip") { _, _ ->
                getSharedPreferences("audit_skip", MODE_PRIVATE)
                    .edit().putBoolean("skip_$dateStr", true).apply()
            }
            .show()
    }

    /** FIX-AUDIT: 매출 자체 진단 다이얼로그 */
    private fun showDailyAuditDialog() {
        // 오늘 콜 0건이면 스킵
        val todayEarnings = EarningsTracker.getToday(this)
        val todayDetail = FilterLog.getTodayDetail(this)
        if (todayDetail.total == 0) return

        val screenTotal = todayEarnings.totalRevenue
        val screenCalls = todayDetail.total
        val acceptCount = todayEarnings.acceptedCount
        val acceptAmount = todayEarnings.totalRevenue

        // FIX-AUDIT-2: 화면 매출 사전 저장 (pending 감지용)
        val todayStr = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault()).format(java.util.Date())
        DailyAuditDb.get(this).saveScreenOnly(todayStr, screenTotal, screenCalls, acceptCount, acceptAmount)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }

        // 화면 매출 표시
        layout.addView(TextView(this).apply {
            text = "화면 매출: ${java.text.NumberFormat.getNumberInstance().format(screenTotal)}원 (${screenCalls}건)"
            textSize = 14f
            setTextColor(Color.parseColor("#AAAAAA"))
        })

        layout.addView(TextView(this).apply {
            text = "수락 감지: ${acceptCount}건 ${java.text.NumberFormat.getNumberInstance().format(acceptAmount)}원"
            textSize = 14f
            setTextColor(Color.parseColor("#AAAAAA"))
            setPadding(0, dp(4), 0, dp(12))
        })

        // 쿠팡 입력
        layout.addView(TextView(this).apply {
            text = "쿠팡 실제 매출 (원)"
            textSize = 13f
            setTextColor(Color.parseColor("#CCCCCC"))
        })
        val coupangInput = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "0"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#666666"))
        }
        layout.addView(coupangInput)

        // 배민 입력
        layout.addView(TextView(this).apply {
            text = "배민 실제 매출 (원)"
            textSize = 13f
            setTextColor(Color.parseColor("#CCCCCC"))
            setPadding(0, dp(8), 0, 0)
        })
        val baeminInput = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "0"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#666666"))
        }
        layout.addView(baeminInput)

        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle("오늘 실제 매출 입력")
            .setView(layout)
            .setPositiveButton("저장") { _, _ ->
                val coupang = coupangInput.text.toString().toIntOrNull() ?: 0
                val baemin = baeminInput.text.toString().toIntOrNull() ?: 0
                val declaredTotal = coupang + baemin
                if (declaredTotal <= 0) {
                    Toast.makeText(this, "매출을 입력해 주세요", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val bubble = BubbleCalculator.calculate(screenTotal, declaredTotal)
                val reliability = BubbleCalculator.acceptReliability(acceptAmount, declaredTotal)
                val todayStr = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault())
                    .format(java.util.Date())

                // 저장
                DailyAuditDb.get(this).save(DailyAuditDb.AuditEntry(
                    date = todayStr,
                    declaredCoupang = coupang,
                    declaredBaemin = baemin,
                    declaredTotal = declaredTotal,
                    screenTotal = screenTotal,
                    screenCalls = screenCalls,
                    acceptLogsCount = acceptCount,
                    acceptLogsAmount = acceptAmount,
                    bubblePct = bubble.bubblePct
                ))

                // 결과 표시
                showAuditResult(bubble, reliability, screenTotal, declaredTotal)
            }
            .setNegativeButton("Skip", null)
            .show()
    }

    private fun showAuditResult(
        bubble: BubbleCalculator.BubbleResult,
        reliability: Float,
        screenTotal: Int,
        declaredTotal: Int
    ) {
        val nf = java.text.NumberFormat.getNumberInstance()
        val diff = screenTotal - declaredTotal
        val kpiColor = when (bubble.kpi) {
            BubbleCalculator.KpiLevel.GREEN -> "#00FF88"
            BubbleCalculator.KpiLevel.YELLOW -> "#FFD700"
            BubbleCalculator.KpiLevel.RED -> "#FF4444"
        }
        val kpiIcon = when (bubble.kpi) {
            BubbleCalculator.KpiLevel.GREEN -> "GREEN"
            BubbleCalculator.KpiLevel.YELLOW -> "YELLOW"
            BubbleCalculator.KpiLevel.RED -> "RED"
        }

        // 7일 트렌드
        val recent = DailyAuditDb.get(this).getRecent(7)
        val trendText = if (recent.size >= 2) {
            recent.reversed().joinToString(" → ") { "${it.bubblePct.toInt()}%" }
        } else ""

        val msg = buildString {
            append("화면: ${nf.format(screenTotal)}원\n")
            append("실제: ${nf.format(declaredTotal)}원\n")
            append("차이: ${if (diff >= 0) "+" else ""}${nf.format(diff)}원\n\n")
            append("거품: ${bubble.label}  [$kpiIcon]\n")
            append("수락 감지 신뢰도: ${reliability.toInt()}%\n")
            if (trendText.isNotEmpty()) {
                append("\n7일 트렌드: $trendText")
            }
        }

        val resultLayout = TextView(this).apply {
            text = msg
            textSize = 15f
            setTextColor(Color.parseColor(kpiColor))
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }

        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle("매출 진단 결과")
            .setView(resultLayout)
            .setPositiveButton("확인", null)
            .show()
    }

    private fun updateDrivingModeUi() {
        try {
            val isDriving = DrivingModeManager.getMode(this) == DrivingMode.DRIVING
            drivingModeSwitch?.let { sw ->
                if (sw.isChecked != isDriving) {
                    sw.setOnCheckedChangeListener(null)
                    sw.isChecked = isDriving
                    setupDrivingToggleListener()
                }
            }
            drivingModeStatusTv?.text = if (isDriving) "운행 모드: ON" else "운행 모드: OFF"
            drivingModeStatusTv?.setTextColor(if (isDriving) Color.parseColor("#00FF88") else C_SUB)
            findViewById<LinearLayout>(R.id.drivingModeCard)?.setBackgroundColor(
                if (isDriving) Color.parseColor("#1A2E1A") else C_WHITE
            )
            val ms = DrivingModeManager.getTodayDrivingTimeMs(this)
            val h = ms / 3_600_000; val m = (ms % 3_600_000) / 60_000
            drivingModeDurationTv?.text = if (h > 0) "오늘 운행: ${h}시간 ${m}분" else "오늘 운행: ${m}분"
            updateHourlyCardDrivingState()
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        if (::tts.isInitialized) { tts.stop(); tts.shutdown() }
        super.onDestroy()
    }

    override fun onInit(status: Int) {
        if (!::tts.isInitialized) return
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.KOREAN
            mainHandler.postDelayed({ speak("오늘도 안전 운행하세요") }, 500)
        }
    }
}

package com.vita.ontheway

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * v3.19.4: 쿠팡이츠 Flutter 접근성 진단 로그 뷰어
 */
class CoupangDiagnosticViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FILE_NAME = "diag_file_name"
        const val EXTRA_TITLE = "diag_title"
    }

    private var FILE_NAME = "coupang_diagnostic.jsonl"
    private var TITLE = "쿠팡 진단 로그"
    private val MAX_DISPLAY = 50

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val MP = ViewGroup.LayoutParams.MATCH_PARENT
    private val WC = ViewGroup.LayoutParams.WRAP_CONTENT
    private fun lp(w: Int, h: Int, wt: Float = 0f) = LinearLayout.LayoutParams(w, h, wt)

    // 필터 상태
    private var filterNonEmpty = true
    private var groupByPhase = false
    private var sortRecent = true

    private lateinit var contentContainer: LinearLayout
    private lateinit var statsText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        FILE_NAME = intent.getStringExtra(EXTRA_FILE_NAME) ?: "coupang_diagnostic.jsonl"
        TITLE = intent.getStringExtra(EXTRA_TITLE) ?: "쿠팡 진단 로그"

        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        val scale = FontSizeManager.getScale(this)

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#F5F5F5"))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(40))
        }

        // --- Header ---
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(44), dp(16), dp(14))
        }
        header.addView(TextView(this).apply {
            text = "←"; textSize = 20f; setTextColor(Color.BLACK)
            setPadding(dp(8), 0, dp(16), 0)
            setOnClickListener { finish() }
        })
        header.addView(TextView(this).apply {
            text = TITLE; textSize = 18f * scale; setTextColor(Color.BLACK)
            setTypeface(null, Typeface.BOLD)
        }, lp(0, WC, 1f))

        // Action buttons
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        btnRow.addView(actionBtn("공유") { shareLog() })
        btnRow.addView(actionBtn("지우기") { confirmClear() })
        btnRow.addView(actionBtn("새로고침") { refreshContent() })
        header.addView(btnRow)

        root.addView(header, lp(MP, WC))
        root.addView(View(this).apply { setBackgroundColor(Color.parseColor("#EEEEEE")) }, lp(MP, dp(1)))

        // --- Stats ---
        statsText = TextView(this).apply {
            textSize = 13f * scale; setTextColor(Color.parseColor("#666666"))
            setPadding(dp(16), dp(12), dp(16), dp(8))
            setTypeface(Typeface.MONOSPACE)
        }
        root.addView(statsText)

        // --- Filter toggles ---
        val filterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(4), dp(12), dp(8))
        }
        filterRow.addView(filterToggle("비어있지 않은 필드만", filterNonEmpty) { checked ->
            filterNonEmpty = checked; refreshContent()
        })
        filterRow.addView(filterToggle("phase별 그룹", groupByPhase) { checked ->
            groupByPhase = checked; refreshContent()
        })
        filterRow.addView(filterToggle("최근 순", sortRecent) { checked ->
            sortRecent = checked; refreshContent()
        })
        root.addView(filterRow)

        // --- Content ---
        contentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(contentContainer, lp(MP, WC))

        scrollView.addView(root)
        setContentView(scrollView)

        refreshContent()
    }

    private fun refreshContent() {
        contentContainer.removeAllViews()
        val scale = FontSizeManager.getScale(this)
        val file = File(filesDir, FILE_NAME)

        if (!file.exists() || file.length() == 0L) {
            statsText.text = "총 0 entries"
            contentContainer.addView(TextView(this).apply {
                text = "진단 로그 없음 — 쿠팡 콜을 받아주세요"
                textSize = 16f * scale; setTextColor(Color.parseColor("#999999"))
                gravity = Gravity.CENTER
                setPadding(dp(32), dp(64), dp(32), dp(64))
            })
            return
        }

        val allEntries = mutableListOf<JSONObject>()
        try {
            file.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (line.isNotBlank()) {
                        try { allEntries.add(JSONObject(line)) } catch (_: Exception) {}
                    }
                }
            }
        } catch (e: Exception) {
            statsText.text = "파일 읽기 실패: ${e.message}"
            return
        }

        // Stats
        val textCount = allEntries.count { it.optString("text", "").isNotEmpty() }
        val descCount = allEntries.count { it.optString("contentDesc", "").isNotEmpty() }
        val hintCount = allEntries.count { it.optString("hintText", "").isNotEmpty() }
        statsText.text = "총 ${allEntries.size} entries | text: $textCount | contentDesc: $descCount | hintText: $hintCount"

        // Filter
        var filtered = if (filterNonEmpty) {
            allEntries.filter { entry ->
                entry.optString("text", "").isNotEmpty() ||
                entry.optString("contentDesc", "").isNotEmpty() ||
                entry.optString("hintText", "").isNotEmpty()
            }
        } else allEntries

        // Sort
        filtered = if (sortRecent) filtered.reversed() else filtered

        // Limit
        val display = filtered.take(MAX_DISPLAY)

        if (display.isEmpty()) {
            contentContainer.addView(TextView(this).apply {
                text = "필터 조건에 맞는 항목 없음"
                textSize = 14f * scale; setTextColor(Color.parseColor("#999999"))
                gravity = Gravity.CENTER
                setPadding(dp(32), dp(32), dp(32), dp(32))
            })
            return
        }

        // Group by phase or flat
        if (groupByPhase) {
            val grouped = display.groupBy { it.optString("phase", "unknown") }
            for ((phase, entries) in grouped) {
                contentContainer.addView(TextView(this).apply {
                    text = "── $phase (${entries.size}건) ──"
                    textSize = 13f * scale; setTextColor(Color.parseColor("#5B6ABF"))
                    setTypeface(null, Typeface.BOLD)
                    setPadding(dp(16), dp(12), dp(16), dp(4))
                })
                entries.forEach { entry -> contentContainer.addView(buildCard(entry, scale)) }
            }
        } else {
            display.forEach { entry -> contentContainer.addView(buildCard(entry, scale)) }
        }

        // Show count
        if (filtered.size > MAX_DISPLAY) {
            contentContainer.addView(TextView(this).apply {
                text = "... ${filtered.size - MAX_DISPLAY}건 더 있음"
                textSize = 12f * scale; setTextColor(Color.parseColor("#999999"))
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(20))
            })
        }
    }

    private fun buildCard(entry: JSONObject, scale: Float): LinearLayout {
        val ts = entry.optLong("ts", 0)
        val timeStr = if (ts > 0) SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ts)) else "?"
        val phase = entry.optString("phase", "?")
        val pkg = entry.optString("pkg", "")
        val depth = entry.optInt("depth", 0)
        val className = entry.optString("className", "?").substringAfterLast('.')
        val text = entry.optString("text", "")
        val contentDesc = entry.optString("contentDesc", "")
        val hintText = entry.optString("hintText", "")
        val viewId = entry.optString("viewId", "")
        val bounds = entry.optString("bounds", "")
        val paneTitle = entry.optString("paneTitle", "")
        val stateDesc = entry.optString("stateDesc", "")
        val tooltipText = entry.optString("tooltipText", "")

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }

        // Header line: [time] [phase]
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        headerRow.addView(TextView(this@CoupangDiagnosticViewerActivity).apply {
            this.text = "[$timeStr]"; textSize = 12f * scale
            setTextColor(Color.parseColor("#999999"))
            setTypeface(Typeface.MONOSPACE)
        })
        headerRow.addView(TextView(this@CoupangDiagnosticViewerActivity).apply {
            this.text = " [$phase]"; textSize = 12f * scale
            setTextColor(Color.parseColor("#5B6ABF"))
            setTypeface(Typeface.MONOSPACE)
        })
        card.addView(headerRow)

        // Package name
        if (pkg.isNotEmpty()) {
            val pkgColor = if (pkg == "com.coupang.mobile.eats.courier") "#4CAF50" else "#F44336"
            card.addView(TextView(this).apply {
                this.text = "pkg: $pkg"
                textSize = 11f * scale; setTextColor(Color.parseColor(pkgColor))
                setTypeface(Typeface.MONOSPACE)
                setPadding(0, dp(2), 0, 0)
            })
        }

        // Depth + class
        card.addView(TextView(this).apply {
            this.text = "depth=$depth class=$className"
            textSize = 12f * scale; setTextColor(Color.parseColor("#666666"))
            setTypeface(Typeface.MONOSPACE)
            setPadding(0, dp(2), 0, dp(4))
        })

        // Fields
        addFieldRow(card, "text", text, scale, Color.parseColor("#4CAF50"))
        addFieldRow(card, "contentDesc", contentDesc, scale, Color.parseColor("#2196F3"))
        addFieldRow(card, "hintText", hintText, scale, Color.parseColor("#FF9800"))
        addFieldRow(card, "viewId", viewId, scale, Color.parseColor("#666666"))
        if (paneTitle.isNotEmpty()) addFieldRow(card, "paneTitle", paneTitle, scale, Color.parseColor("#9C27B0"))
        if (stateDesc.isNotEmpty()) addFieldRow(card, "stateDesc", stateDesc, scale, Color.parseColor("#009688"))
        if (tooltipText.isNotEmpty()) addFieldRow(card, "tooltipText", tooltipText, scale, Color.parseColor("#795548"))

        // Bounds
        if (bounds.isNotEmpty()) {
            card.addView(TextView(this).apply {
                this.text = "bounds: $bounds"
                textSize = 11f * scale; setTextColor(Color.parseColor("#AAAAAA"))
                setTypeface(Typeface.MONOSPACE)
            })
        }

        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(4))
        }
        wrapper.addView(card)
        return wrapper
    }

    private fun addFieldRow(parent: LinearLayout, label: String, value: String, scale: Float, highlightColor: Int) {
        val isEmpty = value.isEmpty()
        val displayValue = if (isEmpty) "(empty)" else value

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(this@CoupangDiagnosticViewerActivity).apply {
            text = "$label: "; textSize = 12f * scale
            setTextColor(if (isEmpty) Color.parseColor("#CCCCCC") else Color.parseColor("#666666"))
            setTypeface(Typeface.MONOSPACE)
        })

        val valueView = TextView(this@CoupangDiagnosticViewerActivity).apply {
            text = displayValue; textSize = 12f * scale
            setTypeface(Typeface.MONOSPACE)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            if (isEmpty) {
                setTextColor(Color.parseColor("#CCCCCC"))
            } else {
                setTextColor(highlightColor)
                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            }
        }
        // Tap to expand
        if (!isEmpty && displayValue.length > 40) {
            valueView.setOnClickListener {
                if (valueView.maxLines == 2) {
                    valueView.maxLines = Int.MAX_VALUE
                } else {
                    valueView.maxLines = 2
                }
            }
        }
        row.addView(valueView, lp(0, WC, 1f))
        parent.addView(row)
    }

    private fun actionBtn(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label; textSize = 13f
            setTextColor(Color.parseColor("#5B6ABF"))
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { onClick() }
        }
    }

    private fun filterToggle(label: String, initialChecked: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        val cb = CheckBox(this).apply {
            isChecked = initialChecked
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }
        container.addView(cb)
        container.addView(TextView(this).apply {
            text = label; textSize = 12f; setTextColor(Color.parseColor("#333333"))
            setOnClickListener { cb.isChecked = !cb.isChecked }
        })
        return container
    }

    private fun shareLog() {
        val file = File(filesDir, FILE_NAME)
        if (!file.exists() || file.length() == 0L) {
            Toast.makeText(this, "공유할 로그 없음", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "쿠팡 진단 로그")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "로그 공유"))
        } catch (e: Exception) {
            Toast.makeText(this, "공유 실패: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("진단 로그 초기화")
            .setMessage("기존 로그를 모두 삭제하시겠습니까?\n새 로그 수집이 바로 시작됩니다.")
            .setPositiveButton("삭제") { _, _ ->
                val file = File(filesDir, FILE_NAME)
                if (file.exists()) file.writeText("")
                Toast.makeText(this, "로그 초기화됨", Toast.LENGTH_SHORT).show()
                refreshContent()
            }
            .setNegativeButton("취소", null)
            .show()
    }
}

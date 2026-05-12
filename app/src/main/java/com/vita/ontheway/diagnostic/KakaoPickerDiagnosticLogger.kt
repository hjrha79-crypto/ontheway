package com.vita.ontheway.diagnostic

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import java.io.File

// [Source-Only 원칙 v1.0]
// kakao_picker는 raw 로깅만. 분기 추가 금지.
// 검증 종료: 2026-07-12 또는 10건 누적 후 평가.

/**
 * 카카오T 픽커(com.kakaomobility.flexer) 접근성 트리 진단 로거.
 * BaeminDiagnosticLogger와 동일 구조.
 * 3-phase 탐색: immediate → delayed_100ms → after_refresh
 */
object KakaoPickerDiagnosticLogger {

    private const val TAG = "KakaoPickerDiag"
    const val FILE_NAME = "kakao_picker_diagnostic.jsonl"
    private const val MAX_ENTRIES = 20000
    private const val MAX_DEPTH = 15
    private const val COOLDOWN_MS = 3000L
    const val PKG_FLEXER = "com.kakaomobility.flexer"

    private var lastLogTime = 0L
    private var ioThread: HandlerThread? = null
    private var ioHandler: Handler? = null
    private var logFile: File? = null
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        logFile = File(context.filesDir, FILE_NAME)
        if (ioThread == null) {
            ioThread = HandlerThread("KakaoPickerDiagIO").apply { start() }
            ioHandler = Handler(ioThread!!.looper)
        }
        Log.d(TAG, "초기화 완료: ${logFile?.absolutePath}")
    }

    fun logEvent(root: AccessibilityNodeInfo, eventType: Int) {
        val rootPkg = root.packageName?.toString() ?: ""
        if (rootPkg != PKG_FLEXER) {
            Log.d(TAG, "패키지 필터링: $rootPkg (flexer 아님, 무시)")
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastLogTime < COOLDOWN_MS) return
        lastLogTime = now

        val eventTypeStr = eventTypeToString(eventType)

        val immediateEntries = mutableListOf<JSONObject>()
        traverseTree(root, 0, eventTypeStr, "immediate", now, immediateEntries)

        val handler = ioHandler ?: return
        handler.postDelayed({
            try {
                val delayedEntries = mutableListOf<JSONObject>()
                traverseTree(root, 0, eventTypeStr, "delayed_100ms", now, delayedEntries)

                val refreshEntries = mutableListOf<JSONObject>()
                traverseTreeWithRefresh(root, 0, eventTypeStr, now, refreshEntries)

                val allEntries = immediateEntries + delayedEntries + refreshEntries
                writeEntries(allEntries)

                Log.d(TAG, "기록 완료: immediate=${immediateEntries.size}, delayed=${delayedEntries.size}, refresh=${refreshEntries.size}")
            } catch (e: Exception) {
                Log.w(TAG, "delayed/refresh 탐색 실패: ${e.message}")
            }
        }, 100)
    }

    private fun traverseTree(
        node: AccessibilityNodeInfo,
        depth: Int,
        eventTypeStr: String,
        phase: String,
        ts: Long,
        entries: MutableList<JSONObject>
    ) {
        if (depth > MAX_DEPTH) return
        try {
            val text = node.text?.toString() ?: ""
            val contentDesc = node.contentDescription?.toString() ?: ""
            val hintText = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                node.hintText?.toString() ?: ""
            } else ""
            val viewId = node.viewIdResourceName ?: ""
            val className = node.className?.toString() ?: ""
            val paneTitle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                node.paneTitle?.toString() ?: ""
            } else ""
            val stateDesc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                node.stateDescription?.toString() ?: ""
            } else ""
            val tooltipText = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                node.tooltipText?.toString() ?: ""
            } else ""
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            val bounds = rect.toShortString()

            if (text.isNotEmpty() || contentDesc.isNotEmpty() || hintText.isNotEmpty() ||
                viewId.isNotEmpty() || paneTitle.isNotEmpty() || stateDesc.isNotEmpty() ||
                tooltipText.isNotEmpty()
            ) {
                val nodePkg = node.packageName?.toString() ?: ""
                val json = JSONObject().apply {
                    put("ts", ts)
                    put("phase", phase)
                    put("eventType", eventTypeStr)
                    put("pkg", nodePkg)
                    put("depth", depth)
                    put("className", className)
                    put("text", text)
                    put("contentDesc", contentDesc)
                    put("hintText", hintText)
                    put("viewId", viewId)
                    put("paneTitle", paneTitle)
                    put("stateDesc", stateDesc)
                    put("tooltipText", tooltipText)
                    put("bounds", bounds)
                }
                entries.add(json)
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child ->
                    val childPkg = child.packageName?.toString() ?: ""
                    if (childPkg.isEmpty() || childPkg == PKG_FLEXER) {
                        traverseTree(child, depth + 1, eventTypeStr, phase, ts, entries)
                    }
                }
            }
        } catch (e: Exception) {
            // 노드 접근 실패 시 무시
        }
    }

    private fun traverseTreeWithRefresh(
        node: AccessibilityNodeInfo,
        depth: Int,
        eventTypeStr: String,
        ts: Long,
        entries: MutableList<JSONObject>
    ) {
        if (depth > MAX_DEPTH) return
        try {
            node.refresh()

            val text = node.text?.toString() ?: ""
            val contentDesc = node.contentDescription?.toString() ?: ""
            val hintText = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                node.hintText?.toString() ?: ""
            } else ""
            val viewId = node.viewIdResourceName ?: ""
            val className = node.className?.toString() ?: ""
            val paneTitle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                node.paneTitle?.toString() ?: ""
            } else ""
            val stateDesc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                node.stateDescription?.toString() ?: ""
            } else ""
            val tooltipText = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                node.tooltipText?.toString() ?: ""
            } else ""
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            val bounds = rect.toShortString()

            if (text.isNotEmpty() || contentDesc.isNotEmpty() || hintText.isNotEmpty() ||
                viewId.isNotEmpty() || paneTitle.isNotEmpty() || stateDesc.isNotEmpty() ||
                tooltipText.isNotEmpty()
            ) {
                val nodePkg = node.packageName?.toString() ?: ""
                val json = JSONObject().apply {
                    put("ts", ts)
                    put("phase", "after_refresh")
                    put("eventType", eventTypeStr)
                    put("pkg", nodePkg)
                    put("depth", depth)
                    put("className", className)
                    put("text", text)
                    put("contentDesc", contentDesc)
                    put("hintText", hintText)
                    put("viewId", viewId)
                    put("paneTitle", paneTitle)
                    put("stateDesc", stateDesc)
                    put("tooltipText", tooltipText)
                    put("bounds", bounds)
                }
                entries.add(json)
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child ->
                    val childPkg = child.packageName?.toString() ?: ""
                    if (childPkg.isEmpty() || childPkg == PKG_FLEXER) {
                        traverseTreeWithRefresh(child, depth + 1, eventTypeStr, ts, entries)
                    }
                }
            }
        } catch (e: Exception) {
            // refresh 실패 시 무시
        }
    }

    fun writeEntries(entries: List<JSONObject>) {
        if (entries.isEmpty()) return
        val file = logFile ?: return
        ioHandler?.post {
            try {
                val existingLines = if (file.exists()) file.readLines().size else 0
                if (existingLines + entries.size > MAX_ENTRIES) {
                    val keepFrom = (existingLines + entries.size) - MAX_ENTRIES
                    if (file.exists()) {
                        val lines = file.readLines()
                        val kept = lines.drop(keepFrom)
                        file.writeText(kept.joinToString("\n") + "\n")
                    }
                }
                file.appendText(entries.joinToString("\n") { it.toString() } + "\n")
            } catch (e: Exception) {
                Log.w(TAG, "파일 쓰기 실패: ${e.message}")
            }
        }

        // Tier 1 ledger에도 동일 raw 데이터 영구 보존
        val ctx = appContext ?: return
        try {
            com.vita.ontheway.ledger.LedgerAppender.appendDiagnosticAccessibility(
                ctx, PKG_FLEXER, entries
            )
        } catch (e: Exception) {
            Log.w(TAG, "ledger append 실패 (silent): ${e.message}")
        }
    }

    /** 테스트용: logFile 직접 설정 */
    internal fun setLogFileForTest(file: File) {
        logFile = file
    }

    private fun eventTypeToString(type: Int): String = when (type) {
        1 -> "TYPE_VIEW_CLICKED"
        2 -> "TYPE_VIEW_LONG_CLICKED"
        4 -> "TYPE_VIEW_SELECTED"
        8 -> "TYPE_VIEW_FOCUSED"
        16 -> "TYPE_VIEW_TEXT_CHANGED"
        32 -> "TYPE_WINDOW_STATE_CHANGED"
        64 -> "TYPE_NOTIFICATION_STATE_CHANGED"
        256 -> "TYPE_VIEW_SCROLLED"
        512 -> "TYPE_VIEW_TEXT_SELECTION_CHANGED"
        2048 -> "TYPE_WINDOW_CONTENT_CHANGED"
        4096 -> "TYPE_VIEW_HOVER_ENTER"
        else -> "TYPE_$type"
    }
}

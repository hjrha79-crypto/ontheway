package com.vita.ontheway

import android.util.Log

/**
 * 배달 플랫폼 이벤트 처리 타임스탬프 관측용.
 * 기존 로직에 영향 없이 순수 로그만 출력.
 */
object PerfTrace {

    private const val TAG = "PerfTrace"

    /** 타임스탬프 마크 출력. detail이 있으면 괄호 안에 추가. */
    fun mark(platform: String, label: String, detail: String? = null) {
        val ts = System.currentTimeMillis()
        val extra = if (detail != null) " ($detail)" else ""
        Log.d(TAG, "[$platform] $label$extra @ $ts")
    }
}

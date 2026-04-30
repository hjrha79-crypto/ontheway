package com.vita.ontheway

import android.util.Log

enum class DropReason {
    DROP_SELF_REFERENCE,   // com.vita.ontheway 자기 화면 무시
    DROP_PACKAGE_FILTER,   // 타겟 앱 아닌 패키지
    DROP_PARSE_FAIL,       // 파싱 실패
    DROP_DUPLICATE,        // 중복 event_id / 콜키
    DROP_COOLDOWN,         // 쿨다운 중
    DROP_NOT_DRIVING,      // 미사용 (나중 확장)
    DROP_SCREEN_FILTER,    // 대기/비콜 화면 필터
    DROP_HISTORY_SCREEN,   // 배민 이전내역(완료 배달 목록) 화면
    DROP_STALE,            // 서비스 시작 전 오래된 이벤트
    DROP_OTHER;            // 기타

    companion object {
        private const val TAG = "OTW_DROP"

        fun recordDrop(reason: DropReason, context: String, packageName: String? = null) {
            try {
                val pkgStr = if (packageName != null) " (pkg=$packageName)" else ""
                val msg = "[$reason] $context$pkgStr"
                Log.d(TAG, msg)
                OtwFileLogger.log(TAG, msg)
                DropStats.increment(reason)
            } catch (_: Exception) {}
        }
    }
}

package com.vita.ontheway

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * 투명 Activity — OnTheWayService에서 수락 감지 후 피드백 다이얼로그 표시용.
 * 다이얼로그 종료 시 자동 finish().
 */
class AcceptFeedbackActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PLATFORM = "platform"
        const val EXTRA_STORE = "store"
        const val EXTRA_PRICE = "price"
        const val EXTRA_DISTANCE = "distance"
        const val EXTRA_VERDICT = "verdict"
        const val EXTRA_REASON = "reason"
        const val EXTRA_SESSION_ID = "session_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val platform = intent.getStringExtra(EXTRA_PLATFORM) ?: ""
        val store = intent.getStringExtra(EXTRA_STORE) ?: ""
        val price = intent.getIntExtra(EXTRA_PRICE, 0)
        val distance = intent.getDoubleExtra(EXTRA_DISTANCE, 0.0)
        val verdict = intent.getStringExtra(EXTRA_VERDICT) ?: ""
        val reason = intent.getStringExtra(EXTRA_REASON) ?: ""
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)

        AcceptFeedbackDialog.show(
            context = this,
            platform = platform,
            storeName = store,
            price = price,
            distanceKm = distance,
            verdict = verdict,
            reason = reason,
            sessionId = sessionId,
            onDone = { finish() }
        )
    }
}

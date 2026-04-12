package com.vita.ontheway

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast

// ──────────────────────────────────────────
// NaviLauncher
// 카카오내비 → 카카오맵 → 구글맵 → 안내 메시지
// 앱이 죽지 않도록 모든 케이스 처리
// ──────────────────────────────────────────
object NaviLauncher {

    private const val PKG_KAKAO_NAVI  = "com.locnall.KimGiSa"
    private const val PKG_KAKAO_MAP   = "net.daum.android.map"
    private const val PKG_GOOGLE_MAP  = "com.google.android.apps.maps"

    // ── 메인 진입점 ────────────────────────
    fun launch(context: Context, pickup: String, dropoff: String) {
        when {
            isInstalled(context, PKG_KAKAO_NAVI) -> launchKakaoNavi(context, dropoff)
            isInstalled(context, PKG_KAKAO_MAP)  -> launchKakaoMap(context, dropoff)
            isInstalled(context, PKG_GOOGLE_MAP) -> launchGoogleMap(context, dropoff)
            else -> showFallbackMessage(context, dropoff)
        }
    }

    // ── 이은석님 폰 사전 확인용 ───────────
    fun checkNaviStatus(context: Context): NaviStatus {
        return NaviStatus(
            kakaoNaviInstalled  = isInstalled(context, PKG_KAKAO_NAVI),
            kakaoMapInstalled   = isInstalled(context, PKG_KAKAO_MAP),
            googleMapInstalled  = isInstalled(context, PKG_GOOGLE_MAP)
        )
    }

    // ── 1순위: 카카오내비 ──────────────────
    private fun launchKakaoNavi(context: Context, destination: String) {
        try {
            val uri = Uri.parse("kakaonavi://navigate?ep=${Uri.encode(destination)}")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            // 카카오내비 실패 시 카카오맵으로
            launchKakaoMap(context, destination)
        }
    }

    // ── 2순위: 카카오맵 ───────────────────
    private fun launchKakaoMap(context: Context, destination: String) {
        try {
            val uri = Uri.parse("kakaomap://look?p=${Uri.encode(destination)}")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            launchGoogleMap(context, destination)
        }
    }

    // ── 3순위: 구글맵 ─────────────────────
    private fun launchGoogleMap(context: Context, destination: String) {
        try {
            val uri = Uri.parse("google.navigation:q=${Uri.encode(destination)}&mode=d")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            showFallbackMessage(context, destination)
        }
    }

    // ── fallback: 안내 메시지 ──────────────
    private fun showFallbackMessage(context: Context, destination: String) {
        Toast.makeText(
            context,
            "내비 앱이 없습니다. 목적지: $destination",
            Toast.LENGTH_LONG
        ).show()
    }

    // ── 설치 여부 확인 ─────────────────────
    private fun isInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
}

// 내비 설치 현황 (이은석님 폰 사전 확인용)
data class NaviStatus(
    val kakaoNaviInstalled: Boolean,
    val kakaoMapInstalled: Boolean,
    val googleMapInstalled: Boolean
) {
    fun toMessage(): String {
        val lines = mutableListOf<String>()
        lines.add("카카오내비: ${if (kakaoNaviInstalled) "✅ 설치됨" else "❌ 없음"}")
        lines.add("카카오맵:  ${if (kakaoMapInstalled)  "✅ 설치됨" else "❌ 없음"}")
        lines.add("구글맵:    ${if (googleMapInstalled) "✅ 설치됨" else "❌ 없음"}")
        lines.add("")
        lines.add("사용 경로: ${
            when {
                kakaoNaviInstalled -> "카카오내비 → fallback"
                kakaoMapInstalled  -> "카카오맵 → fallback"
                googleMapInstalled -> "구글맵 → fallback"
                else               -> "⚠ 내비 앱 없음 — 안내 메시지"
            }
        }")
        return lines.joinToString("\n")
    }
}

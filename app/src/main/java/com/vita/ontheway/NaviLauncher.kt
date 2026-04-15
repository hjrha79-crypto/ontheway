package com.vita.ontheway

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import android.widget.Toast

// ──────────────────────────────────────────
// NaviLauncher
// 카카오내비 → 카카오맵 → 티맵 → 구글맵 → 안내 메시지
// v3.0: 설정 연동 + 자동 실행 지원
// ──────────────────────────────────────────
object NaviLauncher {

    private const val TAG = "NaviLauncher"

    private const val PKG_KAKAO_NAVI  = "com.locnall.KimGiSa"
    private const val PKG_KAKAO_MAP   = "net.daum.android.map"
    private const val PKG_TMAP        = "com.skt.tmap.ku"
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

    /** v3.0: 콜 수락 시 자동 네비 실행 (설정 기반) */
    fun autoLaunchForAccept(ctx: Context, pickupAddress: String) {
        if (!AdvancedPrefs.isNaviAutoLaunchEnabled(ctx)) return
        if (pickupAddress.isBlank()) {
            Log.d(TAG, "픽업 주소 없음 - 네비 자동실행 건너뜀")
            return
        }

        val naviApp = AdvancedPrefs.getNaviApp(ctx)
        Log.d(TAG, "네비 자동실행: app=$naviApp, address=$pickupAddress")

        try {
            val intent = when (naviApp) {
                "kakao_navi" -> kakaoNaviIntent(ctx, pickupAddress)
                "tmap" -> tmapIntent(ctx, pickupAddress)
                "kakao_map" -> kakaoMapIntent(ctx, pickupAddress)
                else -> kakaoNaviIntent(ctx, pickupAddress)
            }
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
                Log.d(TAG, "네비 자동실행 성공: $naviApp")
            } else {
                launchGeoFallback(ctx, pickupAddress)
            }
        } catch (e: Exception) {
            Log.w(TAG, "네비 자동실행 실패: ${e.message}")
            launchGeoFallback(ctx, pickupAddress)
        }
    }

    private fun kakaoNaviIntent(ctx: Context, dest: String): Intent? {
        if (isInstalled(ctx, PKG_KAKAO_NAVI)) {
            return Intent(Intent.ACTION_VIEW, Uri.parse("kakaonavi://navigate?ep=${Uri.encode(dest)}"))
        }
        return kakaoMapIntent(ctx, dest)
    }

    private fun tmapIntent(ctx: Context, dest: String): Intent? {
        if (!isInstalled(ctx, PKG_TMAP)) return null
        return Intent(Intent.ACTION_VIEW, Uri.parse("tmap://route?goalname=${Uri.encode(dest)}"))
    }

    private fun kakaoMapIntent(ctx: Context, dest: String): Intent? {
        if (!isInstalled(ctx, PKG_KAKAO_MAP)) return null
        return Intent(Intent.ACTION_VIEW, Uri.parse("kakaomap://look?p=${Uri.encode(dest)}"))
    }

    private fun launchGeoFallback(ctx: Context, address: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(address)}"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "geo Intent도 실패: ${e.message}")
        }
    }

    // ── 기존 메서드 ────────────────────────

    fun checkNaviStatus(context: Context): NaviStatus {
        return NaviStatus(
            kakaoNaviInstalled  = isInstalled(context, PKG_KAKAO_NAVI),
            kakaoMapInstalled   = isInstalled(context, PKG_KAKAO_MAP),
            tmapInstalled       = isInstalled(context, PKG_TMAP),
            googleMapInstalled  = isInstalled(context, PKG_GOOGLE_MAP)
        )
    }

    private fun launchKakaoNavi(context: Context, destination: String) {
        try {
            val uri = Uri.parse("kakaonavi://navigate?ep=${Uri.encode(destination)}")
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        } catch (e: Exception) {
            launchKakaoMap(context, destination)
        }
    }

    private fun launchKakaoMap(context: Context, destination: String) {
        try {
            val uri = Uri.parse("kakaomap://look?p=${Uri.encode(destination)}")
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        } catch (e: Exception) {
            launchGoogleMap(context, destination)
        }
    }

    private fun launchGoogleMap(context: Context, destination: String) {
        try {
            val uri = Uri.parse("google.navigation:q=${Uri.encode(destination)}&mode=d")
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        } catch (e: Exception) {
            showFallbackMessage(context, destination)
        }
    }

    private fun showFallbackMessage(context: Context, destination: String) {
        Toast.makeText(context, "내비 앱이 없습니다. 목적지: $destination", Toast.LENGTH_LONG).show()
    }

    private fun isInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
}

data class NaviStatus(
    val kakaoNaviInstalled: Boolean,
    val kakaoMapInstalled: Boolean,
    val tmapInstalled: Boolean = false,
    val googleMapInstalled: Boolean
) {
    fun toMessage(): String {
        val lines = mutableListOf<String>()
        lines.add("카카오내비: ${if (kakaoNaviInstalled) "설치됨" else "없음"}")
        lines.add("카카오맵:  ${if (kakaoMapInstalled) "설치됨" else "없음"}")
        lines.add("티맵:     ${if (tmapInstalled) "설치됨" else "없음"}")
        lines.add("구글맵:    ${if (googleMapInstalled) "설치됨" else "없음"}")
        return lines.joinToString("\n")
    }
}

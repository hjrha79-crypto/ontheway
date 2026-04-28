package com.vita.ontheway

enum class OutputMode {
    FULL,          // TTS + Overlay (확신 높음: 거리+단가 있음)
    OVERLAY_ONLY,  // Overlay만 (confidence 낮음: 거리 null)
    SILENT         // 침묵 (데이터 부족 / DROP)
}

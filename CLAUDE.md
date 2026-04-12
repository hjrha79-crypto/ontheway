# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Vita Platform Context

OnTheWay는 독립 앱이 아니라 Vita Platform의 Agent 중 하나다.

- Vita Platform: 사람의 삶 속 불편을 해결하며 데이터를 축적하는 Life Data Platform
- OnTheWay 역할: 이동/콜 판단 Agent. 콜 선택 행동 데이터를 VitaCore에 축적
- 핵심 철학: 좋은 콜을 고르게 하는 것이 아니라 콜 고르는 행동 자체를 없애는 것
- 데이터 원칙: 모든 추천/선택 결과는 VitaCore MobilityEvent로 저장
- 개발 원칙: MVP 우선, 빠른 테스트 우선, 복잡한 구조 금지

## 현재 개발 단계
Shadow Mode 운영 중 (2주 / 100콜 이상 / 85% 정확도 달성 후 Live Mode 전환)

## Project Overview

OnTheWay is a Korean-language Android app that assists ride-hailing drivers using Claude AI. It integrates with KakaoT (Kakao Mobility) via an Accessibility Service to automatically extract call data, score/recommend calls, and provide a voice-enabled chat interface.

## Build & Run

```bash
# Build debug APK
./gradlew assembleDebug

# Run unit tests
./gradlew test

# Run instrumented tests (requires device/emulator)
./gradlew connectedAndroidTest

# Run a single unit test class
./gradlew test --tests "com.vita.ontheway.ExampleUnitTest"
```

- Gradle KTS with version catalog (`gradle/libs.versions.toml`)
- Compile/Target SDK 36, Min SDK 26, Java 11
- AGP 9.1.0

## Architecture

Single-module app (`com.vita.ontheway`) with no DI framework. Manager classes are Kotlin `object` singletons. No MVVM/MVI — uses a direct procedural flow.

**Core components:**

- **MainActivity** — Chat UI, voice input, call result display. Most UI is built programmatically (not XML).
- **OnTheWayService** — Accessibility service that hooks into `com.kakaomobility.flexer` (KakaoT), parses the accessibility tree to extract call amounts/locations, and can auto-click accept buttons.
- **CallAgent** — Claude Sonnet 4 API integration. Sends conversation history, extracts structured state (location, destination, departure time, deadline) from AI responses. Two prompt modes: "full" and "fast".
- **CallRecommender** — Scoring algorithm: direction score (route matching, 0-10) + efficiency score (amount & pickup distance). Time-aware modes: sparse (≤4 calls), offpeak, standard. Direction weight 50-70%.
- **VoiceManager** — SpeechRecognizer wrapper with continuous listening, Korean locale hardcoded.
- **EarningManager** — Daily earning tracking with vehicle-specific fuel cost estimation. SharedPreferences (`"earning"`).
- **PlaceManager** — Favorite places as JSON array in SharedPreferences (`"ontheway"`).
- **ShadowLog** — Analytics logging of AI recommendations vs. driver actions. Capped at 200 entries in SharedPreferences (`"shadow_log"`).
- **StatsActivity** — Statistics/analytics display screen.
- **Config.kt** — Hardcoded API key (security concern).

**Data flow:** KakaoT accessibility events → OnTheWayService extracts CallData → CallRecommender scores → MainActivity displays results. Separately, user voice/text → CallAgent (Claude API) → state extraction → recommendation context.

## API Integration

- Endpoint: `https://api.anthropic.com/v1/messages` (Claude Sonnet 4)
- Uses `org.json.JSONObject` for request/response parsing (no Retrofit or OkHttp wrapper)
- API key in `Config.kt` — no BuildConfig or secrets management

## UI Notes

- Dark theme with teal accent (`#00F0A0`, background `#0D1525`)
- Mostly programmatic UI construction in Kotlin, minimal XML layouts
- All user-facing text is in Korean

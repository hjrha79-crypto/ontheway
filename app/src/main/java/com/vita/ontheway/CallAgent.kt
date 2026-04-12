package com.vita.ontheway

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

data class AgentState(
    val location: String = "",
    val destination: String = "",
    val departureMinutes: Int = 0,
    val deadline: String = "",
    val isReady: Boolean = false
)

object CallAgent {

    private val systemPromptFull = """
당신은 OnTheWay AI 상담원입니다. 콜 기사가 편하게 말하면 콜 추천에 필요한 정보를 파악합니다.

필수 정보 3가지:
1. 현재 위치
2. 이동 방향 또는 목적지
3. 출발 가능 시간

규칙:
- 반드시 한 번에 하나의 질문만 하세요
- 사용자가 말한 내용에서 이미 파악된 정보는 다시 묻지 마세요
- 짧고 자연스럽게 말하세요. 맞춤법에 맞게 정확하게 쓰세요. (운전 중이니까)
- 3가지 모두 파악되면 즉시 READY로 응답하세요
- 절대로 한 응답에 2개 이상 질문하지 마세요

즐겨찾기 장소가 있으면 활용하세요.

3가지 모두 파악되면:
READY:{"location":"위치","destination":"목적지","departure":"출발시간","deadline":"마감시간"}
""".trimIndent()

    private val systemPromptFast = """
콜 기사 AI. 핵심만.
필수: 위치 / 목적지 / 출발시간
한 번에 하나만 질문. 파악되면 즉시 READY.
응답은 5단어 이내.
READY:{"location":"","destination":"","departure":"","deadline":""}
""".trimIndent()

    suspend fun chat(
        messages: List<Pair<String, String>>,
        userMessage: String,
        savedPlaces: List<String> = emptyList(),
        fastMode: Boolean = false
    ): Pair<String, AgentState?> {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("https://api.anthropic.com/v1/messages")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("x-api-key", Config.ANTHROPIC_API_KEY)
                conn.setRequestProperty("anthropic-version", "2023-06-01")
                conn.doOutput = true
                conn.connectTimeout = 10000
                conn.readTimeout = 30000

                val msgArray = JSONArray()
                messages.forEach { (role, content) ->
                    msgArray.put(JSONObject().apply {
                        put("role", role)
                        put("content", content)
                    })
                }
                msgArray.put(JSONObject().apply {
                    put("role", "user")
                    put("content", userMessage)
                })

                val basePrompt = if (fastMode) systemPromptFast else systemPromptFull
                val placesInfo = if (savedPlaces.isNotEmpty())
                    "\n\n즐겨찾기: ${savedPlaces.joinToString(", ")}" else ""

                val body = JSONObject().apply {
                    put("model", "claude-sonnet-4-20250514")
                    put("max_tokens", 150)
                    put("system", basePrompt + placesInfo)
                    put("messages", msgArray)
                }

                conn.outputStream.write(body.toString().toByteArray())

                val responseCode = conn.responseCode
                if (responseCode != 200) {
                    val errorBody = conn.errorStream?.bufferedReader()?.readText() ?: "unknown"
                    Log.e("CallAgent", "API error $responseCode: $errorBody")
                    val msg = when {
                        errorBody.contains("credit balance is too low") ->
                            "API 크레딧이 부족합니다. 충전 후 다시 시도해주세요."
                        responseCode == 401 -> "API 인증 오류입니다. 설정을 확인해주세요."
                        responseCode == 429 -> "요청이 너무 많습니다. 잠시 후 다시 시도해주세요."
                        responseCode in 500..599 -> "서버 오류입니다. 잠시 후 다시 시도해주세요."
                        else -> "연결 오류 ($responseCode). 다시 시도해주세요."
                    }
                    return@withContext Pair(msg, null)
                }

                val response = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(response)
                var text = json.getJSONArray("content").getJSONObject(0).getString("text").trim()
                if (text.contains("READY:") && !text.startsWith("READY:")) {
                    text = text.substring(text.indexOf("READY:"))
                }

                if (text.startsWith("READY:")) {
                    val data = JSONObject(text.removePrefix("READY:"))
                    val state = AgentState(
                        location = data.optString("location"),
                        destination = data.optString("destination"),
                        departureMinutes = parseDeparture(data.optString("departure")),
                        deadline = data.optString("deadline"),
                        isReady = true
                    )
                    val confirmMsg = if (fastMode)
                        "\u2714 ${data.optString("destination")} ${data.optString("departure")}"
                    else
                        "\uC815\uBCF4 \uD655\uC778! \uCE74\uCE74\uC624T \uD53D\uCEE4\uC5D0\uC11C \uCD5C\uC801 \uCF5C \uCC3E\uACA0\uC2B5\uB2C8\uB2E4 \uD83D\uDE4C"
                    Pair(confirmMsg, state)
                } else {
                    Pair(text, null)
                }
            } catch (e: java.net.UnknownHostException) {
                Log.e("CallAgent", "네트워크 없음", e)
                Pair("인터넷 연결을 확인해주세요.", null)
            } catch (e: java.net.SocketTimeoutException) {
                Log.e("CallAgent", "타임아웃", e)
                Pair("응답 시간이 초과되었습니다. 다시 시도해주세요.", null)
            } catch (e: Exception) {
                Log.e("CallAgent", "API 호출 실패", e)
                Pair("오류가 발생했습니다. 다시 말씀해주세요.", null)
            }
        }
    }

    private fun parseDeparture(text: String): Int {
        return when {
            text.contains("30\uBD84") -> 30
            text.contains("1\uC2DC\uAC04") || text.contains("60\uBD84") -> 60
            text.contains("2\uC2DC\uAC04") -> 120
            text.contains("3\uC2DC\uAC04") -> 180
            text.contains("\uBC14\uB85C") || text.contains("\uC9C0\uAE08") -> 0
            else -> 30
        }
    }
}





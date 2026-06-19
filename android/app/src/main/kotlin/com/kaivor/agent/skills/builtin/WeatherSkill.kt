package com.kaivor.agent.skills.builtin

import com.kaivor.agent.skills.DeliveryMode
import com.kaivor.agent.skills.Skill
import com.kaivor.agent.skills.SkillContext
import com.kaivor.agent.skills.SkillManifest
import com.kaivor.agent.skills.SkillResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

class WeatherSkill : Skill {

    override val manifest = SkillManifest(
        id = "weather",
        name = "Weather",
        version = "1.0.0",
        description = "Get current weather and multi-day forecast for any city - works instantly via API without opening any app",
        author = "kaivor-team",
        trusted = true,
        permissions = emptySet(),
        exampleParamsHint = """{"city":"Delhi","days":3} | {"city":"Mumbai"} | {"city":"Bangalore","days":2}""",
    )

    private val client = SharedHttpClient.instance

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val city = (params["city"] as? String
            ?: params["location"] as? String
            ?: params["place"] as? String
            ?: params["where"] as? String)
            ?.trim()
            ?: return SkillResult.Failure("Which city's weather do you want?")

        val days = (params["days"] as? Number)?.toInt()?.coerceIn(1, 14) ?: 1

        return withContext(Dispatchers.IO) {
            try {
                val encoded = URLEncoder.encode(city, "UTF-8")
                val request = Request.Builder()
                    .url("https://wttr.in/$encoded?format=j1")
                    .header("User-Agent", "Kaivor-Agent/1.0")
                    .header("Accept", "application/json")
                    .build()

                val body = client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext SkillResult.Failure("Weather service unavailable (${response.code}). Check city name.")
                    }
                    response.body?.string()
                        ?: return@withContext SkillResult.Failure("Empty weather response.")
                }

                val json = JSONObject(body)
                val current = json.getJSONArray("current_condition").getJSONObject(0)
                val area = json.getJSONArray("nearest_area").getJSONObject(0)

                val areaName = area.getJSONArray("areaName").getJSONObject(0).getString("value")
                val country = area.getJSONArray("country").getJSONObject(0).getString("value")
                val tempC = current.getString("temp_C")
                val feelsLike = current.getString("FeelsLikeC")
                val humidity = current.getString("humidity")
                val windKmph = current.getString("windspeedKmph")
                val windDir = current.getString("winddir16Point")
                val desc = current.getJSONArray("weatherDesc").getJSONObject(0).getString("value")
                val visibility = current.getString("visibility")
                val uvIndex = current.getString("uvIndex")
                val cloudCover = current.getString("cloudcover")
                val precipitation = current.getString("precipMM")

                val reply = buildString {
                    appendLine("*Weather: $areaName, $country*")
                    appendLine()
                    appendLine("*$desc*")
                    appendLine("*Temperature:* ${tempC} deg C  (feels like ${feelsLike} deg C)")
                    appendLine("*Humidity:* $humidity%")
                    appendLine("*Wind:* $windKmph km/h $windDir")
                    appendLine("*Visibility:* $visibility km")
                    appendLine("*UV Index:* $uvIndex")
                    appendLine("*Cloud Cover:* $cloudCover%")
                    appendLine("*Precipitation:* $precipitation mm")

                    if (days > 1) {
                        val weather = json.getJSONArray("weather")
                        appendLine()
                        appendLine("*${days}-Day Forecast*")
                        for (i in 0 until minOf(days, weather.length())) {
                            val day = weather.getJSONObject(i)
                            val date = day.getString("date")
                            val maxC = day.getString("maxtempC")
                            val minC = day.getString("mintempC")
                            val hourly = day.getJSONArray("hourly")
                            // midday reading
                            val midday = hourly.getJSONObject(minOf(4, hourly.length() - 1))
                            val dayDesc = midday.getJSONArray("weatherDesc").getJSONObject(0).getString("value")
                            val rain = midday.getString("chanceofrain")
                            appendLine("*$date*: $dayDesc | $minC-${maxC} deg C | Rain chance: $rain%")
                        }
                    }
                }

                SkillResult.Success(reply.trim(), delivery = DeliveryMode.LONG_TEXT)
            } catch (e: Exception) {
                SkillResult.Failure("Couldn't get weather for \"$city\": ${e.message}")
            }
        }
    }
}

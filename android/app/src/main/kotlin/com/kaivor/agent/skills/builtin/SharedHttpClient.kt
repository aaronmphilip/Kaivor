package com.kaivor.agent.skills.builtin

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Single shared OkHttpClient for all API-only skills (Weather, Currency, QR, Image, PDF, PPTX).
 *
 * OkHttp docs: "OkHttpClient instances should be shared. Call new OkHttpClient() once, share it
 * everywhere. Each client holds its own connection pool and thread pool. Reusing connections and
 * threads reduces latency and saves memory."
 *
 * Without this, each skill created its own instance - 4+ thread pools running simultaneously
 * for background API calls, wasting ~20 threads and their stack memory.
 */
internal object SharedHttpClient {
    val instance: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Longer timeout variant reused by ImageGeneratorSkill (image gen can take 30-60s). */
    val imageInstance: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()
}

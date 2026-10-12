package com.rokid.context.ai

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** The only place model/embedding/test HTTP clients are built: a redirect could carry an API key to another host. */
object Http {
    fun modelClient(callTimeoutSec: Long = 90): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(callTimeoutSec, TimeUnit.SECONDS)
        .writeTimeout(callTimeoutSec, TimeUnit.SECONDS)
        .callTimeout(callTimeoutSec, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
}

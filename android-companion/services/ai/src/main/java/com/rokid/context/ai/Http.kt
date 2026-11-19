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
        .dns(OfflineOnlyDns.shared) // Offline only: every resolved address must be on the user's own network
        .build()

    /**
     * Keyless model-file downloads (catalog URLs only). Pinned Hugging Face URLs answer with a redirect to their CDN, so
     * redirects are followed, but never from https to http. No call timeout: a slow 37 MB download is not a failure,
     * a stalled one is (read timeout), and the worker resumes it through Range.
     */
    fun downloadClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(false)
        .build()
}

package com.rokid.context.model

import com.rokid.context.ai.ProviderConfigException
import com.rokid.context.model.provider.OpenAiCompatProvider
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiHttpKeyTest {
    @Test fun aKeyIsNeverSentOverPlainHttp() = runTest {
        val server = MockWebServer().apply { start() }
        try {
            val p = OpenAiCompatProvider(server.url("/").toString().trimEnd('/'), "k", "m", style = OpenAiCompatProvider.ApiStyle.OPENAI)
            assertTrue(runCatching { p.generate("q") }.exceptionOrNull() is ProviderConfigException)
            assertEquals(0, server.requestCount)
        } finally { server.shutdown() }
    }
}

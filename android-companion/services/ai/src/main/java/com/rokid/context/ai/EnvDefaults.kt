package com.rokid.context.ai

/**
 * `.env`-derived build defaults. Libraries cannot read the app's BuildConfig, so `ContextApp.onCreate` calls [init]
 * before any provider or worker runs. Initial values equal the Gradle fallbacks in `phone/build.gradle.kts`.
 */
object EnvDefaults {
    @Volatile var azureEndpoint: String = ""; private set
    @Volatile var azureDeployment: String = "gpt-5.4"; private set
    @Volatile var ollamaBaseUrl: String = "http://10.0.2.2:11434"; private set
    @Volatile var ollamaModel: String = "llama3.1:8b"; private set
    @Volatile var localRuntimeMode: String = "native_first"; private set
    @Volatile var localNativeModelPath: String = ""; private set

    fun init(
        azureEndpoint: String,
        azureDeployment: String,
        ollamaBaseUrl: String,
        ollamaModel: String,
        localRuntimeMode: String,
        localNativeModelPath: String,
    ) {
        this.azureEndpoint = azureEndpoint
        this.azureDeployment = azureDeployment
        this.ollamaBaseUrl = ollamaBaseUrl
        this.ollamaModel = ollamaModel
        this.localRuntimeMode = localRuntimeMode
        this.localNativeModelPath = localNativeModelPath
    }
}

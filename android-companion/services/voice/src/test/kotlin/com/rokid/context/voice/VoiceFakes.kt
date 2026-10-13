package com.rokid.context.voice

class FakeSpeechEngine : SpeechEngine {
    inner class Session(val external: Boolean, val listener: SttListener) : SttSession {
        val fed = mutableListOf<ByteArray>()
        var ended = false
        var cancelled = false
        override fun feed(pcm: ByteArray) { fed += pcm }
        override fun end() { ended = true }
        override fun cancel() { cancelled = true }
    }
    val sessions = mutableListOf<Session>()
    val last: Session get() = sessions.last()
    override fun startExternal(localeTag: String, listener: SttListener): SttSession = Session(true, listener).also { sessions += it }
    override fun startPhoneMic(localeTag: String, listener: SttListener): SttSession = Session(false, listener).also { sessions += it }
}

class FakeSpeaker(var available: Boolean = true) : Speaker {
    val spoken = mutableListOf<String>()
    var stops = 0
    private var onDone: (() -> Unit)? = null
    override fun speak(id: String, text: String, onDone: () -> Unit): Boolean {
        if (!available) return false
        spoken += text
        this.onDone = onDone
        return true
    }
    override fun stop() { stops++; finish() }
    fun finish() { onDone?.also { onDone = null }?.invoke() }
}

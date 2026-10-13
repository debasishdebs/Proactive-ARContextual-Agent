package com.rokid.context.voice

class FakeSpeechEngine : SpeechEngine {
    inner class Session(val external: Boolean, val listener: SttListener) : SttSession {
        val fed = mutableListOf<ByteArray>()
        var ended = false
        var cancels = 0
        val cancelled: Boolean get() = cancels > 0
        override fun feed(pcm: ByteArray) { fed += pcm }
        override fun end() { ended = true }
        override fun cancel() { cancels++ }
    }
    /** The next start throws, as a recognizer that can't be created does. */
    var failStart = false
    val sessions = mutableListOf<Session>()
    val last: Session get() = sessions.last()
    override fun startExternal(localeTag: String, listener: SttListener): SttSession = start(true, listener)
    override fun startPhoneMic(localeTag: String, listener: SttListener): SttSession = start(false, listener)
    private fun start(external: Boolean, listener: SttListener): SttSession {
        if (failStart) throw IllegalStateException("recognizer unavailable")
        return Session(external, listener).also { sessions += it }
    }
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

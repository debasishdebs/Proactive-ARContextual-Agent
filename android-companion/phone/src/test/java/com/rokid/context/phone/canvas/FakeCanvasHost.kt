package com.rokid.context.phone.canvas

import com.rokid.context.ai.canvas.Pipeline
import com.rokid.context.canvas.CanvasPublisher
import com.rokid.context.phone.canvas.server.CanvasAuth
import kotlinx.coroutines.flow.MutableStateFlow

/** A [CanvasHost] that records calls; [start] binds a pretend port. */
class FakeCanvasHost : CanvasHost {
    override val status = MutableStateFlow(CanvasStatus())
    override var screenOpen = false
    var outcome: CanvasPublisher.Outcome = CanvasPublisher.Outcome.Blocked(listOf("Critic was changed after the publish."))
    val calls = mutableListOf<String>()
    override fun start(lan: Boolean) { calls += "start:$lan"; status.value = CanvasStatus(on = true, port = 8765, lan = lan, lanIp = "192.168.1.20") }
    override fun stop() { calls += "stop"; status.value = status.value.copy(on = false, port = null) }
    override fun setLan(on: Boolean) { calls += "lan:$on" }
    override fun revokeAll() { calls += "revoke" }
    /** True while the pairing is locked out and waiting for the explicit "New code" (nothing is minted). */
    var lockedOut = false
    override fun pairingCode(): CanvasAuth.Code? {
        if (lockedOut) { calls += "code:locked"; return null }
        return CanvasAuth.Code("654321", Long.MAX_VALUE).also { calls += "code"; status.value = status.value.copy(code = it.code, codeExpiresAt = it.expiresAt) }
    }
    override fun newPairingCode() = CanvasAuth.Code("654321", Long.MAX_VALUE).also {
        calls += "newCode"; lockedOut = false
        status.value = status.value.copy(code = it.code, codeExpiresAt = it.expiresAt, pairLocked = false)
    }
    override fun rotateCertificate() { calls += "rotate" }
    var identity: com.rokid.context.phone.canvas.server.TlsIdentity? = null
    override fun tlsIdentity() = identity
    val endedSessions = mutableListOf<String>()
    override fun endWebViewSession(token: String) { endedSessions += token }
    override fun webViewToken() = "t"
    override fun refreshNetwork() {}
    var idle = false
    override fun shouldIdleStop() = idle
    override fun stopWithNotice(text: String) { calls += "stopNotice"; status.value = CanvasStatus(notice = text) }
    var newest: PublishRow? = PublishRow(7, "prompts", null)
    val rollbackIds = mutableListOf<Long>()
    override suspend fun home() = HomeData(emptyList(), emptyList(), newest != null, newest)
    override suspend fun rollback(expectedId: Long) = outcome.also { rollbackIds += expectedId }
    override suspend fun pipeline(id: String): Pipeline? = null
}

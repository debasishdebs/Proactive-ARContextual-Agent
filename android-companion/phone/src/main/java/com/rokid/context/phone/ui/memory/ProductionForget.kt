package com.rokid.context.phone.ui.memory

import com.rokid.context.memory.forget.ForgetScope
import com.rokid.context.memory.forget.ForgetScopeSetting
import com.rokid.context.memory.forget.ForgetTarget
import com.rokid.context.orchestration.forget.ForgetActions
import com.rokid.context.phone.hub.HubGraph

/** The production Forget port (the previewer uses it): everything goes through [ForgetActions] at the Forget scope setting. */
fun productionMemoryForget(app: android.content.Context): MemoryForget = object : MemoryForget {
            // The hub refresh makes the next StateFrame drop the memory from `today`.
            private val onForgotten = { HubGraph.get(app).refresh() }
            // v0.9.7 slice C: both go through the Forget scope (Settings > Capture > Forget scope); the dialog passes the scope it showed.
            override suspend fun forgetFact(memoryId: String) = done(ForgetActions.forgetScopedOutcome(app, ForgetTarget.Fact(memoryId), onForgotten = onForgotten))
            override suspend fun forgetMoment(frameId: String) = done(ForgetActions.forgetScopedOutcome(app, ForgetTarget.Photo(frameId), onForgotten = onForgotten))
            override suspend fun forgetObject(kind: ForgetKind, id: String): Boolean {
                val target = when (kind) { ForgetKind.MARK -> ForgetTarget.Mark(id); else -> return false }
                return done(ForgetActions.forgetScopedOutcome(app, target, onForgotten = onForgotten, undoMark = com.rokid.context.phone.canvas.MarkGraph.recorder(app)::undoMark))
            }
            override fun activeScope() = ForgetScopeSetting.get()
            private fun targetOf(kind: ForgetKind, memoryId: String, frameId: String?): ForgetTarget? = when (kind) { ForgetKind.FACT -> ForgetTarget.Fact(memoryId); ForgetKind.MOMENT -> frameId?.let { ForgetTarget.Photo(it) }; else -> null }
            override suspend fun preview(kind: ForgetKind, memoryId: String, frameId: String?): ForgetPreview? {
                val target = targetOf(kind, memoryId, frameId) ?: return null
                val scope = ForgetScopeSetting.get() // read once: the counts and the delete name this scope
                val p = ForgetActions.previewScoped(app, target, scope)
                return ForgetPreview(scope, p.effective, p.photos, p.memories, p.marks, p.moments, p.scenesRemoved, p.scenesShrunk, p.planHash, p.outside, p.tooLarge, p.peerResults, p.needsAck, p.ackHash, pins = p.pins, pinNames = p.pinNames)
            }
            override suspend fun forgetScoped(kind: ForgetKind, memoryId: String, frameId: String?, scope: ForgetScope, planHash: String?): Boolean {
                val target = targetOf(kind, memoryId, frameId) ?: return false
                return done(ForgetActions.forgetScopedOutcome(app, target, scope, planHash, onForgotten = onForgotten))
            }
            /** The typed outcome: a blocked Forget becomes the typed exception the view model names to the user (an export, a Canvas save, or a cached export that could not be deleted). */
            private fun done(o: ForgetActions.Outcome): Boolean = when (o) {
                ForgetActions.Outcome.DONE -> true
                ForgetActions.Outcome.EXPORT_IN_PROGRESS -> throw com.rokid.context.orchestration.DataOpGate.ExportInProgress()
                ForgetActions.Outcome.SAVE_IN_PROGRESS -> throw com.rokid.context.orchestration.DataOpGate.WriteInProgress()
                ForgetActions.Outcome.CACHE_CLEANUP_FAILED -> throw CacheCleanupFailed()
                ForgetActions.Outcome.CONTENT_CHANGED -> throw ContentChanged()
                ForgetActions.Outcome.TOO_LARGE -> throw TooLarge()
                ForgetActions.Outcome.PIN_PREP_FAILED -> throw PinPrepFailed()
                ForgetActions.Outcome.PIN_CLEANUP_PENDING -> throw PinCleanupPending()
                ForgetActions.Outcome.PINS_NOT_CONFIRMED -> throw PinsNotConfirmed()
                ForgetActions.Outcome.FAILED -> false
            }
        }

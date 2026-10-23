package com.rokid.context.phone.canvas.helper

import android.content.Context
import com.rokid.context.canvas.helper.CanvasLogSink
import com.rokid.context.canvas.helper.DaoHelperJobStore
import com.rokid.context.canvas.helper.HelperJobPort
import com.rokid.context.canvas.helper.HelperJobService
import com.rokid.context.canvas.helper.HelperJobStore
import com.rokid.context.canvas.helper.InMemoryReadinessStore
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.canvas.HelperDeletion
import com.rokid.context.memory.canvas.HelperFenceLive
import com.rokid.context.memory.canvas.HelperSessionHooks
import com.rokid.context.memory.db.DeletionEpochStore
import com.rokid.context.orchestration.MemoryWipe
import com.rokid.context.phone.canvas.server.CanvasAuth
import com.rokid.context.phone.canvas.server.HelperAuth
import com.rokid.context.phone.log.PhoneLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import java.security.SecureRandom

/**
 * The native helper actions with the session lifecycle bound in (core plan WP6): the card's "Accept pairing" and a grant-on START the dev session; a grant-off voids the open
 * jobs (WP4 `grantOff`: also one awaiting its tap) with their cleanup; Disconnect ends the HELPER session (grant off, token and key revoked, jobs voided) without stopping the canvas server. The wrapped
 * [HelperNative] does the credential work. These calls read and commit preferences and touch the database: the view model calls them off the main thread.
 */
class BoundHelperNative(
    private val inner: HelperNativeActions,
    private val session: HelperDevSession,
    private val ender: HelperJobEnder,
) : HelperNativeActions {
    override fun state(): HelperCardState = inner.state()

    override fun acceptPairing() {
        session.start()
        inner.acceptPairing()
    }

    override fun setGrant(grant: String, on: Boolean) {
        if (grant !in HelperGrants.KNOWN) return
        if (on) session.start()
        inner.setGrant(grant, on)
        if (!on) runBlocking { ender.grantOff() }
    }

    override fun disconnectHelper() {
        inner.disconnectHelper()
        session.endHelperSession() // the helper session only: the canvas server stays up while the user is on the screen
    }
}

/**
 * Everything of the laptop helper on the phone, built ONCE (by [CanvasGraph] in production, by the tests through [create]): grants, auth, the memory-only confirmation key,
 * the job service over the fenced DAO, the confirm adapter, the helper routes under `/api/helper/`, the browser status routes, the dev session with its Wi-Fi observer, and the native
 * actions bound to that session. Nothing here is reachable from HTTP except [routes] (helper token) and [browserRoutes] (browser token).
 */
class HelperBackend(
    val auth: HelperAuth,
    val grants: HelperGrants,
    val confirm: HelperConfirm,
    val service: HelperJobService,
    val store: HelperJobStore,
    val adapter: HelperConfirmAdapter,
    val routes: HelperRoutes,
    val browserRoutes: HelperBrowserRoutes,
    val session: HelperDevSession,
    val native: HelperNativeActions,
    val hooks: HelperSessionHooks,
) {
    companion object {
        /**
         * The composition. [log] is the ONE call-side logger every consumer gets (production: the gated core port `PhoneLogging.canvasPort()`); [portWrapper] lets a test
         * observe the port the adapter and the runner use.
         */
        fun create(
            store: HelperJobStore,
            grantStore: GrantStore,
            enderFactory: (HelperSessionHooks) -> HelperJobEnder,
            clock: () -> Long,
            scope: CoroutineScope,
            network: NetworkWatch,
            userActivityMs: () -> Long,
            screenOpen: () -> Boolean,
            stopServer: () -> Unit,
            log: CanvasLogSink = PhoneLogging.canvasPort(),
            random: SecureRandom = SecureRandom(),
            inactivityMs: Long = HelperDevSession.INACTIVITY_MS,
            checkEveryMs: Long = HelperDevSession.CHECK_EVERY_MS,
            portWrapper: (HelperJobPort) -> HelperJobPort = { it },
        ): HelperBackend {
            val grants = HelperGrants(grantStore)
            val auth = HelperAuth(clock, random, log, grantOn = grants::isOn)
            val confirm = HelperConfirm(random, log)
            val hooks = PhoneSessionHooks(grants, revoke = auth::revoke, rotateKey = confirm::rotate)
            val ender = enderFactory(hooks)
            val service = HelperJobService(store, clock, sessionGen = auth::sessionGen, readiness = InMemoryReadinessStore(), log = log)
            val port = portWrapper(service)
            val adapter = HelperConfirmAdapter(port, confirm, scope, clock, grantOn = { grants.isOn(HelperGrants.PY_EXEC) }, log = log)
            val session = HelperDevSession(
                clock, scope, ender, hooks, network, userActivityMs, screenOpen, stopServer,
                onStart = confirm::rotate, onEnded = adapter::clear, inactivityMs = inactivityMs, checkEveryMs = checkEveryMs, log = log,
            )
            val native = BoundHelperNative(HelperNative(auth, grants, clock, log), session, ender)
            val routes = HelperRoutes(auth, service, store, grants::isOn, clock, log)
            val browser = HelperBrowserRoutes(auth, service, grants::isOn)
            return HelperBackend(auth, grants, confirm, service, store, adapter, routes, browser, session, native, hooks)
        }

        /**
         * The production composition for [CanvasGraph]: the fenced job DAO of the app database with the real wipe generation and deletion epoch readers, WP4's `HelperDeletion`
         * as the job half of a session end, and the session-owned Wi-Fi observer. [browserAuth] supplies the user-activity clock (only authenticated BROWSER requests move it).
         */
        fun forApp(app: Context, browserAuth: CanvasAuth, scope: CoroutineScope, screenOpen: () -> Boolean, stopServer: () -> Unit, log: CanvasLogSink): HelperBackend {
            val dao = MemoryGraph.get(app).db.canvasSlice3Dao()
            val epoch = DeletionEpochStore.of(app)
            val live = HelperFenceLive({ MemoryWipe.generation.get() }, { epoch.read() })
            return create(
                store = DaoHelperJobStore(dao, live),
                grantStore = PrefsGrantStore(app),
                enderFactory = { hooks -> DeletionJobEnder(HelperDeletion(dao, hooks)) },
                clock = System::currentTimeMillis, scope = scope, network = ConnectivityNetworkWatch(app),
                userActivityMs = { browserAuth.lastActivityMs }, screenOpen = screenOpen, stopServer = stopServer, log = log,
            )
        }
    }
}

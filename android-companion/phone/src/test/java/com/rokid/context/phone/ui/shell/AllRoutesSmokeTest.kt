package com.rokid.context.phone.ui.shell

import android.app.Application
import android.content.Context
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.Capability
import com.rokid.context.ai.Endpoint
import com.rokid.context.ai.EndpointKind
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.orchestration.AskOutcome
import com.rokid.context.phone.ui.FakePipelineControl
import com.rokid.context.phone.ui.Fixtures
import com.rokid.context.phone.ui.glasses.FakeGlassesStatusSource
import com.rokid.context.phone.ui.home.AskService
import com.rokid.context.phone.ui.onboarding.OnboardingStore
import com.rokid.context.phone.ui.settings.DiagnosticsActions
import com.rokid.context.phone.ui.settings.QueueActions
import com.rokid.context.phone.ui.settings.TestSettings
import com.rokid.context.ui.queues.QueueSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.lang.reflect.Modifier

/**
 * R-73 (spec section 6.4): every route in [Routes] renders in the real [AppShell] NavHost over an in-memory DB, with
 * seeded fixtures, without throwing and without raw error text (spec section 6.3). Routes are reflected from the
 * `const val` Strings of [Routes], so a new route is covered (or fails for want of a fixture) without editing this test.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class AllRoutesSmokeTest {
    @get:Rule val compose = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var deps: PhoneDeps
    private lateinit var nav: NavHostController

    private fun deps() = PhoneDeps(
        memory = MemoryGraph.get(ctx), glasses = FakeGlassesStatusSource(), pipeline = FakePipelineControl(), onboarding = OnboardingStore(ctx),
        askService = AskService { AskOutcome.Failed("offline in tests") }, rateAsk = { _, _ -> true },
        diagnostics = object : DiagnosticsActions {
            override suspend fun summarizeNow() = "ok"; override suspend fun captureNow() = "ok"; override suspend fun reprocessOldPhotos() = "ok"
        },
        queues = object : QueueActions {
            override suspend fun snapshot() = QueueSnapshot(0, 0, 0, 0, 0, 24, 0, 0, 0, "none")
            override suspend fun queueExplicitWrite(text: String) = "ok"; override suspend fun queueReadContext(query: String) = "ok"; override suspend fun drainNow() = "ok"
        },
        settings = TestSettings.deps(ctx),
        storagePct = flowOf(10), clock = flowOf(Fixtures.NOW), zone = { Fixtures.ZONE },
    )

    @Before fun setUp() {
        // The canvas routes exist only in Developer mode, and run over a fake host (no server, no AI graph).
        com.rokid.context.phone.canvas.DeveloperModeStore.get(ctx).set(true)
        com.rokid.context.phone.canvas.CanvasGraph.installForTests(com.rokid.context.phone.canvas.FakeCanvasHost())
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        deps = deps()
    }

    // As AppShellTest: ViewModels are cleared after @After, so the in-memory DB is left open (GC'd), not closed under collectors.
    @After fun tearDown() {
        com.rokid.context.phone.canvas.CanvasGraph.installForTests(null)
        com.rokid.context.phone.canvas.DeveloperModeStore.get(ctx).set(false)
        MemoryGraph.resetForTests(null)
    }

    /** One row of every kind a `{id}`/`{start}` route opens, all on [today]. */
    private fun seed() = runBlocking {
        val mem = MemoryGraph.get(ctx)
        val today = deps.today()
        mem.frames.add(Fixtures.frame(FRAME_ID, Fixtures.at(today, 9)))
        mem.memories.add(Fixtures.memory(MEMORY_ID, "Keys on the hallway shelf", Fixtures.at(today, 9, 1), frameId = FRAME_ID, tags = "keys"))
        mem.memories.add(Fixtures.memory("mem_review", "Maybe a cat on the sofa", Fixtures.at(today, 9, 2), confidence = 0.3, review = ReviewState.NEEDS_REVIEW))
        mem.asks.upsert(Fixtures.ask(ASK_ID, Fixtures.at(today, 9, 5)))
        mem.nudges.addIgnore(Fixtures.nudge(NUDGE_ID, Fixtures.at(today, 9, 6)))
        mem.proposals.addIgnore(Fixtures.proposal("prop_fx", ASK_ID))
        deps.settings.ai.endpoints.save(
            Endpoint(ENDPOINT_ID, "Ollama", EndpointKind.OLLAMA, "http://localhost:11434", null, listOf("llama3"), setOf(Capability.CHAT), 2), null,
        ).getOrThrow()
    }

    private fun texts(n: SemanticsNode): List<String> = buildList {
        n.config.getOrNull(SemanticsProperties.Text)?.forEach { add(it.text) }
        n.config.getOrNull(SemanticsProperties.EditableText)?.let { add(it.text) }
        n.config.getOrNull(SemanticsProperties.ContentDescription)?.let { addAll(it) }
        n.children.forEach { addAll(texts(it)) }
    }

    private fun nodeCount(n: SemanticsNode): Int = 1 + n.children.sumOf(::nodeCount)

    private fun roots() = compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes()

    /** Opens [pattern] alone on the back stack and returns what is wrong with it, or null. */
    private fun check(pattern: String, route: String? = fill(pattern)): String? {
        route ?: return "no fixture for a placeholder"
        compose.runOnIdle { nav.navigate(route) { popUpTo(nav.graph.id) { inclusive = false } } }
        compose.waitUntil(5_000) { nav.currentDestination?.route == pattern }
        // Room emits off Main: poll until the visible text has been stable for three polls (or 3 s), checking every snapshot.
        var last: List<String>? = null
        var stable = 0
        val deadline = System.currentTimeMillis() + 3_000
        while (stable < 3 && System.currentTimeMillis() < deadline) {
            compose.waitForIdle()
            val shown = roots().flatMap(::texts)
            shown.firstOrNull { RAW_ERROR.containsMatchIn(it) }?.let { return "raw error text \"$it\"" }
            if (shown == last) stable++ else { stable = 0; last = shown }
            Thread.sleep(50)
        }
        if (roots().sumOf(::nodeCount) <= 1) return "rendered no node"
        return null
    }

    @Test fun everyRoutePlaceholderHasAFixture() {
        val missing = unresolved(routes())
        assertTrue("routes with a placeholder that has no fixture:\n" + missing.joinToString("\n"), missing.isEmpty())
    }

    /** The previewer is one route for every kind: each kind's seeded item renders through it (the memory is the generic route's fixture). */
    @Test fun everyKindOfItemRendersInThePreviewer() {
        seed()
        compose.setContent { nav = rememberNavController(); PcTheme { AppShell(deps, Routes.HOME, nav, MutableStateFlow(false)) } }
        compose.waitForIdle()
        val failures = listOf("frame:$FRAME_ID", "ask:$ASK_ID", "nudge:$NUDGE_ID", "ask:missing", "nudge:missing", "frame:missing").mapNotNull { ref -> check(Routes.PREVIEW, Routes.preview(ref))?.let { "$ref: $it" } }
        assertTrue("previews failed the smoke check:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun everyRouteRendersWithoutCrashOrRawErrorText() {
        val all = routes()
        assertTrue("reflected only ${all.size} routes", all.size >= 30)
        seed()
        compose.setContent { nav = rememberNavController(); PcTheme { AppShell(deps, Routes.HOME, nav, MutableStateFlow(false)) } }
        compose.waitForIdle()
        val failures = mutableListOf<String>()
        for (pattern in all.filterNot { it.value in KNOWN_BROKEN }.values + all.filter { it.value in KNOWN_BROKEN }.values) {
            val problem = try { check(pattern) } catch (t: Throwable) {
                "threw ${t.javaClass.name}: ${t.message?.lineSequence()?.firstOrNull()} at ${t.stackTrace.take(3).joinToString(" < ")}"
            }
            val known = KNOWN_BROKEN[pattern]
            when {
                problem != null && known == null -> failures += "$pattern: $problem"
                problem == null && known != null -> failures += "$pattern: renders cleanly now, remove it from KNOWN_BROKEN ($known)"
            }
        }
        assertTrue("routes failed the smoke check:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    private companion object {
        const val ASK_ID = "ask_fx"
        const val MEMORY_ID = "mem_fx"
        const val NUDGE_ID = "nudge_fx"
        const val FRAME_ID = "frame_fx"
        const val ENDPOINT_ID = "ep_fx"
        /** A built-in prompt id (prompts are keyed by role). */
        const val PROMPT_ID = "ANSWER"

        /** Spec section 6.3: no stack-trace or class-name text reaches the user. */
        val RAW_ERROR = Regex("Exception|java\\.|kotlin\\.|at com\\.rokid")
        val PLACEHOLDER = Regex("\\{([A-Za-z]+)\\}")

        /** `const val` Strings of [Routes] that are not routes, with the reason. */
        val NOT_ROUTES: Map<String, String> = mapOf("NEW_ENDPOINT" to "the `{id}` value that opens an empty endpoint editor, not a route")

        /** Routes known to fail the smoke check (each with its symptom). A listed route that passes fails the test. */
        val KNOWN_BROKEN: Map<String, String> = emptyMap()

        /** The seeded row each `{id}` route opens. */
        val ID_FOR_ROUTE: Map<String, String> = mapOf(
            Routes.SETTINGS_ENDPOINT to ENDPOINT_ID,
            Routes.SETTINGS_PROMPT to PROMPT_ID, Routes.SETTINGS_PROMPT_HISTORY to PROMPT_ID, Routes.SETTINGS_CANVAS_PIPELINE to "live-extract",
        )

        /** Reflected `const val` name to route pattern. */
        fun routes(): Map<String, String> = Routes::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && Modifier.isFinal(it.modifiers) && Modifier.isPublic(it.modifiers) && it.type == String::class.java }
            .filterNot { it.name in NOT_ROUTES }
            .associate { it.name to it.get(null) as String }

        fun value(pattern: String, token: String): String? = when (token) {
            "id" -> ID_FOR_ROUTE[pattern]
            "date" -> java.time.LocalDate.now(Fixtures.ZONE).toString() // PhoneDeps.today()
            "at" -> "-1" // the day view's optional scroll time (none)
            "period" -> "week"
            "ref" -> "memory:$MEMORY_ID" // the previewer
            "kind" -> "frames" // the common list of an Activity kind
            "ids" -> "capture,ask,nudge"
            "scope" -> "MONTH:${java.time.LocalDate.now(Fixtures.ZONE)}" // a period list: resolved by the list itself
            "q" -> "keys" // Find a photo: a non-blank q searches once on open (PhotoSearchDeps.None: no photos)
            else -> null
        }

        fun fill(pattern: String): String? {
            var out = pattern
            for (m in PLACEHOLDER.findAll(pattern)) out = out.replace(m.value, value(pattern, m.groupValues[1]) ?: return null)
            return out
        }

        fun unresolved(routes: Map<String, String>): List<String> = routes.flatMap { (name, pattern) ->
            PLACEHOLDER.findAll(pattern).filter { value(pattern, it.groupValues[1]) == null }.map { "$name ($pattern): ${it.value}" }.toList()
        }
    }
}

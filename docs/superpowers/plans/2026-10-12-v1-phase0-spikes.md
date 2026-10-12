# Phase 0 — Device Spikes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Measure, on the user's real phone + Rokid glasses, the facts Phases 3 and 6 depend on: the glasses→phone CXR text limit and throughput for chunked Base64 JPEGs, ack/interrupt/backlog behaviour, PTT audio latency during a frame flush, whether the glasses can send while backgrounded, the TTS audio route, and on-device STT with external audio.

**Architecture:** A throwaway, standalone Gradle project `spikes/cxr-probe/` (its own settings, not part of `android-companion`) with two tiny apps: `phone-probe` (CXR-L session + receivers + TTS/STT probes) and `glasses-probe` (CXR-S bridge + senders). Plain Android Views (no Compose) to keep code small. Spike code lives only in a **separate git worktree** at `/Users/debasishkanhar/Desktop/rokid/pca-spike` on branch `spike/cxr-probe`, created from the baseline commit, and is never merged. The main checkout never switches branches, so Phase 1 can run there in parallel. Only the **results document** is committed, in the main checkout on `feat/proactive-mvp-implementation`.

**Tech Stack:** AGP 8.13.2, Kotlin 2.0.21, Gradle 8.13 (copied from Live Fit), `com.rokid.cxr:client-l:1.1.2`, `com.rokid.cxr:cxr-service-bridge:1.5`.

**Spec:** `docs/superpowers/specs/2026-10-12-v1-redesign-design.md` §1.5, §1.8, §3.6, §5.1.1, §6.5 (phase 0), R-76, R-67. Master plan: `docs/superpowers/plans/2026-10-12-v1-master-plan.md`.

## Global Constraints

- **Prerequisite:** Phase 1 Task 0 (baseline commit) is done; its SHA is `BASELINE`.
- Throwaway code: no tests, no production quality bar, never merged. Only `docs/spikes/2026-10-12-phase0-results.md` survives.
- All spike paths below are relative to the worktree `$SPIKE=/Users/debasishkanhar/Desktop/rokid/pca-spike`. Never run `git switch`/`git checkout` in the main checkout.
- Frame transfer follows the spec §1.8 contract exactly: the phone sends a **frame-level** `probe_frame_ack {frameId, ok, reason}` only after reassembly, CRC check and Base64 decode; on `ok=true` the glasses delete the frame; on a NACK they resend once and drop the frame after a second NACK; on a timeout they retry from chunk 0 up to 3 attempts, then keep it (and the rest of the backlog) for the next flush.
- Glasses side sends with `CXRServiceBridge.sendMessage(channel, Caps)` — text only (Base64 for binary), per Live Fit's verified `lf_audio` path.
- Device steps need the user: phone with **Hi Rokid** installed and paired to the glasses; glasses connected by USB with adb enabled. Ask the user before every physical action (toggle Bluetooth, take glasses off, etc.).
- `adb` binary: `/Users/debasishkanhar/Desktop/rokid/android-sdk/platform-tools/adb`. Use `-s <serial>` for every command (two devices attached).

## Review Focus

1. **Glasses app backgrounded or display asleep** — expected: we learn whether a glasses foreground service can still `sendMessage`; Phase 3's capture design depends on it (Task 3).
2. **Bluetooth drop mid-chunk** — expected: sender sees failed/timeout acks, resends from chunk 0 after reconnect, phone dedupes by `frameId` (Task 2).
3. **Recognizer silently using the phone mic** — expected: the probe distinguishes injected audio from mic audio by transcript and by pipe back-pressure (Task 4).
4. **A Caps string over the hidden size limit** — expected: we record the size where messages start failing or arriving truncated (rc ≠ 0, missing, or CRC mismatch) (Task 2).
5. **Two phones/Hi Rokid package variants (global vs China)** — expected: probe uses Live Fit's `preferGlobalHiRokid` quirk so authorization binds the installed variant (Task 1).

---

### Task 1: Probe project scaffold + link round trip

**Files:**
- Create: `spikes/cxr-probe/settings.gradle.kts`, `spikes/cxr-probe/build.gradle.kts`, `spikes/cxr-probe/gradle.properties`
- Create: `spikes/cxr-probe/gradle/wrapper/*`, `spikes/cxr-probe/gradlew` (copied)
- Create: `spikes/cxr-probe/phone-probe/build.gradle.kts`, `.../src/main/AndroidManifest.xml`, `.../src/main/java/com/rokid/context/probe/{MainActivity.kt,ProbeLink.kt}`
- Create: `spikes/cxr-probe/glasses-probe/build.gradle.kts`, `.../src/main/AndroidManifest.xml`, `.../src/main/java/com/rokid/context/probe/glasses/MainActivity.kt`

**Requirements:** R-76

**Interfaces:**
- Produces (probe-only, Tasks 2–4): channels `probe_cmd` (P→G), `probe_chunk_ack` and `probe_frame_ack` (P→G), `probe_data` / `probe_chunk` / `probe_audio` / `probe_hb` / `probe_log` (G→P). `ProbeLink.send(channel: String, text: String): Boolean`, `ProbeLink.onMessage: (channel: String, parts: List<String>) -> Unit`. Glasses: `Probe.send(channel: String, vararg parts: String): Int` (CXR rc, 0 = sent).

- [ ] **Step 1: Branch and scaffold**

```bash
cd /Users/debasishkanhar/Desktop/rokid/proactive-context-agent
git worktree add /Users/debasishkanhar/Desktop/rokid/pca-spike -b spike/cxr-probe BASELINE
SPIKE=/Users/debasishkanhar/Desktop/rokid/pca-spike
mkdir -p $SPIKE/spikes/cxr-probe && cd $SPIKE/spikes/cxr-probe
cp -R /Users/debasishkanhar/Desktop/rokid/live-fitness-tracker/gradle .
cp /Users/debasishkanhar/Desktop/rokid/live-fitness-tracker/gradlew .
rm -f gradle/libs.versions.toml
echo "sdk.dir=/Users/debasishkanhar/Desktop/rokid/android-sdk" > local.properties
```

`settings.gradle.kts`:
```kotlin
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.rokid.com/repository/maven-public/") }
        google(); mavenCentral()
    }
}
rootProject.name = "cxr-probe"
include(":phone-probe", ":glasses-probe")
```

`build.gradle.kts`:
```kotlin
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}
```

`gradle.properties`:
```properties
android.useAndroidX=true
org.gradle.jvmargs=-Xmx3g
```

- [ ] **Step 2: Phone probe build + manifest**

`phone-probe/build.gradle.kts`:
```kotlin
plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.rokid.context.probe"
    compileSdk = 36
    defaultConfig { applicationId = "com.rokid.context.probe"; minSdk = 29; targetSdk = 35; versionCode = 1; versionName = "0.0.1" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("com.rokid.cxr:client-l:1.1.2")
    implementation("com.rokid.cxr:cxr-service-bridge:1.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
```

`phone-probe/src/main/AndroidManifest.xml`:
```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <application android:label="CXR Probe" android:theme="@android:style/Theme.Material.Light.NoActionBar">
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

- [ ] **Step 3: Phone `ProbeLink.kt`** (auth + session, adapted from Live Fit `CxrGlassesLink.openSession`/`authorize`/`preferGlobalHiRokid`)

```kotlin
package com.rokid.context.probe

import android.app.Activity
import android.content.Context
import android.util.Log
import com.rokid.cxr.Caps
import com.rokid.cxr.session.AiInterceptMode
import com.rokid.cxr.session.CloseReason
import com.rokid.cxr.session.CxrSession
import com.rokid.cxr.session.CxrSessionManager
import com.rokid.cxr.session.GlassPermission
import com.rokid.cxr.session.ICustomCmdSessionCallback
import com.rokid.cxr.session.ISessionLifecycleCbk
import com.rokid.cxr.session.PausedReason
import com.rokid.cxr.session.SessionConfig
import com.rokid.cxr.session.SessionErrorCode
import com.rokid.cxr.session.SessionTimeouts
import com.rokid.cxr.session.SessionType
import com.rokid.cxr.session.TerminatingReason

class ProbeLink(private val app: Context, private val log: (String) -> Unit) {
    private val manager = CxrSessionManager.getInstance(app)
    private var session: CxrSession? = null
    var onMessage: (channel: String, parts: List<String>) -> Unit = { _, _ -> }

    fun authorize(activity: Activity) {
        preferGlobalHiRokid()
        manager.requestAuthorization(activity, listOf(GlassPermission.MICROPHONE, GlassPermission.DEVICE_MANAGE, GlassPermission.MEDIA)) { r ->
            log("auth ok=${r.isSuccess} code=${r.errorCode} token=${r.token != null}")
            r.token?.takeIf { r.isSuccess }?.let { app.getSharedPreferences("probe", 0).edit().putString("token", it).apply() }
        }
    }

    fun connect() {
        val token = app.getSharedPreferences("probe", 0).getString("token", null) ?: return log("no token: authorize first")
        preferGlobalHiRokid()
        session?.close()
        val s = manager.create(SessionConfig(
            sessionType = SessionType.CUSTOM_APP,
            glassesPackageName = GLASSES_PKG,
            aiInterceptMode = AiInterceptMode.ALLOW_WITH_PAUSE,
            terminatingGracePeriodMs = 5_000L,
            timeouts = SessionTimeouts(),
            viewData = "",
            viewIconData = "",
            glassesActivityName = GLASSES_ACTIVITY,
            glassesApkPath = "",
        ))
        s.addLifecycleCallback(object : ISessionLifecycleCbk {
            override fun onSessionStarted() = log("session started")
            override fun onSessionPaused(reason: PausedReason) = log("paused $reason")
            override fun onSessionResumed() = log("resumed")
            override fun onSessionTerminating(reason: TerminatingReason, graceMs: Long) = log("terminating $reason")
            override fun onSessionClosed(reason: CloseReason) = log("closed $reason")
            override fun onConnectResult(ok: Boolean, code: SessionErrorCode?) = log("connect ok=$ok code=$code")
        })
        s.addCustomCmdCallback(object : ICustomCmdSessionCallback {
            override fun onCustomCmdResult(cmd: String, bytes: ByteArray?) {
                val parts = runCatching {
                    val caps = Caps.fromBytes(bytes ?: ByteArray(0))
                    (0 until 8).mapNotNull { i -> runCatching { caps.at(i).string }.getOrNull() }
                }.getOrElse { log("bad caps on $cmd: ${it.javaClass.simpleName}"); emptyList() }
                onMessage(cmd, parts)
            }
        })
        session = s
        s.connect(token)
    }

    fun send(channel: String, text: String): Boolean {
        val s = session ?: return false.also { log("send: no session") }
        val r = s.sendCustomCmd(channel, Caps().apply { write(text) }, ByteArray(0))
        return r.toString().let { it == "0" || it.equals("true", true) || it.contains("SUCCESS", true) }.also { if (!it) log("send $channel r=$r") }
    }

    private fun preferGlobalHiRokid() {
        val global = runCatching { app.packageManager.getPackageInfo("com.rokid.sprite.global.aiapp", 0) }.isSuccess
        if (!global) return
        runCatching {
            Class.forName("com.rokid.sprite.aiapp.externalapp.auth.AuthorizationHelper")
                .getDeclaredField("a").apply { isAccessible = true }.setBoolean(null, true)
        }.onFailure { Log.w("Probe", "global quirk failed", it) }
    }

    companion object {
        const val GLASSES_PKG = "com.rokid.context.probe.glasses"
        const val GLASSES_ACTIVITY = "com.rokid.context.probe.glasses.MainActivity"
    }
}
```

> If `sendCustomCmd`'s return type differs, log it and adapt `send()` — note the actual type in the results doc.

- [ ] **Step 4: Phone `MainActivity.kt`** (buttons + log; Tasks 2–4 add handlers to `onMessage`)

```kotlin
package com.rokid.context.probe

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    lateinit var link: ProbeLink
    private lateinit var out: TextView
    private val buttons = mutableListOf<Pair<String, () -> Unit>>()

    fun log(s: String) { Log.i("Probe", s); runOnUiThread { out.append("$s\n") } }
    fun button(label: String, action: () -> Unit) { buttons += label to action }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        out = TextView(this).apply { textSize = 11f }
        link = ProbeLink(applicationContext, ::log)
        link.onMessage = { ch, parts -> Handlers.onMessage(this, ch, parts) }
        button("Authorize") { link.authorize(this) }
        button("Connect") { link.connect() }
        button("Ping") { link.send("probe_cmd", """{"t":"ping","at":${System.currentTimeMillis()}}""") }
        Handlers.registerButtons(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        buttons.forEach { (l, a) -> col.addView(Button(this).apply { text = l; setOnClickListener { a() } }) }
        col.addView(out)
        setContentView(ScrollView(this).apply { addView(col) })
    }
}

object Handlers {
    fun registerButtons(a: MainActivity) {}
    fun onMessage(a: MainActivity, channel: String, parts: List<String>) {
        when (channel) {
            "probe_log" -> a.log("G: ${parts.firstOrNull()}")
            else -> a.log("rx $channel parts=${parts.size} len=${parts.map { it.length }}")
        }
    }
}
```

- [ ] **Step 5: Glasses probe build + manifest**

`glasses-probe/build.gradle.kts`:
```kotlin
plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.rokid.context.probe.glasses"
    compileSdk = 36
    defaultConfig { applicationId = "com.rokid.context.probe.glasses"; minSdk = 28; targetSdk = 32; versionCode = 1; versionName = "0.0.1" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    lint { disable += "ExpiredTargetSdkVersion" }
}
dependencies { implementation("com.rokid.cxr:cxr-service-bridge:1.5") }
```

`glasses-probe/src/main/AndroidManifest.xml`:
```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <application android:label="CXR Probe G">
        <activity android:name=".MainActivity" android:exported="true" android:screenOrientation="portrait">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
        <service android:name=".HeartbeatService" android:exported="false" />
    </application>
</manifest>
```

- [ ] **Step 6: Glasses `MainActivity.kt`** (Tasks 2–4 extend `Tests.run`)

```kotlin
package com.rokid.context.probe.glasses

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import com.rokid.cxr.CXRServiceBridge
import com.rokid.cxr.Caps
import org.json.JSONObject
import kotlin.concurrent.thread

object Probe {
    lateinit var bridge: CXRServiceBridge
    fun send(channel: String, vararg parts: String): Int =
        bridge.sendMessage(channel, Caps().apply { parts.forEach { write(it) } })
    fun log(s: String) { Log.i("ProbeG", s); send("probe_log", s) }
}

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "CXR probe (glasses)"; textSize = 24f; setTextColor(0xFF3CFF6E.toInt()) })
        Probe.bridge = CXRServiceBridge(this)
        Probe.bridge.subscribe("probe_cmd", CXRServiceBridge.MsgCallback { _, caps, _ ->
            val text = runCatching { caps?.at(0)?.string }.getOrNull() ?: return@MsgCallback
            thread(name = "probe-test") { Tests.run(this, JSONObject(text)) }
        })
        for (ch in listOf("probe_chunk_ack", "probe_frame_ack")) {
            Probe.bridge.subscribe(ch, CXRServiceBridge.MsgCallback { _, caps, _ ->
                runCatching { caps?.at(0)?.string }.getOrNull()?.let { Acks.onAck(JSONObject(it)) }
            })
        }
    }
}

object Acks { fun onAck(j: JSONObject) {} }

object Tests {
    fun run(a: Activity, cmd: JSONObject) {
        when (cmd.getString("t")) {
            "ping" -> Probe.log("pong at=${cmd.getLong("at")} now=${System.currentTimeMillis()}")
            else -> Probe.log("unknown test ${cmd.getString("t")}")
        }
    }
}
```

Add an empty `HeartbeatService.kt` so the manifest resolves (filled in Task 3):
```kotlin
package com.rokid.context.probe.glasses
import android.app.Service
import android.content.Intent
class HeartbeatService : Service() { override fun onBind(i: Intent?) = null }
```

- [ ] **Step 7: Build both**

Run: `cd $SPIKE/spikes/cxr-probe && ./gradlew :phone-probe:assembleDebug :glasses-probe:assembleDebug`
Expected: BUILD SUCCESSFUL. If a CXR class/parameter name differs from Live Fit's, open the AAR (`~/.gradle/caches/.../client-l-1.1.2.aar`) with `unzip -l` / `javap` and adapt; record the difference in the results doc.

- [ ] **Step 8: Install and verify round trip (needs the user)**

```bash
ADB=/Users/debasishkanhar/Desktop/rokid/android-sdk/platform-tools/adb
$ADB devices   # ask the user which serial is phone and which is glasses
$ADB -s <phone> install -r phone-probe/build/outputs/apk/debug/phone-probe-debug.apk
$ADB -s <glasses> install -r glasses-probe/build/outputs/apk/debug/glasses-probe-debug.apk
```
Ask the user to: open **CXR Probe** on the phone → Authorize → Connect → Ping.
Expected on phone log: `auth ok=true`, `connect ok=true`, `session started`, then `G: pong at=… now=…`. Record phone→glasses→phone ping round trip (phone `now` at receipt − `at`) for 5 pings.

- [ ] **Step 9: Commit (spike branch only)**

```bash
cd $SPIKE && git add spikes/cxr-probe && git commit -m "spike: cxr probe scaffold with ping

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Size limit, chunked frames, frame-level ack, backlog, interrupted flush

**Requirements:** R-76, R-08

**Files:**
- Modify: `spikes/cxr-probe/glasses-probe/src/main/java/com/rokid/context/probe/glasses/MainActivity.kt` (`Tests`, `Acks`)
- Create: `spikes/cxr-probe/glasses-probe/src/main/assets/sample.jpg` (copy of the main checkout's `latest_rokid_frame.jpg`, 360×270, 20 KB)
- Modify: `spikes/cxr-probe/phone-probe/src/main/java/com/rokid/context/probe/MainActivity.kt` (`Handlers`)

**Interfaces:**
- Consumes: `Probe.send`, `ProbeLink.send`, channels from Task 1.
- Produces: measured `SAFE_CHUNK_CHARS`, whether per-chunk acks are needed for flow control, per-frame timings, and verified buffer semantics (delete-after-positive-ack, retain-on-failure, recover-on-flush, dedupe) → results doc.

- [ ] **Step 1: Copy the sample JPEG**

```bash
mkdir -p $SPIKE/spikes/cxr-probe/glasses-probe/src/main/assets
cp /Users/debasishkanhar/Desktop/rokid/proactive-context-agent/latest_rokid_frame.jpg $SPIKE/spikes/cxr-probe/glasses-probe/src/main/assets/sample.jpg
```

- [ ] **Step 2: Glasses — sizes test and a buffered sender with frame-level acks**

Replace `Acks` and `Tests` in the glasses `MainActivity.kt`:

```kotlin
object Acks {
    private val waiting = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<JSONObject>>()
    fun expect(key: String) = java.util.concurrent.CompletableFuture<JSONObject>().also { waiting[key] = it }
    /** Chunk acks carry `k = frameId/idx`; frame acks carry `k = frameId` plus `ok` and `reason`. */
    fun onAck(j: JSONObject) { waiting.remove(j.getString("k"))?.complete(j) }
}

/** One buffered frame. Deleted only on a positive frame ack (spec §1.8). */
class Pending(val frameId: String, val b64: String, var corruptNextSend: Boolean = false, val corruptAlways: Boolean = false)

object Tests {
    private val buffer = java.util.concurrent.ConcurrentLinkedDeque<Pending>()
    @Volatile private var flushing = false
    private fun crc(s: String) = java.util.zip.CRC32().apply { update(s.toByteArray()) }.value

    fun run(a: Activity, cmd: JSONObject) {
        when (cmd.getString("t")) {
            "ping" -> Probe.log("pong at=${cmd.getLong("at")} now=${System.currentTimeMillis()}")
            "sizes" -> sizes()
            "frames" -> {
                enqueue(a, count = cmd.optInt("count", 1), corruptFirst = cmd.optBoolean("corrupt", false), corruptFirstAlways = cmd.optBoolean("corruptAlways", false))
                flush(chunkChars = cmd.optInt("chunk", 16_384), chunkAck = cmd.optBoolean("chunkAck", false))
            }
            "flush" -> flush(chunkChars = cmd.optInt("chunk", 16_384), chunkAck = cmd.optBoolean("chunkAck", false))
            "buffer" -> Probe.log("buffer size=${buffer.size} ids=${buffer.map { it.frameId }}")
            else -> Probe.log("unknown test ${cmd.getString("t")}")
        }
    }

    private fun sizes() {
        val rnd = java.util.Random(7)
        for (kb in listOf(4, 8, 16, 24, 32, 48, 64, 96, 128, 192, 256)) repeat(5) { i ->
            val payload = buildString(kb * 1024) { repeat(kb * 1024) { append('A' + rnd.nextInt(26)) } }
            val meta = JSONObject().put("kb", kb).put("i", i).put("crc", crc(payload)).toString()
            val rc = Probe.send("probe_data", meta, payload)
            Probe.log("sizes kb=$kb i=$i rc=$rc")
            Thread.sleep(300)
        }
        Probe.log("sizes done")
    }

    private fun enqueue(a: Activity, count: Int, corruptFirst: Boolean, corruptFirstAlways: Boolean) {
        val b64 = android.util.Base64.encodeToString(a.assets.open("sample.jpg").readBytes(), android.util.Base64.NO_WRAP)
        repeat(count) { f -> buffer.addLast(Pending("f${System.currentTimeMillis()}_$f", b64, corruptNextSend = corruptFirst && f == 0, corruptAlways = corruptFirstAlways && f == 0)) }
        Probe.log("enqueued $count buffer=${buffer.size}")
    }

    /**
     * spec §1.8, stop-and-wait per frame:
     * - positive frame ack → delete from buffer;
     * - negative ack (CRC/decode) → resend once; a second NACK → drop the frame (logged) and continue the backlog;
     * - timeout (no frame ack in 5 s, chunk rc ≠ 0, or chunk-ack timeout) → retry from chunk 0, at most 3 attempts,
     *   then keep the frame and every later one buffered and stop this flush (next flush retries).
     */
    private fun flush(chunkChars: Int, chunkAck: Boolean) {
        if (flushing) return Probe.log("flush already running")
        flushing = true
        val t0 = System.currentTimeMillis()
        var sent = 0
        var dropped = 0
        try {
            while (true) {
                val frame = buffer.peekFirst() ?: break
                val tf = System.currentTimeMillis()
                var timeouts = 0
                var nacks = 0
                val outcome: String
                while (true) {
                    val corrupt = frame.corruptAlways || frame.corruptNextSend
                    frame.corruptNextSend = false
                    val body = if (corrupt) frame.b64.replaceRange(10, 11, if (frame.b64[10] == 'A') "B" else "A") else frame.b64
                    val chunks = body.chunked(chunkChars)
                    val frameAck = Acks.expect(frame.frameId)
                    var sendFailed = false
                    for ((idx, c) in chunks.withIndex()) {
                        val chunkAckF = if (chunkAck) Acks.expect("${frame.frameId}/$idx") else null
                        val meta = JSONObject().put("frameId", frame.frameId).put("idx", idx).put("n", chunks.size).put("crc", crc(frame.b64)).toString()
                        val rc = Probe.send("probe_chunk", meta, c)
                        val chunkOk = chunkAckF?.let { runCatching { it.get(5, java.util.concurrent.TimeUnit.SECONDS) }.isSuccess } ?: true
                        if (rc != 0 || !chunkOk) { Probe.log("frame ${frame.frameId} chunk $idx rc=$rc chunkAck=$chunkOk"); sendFailed = true; break }
                    }
                    val ack = if (sendFailed) null else runCatching { frameAck.get(5, java.util.concurrent.TimeUnit.SECONDS) }.getOrNull()
                    when {
                        ack == null -> {
                            timeouts++
                            Probe.log("frame ${frame.frameId} timeout $timeouts/3")
                            if (timeouts >= 3) { outcome = "retain"; break }
                            Thread.sleep(2_000)
                        }
                        ack.getBoolean("ok") -> { outcome = "delivered"; break }
                        else -> {
                            nacks++
                            Probe.log("frame ${frame.frameId} NACK reason=${ack.optString("reason")} $nacks/2")
                            if (nacks >= 2) { outcome = "dropped"; break }
                        }
                    }
                }
                when (outcome) {
                    "delivered" -> { buffer.pollFirst(); sent++; Probe.log("frame ${frame.frameId} delivered ms=${System.currentTimeMillis() - tf} timeouts=$timeouts nacks=$nacks") }
                    "dropped" -> { buffer.pollFirst(); dropped++; Probe.log("frame ${frame.frameId} DROPPED after 2 NACKs") }
                    else -> { Probe.log("flush stopped after 3 timeouts; retained=${buffer.size}"); return }
                }
            }
            Probe.log("flush done sent=$sent dropped=$dropped retained=${buffer.size} chunk=$chunkChars chunkAck=$chunkAck totalMs=${System.currentTimeMillis() - t0}")
        } finally { flushing = false }
    }
}
```

- [ ] **Step 3: Phone handlers — verify sizes; validate the whole frame before a frame-level ack; dedupe**

Replace `Handlers` in the phone `MainActivity.kt`:

```kotlin
object Handlers {
    private val sizeStats = sortedMapOf<Int, IntArray>() // kb -> [received, intact]
    private val slots = HashMap<String, Array<String?>>()
    private val ingested = HashSet<String>()
    private var dupes = 0
    private var nacks = 0
    private fun crc(s: String) = java.util.zip.CRC32().apply { update(s.toByteArray()) }.value

    fun registerButtons(a: MainActivity) {
        a.button("Test: sizes") { sizeStats.clear(); a.link.send("probe_cmd", """{"t":"sizes"}""") }
        for (chunk in listOf(8_192, 16_384, 32_768)) {
            a.button("1 frame @${chunk / 1024}K") { a.link.send("probe_cmd", """{"t":"frames","count":1,"chunk":$chunk}""") }
        }
        a.button("1 frame @16K + chunk acks") { a.link.send("probe_cmd", """{"t":"frames","count":1,"chunk":16384,"chunkAck":true}""") }
        a.button("1 corrupted frame @16K") { a.link.send("probe_cmd", """{"t":"frames","count":1,"chunk":16384,"corrupt":true}""") }
        a.button("3 frames, #1 always corrupt") { a.link.send("probe_cmd", """{"t":"frames","count":3,"chunk":16384,"corruptAlways":true}""") }
        a.button("Backlog 20 @16K") { a.link.send("probe_cmd", """{"t":"frames","count":20,"chunk":16384}""") }
        a.button("Flush retained") { a.link.send("probe_cmd", """{"t":"flush","chunk":16384}""") }
        a.button("Glasses buffer?") { a.link.send("probe_cmd", """{"t":"buffer"}""") }
        a.button("Drop next frame ack") { dropNextFrameAck = true }
        a.button("Print stats") {
            sizeStats.forEach { (kb, s) -> a.log("kb=$kb received=${s[0]}/5 intact=${s[1]}") }
            a.log("ingested=${ingested.size} dupes=$dupes nacks=$nacks")
        }
    }

    /** Simulates a lost ack: the frame is ingested but the glasses never hear "ok", so they resend it. */
    @Volatile private var dropNextFrameAck = false

    private fun frameAck(a: MainActivity, id: String, ok: Boolean, reason: String? = null) {
        if (ok && dropNextFrameAck) { dropNextFrameAck = false; a.log("dropping ack for $id (simulated loss)"); return }
        a.link.send("probe_frame_ack", org.json.JSONObject().put("k", id).put("ok", ok).apply { reason?.let { put("reason", it) } }.toString())
    }

    fun onMessage(a: MainActivity, channel: String, parts: List<String>) {
        when (channel) {
            "probe_log" -> a.log("G: ${parts.firstOrNull()}")
            "probe_data" -> {
                val meta = org.json.JSONObject(parts[0]); val payload = parts.getOrElse(1) { "" }
                val s = sizeStats.getOrPut(meta.getInt("kb")) { IntArray(2) }
                s[0]++; if (crc(payload) == meta.getLong("crc")) s[1]++
            }
            "probe_chunk" -> {
                val meta = org.json.JSONObject(parts[0]); val id = meta.getString("frameId"); val idx = meta.getInt("idx"); val n = meta.getInt("n")
                a.link.send("probe_chunk_ack", """{"k":"$id/$idx"}""") // flow-control only; ignored unless chunkAck mode
                val buf = slots.getOrPut(id) { arrayOfNulls(n) }.let { if (it.size != n) arrayOfNulls<String>(n).also { f -> slots[id] = f } else it }
                buf[idx] = parts.getOrElse(1) { "" }
                if (buf.any { it == null }) return
                slots.remove(id)
                val whole = buf.joinToString("")
                val bytes = runCatching { android.util.Base64.decode(whole, android.util.Base64.NO_WRAP) }.getOrNull()
                when {
                    crc(whole) != meta.getLong("crc") -> { nacks++; a.log("frame $id CRC MISMATCH → NACK"); frameAck(a, id, false, "crc") }
                    bytes == null || bytes.size < 4 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte() -> { nacks++; a.log("frame $id not a JPEG → NACK"); frameAck(a, id, false, "decode") }
                    id in ingested -> { dupes++; a.log("frame $id duplicate (already ingested) → ack ok, not re-ingested"); frameAck(a, id, true) }
                    else -> { ingested += id; a.log("frame $id ingested jpegBytes=${bytes.size}"); frameAck(a, id, true) }
                }
            }
            else -> a.log("rx $channel parts=${parts.size}")
        }
    }
}
```

- [ ] **Step 4: Build, install both, run the size test (needs the user)**

Rebuild/install as Task 1 Step 8. User taps Connect → **Test: sizes**, waits for `G: sizes done`, taps **Print size stats**.
Record: for each kb, received/5 and intact/5, and the glasses `rc` values. `SAFE_CHUNK_CHARS` = the largest size with 5/5 intact at every size up to it, rounded down to a power of two, capped at 32 768.

- [ ] **Step 5: Run frame tests**

User taps **1 frame @8K / @16K / @32K** (each 3×) and **1 frame @16K + chunk acks** (3×). Record per run: delivered ms, chunk count, attempts.
Decision: per-chunk acks are needed only if the no-chunk-ack runs show missing/CRC-failed frames that the chunk-ack runs do not.
Then **1 corrupted frame @16K**: expected phone `CRC MISMATCH → NACK` then `ingested`; glasses `NACK reason=crc 1/2` then `delivered … nacks=1`.
Then **3 frames, #1 always corrupt**: expected glasses `NACK 1/2`, `NACK 2/2`, `DROPPED after 2 NACKs`, then frames 2 and 3 `delivered`; `flush done sent=2 dropped=1 retained=0` (persistent corruption never blocks the backlog).
Then **Drop next frame ack** → **1 frame @16K**: expected glasses `timeout 1/3`, resend, phone `duplicate (already ingested) → ack ok, not re-ingested`, glasses `delivered … timeouts=1`; phone `ingested` count +1 only.
Then **Backlog 20 @16K**: record `totalMs`, retries, final `retained=0`.

- [ ] **Step 6: Interrupted flush and recovery (needs the user)**

User starts **Backlog 20 @16K**; after ~5 `delivered` lines, ask the user to toggle **phone Bluetooth off for 5 s, then on**, and tap Connect again if the session closed.
Expected: glasses log `timeout 1/3 … 3/3` (or chunk rc ≠ 0) and either recover within the 3 attempts or end with `flush stopped after 3 timeouts; retained=N`; **no frame is deleted that the phone did not ingest** (compare **Glasses buffer?** ids with phone `ingested` ids). If frames were retained, after reconnect the user taps **Flush retained**: all are delivered; phone `ingested` total = 20, with `dupes` only for frames whose ack was lost in the drop.
Record: N retained, reconnect ms, frames ingested twice (must be 0), frames lost (must be 0).

- [ ] **Step 7: Commit**

```bash
cd $SPIKE && git add spikes/cxr-probe && git commit -m "spike: size, chunked frame, backlog and interrupt probes

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: PTT audio during a flush + background sending

**Requirements:** R-76, R-08, R-30

**Files:**
- Modify: glasses `MainActivity.kt` (`Tests`), `HeartbeatService.kt`
- Modify: phone `MainActivity.kt` (`Handlers`)

**Interfaces:**
- Consumes: Task 2 `frames()` and handlers.
- Produces: audio gap stats; background-send verdict → results doc.

- [ ] **Step 1: Glasses — audio stream test + heartbeat service**

Add to `Tests.run`'s `when`:
```kotlin
            "audio" -> audio(withFlush = cmd.optBoolean("flush", false), a = a)
            "hb" -> a.startForegroundService(android.content.Intent(a, HeartbeatService::class.java))
```
Add to `Tests`:
```kotlin
    private fun audio(withFlush: Boolean, a: Activity) {
        if (withFlush) thread { run(a, JSONObject().put("t", "frames").put("count", 20).put("chunk", 16_384)) }
        val chunk = android.util.Base64.encodeToString(ByteArray(3_200), android.util.Base64.NO_WRAP)
        val start = System.currentTimeMillis()
        var seq = 0
        while (System.currentTimeMillis() - start < 10_000) {
            Probe.send("probe_audio", """{"seq":${seq++}}""", chunk)
            Thread.sleep(100)
        }
        Probe.log("audio done sent=$seq flush=$withFlush")
    }
```
Replace `HeartbeatService.kt`:
```kotlin
package com.rokid.context.probe.glasses

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import kotlin.concurrent.thread

class HeartbeatService : Service() {
    @Volatile private var running = false
    override fun onBind(i: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("hb", "hb", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, Notification.Builder(this, "hb").setContentTitle("probe").setSmallIcon(android.R.drawable.ic_menu_camera).build())
        if (!running) { running = true; thread {
            var n = 0
            while (running && n < 300) { val rc = Probe.send("probe_hb", """{"n":${n++},"t":${System.currentTimeMillis()}}"""); if (rc != 0) android.util.Log.w("ProbeG", "hb rc=$rc"); Thread.sleep(2_000) }
        } }
        return START_STICKY
    }
    override fun onDestroy() { running = false }
}
```

- [ ] **Step 2: Phone — audio gap and heartbeat stats**

Add to phone `Handlers`:
```kotlin
    private val audioAt = ArrayList<Long>()
    private val hbAt = ArrayList<Long>()
```
In `registerButtons`:
```kotlin
        a.button("Test: audio alone") { audioAt.clear(); a.link.send("probe_cmd", """{"t":"audio"}""") }
        a.button("Test: audio + flush") { audioAt.clear(); a.link.send("probe_cmd", """{"t":"audio","flush":true}""") }
        a.button("Print audio gaps") {
            val gaps = audioAt.zipWithNext { x, y -> y - x }.sorted()
            if (gaps.isNotEmpty()) a.log("audio n=${audioAt.size} p50=${gaps[gaps.size / 2]} p95=${gaps[(gaps.size * 95) / 100]} max=${gaps.last()}")
        }
        a.button("Start heartbeat") { hbAt.clear(); a.link.send("probe_cmd", """{"t":"hb"}""") }
        a.button("Print heartbeat") { val g = hbAt.zipWithNext { x, y -> y - x }; a.log("hb n=${hbAt.size} maxGap=${g.maxOrNull()}") }
```
In `onMessage`'s `when`:
```kotlin
            "probe_audio" -> audioAt += System.currentTimeMillis()
            "probe_hb" -> hbAt += System.currentTimeMillis()
```

- [ ] **Step 3: Run audio tests (needs the user)**

Install both. User: Connect → **audio alone** (wait 12 s) → **Print audio gaps**; then **audio + flush** → wait for `audio done` → **Print audio gaps**.
Record p50/p95/max gap for both. Decision rule for Phase 3: if p95 with flush > 2× p95 alone, frame flush MUST be suspended while listening (spec §1.8 already requires this — record the numbers that justify it).

- [ ] **Step 4: Background sending (needs the user)**

User: Connect → **Start heartbeat**. Then, in order, each ~30 s, ask the user to: (a) leave the probe app in front; (b) swipe/back out of the probe app to the Rokid launcher; (c) let the glasses display sleep (or take the glasses off). Tap **Print heartbeat** after each.
Record for each state: heartbeats received in the window, maxGap, and any `hb rc` warnings from `adb -s <glasses> logcat -s ProbeG`.
Decision for Phase 3: if (b)/(c) keep heartbeats flowing, `CaptureService` sends directly from the foreground service; otherwise Phase 3 must buffer while backgrounded and flush when the activity is foregrounded (CXR `CUSTOM_APP` session reopens it).

- [ ] **Step 5: Commit**

```bash
cd $SPIKE && git add spikes/cxr-probe && git commit -m "spike: ptt-during-flush and background send probes

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: TTS route and on-device STT with injected audio

**Requirements:** R-76, R-67, R-35

**Files:**
- Create: `spikes/cxr-probe/phone-probe/src/main/java/com/rokid/context/probe/VoiceProbes.kt`
- Create: `spikes/cxr-probe/phone-probe/src/main/assets/question.pcm` (16 kHz mono PCM16, raw)
- Modify: phone `MainActivity.kt` (`Handlers.registerButtons`), glasses `MainActivity.kt` (`Tests` — TTS engine check)

**Interfaces:**
- Produces: TTS route verdict, glasses TTS engine availability, STT capability table + injection verdict → results doc.

- [ ] **Step 1: Generate the injected-audio asset**

```bash
cd $SPIKE/spikes/cxr-probe/phone-probe/src/main && mkdir -p assets
say -o /tmp/q.wav --data-format=LEI16@16000 --channels=1 "what do I need from the store"
python3 - <<'EOF'
import wave; w=wave.open('/tmp/q.wav'); open('assets/question.pcm','wb').write(w.readframes(w.getnframes()))
# pad with 3 s of silence so total > 64 KiB pipe capacity
open('assets/question.pcm','ab').write(b'\x00'*96000)
EOF
ls -la assets/question.pcm   # expect > 100 KB
```

- [ ] **Step 2: `VoiceProbes.kt`**

```kotlin
package com.rokid.context.probe

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.io.FileOutputStream
import java.util.Locale
import kotlin.concurrent.thread

object VoiceProbes {
    private var tts: TextToSpeech? = null

    fun outputs(ctx: Context, log: (String) -> Unit) {
        val am = ctx.getSystemService(AudioManager::class.java)
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).forEach { log("out type=${it.type} name=${it.productName} a2dp=${it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP}") }
    }

    fun speak(ctx: Context, log: (String) -> Unit) {
        tts = TextToSpeech(ctx) { st ->
            log("tts init=$st")
            tts?.language = Locale.US
            tts?.speak("Milk and eggs. The fridge looked low this morning.", TextToSpeech.QUEUE_FLUSH, null, "probe")
        }
    }

    fun sttCapability(ctx: Context, log: (String) -> Unit) {
        log("api=${Build.VERSION.SDK_INT} recognitionAvailable=${SpeechRecognizer.isRecognitionAvailable(ctx)}")
        if (Build.VERSION.SDK_INT >= 31) log("onDeviceAvailable=${SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)}")
    }

    /** Feeds question.pcm through EXTRA_AUDIO_SOURCE; logs bytes written over time (plateau ≈ recognizer not reading) and the transcript. */
    fun sttInjected(ctx: Context, main: android.app.Activity, log: (String) -> Unit) {
        if (Build.VERSION.SDK_INT < 33) return log("needs API 33 for EXTRA_AUDIO_SOURCE")
        val pcm = ctx.assets.open("question.pcm").readBytes()
        val pipe = ParcelFileDescriptor.createPipe()
        var written = 0
        main.runOnUiThread {
            val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
            r.setRecognitionListener(object : RecognitionListener {
                override fun onResults(b: Bundle?) { log("STT final=${b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)} written=$written/${pcm.size}"); r.destroy() }
                override fun onPartialResults(b: Bundle?) { log("STT partial=${b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)}") }
                override fun onError(e: Int) { log("STT error=$e written=$written/${pcm.size}"); r.destroy() }
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(t: Int, p: Bundle?) {}
            })
            r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16_000))
        }
        thread {
            FileOutputStream(pipe[1].fileDescriptor).use { out ->
                pcm.toList().chunked(3_200).forEach { c -> out.write(c.toByteArray()); written += c.size; Thread.sleep(100) }
            }
            pipe[1].close()
            log("feeder done written=$written")
        }
        thread { repeat(12) { Thread.sleep(1_000); log("t=${it + 1}s written=$written") } }
    }
}
```

- [ ] **Step 3: Buttons**

In phone `Handlers.registerButtons`:
```kotlin
        a.button("Audio outputs") { VoiceProbes.outputs(a, a::log) }
        a.button("TTS speak") { VoiceProbes.speak(a, a::log) }
        a.button("STT capability") { VoiceProbes.sttCapability(a, a::log) }
        a.button("STT injected (stay silent)") { VoiceProbes.sttInjected(a.applicationContext, a, a::log) }
        a.button("Glasses TTS engine?") { a.link.send("probe_cmd", """{"t":"tts"}""") }
```
Grant mic permission once: `$ADB -s <phone> shell pm grant com.rokid.context.probe android.permission.RECORD_AUDIO`.

In glasses `Tests.run`'s `when`:
```kotlin
            "tts" -> a.runOnUiThread {
                var t: android.speech.tts.TextToSpeech? = null
                t = android.speech.tts.TextToSpeech(a) { st -> Probe.log("glasses tts init=$st engines=${t?.engines?.map { it.name }}"); t?.speak("Glasses speaker test", 0, null, "g") }
            }
```

- [ ] **Step 4: Run (needs the user)**

1. **Audio outputs** — record whether a Bluetooth output for the glasses is listed.
2. **TTS speak** with glasses connected — ask the user: *did you hear it in the glasses, or from the phone speaker?* Record.
3. **Glasses TTS engine?** — record `init` status (0 = success) and engines; ask whether the user heard "Glasses speaker test".
4. **STT capability** — record api / available / onDeviceAvailable.
5. **STT injected** — the user stays silent in a quiet room. Record final transcript and `written` progression. Verdict: **injection works** if the transcript contains "store" and `written` reaches the full size; **phone mic fallback** if the transcript is empty/unrelated and `written` plateaus near ~64 KiB (pipe full, nobody reading).

- [ ] **Step 5: Commit**

```bash
cd $SPIKE && git add spikes/cxr-probe && git commit -m "spike: tts route and injected stt probes

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Record results and decisions (main checkout)

**Requirements:** R-76

**Files:**
- Create: `docs/spikes/2026-10-12-phase0-results.md` (on `feat/proactive-mvp-implementation`)

- [ ] **Step 1: Write the results doc in the main checkout (no branch switch)**

```bash
cd /Users/debasishkanhar/Desktop/rokid/proactive-context-agent
git -C $SPIKE log --oneline -1   # record this SHA in the doc
mkdir -p docs/spikes
```

Write `docs/spikes/2026-10-12-phase0-results.md` with exactly these sections, filled with the recorded numbers (no blanks — write "not observed" if a probe could not run, and why):

```markdown
# Phase 0 spike results (2026-10-12)
Devices: phone <model, Android version>, glasses <model, firmware>, Hi Rokid <package + version>.
Probe branch: spike/cxr-probe @ <sha>.

## CXR API notes
- sendCustomCmd return type: … ; any class/param differences from Live Fit: …

## Glasses → phone Caps string size
| KB | received/5 | intact/5 | glasses rc |
**SAFE_CHUNK_CHARS = …**

## Chunked frames (20 KB JPEG ≈ 27 KB Base64)
| chunk | chunk acks | runs | ms per frame (min/median/max) | chunks | attempts |
Per-chunk acks needed for flow control: yes/no (evidence: …)
Corrupted frame: NACK observed yes/no; resend delivered yes/no.
Persistent corruption: frame dropped after 2 NACKs yes/no; later frames delivered yes/no.
Lost frame ack: resend after timeout deduped yes/no.
Backlog 20 @16K: total ms …, retries …, retained at end …

## Interrupted flush
Retained N: … ; reconnect ms: … ; recovered by Flush: yes/no; ingested twice: … (must be 0); lost: … (must be 0).

## PTT audio gaps (ms)
| case | n | p50 | p95 | max |
Decision: suspend flush while listening — yes (required by spec §1.8) / numbers: …

## Background sending
| state | heartbeats in 30 s | maxGap | rc warnings |
**Decision for CaptureService:** send from service / buffer until foreground.

## TTS
Phone TTS heard on: glasses / phone. Glasses BT output listed: yes/no. Glasses TTS engine: init=…, engines=…, heard: yes/no.
**Decision:** phone TTS via BT route / glasses-side TTS.

## STT
api=…, recognitionAvailable=…, onDeviceAvailable=…
Injected transcript: "…"; written progression: … ; **verdict:** injection works / falls back to mic.
**Detection method for spec §5.1.1:** pipe back-pressure with ≥ 96 KB feed (the 1 s buffer in the spec is below pipe capacity and cannot detect fallback) — confirm or replace.

## Spec/plan follow-ups
- … (each with the spec section to amend)
```

- [ ] **Step 2: Amend the spec where results contradict it**

For every "Spec/plan follow-ups" line, edit the named section of `docs/superpowers/specs/2026-10-12-v1-redesign-design.md` (e.g. replace "assume 16 KB until measured" with the measured `SAFE_CHUNK_CHARS`; replace "1 s buffer" in §5.1.1 with the verified detection method).

- [ ] **Step 3: Commit and detach the worktree**

```bash
git add docs/spikes/2026-10-12-phase0-results.md docs/superpowers/specs/2026-10-12-v1-redesign-design.md
git worktree remove /Users/debasishkanhar/Desktop/rokid/pca-spike   # branch spike/cxr-probe keeps the probe code
git commit -m "docs: record phase 0 spike results and spec follow-ups

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
Expected: R-76 satisfied; Phase 3 and Phase 6 plans can now be written.

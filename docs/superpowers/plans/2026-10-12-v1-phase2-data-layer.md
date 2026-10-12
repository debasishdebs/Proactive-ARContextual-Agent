# Phase 2 — Data Layer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the tab-separated log files with one Room database (schema per spec §6.1), import existing data once, and route every model call through an endpoint registry + per-role bindings — with the existing UI and workers behaving the same.

**Architecture:** `ContextDatabase` (Room + KSP, optional SQLCipher) lives in `:services:memory` with repositories behind `MemoryGraph`. The legacy `MemoryRecordStore` / `FrameStreamStore` / `SummaryTraceStore` objects keep their public API but delegate to repositories (transitional facades, removed in Phase 4). `LegacyImporter` copies old files into Room idempotently. `:services:ai` gains `EndpointRepository` (keys in EncryptedSharedPreferences via `KeyVault`), `EndpointTester`, `RoleBindingRepository` and `ModelRouter`, which replaces `ModelProviderResolver` in the workers.

**Tech Stack:** Room 2.6.1 + KSP 2.0.21-1.0.27, `net.zetetic:sqlcipher-android:4.6.1`, `androidx.sqlite:sqlite-ktx:2.4.0`, `androidx.security:security-crypto:1.1.0-alpha06`, OkHttp 4.12.0 + MockWebServer 4.12.0, Robolectric 4.13.

**Spec:** `docs/superpowers/specs/2026-10-12-v1-redesign-design.md` §4.1, §4.2, §4.6 (FTS part), §6.1, §6.2. Master plan: `docs/superpowers/plans/2026-10-12-v1-master-plan.md` (Global Constraints and Review Focus apply). Phase 1 must be complete.

## Global Constraints

- All master-plan Global Constraints apply.
- **Schema version 1 is frozen at the end of Task 2.** Room is first used on a device in Task 5. Every later schema change (any phase) bumps `ContextDatabase.version` and ships a `Migration` with a `MigrationTestHelper` test. No `fallbackToDestructiveMigration` outside tests.
- Room DAO calls never run on the main thread. The transitional facades (Task 5) wrap them in `runBlocking(Dispatchers.IO)`; this is the only allowed `runBlocking` and is deleted in Phase 4.
- Lexical search indexes `memory.searchText` = `SearchNormalizer.normalize(text + " " + tags)`; queries go through `SearchNormalizer.toMatchQuery`. Never put raw user text into `MATCH`.
- Robolectric tests use `@Config(sdk = [34])`, in-memory Room, `InMemoryKeyVault`, and call `MemoryGraph.resetForTests()` / `AiGraph.resetForTests()` in `@After`.

## Review Focus

(From the master plan; owned here.)
1. **Malformed / partial / re-run legacy import** → Task 4 tests `skipsMalformedLines`, `rerunIsIdempotent`, `resumesAfterPartialImport`.
2. **Messy endpoint input** → Task 6 tests in `UrlNormalizerTest`.
3. **Non-Latin search text** → Task 1 tests `devanagariWordIsFound`, `cjkPrefixIsFound`, `emojiAndPunctuationAreIgnored`.
4. **Timezone/DST day buckets** → Task 3 tests `dstDayHas23Hours`, `bucketUsesGivenZone`.
5. **Process killed while the facade is first used (import not finished)** → Task 5 test `facadeTriggersImportOnFirstUse`.

---

### Task 1: Room database core — frames, memories, FTS, provenance, vectors, traces

**Files:**
- Modify: `android-companion/services/memory/build.gradle.kts`
- Create: `android-companion/services/memory/src/main/java/com/rokid/context/memory/db/SearchNormalizer.kt`
- Create: `.../memory/db/MemoryEntities.kt`, `.../memory/db/MemoryDaos.kt`, `.../memory/db/ContextDatabase.kt`, `.../memory/db/DatabaseFactory.kt`
- Test: `android-companion/services/memory/src/test/java/com/rokid/context/memory/db/SearchNormalizerTest.kt`, `.../memory/db/MemoryDaoTest.kt`

**Interfaces:**
- Produces: `SearchNormalizer.normalize(text): String`, `SearchNormalizer.toMatchQuery(userText): String?`; entities `FrameEntity`, `MemoryEntity`, `MemoryFts`, `MemorySourceEntity`, `ForgetTombstoneEntity`, `EmbeddingSpaceEntity`, `MemoryVectorEntity`, `SummaryTraceEntity`; enums `MemoryTier {SHORT, MEDIUM, LONG}`, `ReviewState {OK, NEEDS_REVIEW, INVALIDATED}`, `SpaceState {BUILDING, ACTIVE, STALE, RETIRED}`; DAOs `FrameDao`, `MemoryDao`, `TraceDao`; `ContextDatabase` (abstract `frameDao()`, `memoryDao()`, `traceDao()`); `DatabaseFactory.build(context, encrypted: Boolean, passphrase: () -> ByteArray): ContextDatabase`, `DatabaseFactory.inMemory(context): ContextDatabase`.

- [ ] **Step 1: Build file**

Replace `services/memory/build.gradle.kts` with:
```kotlin
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.rokid.context.services.memory"
    compileSdk = 36
    defaultConfig { minSdk = 29 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
    sourceSets["test"].assets.srcDirs("$projectDir/schemas")
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    api("androidx.room:room-runtime:2.6.1")
    api("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("net.zetetic:sqlcipher-android:4.6.1@aar")
    implementation("androidx.sqlite:sqlite-ktx:2.4.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("androidx.room:room-testing:2.6.1")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
}
```

- [ ] **Step 2: Write the failing normaliser test**

`services/memory/src/test/java/com/rokid/context/memory/db/SearchNormalizerTest.kt`:
```kotlin
package com.rokid.context.memory.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchNormalizerTest {
    @Test fun lowercasesAndStripsPunctuationAndSymbols() {
        assertEquals("milk carton is empty", SearchNormalizer.normalize("Milk-carton, is EMPTY!! 🥛"))
    }

    @Test fun keepsIndicCombiningMarksInsideWords() {
        // दूध = द + ू (U+0942, a combining vowel sign) + ध; the danda (।) is punctuation.
        assertEquals("आज दूध खत्म", SearchNormalizer.normalize("आज दूध खत्म।"))
    }

    @Test fun removesZeroWidthFormatCharacters() {
        assertEquals("क्षेत्र", SearchNormalizer.normalize("क्‍षेत्र"))
    }

    @Test fun matchQueryQuotesAndPrefixesEachToken() {
        assertEquals("\"दूध*\" \"milk*\"", SearchNormalizer.toMatchQuery("दूध, milk"))
        assertNull(SearchNormalizer.toMatchQuery("  !!! 🥛 "))
    }

    @Test fun matchQueryCannotInjectFtsSyntax() {
        assertEquals("\"a*\" \"or*\" \"b*\"", SearchNormalizer.toMatchQuery("a\" OR b*"))
    }

    @Test fun matchQueryCapsTokenCount() {
        assertEquals(8, SearchNormalizer.toMatchQuery((1..20).joinToString(" ") { "w$it" })!!.split(' ').size)
    }
}
```

- [ ] **Step 3: Run to verify failure**

Run: `./gradlew :services:memory:testDebugUnitTest --tests "*SearchNormalizerTest"`
Expected: FAIL — `Unresolved reference: SearchNormalizer`.

- [ ] **Step 4: Implement `SearchNormalizer.kt`**

```kotlin
package com.rokid.context.memory.db

import java.text.Normalizer
import java.util.Locale

/**
 * Makes text safe for SQLite's `simple` FTS tokenizer in every script (Review Focus #3). `simple` treats all
 * code points ≥ 128 as word characters, so Indic combining marks stay inside words; we therefore must turn
 * Unicode punctuation, symbols (incl. emoji), separators and format characters into spaces ourselves.
 */
object SearchNormalizer {
    private const val MAX_QUERY_TOKENS = 8

    fun normalize(text: String): String {
        val folded = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val out = StringBuilder(folded.length)
        var i = 0
        while (i < folded.length) {
            val cp = folded.codePointAt(i)
            when (Character.getType(cp).toByte()) {
                Character.FORMAT -> Unit // ZWJ/ZWNJ and friends: drop
                Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
                Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
                Character.OTHER_PUNCTUATION, Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL,
                Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL, Character.SPACE_SEPARATOR,
                Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR, Character.CONTROL,
                Character.SURROGATE, Character.UNASSIGNED, Character.PRIVATE_USE -> out.append(' ')
                else -> out.appendCodePoint(cp)
            }
            i += Character.charCount(cp)
        }
        return out.toString().trim().replace(Regex(" +"), " ")
    }

    /** Each token becomes a quoted prefix term, AND-ed by FTS; null when nothing searchable remains. */
    fun toMatchQuery(userText: String): String? =
        normalize(userText).split(' ').filter { it.isNotBlank() }.take(MAX_QUERY_TOKENS)
            .joinToString(" ") { "\"$it*\"" }.ifBlank { null }
}
```
Note: emoji such as 🥛 are surrogate pairs; `codePointAt` yields the full code point whose type is `OTHER_SYMBOL`, so it becomes a space.

- [ ] **Step 5: Run normaliser test**

Run: `./gradlew :services:memory:testDebugUnitTest --tests "*SearchNormalizerTest"`
Expected: PASS (6 tests).

- [ ] **Step 6: Write the failing DAO test**

`services/memory/src/test/java/com/rokid/context/memory/db/MemoryDaoTest.kt`:
```kotlin
package com.rokid.context.memory.db

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MemoryDaoTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    @After fun close() = db.close()

    private fun mem(id: String, text: String, at: Long, state: ReviewState = ReviewState.OK) = MemoryEntity(
        id = id, frameId = null, text = text, type = "short_term", tags = "", tier = MemoryTier.SHORT, confidence = 0.8,
        reviewState = state, trusted = false, createdAt = at, derived = false, searchText = SearchNormalizer.normalize(text),
    )

    @Test fun devanagariWordIsFound() = runTest {
        db.memoryDao().insert(mem("m1", "आज सुबह दूध खत्म हो गया।", 1))
        db.memoryDao().insert(mem("m2", "Keys on the hallway shelf", 2))
        assertEquals(listOf("m1"), db.memoryDao().search(SearchNormalizer.toMatchQuery("दूध")!!, 10).map { it.id })
    }

    @Test fun cjkPrefixIsFound() = runTest {
        db.memoryDao().insert(mem("m1", "牛奶没了", 1))
        assertEquals(listOf("m1"), db.memoryDao().search(SearchNormalizer.toMatchQuery("牛奶")!!, 10).map { it.id })
    }

    @Test fun emojiAndPunctuationAreIgnored() = runTest {
        db.memoryDao().insert(mem("m1", "Milk carton nearly empty", 1))
        assertEquals(listOf("m1"), db.memoryDao().search(SearchNormalizer.toMatchQuery("🥛 milk!")!!, 10).map { it.id })
    }

    @Test fun invalidatedMemoriesAreNotSearchable() = runTest {
        db.memoryDao().insert(mem("m1", "milk", 1, ReviewState.INVALIDATED))
        assertTrue(db.memoryDao().search(SearchNormalizer.toMatchQuery("milk")!!, 10).isEmpty())
    }

    @Test fun ftsFollowsUpdatesAndDeletes() = runTest {
        db.memoryDao().insert(mem("m1", "milk", 1))
        db.memoryDao().insert(mem("m1", "eggs", 1)) // REPLACE
        assertTrue(db.memoryDao().search("\"milk*\"", 10).isEmpty())
        assertEquals(1, db.memoryDao().search("\"eggs*\"", 10).size)
        db.memoryDao().deleteByIds(listOf("m1"))
        assertTrue(db.memoryDao().search("\"eggs*\"", 10).isEmpty())
    }

    @Test fun recentAndBetweenAreNewestFirst() = runTest {
        listOf(mem("a", "x", 10), mem("b", "y", 30), mem("c", "z", 20)).forEach { db.memoryDao().insert(it) }
        assertEquals(listOf("b", "c", "a"), db.memoryDao().recent(10).map { it.id })
        assertEquals(listOf("c", "a"), db.memoryDao().between(10, 25).map { it.id })
    }

    @Test fun frameInsertIgnoresDuplicatesAndTraceUpserts() = runTest {
        val f = FrameEntity(id = "f1", ts = 1, path = "/p.jpg", source = "rokid_glasses", payload = "x")
        assertTrue(db.frameDao().insertIgnore(f) > 0L)
        assertEquals(-1L, db.frameDao().insertIgnore(f.copy(payload = "dup")))
        assertEquals("x", db.frameDao().byIds(listOf("f1")).single().payload)
        db.traceDao().upsert(SummaryTraceEntity("s1", "QUEUED", 1, "n"))
        db.traceDao().upsert(SummaryTraceEntity("s1", "TRUSTED", 2, "n2"))
        assertEquals(listOf("TRUSTED"), db.traceDao().recent(10).map { it.state })
    }
}
```
Room returns `-1` from an `IGNORE` insert that hit an existing row.

- [ ] **Step 7: Run to verify failure**

Run: `./gradlew :services:memory:testDebugUnitTest --tests "*MemoryDaoTest"`
Expected: FAIL — unresolved `DatabaseFactory`, `MemoryEntity`, …

- [ ] **Step 8: Implement entities — `MemoryEntities.kt`**

```kotlin
package com.rokid.context.memory.db

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

enum class MemoryTier { SHORT, MEDIUM, LONG }
enum class ReviewState { OK, NEEDS_REVIEW, INVALIDATED }
enum class SpaceState { BUILDING, ACTIVE, STALE, RETIRED }

/** spec §6.1 `frame`. [payload] = legacy metadata excerpt. JPEG stays on disk at [path]. */
@Entity(tableName = "frame", indices = [Index("ts")])
data class FrameEntity(
    @PrimaryKey val id: String,
    val ts: Long,
    val path: String,
    val source: String,
    val payload: String = "",
    val luma: Int? = null,
    val phash: String? = null,
    val placeLabel: String? = null,
)

/** spec §6.1 `memory`. [searchText] is the normalised text the FTS table indexes (see [SearchNormalizer]). */
@Entity(tableName = "memory", indices = [Index("frameId"), Index("createdAt")])
data class MemoryEntity(
    @PrimaryKey val id: String,
    val frameId: String?,
    val text: String,
    val type: String,
    val tags: String,
    val tier: MemoryTier,
    val confidence: Double,
    val reviewState: ReviewState,
    val trusted: Boolean,
    val createdAt: Long,
    val derived: Boolean,
    val searchText: String,
)

/** External-content FTS4 over [MemoryEntity.searchText]; Room keeps it in sync with triggers. Default `simple` tokenizer. */
@Fts4(contentEntity = MemoryEntity::class)
@Entity(tableName = "memory_fts")
data class MemoryFts(val searchText: String)

@Entity(tableName = "memory_source", primaryKeys = ["memoryId", "sourceMemoryId"], indices = [Index("sourceMemoryId")])
data class MemorySourceEntity(val memoryId: String, val sourceMemoryId: String)

/** A frame whose extraction is locked after Forget fact / Forget moment (spec §6.1.1). */
@Entity(tableName = "forget_tombstone")
data class ForgetTombstoneEntity(@PrimaryKey val frameId: String, val createdAt: Long)

@Entity(tableName = "embedding_space", indices = [Index(value = ["fingerprint"], unique = true), Index("state")])
data class EmbeddingSpaceEntity(
    @PrimaryKey val id: String,
    val fingerprint: String,
    val configJson: String,
    val dim: Int,
    val state: SpaceState,
    val canaryJson: String,
    val createdAt: Long,
)

@Entity(tableName = "memory_vector", primaryKeys = ["memoryId", "spaceId"], indices = [Index("spaceId")])
data class MemoryVectorEntity(val memoryId: String, val spaceId: String, val blob: ByteArray) {
    override fun equals(other: Any?) = other is MemoryVectorEntity && memoryId == other.memoryId && spaceId == other.spaceId && blob.contentEquals(other.blob)
    override fun hashCode() = 31 * (31 * memoryId.hashCode() + spaceId.hashCode()) + blob.contentHashCode()
}

@Entity(tableName = "summary_trace", indices = [Index("updatedAt")])
data class SummaryTraceEntity(@PrimaryKey val summaryId: String, val state: String, val updatedAt: Long, val note: String)
```

- [ ] **Step 9: Implement DAOs — `MemoryDaos.kt`**

```kotlin
package com.rokid.context.memory.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface FrameDao {
    /** Returns the new rowid, or -1 when a frame with this id already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertIgnore(frame: FrameEntity): Long
    @Query("SELECT * FROM frame ORDER BY ts DESC LIMIT :limit") suspend fun recent(limit: Int): List<FrameEntity>
    @Query("SELECT * FROM frame ORDER BY ts ASC") suspend fun all(): List<FrameEntity>
    @Query("SELECT * FROM frame WHERE id IN (:ids) ORDER BY ts ASC") suspend fun byIds(ids: List<String>): List<FrameEntity>
    @Query("SELECT * FROM frame WHERE ts >= :fromMs AND ts < :toMs ORDER BY ts DESC") suspend fun between(fromMs: Long, toMs: Long): List<FrameEntity>
    @Query("DELETE FROM frame WHERE id IN (:ids)") suspend fun deleteByIds(ids: List<String>)
    @Query("SELECT COUNT(*) FROM frame") suspend fun count(): Int
}

@Dao
interface MemoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(memory: MemoryEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertIgnore(memory: MemoryEntity): Long
    @Query("SELECT * FROM memory ORDER BY createdAt DESC LIMIT :limit") suspend fun recent(limit: Int): List<MemoryEntity>
    @Query("SELECT * FROM memory ORDER BY createdAt ASC") suspend fun all(): List<MemoryEntity>
    @Query("SELECT * FROM memory WHERE createdAt >= :fromMs AND createdAt < :toMs AND reviewState != 'INVALIDATED' ORDER BY createdAt DESC")
    suspend fun between(fromMs: Long, toMs: Long): List<MemoryEntity>
    @Query(
        "SELECT memory.* FROM memory JOIN memory_fts ON memory.rowid = memory_fts.rowid " +
            "WHERE memory_fts MATCH :matchQuery AND memory.reviewState != 'INVALIDATED' ORDER BY memory.createdAt DESC LIMIT :limit",
    )
    suspend fun search(matchQuery: String, limit: Int): List<MemoryEntity>
    @Query("DELETE FROM memory WHERE id IN (:ids)") suspend fun deleteByIds(ids: List<String>)
    @Query("SELECT COUNT(*) FROM memory") suspend fun count(): Int
}

@Dao
interface TraceDao {
    @Upsert suspend fun upsert(trace: SummaryTraceEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertIgnore(trace: SummaryTraceEntity): Long
    @Query("SELECT * FROM summary_trace ORDER BY updatedAt DESC LIMIT :limit") suspend fun recent(limit: Int): List<SummaryTraceEntity>
    @Query("DELETE FROM summary_trace WHERE summaryId IN (:ids)") suspend fun deleteByIds(ids: List<String>)
}
```

- [ ] **Step 10: Implement `ContextDatabase.kt` and `DatabaseFactory.kt`**

`ContextDatabase.kt`:
```kotlin
package com.rokid.context.memory.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        FrameEntity::class, MemoryEntity::class, MemoryFts::class, MemorySourceEntity::class, ForgetTombstoneEntity::class,
        EmbeddingSpaceEntity::class, MemoryVectorEntity::class, SummaryTraceEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class ContextDatabase : RoomDatabase() {
    abstract fun frameDao(): FrameDao
    abstract fun memoryDao(): MemoryDao
    abstract fun traceDao(): TraceDao
}
```

`DatabaseFactory.kt`:
```kotlin
package com.rokid.context.memory.db

import android.content.Context
import androidx.room.Room
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

object DatabaseFactory {
    const val NAME = "context.db"

    /** [encrypted] is decided once at first creation and must never change for an existing file (migration: Phase 5). */
    fun build(context: Context, encrypted: Boolean, passphrase: () -> ByteArray): ContextDatabase {
        val builder = Room.databaseBuilder(context.applicationContext, ContextDatabase::class.java, NAME)
        if (encrypted) {
            System.loadLibrary("sqlcipher")
            builder.openHelperFactory(SupportOpenHelperFactory(passphrase()))
        }
        return builder.build()
    }

    fun inMemory(context: Context): ContextDatabase =
        Room.inMemoryDatabaseBuilder(context.applicationContext, ContextDatabase::class.java).allowMainThreadQueries().build()
}
```

- [ ] **Step 11: Run DAO tests**

Run: `./gradlew :services:memory:testDebugUnitTest`
Expected: PASS (SearchNormalizerTest 6, MemoryDaoTest 7, legacy 6). `services/memory/schemas/com.rokid.context.memory.db.ContextDatabase/1.json` is generated.

- [ ] **Step 12: Commit**

```bash
git add android-companion/services/memory
git commit -m "feat(memory): Room core schema with script-safe FTS search

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Remaining v1 tables + endpoint/role DAOs; remove the dead legacy Room code

**Files:**
- Create: `android-companion/services/memory/src/main/java/com/rokid/context/memory/db/AppEntities.kt`, `.../memory/db/AppDaos.kt`
- Modify: `.../memory/db/ContextDatabase.kt`
- Delete: `android-companion/services/orchestration/src/main/java/com/rokid/context/data/db/` (5 entities + old `ContextDatabase`, never wired), `android-companion/services/orchestration/src/test/java/com/rokid/context/data/SchemaVersioningTest.kt`
- Test: `android-companion/services/memory/src/test/java/com/rokid/context/memory/db/SchemaTest.kt`

**Interfaces:**
- Produces: entities `EndpointEntity`, `RoleBindingEntity`, `PromptEntity`, `PromptVersionEntity`, `AskEntity`, `NudgeEntity`, `ProposalEntity`, `ActionLogEntity`, `PauseRecordEntity`, `PauseIntervalEntity`, `ShoppingItemEntity`, `NoteEntity`; DAOs `EndpointDao`, `RoleBindingDao`; `ContextDatabase.endpointDao()`, `roleBindingDao()`. Enum-like columns are `String` here (owning services map them) so `:services:memory` never depends on `:services:ai`.

- [ ] **Step 1: Write the failing schema test**

`services/memory/src/test/java/com/rokid/context/memory/db/SchemaTest.kt`:
```kotlin
package com.rokid.context.memory.db

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SchemaTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    @After fun close() = db.close()

    @Test fun everySpecTableExists() {
        val expected = setOf(
            "frame", "memory", "memory_fts", "memory_source", "embedding_space", "memory_vector", "forget_tombstone",
            "endpoint", "role_binding", "prompt", "prompt_version", "ask", "nudge", "proposal", "action_log",
            "pause_record", "pause_interval", "shopping_item", "note", "summary_trace",
        )
        val c = db.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type = 'table'")
        val tables = buildSet { while (c.moveToNext()) add(c.getString(0)) }
        c.close()
        assertEquals(emptySet<String>(), expected - tables)
    }

    @Test fun endpointAndBindingRoundTrip() = runTest {
        val e = EndpointEntity("e1", "Azure", "AZURE_OPENAI", "https://az.example", "2024-10-21", "gpt-5.4", "key:e1", "CHAT,VISION", 1)
        db.endpointDao().upsert(e)
        assertEquals(e, db.endpointDao().byId("e1"))
        db.roleBindingDao().upsert(RoleBindingEntity("ANSWER", "e1", "gpt-5.4", 0.2, 500, null, null))
        assertEquals("e1", db.roleBindingDao().byRole("ANSWER")!!.endpointId)
        assertEquals(1, db.endpointDao().all().size)
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :services:memory:testDebugUnitTest --tests "*SchemaTest"`
Expected: FAIL — unresolved `EndpointEntity` / `endpointDao`.

- [ ] **Step 3: `AppEntities.kt`**

```kotlin
package com.rokid.context.memory.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** spec §4.1. [keyRef] points into the KeyVault; the key itself is never stored here. CSV columns hold enum names. */
@Entity(tableName = "endpoint")
data class EndpointEntity(
    @PrimaryKey val id: String,
    val name: String,
    val kind: String,
    val baseUrl: String,
    val apiVersion: String?,
    val modelsCsv: String,
    val keyRef: String,
    val capabilitiesCsv: String,
    val createdAt: Long,
)

/** spec §4.2. One row per role. */
@Entity(tableName = "role_binding")
data class RoleBindingEntity(
    @PrimaryKey val role: String,
    val endpointId: String,
    val model: String,
    val temperature: Double?,
    val maxTokens: Int?,
    val fallbackEndpointId: String?,
    val fallbackModel: String?,
)

/** spec §4.3. [id] = role name or "SYSTEM". */
@Entity(tableName = "prompt")
data class PromptEntity(@PrimaryKey val id: String, val activeVersion: Int)

@Entity(tableName = "prompt_version", primaryKeys = ["promptId", "version"])
data class PromptVersionEntity(
    val promptId: String,
    val version: Int,
    val template: String,
    val outputSchema: String?,
    val createdAt: Long,
    val note: String,
    val testedOk: Boolean,
)

@Entity(tableName = "ask", indices = [Index("ts")])
data class AskEntity(
    @PrimaryKey val id: String,
    val ts: Long,
    val question: String,
    val answer: String,
    val sourcesJson: String,
    val rating: Int?,
    val source: String,
    val redacted: Boolean,
)

@Entity(tableName = "nudge", indices = [Index("ts"), Index("hash")])
data class NudgeEntity(
    @PrimaryKey val id: String,
    val ts: Long,
    val text: String,
    val why: String,
    val sourceIdsJson: String,
    val priority: Int,
    val hash: String,
    val shownOn: String,
    val redacted: Boolean,
)

/** spec §5.3. state ∈ PENDING | EXECUTING | EXECUTED | DECLINED | EXPIRED | SUPPRESSED | FAILED. */
@Entity(tableName = "proposal", indices = [Index("state"), Index("originId")])
data class ProposalEntity(
    @PrimaryKey val id: String,
    val origin: String,
    val originId: String,
    val type: String,
    val paramsJson: String,
    val state: String,
    val expiresAt: Long,
    val resolvedBy: String?,
    val createdRef: String?,
    val createdAt: Long,
)

@Entity(tableName = "action_log", indices = [Index("proposalId")])
data class ActionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val proposalId: String,
    val transition: String,
    val detail: String,
)

@Entity(tableName = "pause_record")
data class PauseRecordEntity(@PrimaryKey val pauseId: String, val origin: String, val createdAtMs: Long, val untilMs: Long?)

@Entity(tableName = "pause_interval", indices = [Index("startMs")])
data class PauseIntervalEntity(@PrimaryKey val pauseId: String, val origin: String, val startMs: Long, val effectiveEndMs: Long?)

@Entity(tableName = "shopping_item")
data class ShoppingItemEntity(
    @PrimaryKey val id: String,
    val name: String,
    val qty: Double?,
    val unit: String?,
    val done: Boolean,
    val createdAt: Long,
    val proposalId: String?,
)

@Entity(tableName = "note")
data class NoteEntity(@PrimaryKey val id: String, val text: String, val createdAt: Long)
```

- [ ] **Step 4: `AppDaos.kt`**

```kotlin
package com.rokid.context.memory.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface EndpointDao {
    @Upsert suspend fun upsert(endpoint: EndpointEntity)
    @Query("SELECT * FROM endpoint WHERE id = :id") suspend fun byId(id: String): EndpointEntity?
    @Query("SELECT * FROM endpoint ORDER BY createdAt ASC") suspend fun all(): List<EndpointEntity>
    @Query("DELETE FROM endpoint WHERE id = :id") suspend fun delete(id: String)
}

@Dao
interface RoleBindingDao {
    @Upsert suspend fun upsert(binding: RoleBindingEntity)
    @Query("SELECT * FROM role_binding WHERE role = :role") suspend fun byRole(role: String): RoleBindingEntity?
    @Query("SELECT * FROM role_binding") suspend fun all(): List<RoleBindingEntity>
}
```

- [ ] **Step 5: Register entities/DAOs**

In `ContextDatabase.kt` extend `entities` with:
```kotlin
        EndpointEntity::class, RoleBindingEntity::class, PromptEntity::class, PromptVersionEntity::class, AskEntity::class,
        NudgeEntity::class, ProposalEntity::class, ActionLogEntity::class, PauseRecordEntity::class, PauseIntervalEntity::class,
        ShoppingItemEntity::class, NoteEntity::class,
```
and add:
```kotlin
    abstract fun endpointDao(): EndpointDao
    abstract fun roleBindingDao(): RoleBindingDao
```
Keep `version = 1` (nothing has shipped on Room yet).

- [ ] **Step 6: Delete the dead legacy Room package**

```bash
cd android-companion
git rm -r services/orchestration/src/main/java/com/rokid/context/data/db services/orchestration/src/test/java/com/rokid/context/data/SchemaVersioningTest.kt
grep -rn "data.db" services phone/src || echo clean
```
Expected: `clean`. (Legacy test count becomes 43; the 2 removed tests covered the deleted, never-wired entities.) Remove `implementation("androidx.room:room-runtime:2.6.1")` from `services/orchestration/build.gradle.kts` and `phone/build.gradle.kts` (Room now arrives via `:services:memory`'s `api`).

- [ ] **Step 7: Run all tests**

Run: `./gradlew test -q`
Expected: PASS; regenerate the schema JSON (`schemas/.../1.json` now lists 20 tables).

- [ ] **Step 8: Commit**

```bash
git add -A android-companion
git commit -m "feat(memory): complete v1 Room schema; drop dead legacy Room entities

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Repositories, `MemoryGraph`, day buckets

**Files:**
- Create: `android-companion/services/memory/src/main/java/com/rokid/context/memory/DayBucket.kt`, `.../memory/Repositories.kt`, `.../memory/MemoryGraph.kt`, `.../memory/DbKeyStore.kt`, `.../memory/Embedder.kt`
- Test: `android-companion/services/memory/src/test/java/com/rokid/context/memory/DayBucketTest.kt`, `.../memory/RepositoriesTest.kt`

**Interfaces:**
- Consumes: Task 1–2 DAOs.
- Produces: `DayBucket.bounds(date: LocalDate, zone: ZoneId): LongRange` (end exclusive as `last + 1`), `DayBucket.dayOf(epochMs, zone): LocalDate`; `FrameRepository(dao)` (`add(frame): Boolean`, `recent(limit)`, `all()`, `byIds(ids)`, `deleteByIds(ids)`), `MemoryRepository(dao)` (`add(memory: MemoryEntity)` — computes `searchText`, `addIgnore(memory): Boolean`, `recent(limit)`, `all()`, `search(userText, limit)`, `onDay(date, zone)`, `deleteByIds(ids)`), `TraceRepository(dao)` (`upsert`, `recent`, `deleteByIds`); `MemoryGraph` (`db`, `frames`, `memories`, `traces`, `get(context)`, `resetForTests(db)`), `DbKeyStore.passphrase(context): ByteArray`; `interface Embedder` (shape only, implemented in Phase 5).

- [ ] **Step 1: Write the failing tests**

`services/memory/src/test/java/com/rokid/context/memory/DayBucketTest.kt`:
```kotlin
package com.rokid.context.memory

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class DayBucketTest {
    @Test fun dstDayHas23Hours() {
        val r = DayBucket.bounds(LocalDate.of(2026, 3, 29), ZoneId.of("Europe/Berlin"))
        assertEquals(23L * 3_600_000, r.last + 1 - r.first)
    }

    @Test fun bucketUsesGivenZone() {
        val instant = java.time.Instant.parse("2026-10-12T20:00:00Z").toEpochMilli() // 01:30 next day in India
        assertEquals(LocalDate.of(2026, 10, 13), DayBucket.dayOf(instant, ZoneId.of("Asia/Kolkata")))
        assertEquals(LocalDate.of(2026, 10, 12), DayBucket.dayOf(instant, ZoneId.of("Europe/London")))
    }
}
```

`services/memory/src/test/java/com/rokid/context/memory/RepositoriesTest.kt`:
```kotlin
package com.rokid.context.memory

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RepositoriesTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val memories = MemoryRepository(db.memoryDao())
    @After fun close() = db.close()

    private fun m(id: String, text: String, at: Long) = MemoryEntity(id, null, text, "short_term", "", MemoryTier.SHORT, 0.8, ReviewState.OK, false, at, false, searchText = "")

    @Test fun addComputesSearchTextSoSearchWorks() = runTest {
        memories.add(m("m1", "Milk-carton EMPTY", 1))
        assertEquals("milk carton empty", memories.recent(1).single().searchText)
        assertEquals(listOf("m1"), memories.search("milk", 10).map { it.id })
        assertTrue(memories.search("!!!", 10).isEmpty())
    }

    @Test fun onDayReturnsOnlyThatLocalDay() = runTest {
        val zone = ZoneId.of("Asia/Kolkata")
        val day = DayBucket.bounds(LocalDate.of(2026, 10, 12), zone)
        memories.add(m("in", "a", day.first + 1))
        memories.add(m("before", "b", day.first - 1))
        memories.add(m("after", "c", day.last + 1))
        assertEquals(listOf("in"), memories.onDay(LocalDate.of(2026, 10, 12), zone).map { it.id })
    }

    @Test fun addIgnoreKeepsFirst() = runTest {
        assertTrue(memories.addIgnore(m("m1", "first", 1)))
        assertFalse(memories.addIgnore(m("m1", "second", 2)))
        assertEquals("first", memories.recent(1).single().text)
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :services:memory:testDebugUnitTest --tests "*DayBucketTest" --tests "*RepositoriesTest"`
Expected: FAIL — unresolved `DayBucket`, `MemoryRepository`.

- [ ] **Step 3: Implement `DayBucket.kt`**

```kotlin
package com.rokid.context.memory

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Local-day buckets computed at query time in the device's current zone (Review Focus #4). */
object DayBucket {
    /** [first] = start of [date] in [zone]; [last] = start of the next day − 1 ms. DST-safe. */
    fun bounds(date: LocalDate, zone: ZoneId): LongRange {
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val next = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return start until next
    }

    fun dayOf(epochMs: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
}
```

- [ ] **Step 4: Implement `Repositories.kt`**

```kotlin
package com.rokid.context.memory

import com.rokid.context.memory.db.FrameDao
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.MemoryDao
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.SearchNormalizer
import com.rokid.context.memory.db.SummaryTraceEntity
import com.rokid.context.memory.db.TraceDao
import java.time.LocalDate
import java.time.ZoneId

class FrameRepository(private val dao: FrameDao) {
    /** False when a frame with this id already exists. */
    suspend fun add(frame: FrameEntity): Boolean = dao.insertIgnore(frame) != -1L
    suspend fun recent(limit: Int) = dao.recent(limit)
    suspend fun all() = dao.all()
    suspend fun byIds(ids: List<String>) = if (ids.isEmpty()) emptyList() else dao.byIds(ids)
    suspend fun deleteByIds(ids: List<String>) { if (ids.isNotEmpty()) dao.deleteByIds(ids) }
}

class MemoryRepository(private val dao: MemoryDao) {
    private fun indexed(m: MemoryEntity) = m.copy(searchText = SearchNormalizer.normalize("${m.text} ${m.tags}"))

    suspend fun add(memory: MemoryEntity) = dao.insert(indexed(memory))
    suspend fun addIgnore(memory: MemoryEntity): Boolean = dao.insertIgnore(indexed(memory)) != -1L
    suspend fun recent(limit: Int) = dao.recent(limit)
    suspend fun all() = dao.all()
    suspend fun search(userText: String, limit: Int): List<MemoryEntity> =
        SearchNormalizer.toMatchQuery(userText)?.let { dao.search(it, limit) } ?: emptyList()
    suspend fun onDay(date: LocalDate, zone: ZoneId): List<MemoryEntity> {
        val r = DayBucket.bounds(date, zone)
        return dao.between(r.first, r.last + 1)
    }
    suspend fun deleteByIds(ids: List<String>) { if (ids.isNotEmpty()) dao.deleteByIds(ids) }
}

class TraceRepository(private val dao: TraceDao) {
    suspend fun upsert(trace: SummaryTraceEntity) = dao.upsert(trace)
    suspend fun addIgnore(trace: SummaryTraceEntity): Boolean = dao.insertIgnore(trace) != -1L
    suspend fun recent(limit: Int) = dao.recent(limit)
    suspend fun deleteByIds(ids: List<String>) { if (ids.isNotEmpty()) dao.deleteByIds(ids) }
}
```

- [ ] **Step 5: `DbKeyStore.kt`, `Embedder.kt`, `MemoryGraph.kt`**

`DbKeyStore.kt`:
```kotlin
package com.rokid.context.memory

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

/** Random 32-byte SQLCipher passphrase, created once and kept in EncryptedSharedPreferences. */
object DbKeyStore {
    private const val PREFS = "db_key"
    private const val KEY = "passphrase"

    fun passphrase(context: Context): ByteArray {
        val prefs = EncryptedSharedPreferences.create(
            context, PREFS, MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        prefs.getString(KEY, null)?.let { return Base64.decode(it, Base64.NO_WRAP) }
        val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString(KEY, Base64.encodeToString(fresh, Base64.NO_WRAP)).commit()
        return fresh
    }
}
```

`Embedder.kt`:
```kotlin
package com.rokid.context.memory

/** Implemented in `:services:ai` (Phase 5) so this module never depends on it. */
interface Embedder {
    /** Canonical fingerprint of the embedding space this embedder writes (spec §4.6). */
    val fingerprint: String
    suspend fun embedDocuments(texts: List<String>): List<FloatArray>
    suspend fun embedQuery(text: String): FloatArray
}
```

`MemoryGraph.kt`:
```kotlin
package com.rokid.context.memory

import android.content.Context
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.db.DatabaseFactory

/** Process-wide data graph (no DI framework in v1). The encryption mode is fixed when the DB file is first created. */
class MemoryGraph private constructor(val db: ContextDatabase) {
    val frames = FrameRepository(db.frameDao())
    val memories = MemoryRepository(db.memoryDao())
    val traces = TraceRepository(db.traceDao())

    companion object {
        private const val PREFS = "memory_graph"
        private const val K_ENCRYPTED = "db_encrypted"
        @Volatile private var instance: MemoryGraph? = null

        /** [encryptionRequested] is read only when the DB does not exist yet (ContextApp passes AppConfig.encryptionEnabled). */
        fun get(context: Context, encryptionRequested: () -> Boolean = { false }): MemoryGraph =
            instance ?: synchronized(this) {
                instance ?: run {
                    val app = context.applicationContext
                    val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    val exists = app.getDatabasePath(DatabaseFactory.NAME).exists()
                    val encrypted = if (exists && prefs.contains(K_ENCRYPTED)) prefs.getBoolean(K_ENCRYPTED, false) else encryptionRequested()
                    prefs.edit().putBoolean(K_ENCRYPTED, encrypted).apply()
                    MemoryGraph(DatabaseFactory.build(app, encrypted) { DbKeyStore.passphrase(app) })
                }.also { instance = it }
            }

        fun resetForTests(db: ContextDatabase?) { synchronized(this) { instance = db?.let { MemoryGraph(it) } } }
    }
}
```

- [ ] **Step 6: Run tests**

Run: `./gradlew :services:memory:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add android-companion/services/memory
git commit -m "feat(memory): repositories, MemoryGraph, DST-safe day buckets

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: `LegacyImporter` + `LegacyImportWorker`

**Files:**
- Create: `android-companion/services/memory/src/main/java/com/rokid/context/memory/legacy/LegacyImporter.kt`
- Create: `android-companion/services/orchestration/src/main/java/com/rokid/context/orchestration/LegacyImportWorker.kt`
- Test: `android-companion/services/memory/src/test/java/com/rokid/context/memory/legacy/LegacyImporterTest.kt`

**Interfaces:**
- Consumes: `MemoryGraph`, repositories.
- Produces: `LegacyImporter(context, graph)` with `suspend fun importAll(): ImportReport` and `fun isDone(): Boolean`; `ImportReport(memories, frames, traces, skipped)`; `LegacyImporter.legacyTier(type): MemoryTier`, `LegacyImporter.legacyReviewState(type): ReviewState`; `LegacyImportWorker` (unique work name `legacy_import_v1`) with `LegacyImportWorker.enqueue(context)`.

Mapping (spec §6.2; mirrors the legacy purge rules so the old `MainActivity` purges become no-ops):

| Legacy file (`filesDir`) | Line format (tab-separated) | Kept when | Room row |
|---|---|---|---|
| `memory_records.log` | `id, type, confidence, createdAtMs, content` | `id` starts `rokid_` (case-insensitive); not `rokid_review_`; content has none of the low-signal markers in `MemoryRecordStore.purgeLowSignalSummaries` | `memory(id, frameId=null, text=content, type, tags="", tier=legacyTier(type), confidence, reviewState=legacyReviewState(type), trusted=false, createdAt, derived=false)` |
| `captured_frames.log` | `frameId, capturedAtMs, source, payload, previewPath` | source starts `rokid`; preview file exists; not synthetic/screenshot; ext ∈ jpg/jpeg/webp | `frame(id=frameId, ts, path=previewPath, source, payload)` |
| `summary_trace.log` | `summaryId, state, updatedAtMs, note` | note has no "placeholder"; id doesn't start `frame_ingest` | `summary_trace` |

- [ ] **Step 1: Write the failing test**

`services/memory/src/test/java/com/rokid/context/memory/legacy/LegacyImporterTest.kt`:
```kotlin
package com.rokid.context.memory.legacy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LegacyImporterTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var graph: MemoryGraph
    private lateinit var jpg: File

    @Before fun setUp() {
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        graph = MemoryGraph.get(ctx)
        jpg = File(ctx.filesDir, "rokid_archive/1_a.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
        File(ctx.filesDir, "memory_records.log").writeText(
            listOf(
                "rokid_mem_1\tshort_term\t0.9\t100\tMilk carton nearly empty",
                "rokid_mem_1\tshort_term\t0.9\t100\tduplicate line in file",
                "rokid_review_2\treview\t0.2\t101\tneeds review",
                "rokid_mem_3\tshort_term\t0.5\t102\tMetadata only frame",
                "other_4\tshort_term\t0.5\t103\tnot from rokid",
                "broken line without tabs",
                "rokid_mem_5\tlong_term\tnot-a-number\tnot-a-time\tbad numbers",
                "rokid_mem_6\tlong_term\t0.7\t104\tKeys on the shelf",
            ).joinToString("\n", postfix = "\n"),
        )
        File(ctx.filesDir, "captured_frames.log").writeText(
            listOf(
                "f1\t200\trokid_glasses\tx\t${jpg.absolutePath}",
                "f2\t201\trokid_glasses\tx\t/missing/file.jpg",
                "f3\t202\tphone\tx\t${jpg.absolutePath}",
                "f4\t203\trokid_glasses\tsynthetic frame\t${jpg.absolutePath}",
                "f5\tbad",
            ).joinToString("\n", postfix = "\n"),
        )
        File(ctx.filesDir, "summary_trace.log").writeText("s1\tTRUSTED\t300\tframes=3\ns2\tQUEUED\t301\tplaceholder\n")
    }

    @After fun tearDown() { graph.db.close(); MemoryGraph.resetForTests(null) }

    @Test fun importsOnlyKeepableRowsWithMappedFields() = runTest {
        val report = LegacyImporter(ctx, graph).importAll()
        assertEquals(listOf("rokid_mem_1", "rokid_mem_6"), graph.memories.all().map { it.id })
        assertEquals(MemoryTier.LONG, graph.memories.all().last().tier)
        assertEquals(listOf("f1"), graph.frames.all().map { it.id })
        assertEquals(listOf("s1"), graph.traces.recent(10).map { it.summaryId })
        assertEquals(2, report.memories); assertEquals(1, report.frames); assertEquals(1, report.traces)
        assertTrue(LegacyImporter(ctx, graph).isDone())
    }

    @Test fun skipsMalformedLines() = runTest {
        val report = LegacyImporter(ctx, graph).importAll()
        assertTrue("broken + bad-number + short frame lines are counted", report.skipped >= 3)
    }

    @Test fun rerunIsIdempotent() = runTest {
        LegacyImporter(ctx, graph).importAll()
        val second = LegacyImporter(ctx, graph).importAll()
        assertEquals(0, second.memories + second.frames + second.traces)
        assertEquals(2, graph.memories.all().size)
    }

    @Test fun resumesAfterPartialImport() = runTest {
        // Simulate a crash after one memory was written and before the done marker.
        graph.memories.add(LegacyImporter.toMemory("rokid_mem_1", "short_term", 0.9, 100, "Milk carton nearly empty"))
        val report = LegacyImporter(ctx, graph).importAll()
        assertEquals(1, report.memories)
        assertEquals(2, graph.memories.all().size)
    }

    @Test fun reviewTypeMapsToNeedsReview() {
        assertEquals(ReviewState.NEEDS_REVIEW, LegacyImporter.legacyReviewState("review"))
        assertEquals(MemoryTier.MEDIUM, LegacyImporter.legacyTier("medium_term"))
        assertEquals(MemoryTier.SHORT, LegacyImporter.legacyTier("anything_else"))
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :services:memory:testDebugUnitTest --tests "*LegacyImporterTest"`
Expected: FAIL — unresolved `LegacyImporter`.

- [ ] **Step 3: Implement `LegacyImporter.kt`**

```kotlin
package com.rokid.context.memory.legacy

import android.content.Context
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.memory.db.SummaryTraceEntity
import java.io.File

data class ImportReport(val memories: Int, val frames: Int, val traces: Int, val skipped: Int)

/** One-time copy of the legacy tab-separated logs into Room (spec §6.2). Safe to re-run: inserts ignore existing ids. */
class LegacyImporter(private val context: Context, private val graph: MemoryGraph) {
    private val prefs = context.getSharedPreferences("legacy_import", Context.MODE_PRIVATE)

    fun isDone(): Boolean = prefs.getBoolean(K_DONE, false)

    suspend fun importAll(): ImportReport {
        var skipped = 0
        var memories = 0
        var frames = 0
        var traces = 0

        lines("memory_records.log").forEach { line ->
            val p = line.split("\t", limit = 5)
            val confidence = p.getOrNull(2)?.toDoubleOrNull()
            val createdAt = p.getOrNull(3)?.toLongOrNull()
            if (p.size < 5 || confidence == null || createdAt == null) { skipped++; return@forEach }
            val (id, type, content) = Triple(p[0], p[1], p[4])
            if (!id.startsWith("rokid_", ignoreCase = true) || id.startsWith("rokid_review_", ignoreCase = true) ||
                LOW_SIGNAL.any { content.lowercase().contains(it) }
            ) return@forEach
            if (graph.memories.addIgnore(toMemory(id, type, confidence, createdAt, content))) memories++
        }

        lines("captured_frames.log").forEach { line ->
            val p = line.split("\t")
            val ts = p.getOrNull(1)?.toLongOrNull()
            if (p.size < 5 || ts == null) { skipped++; return@forEach }
            val (id, source, payload, path) = listOf(p[0], p[2], p[3], p[4])
            if (!isRealPhoto(source, payload, path)) return@forEach
            if (graph.frames.add(FrameEntity(id = id, ts = ts, path = path, source = source, payload = payload))) frames++
        }

        lines("summary_trace.log").forEach { line ->
            val p = line.split("\t", limit = 4)
            val updated = p.getOrNull(2)?.toLongOrNull()
            if (p.size < 4 || updated == null) { skipped++; return@forEach }
            if (p[3].contains("placeholder", ignoreCase = true) || p[0].startsWith("frame_ingest", ignoreCase = true)) return@forEach
            if (graph.traces.addIgnore(SummaryTraceEntity(p[0], p[1], updated, p[3]))) traces++
        }

        prefs.edit().putBoolean(K_DONE, true).commit()
        return ImportReport(memories, frames, traces, skipped)
    }

    private fun lines(name: String): List<String> =
        File(context.filesDir, name).takeIf { it.exists() }?.readLines()?.filter { it.isNotBlank() }.orEmpty()

    private fun isRealPhoto(source: String, payload: String, path: String): Boolean {
        if (!source.startsWith("rokid", ignoreCase = true) || path.isBlank()) return false
        if (listOf(payload, path).any { it.contains("synthetic", true) || it.contains("screenshot", true) }) return false
        if (File(path).extension.lowercase() !in setOf("jpg", "jpeg", "webp")) return false
        return File(path).exists()
    }

    companion object {
        private const val K_DONE = "v1_done"
        /** Same markers as `MemoryRecordStore.purgeLowSignalSummaries`. */
        private val LOW_SIGNAL = listOf(
            "no visual content", "only file references", "file references and timestamps", "metadata only", "no image pixels",
            "no visual content or scene details",
        )

        fun legacyTier(type: String): MemoryTier = when (type.lowercase()) {
            "medium_term" -> MemoryTier.MEDIUM
            "long_term" -> MemoryTier.LONG
            else -> MemoryTier.SHORT
        }

        fun legacyReviewState(type: String): ReviewState = if (type.equals("review", true)) ReviewState.NEEDS_REVIEW else ReviewState.OK

        fun toMemory(id: String, type: String, confidence: Double, createdAt: Long, content: String) = MemoryEntity(
            id = id, frameId = null, text = content, type = type, tags = "", tier = legacyTier(type), confidence = confidence,
            reviewState = legacyReviewState(type), trusted = false, createdAt = createdAt, derived = false, searchText = "",
        )
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew :services:memory:testDebugUnitTest --tests "*LegacyImporterTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: `LegacyImportWorker.kt`** (orchestration)

```kotlin
package com.rokid.context.orchestration

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.legacy.LegacyImporter

class LegacyImportWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val importer = LegacyImporter(applicationContext, MemoryGraph.get(applicationContext))
        if (importer.isDone()) return Result.success()
        val report = importer.importAll()
        Log.i(TAG, "legacy import $report")
        return Result.success()
    }

    companion object {
        private const val TAG = "LegacyImportWorker"
        const val NAME = "legacy_import_v1"
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<LegacyImportWorker>().build())
        }
    }
}
```

- [ ] **Step 6: Build**

Run: `./gradlew :services:orchestration:assembleDebug -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add android-companion/services
git commit -m "feat(memory): idempotent legacy log importer and import worker

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Switch legacy stores to Room (signature-preserving facades)

**Files:**
- Modify: `android-companion/services/memory/src/main/java/com/rokid/context/data/runtime/MemoryRecordStore.kt`
- Modify: `android-companion/services/orchestration/src/main/java/com/rokid/context/data/frames/FrameStreamStore.kt`
- Modify: `android-companion/services/orchestration/src/main/java/com/rokid/context/data/runtime/SummaryTraceStore.kt`
- Modify: `android-companion/services/memory/src/main/java/com/rokid/context/memory/MemoryGraph.kt` (add `ensureLegacyImported`)
- Modify: `android-companion/phone/src/main/java/com/rokid/context/ContextApp.kt`
- Modify: `android-companion/services/memory/src/test/java/com/rokid/context/data/runtime/MemoryRecordStoreTest.kt` (add setup/teardown only)
- Test: `android-companion/services/orchestration/src/test/java/com/rokid/context/data/LegacyFacadesTest.kt`

**Interfaces:**
- Consumes: Task 3 repositories, Task 4 importer.
- Produces: `MemoryGraph.ensureLegacyImported(context)` (blocking, runs importer once under a lock); unchanged public APIs of the three legacy objects.

- [ ] **Step 1: Write the failing facade test**

`services/orchestration/src/test/java/com/rokid/context/data/LegacyFacadesTest.kt`:
```kotlin
package com.rokid.context.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.data.frames.CapturedFrame
import com.rokid.context.data.frames.FrameStreamStore
import com.rokid.context.data.runtime.MemoryRecordStore
import com.rokid.context.data.runtime.SummaryTrace
import com.rokid.context.data.runtime.SummaryTraceStore
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LegacyFacadesTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    @Before fun setUp() = MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
    @After fun tearDown() = MemoryGraph.resetForTests(null)

    private fun jpg(name: String) = File(ctx.filesDir, name).apply { writeBytes(byteArrayOf(1)) }.absolutePath

    @Test fun framesRoundTripAndRejectNonRokidOrMissingPreview() {
        assertTrue(FrameStreamStore.append(ctx, CapturedFrame("f1", 10, "rokid_glasses", "p", jpg("a.jpg"))))
        assertFalse(FrameStreamStore.append(ctx, CapturedFrame("f2", 11, "phone", "p", jpg("b.jpg"))))
        assertFalse(FrameStreamStore.append(ctx, CapturedFrame("f3", 12, "rokid_glasses", "p", "/missing.jpg")))
        assertEquals(listOf("f1"), FrameStreamStore.recent(ctx, 10).map { it.frameId })
        assertEquals(listOf("f1"), FrameStreamStore.byIds(ctx, listOf("f1", "nope")).map { it.frameId })
    }

    @Test fun purgeNonPhotoDeletesSyntheticAndItsFile() {
        val synth = jpg("synthetic_1.jpg")
        FrameStreamStore.append(ctx, CapturedFrame("s1", 10, "rokid_glasses", "synthetic", synth))
        FrameStreamStore.append(ctx, CapturedFrame("r1", 11, "rokid_glasses", "real", jpg("r.jpg")))
        val r = FrameStreamStore.purgeNonPhotoAndSynthetic(ctx)
        assertEquals(1, r.kept); assertEquals(1, r.dropped); assertEquals(1, r.deletedFiles)
        assertFalse(File(synth).exists())
    }

    @Test fun tracesUpsertNewestFirst() {
        SummaryTraceStore.upsert(ctx, SummaryTrace("s1", "QUEUED", 1, "a"))
        SummaryTraceStore.upsert(ctx, SummaryTrace("s2", "QUEUED", 2, "b"))
        SummaryTraceStore.upsert(ctx, SummaryTrace("s1", "TRUSTED", 3, "c"))
        assertEquals(listOf("s1" to "TRUSTED", "s2" to "QUEUED"), SummaryTraceStore.recent(ctx, 10).map { it.summaryId to it.state })
    }

    @Test fun facadeTriggersImportOnFirstUse() {
        File(ctx.filesDir, "memory_records.log").writeText("rokid_mem_9\tshort_term\t0.8\t5\tImported before first use\n")
        assertEquals(listOf("rokid_mem_9"), MemoryRecordStore.recent(ctx, 10).map { it.id })
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :services:orchestration:testDebugUnitTest --tests "*LegacyFacadesTest"`
Expected: FAIL — `facadeTriggersImportOnFirstUse` returns empty (stores still read files) and in-memory DB is not used.

- [ ] **Step 3: `MemoryGraph.ensureLegacyImported`**

Add to `MemoryGraph`'s companion:
```kotlin
        private val importLock = Any()

        /** Runs the legacy import once, blocking, before the first facade read/write (Review Focus #5). */
        fun ensureLegacyImported(context: Context) {
            val graph = get(context)
            val importer = com.rokid.context.memory.legacy.LegacyImporter(context.applicationContext, graph)
            if (importer.isDone()) return
            synchronized(importLock) {
                if (!importer.isDone()) kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) { importer.importAll() }
            }
        }
```

- [ ] **Step 4: Rewrite `MemoryRecordStore` bodies (API unchanged)**

Replace the file body after the `MemoryRecord` data class with:
```kotlin
/** Transitional facade over Room (Phase 2); deleted with the legacy UI in Phase 4. */
object MemoryRecordStore {
    private val lowSignalMarkers = listOf(
        "no visual content", "only file references", "file references and timestamps", "metadata only", "no image pixels",
        "no visual content or scene details",
    )

    private fun <T> io(context: Context, block: suspend (com.rokid.context.memory.MemoryGraph) -> T): T {
        com.rokid.context.memory.MemoryGraph.ensureLegacyImported(context)
        val graph = com.rokid.context.memory.MemoryGraph.get(context)
        return kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) { block(graph) }
    }

    fun append(context: Context, record: MemoryRecord) = io(context) {
        val safe = record.content.replace("\n", " ").replace("\t", " ").take(260)
        it.memories.add(
            com.rokid.context.memory.legacy.LegacyImporter.toMemory(record.id, record.memoryType, record.confidence, record.createdAtEpochMs, safe),
        )
    }

    fun recent(context: Context, limit: Int): List<MemoryRecord> = io(context) { g ->
        g.memories.recent(limit).map { MemoryRecord(it.id, it.type, it.text, it.confidence, it.createdAt) }
    }

    fun purgeNonRokid(context: Context) = io(context) { g ->
        g.memories.deleteByIds(g.memories.all().filterNot { it.id.startsWith("rokid_", ignoreCase = true) }.map { it.id })
    }

    fun purgeLowSignalSummaries(context: Context) = io(context) { g ->
        g.memories.deleteByIds(
            g.memories.all().filter { m ->
                m.id.startsWith("rokid_review_", ignoreCase = true) || lowSignalMarkers.any { m.text.lowercase().contains(it) }
            }.map { it.id },
        )
    }
}
```
Remove the now-unused `import java.io.File`.
Note ordering: legacy `recent()` returned newest-*appended* first; Room orders by `createdAt DESC`. Every legacy writer sets `createdAtEpochMs = System.currentTimeMillis()`, so the orders agree.

In `MemoryRecordStoreTest.kt` add (and nothing else):
```kotlin
    @org.junit.Before fun setUp() = com.rokid.context.memory.MemoryGraph.resetForTests(com.rokid.context.memory.db.DatabaseFactory.inMemory(ctx))
    @org.junit.After fun tearDown() = com.rokid.context.memory.MemoryGraph.resetForTests(null)
```

- [ ] **Step 5: Rewrite `FrameStreamStore` bodies (API unchanged)**

Keep `CapturedFrame`, `PurgeResult`, `isRealPhotoFrame`, `ensurePreviewPath` and `TAG` as they are. Replace the other members:
```kotlin
    private fun <T> io(context: Context, block: suspend (com.rokid.context.memory.MemoryGraph) -> T): T {
        com.rokid.context.memory.MemoryGraph.ensureLegacyImported(context)
        val graph = com.rokid.context.memory.MemoryGraph.get(context)
        return kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) { block(graph) }
    }

    private fun com.rokid.context.memory.db.FrameEntity.toCaptured() = CapturedFrame(id, ts, source, payload, path)

    fun append(context: Context, frame: CapturedFrame): Boolean {
        if (!frame.source.startsWith("rokid", ignoreCase = true)) { Log.w(TAG, "append rejected non-rokid source=${frame.source}"); return false }
        val preview = ensurePreviewPath(frame)
        if (preview.isBlank()) { Log.w(TAG, "append rejected preview missing frameId=${frame.frameId} path=${frame.previewImagePath}"); return false }
        val safePayload = frame.payload.replace("\n", " ").replace("\t", " ").take(220)
        val ok = io(context) { it.frames.add(com.rokid.context.memory.db.FrameEntity(id = frame.frameId, ts = frame.capturedAtEpochMs, path = preview, source = frame.source, payload = safePayload)) }
        Log.i(TAG, "append ok=$ok frameId=${frame.frameId}")
        return ok
    }

    private fun existing(list: List<com.rokid.context.memory.db.FrameEntity>) =
        list.filter { it.source.startsWith("rokid", true) && it.path.isNotBlank() && File(it.path).exists() }.map { it.toCaptured() }

    fun recent(context: Context, limit: Int): List<CapturedFrame> = io(context) { existing(it.frames.recent(limit.coerceAtLeast(1) * 2)) }.take(limit.coerceAtLeast(1))
    fun all(context: Context): List<CapturedFrame> = io(context) { existing(it.frames.all()) }
    fun byIds(context: Context, frameIds: List<String>): List<CapturedFrame> {
        val wanted = frameIds.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        return if (wanted.isEmpty()) emptyList() else io(context) { existing(it.frames.byIds(wanted)) }
    }

    fun purgeNonRokidEntries(context: Context) = io(context) { g ->
        g.frames.deleteByIds(g.frames.all().filter { !it.source.startsWith("rokid", true) || it.path.isBlank() }.map { it.id })
    }

    fun purgeNonPhotoAndSynthetic(context: Context): PurgeResult = io(context) { g ->
        val all = g.frames.all().map { it.toCaptured() }
        val (kept, dropped) = all.partition(::isRealPhotoFrame)
        g.frames.deleteByIds(dropped.map { it.frameId })
        val deleted = dropped.map { it.previewImagePath }.filter { it.isNotBlank() }.distinct().count { File(it).let { f -> f.exists() && f.delete() } }
        PurgeResult(kept = kept.size, dropped = dropped.size, deletedFiles = deleted)
    }
```
Delete the old `lock`, `FILE_NAME`, `parse`, `file` members. `recent` over-fetches 2× before dropping rows whose JPEG vanished, then trims to `limit` (legacy semantics: newest existing frames).

- [ ] **Step 6: Rewrite `SummaryTraceStore` bodies (API unchanged)**

```kotlin
object SummaryTraceStore {
    private fun <T> io(context: Context, block: suspend (com.rokid.context.memory.MemoryGraph) -> T): T {
        com.rokid.context.memory.MemoryGraph.ensureLegacyImported(context)
        val graph = com.rokid.context.memory.MemoryGraph.get(context)
        return kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) { block(graph) }
    }

    fun upsert(context: Context, trace: SummaryTrace) = io(context) {
        it.traces.upsert(com.rokid.context.memory.db.SummaryTraceEntity(trace.summaryId, trace.state, trace.updatedAtEpochMs, trace.note.replace("\n", " ").replace("\t", " ")))
    }

    fun recent(context: Context, limit: Int): List<SummaryTrace> = io(context) { g ->
        g.traces.recent(limit).map { SummaryTrace(it.summaryId, it.state, it.updatedAt, it.note) }
    }

    fun purgeSynthetic(context: Context) = io(context) { g ->
        g.traces.deleteByIds(g.traces.recent(Int.MAX_VALUE).filter { it.note.contains("placeholder", true) || it.summaryId.startsWith("frame_ingest", true) }.map { it.summaryId })
    }
}
```
(Keep the `SummaryTrace` data class; remove the file-based members and `import java.io.File`.)

- [ ] **Step 7: Start the import early from `ContextApp`**

In `ContextApp.onCreate` after `EnvDefaults.init(...)`:
```kotlin
        com.rokid.context.memory.MemoryGraph.get(this) { com.rokid.context.data.runtime.AppConfigStore.get(this).encryptionEnabled }
        com.rokid.context.orchestration.LegacyImportWorker.enqueue(this)
```

- [ ] **Step 8: Run all tests**

Run: `./gradlew test -q`
Expected: PASS — including the unchanged-behaviour `MemoryRecordStoreTest` (now on Room) and `LegacyFacadesTest`.

- [ ] **Step 9: Device upgrade check (needs the user)**

Install over the existing app (do **not** uninstall — this exercises the real import):
```bash
ADB=/Users/debasishkanhar/Desktop/rokid/android-sdk/platform-tools/adb
$ADB -s <phone> install -r phone/build/outputs/apk/debug/phone-debug.apk
$ADB -s <phone> shell am start -n com.rokid.context/.MainActivity
$ADB -s <phone> logcat -d -s LegacyImportWorker AndroidRuntime | tail -20
```
Expected: `legacy import ImportReport(memories=…, frames=…, traces=…, skipped=…)`; no crash. Ask the user to open **Memory** and **Recent Frames** and confirm the same items as before the upgrade.

- [ ] **Step 10: Commit**

```bash
git add -A android-companion
git commit -m "feat(data): legacy stores now backed by Room; import on first use

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Endpoint registry, key vault, URL normalisation, endpoint test

**Files:**
- Modify: `android-companion/services/ai/build.gradle.kts`
- Modify: `android-companion/services/ai/src/main/java/com/rokid/context/model/provider/OpenAiCompatProvider.kt` (add `style`)
- Create: `.../services/ai/src/main/java/com/rokid/context/ai/{Endpoint.kt,UrlNormalizer.kt,KeyVault.kt,EndpointRepository.kt,EndpointTester.kt,ProviderFactory.kt}`
- Test: `.../services/ai/src/test/java/com/rokid/context/ai/{UrlNormalizerTest.kt,EndpointRepositoryTest.kt,EndpointTesterTest.kt}`

**Interfaces:**
- Consumes: `EndpointDao`, `MemoryGraph`.
- Produces: `enum EndpointKind {AZURE_OPENAI, OPENAI_COMPAT, OLLAMA, LLAMACPP_LOCAL}`, `enum Capability {CHAT, VISION, EMBEDDINGS, JSON_SCHEMA}`, `data class Endpoint(id, name, kind, baseUrl, apiVersion, models: List<String>, capabilities: Set<Capability>, createdAt)`; `UrlNormalizer.normalize(raw, kind): Result<String>`, `UrlNormalizer.cleanKey(raw): Result<String>`; `interface KeyVault { get(ref); put(ref, key); remove(ref) }`, `EncryptedKeyVault(context)`, `InMemoryKeyVault`; `EndpointRepository(dao, vault)` (`save(endpoint, apiKey: String?): Result<Endpoint>`, `all()`, `byId(id)`, `keyFor(endpoint)`, `delete(id)`); `ProviderFactory.create(endpoint, model, apiKey): ModelProvider`; `EndpointTester(repo, factory)` → `suspend fun test(endpointId, model): TestResult(ok, latencyMs, message)`; `OpenAiCompatProvider.ApiStyle {AZURE, OPENAI}`.

- [ ] **Step 1: Build deps**

In `services/ai/build.gradle.kts` add:
```kotlin
    api(project(":services:memory"))
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
```
and inside `android {}`: `testOptions { unitTests.isIncludeAndroidResources = true }`.

- [ ] **Step 2: Write the failing tests**

`services/ai/src/test/java/com/rokid/context/ai/UrlNormalizerTest.kt`:
```kotlin
package com.rokid.context.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlNormalizerTest {
    private fun ok(raw: String, kind: EndpointKind = EndpointKind.AZURE_OPENAI) = UrlNormalizer.normalize(raw, kind).getOrThrow()
    private fun err(raw: String, kind: EndpointKind = EndpointKind.AZURE_OPENAI) = UrlNormalizer.normalize(raw, kind).exceptionOrNull()!!.message!!

    @Test fun trimsLowercasesHostAndDropsTrailingSlash() =
        assertEquals("https://my-res.openai.azure.com/Path/V1", ok("  HTTPS://My-Res.OpenAI.Azure.com/Path/V1/  "))

    @Test fun addsSchemeByKind() {
        assertEquals("https://api.example.com", ok("api.example.com"))
        assertEquals("http://192.168.1.5:11434", ok("192.168.1.5:11434", EndpointKind.OLLAMA))
    }

    @Test fun dropsDefaultPort() = assertEquals("https://h.example", ok("https://h.example:443/"))

    @Test fun rejectsBadInput() {
        assertTrue(err("").contains("Enter"))
        assertTrue(err("https://bad host/x").contains("space"))
        assertTrue(err("ftp://h.example").contains("http"))
        assertTrue(err("https:///nohost").contains("host"))
    }

    @Test fun localModelPathIsNotAUrl() = assertEquals("/sdcard/m.gguf", ok("  /sdcard/m.gguf ", EndpointKind.LLAMACPP_LOCAL))

    @Test fun keysAreTrimmedAndMustNotContainSpaces() {
        assertEquals("abc123", UrlNormalizer.cleanKey("  abc123\n").getOrThrow())
        assertTrue(UrlNormalizer.cleanKey("abc 123").isFailure)
    }
}
```

`services/ai/src/test/java/com/rokid/context/ai/EndpointRepositoryTest.kt`:
```kotlin
package com.rokid.context.ai

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.DatabaseFactory
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class EndpointRepositoryTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val vault = InMemoryKeyVault()
    private val repo = EndpointRepository(db.endpointDao(), vault)
    @After fun close() = db.close()

    private fun e(url: String) = Endpoint("e1", "Azure", EndpointKind.AZURE_OPENAI, url, "2024-10-21", listOf("gpt-5.4"), setOf(Capability.CHAT, Capability.VISION), 1)

    @Test fun savesNormalisedUrlAndKeepsKeyOutOfRoom() = runTest {
        val saved = repo.save(e(" my-res.openai.azure.com/ "), apiKey = " secret ").getOrThrow()
        assertEquals("https://my-res.openai.azure.com", saved.baseUrl)
        assertEquals("secret", repo.keyFor(saved))
        assertFalse(db.endpointDao().byId("e1").toString().contains("secret"))
        assertEquals(setOf(Capability.CHAT, Capability.VISION), repo.byId("e1")!!.capabilities)
    }

    @Test fun rejectsBrokenInputWithoutStoringAnything() = runTest {
        assertTrue(repo.save(e("https://bad host"), apiKey = "k").isFailure)
        assertTrue(repo.all().isEmpty())
    }

    @Test fun nullKeyKeepsExistingKey() = runTest {
        repo.save(e("https://h.example"), apiKey = "k1").getOrThrow()
        repo.save(e("https://h.example").copy(name = "Renamed"), apiKey = null).getOrThrow()
        assertEquals("k1", repo.keyFor(repo.byId("e1")!!))
        assertEquals("Renamed", repo.byId("e1")!!.name)
    }

    @Test fun deleteRemovesKey() = runTest {
        val saved = repo.save(e("https://h.example"), apiKey = "k1").getOrThrow()
        repo.delete("e1")
        assertEquals(null, repo.byId("e1")); assertEquals(null, vault.get(EndpointRepository.keyRef(saved.id)))
    }
}
```

`services/ai/src/test/java/com/rokid/context/ai/EndpointTesterTest.kt`:
```kotlin
package com.rokid.context.ai

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.DatabaseFactory
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class EndpointTesterTest {
    private val server = MockWebServer().apply { start() }
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val repo = EndpointRepository(db.endpointDao(), InMemoryKeyVault())
    private val tester = EndpointTester(repo, ProviderFactory)
    @After fun close() { server.shutdown(); db.close() }

    private suspend fun save(kind: EndpointKind, caps: Set<Capability>) = repo.save(
        Endpoint("e", "t", kind, server.url("/").toString(), "2024-10-21", listOf("m"), caps, 1), apiKey = "k",
    ).getOrThrow()

    @Test fun azureChatSuccess() = runTest {
        save(EndpointKind.AZURE_OPENAI, setOf(Capability.CHAT))
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"OK"}}]}"""))
        val r = tester.test("e", "m")
        assertTrue(r.ok)
        assertTrue(server.takeRequest().path!!.startsWith("/openai/deployments/m/chat/completions?api-version=2024-10-21"))
    }

    @Test fun openAiCompatUsesBearerAndModelField() = runTest {
        save(EndpointKind.OPENAI_COMPAT, setOf(Capability.CHAT))
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"OK"}}]}"""))
        assertTrue(tester.test("e", "m").ok)
        val req = server.takeRequest()
        assertEquals("/chat/completions", req.path)
        assertEquals("Bearer k", req.getHeader("Authorization"))
        assertTrue(req.body.readUtf8().contains("\"model\":\"m\""))
    }

    @Test fun embeddingsOnlyEndpointIsTestedWithAnEmbedding() = runTest {
        save(EndpointKind.OLLAMA, setOf(Capability.EMBEDDINGS))
        server.enqueue(MockResponse().setBody("""{"embeddings":[[0.1,0.2,0.3]]}"""))
        val r = tester.test("e", "m")
        assertTrue(r.ok); assertTrue(r.message.contains("3"))
        assertEquals("/api/embed", server.takeRequest().path)
    }

    @Test fun failureMessageIsShortAndHuman() = runTest {
        save(EndpointKind.AZURE_OPENAI, setOf(Capability.CHAT))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"Access denied due to invalid subscription key."}}"""))
        val r = tester.test("e", "m")
        assertFalse(r.ok)
        assertEquals("Key rejected (401)", r.message)
    }
}
```

- [ ] **Step 3: Run to verify failure**

Run: `./gradlew :services:ai:testDebugUnitTest`
Expected: FAIL — unresolved `UrlNormalizer`, `Endpoint`, `EndpointRepository`, …

- [ ] **Step 4: `Endpoint.kt` + `UrlNormalizer.kt`**

`Endpoint.kt`:
```kotlin
package com.rokid.context.ai

enum class EndpointKind { AZURE_OPENAI, OPENAI_COMPAT, OLLAMA, LLAMACPP_LOCAL }
enum class Capability { CHAT, VISION, EMBEDDINGS, JSON_SCHEMA }

/** spec §4.1. For [EndpointKind.LLAMACPP_LOCAL], [baseUrl] holds the on-device model file path. */
data class Endpoint(
    val id: String,
    val name: String,
    val kind: EndpointKind,
    val baseUrl: String,
    val apiVersion: String?,
    val models: List<String>,
    val capabilities: Set<Capability>,
    val createdAt: Long,
)
```

`UrlNormalizer.kt`:
```kotlin
package com.rokid.context.ai

import java.net.URI

/** Review Focus #2: messy endpoint input is normalised or rejected with a short reason; never stored broken. */
object UrlNormalizer {
    fun normalize(raw: String, kind: EndpointKind): Result<String> = runCatching {
        val s = raw.trim()
        require(s.isNotEmpty()) { "Enter an address" }
        if (kind == EndpointKind.LLAMACPP_LOCAL) return@runCatching s
        require(s.none { it.isWhitespace() }) { "Address can't contain a space" }
        val withScheme = if ("://" in s) s else (if (kind == EndpointKind.OLLAMA) "http://" else "https://") + s
        val uri = URI(withScheme)
        val scheme = uri.scheme?.lowercase()
        require(scheme == "http" || scheme == "https") { "Address must start with http:// or https://" }
        val host = uri.host?.lowercase()
        require(!host.isNullOrBlank()) { "Address is missing a host" }
        val port = uri.port.takeUnless { it == -1 || (scheme == "https" && it == 443) || (scheme == "http" && it == 80) }
        val path = uri.rawPath.orEmpty().trimEnd('/')
        buildString {
            append(scheme).append("://").append(host)
            if (port != null) append(':').append(port)
            append(path)
        }
    }.recoverCatching { e -> throw IllegalArgumentException(if (e is IllegalArgumentException) e.message else "That doesn't look like a web address") }

    fun cleanKey(raw: String): Result<String> = runCatching {
        val k = raw.trim()
        require(k.isNotEmpty()) { "Enter a key" }
        require(k.none { it.isWhitespace() }) { "Key can't contain spaces" }
        k
    }
}
```

- [ ] **Step 5: `KeyVault.kt` + `EndpointRepository.kt`**

`KeyVault.kt`:
```kotlin
package com.rokid.context.ai

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

interface KeyVault {
    fun get(ref: String): String?
    fun put(ref: String, key: String)
    fun remove(ref: String)
}

class EncryptedKeyVault(context: Context) : KeyVault {
    private val prefs = EncryptedSharedPreferences.create(
        context, "endpoint_keys", MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
    override fun get(ref: String) = prefs.getString(ref, null)
    override fun put(ref: String, key: String) { prefs.edit().putString(ref, key).apply() }
    override fun remove(ref: String) { prefs.edit().remove(ref).apply() }
}

class InMemoryKeyVault : KeyVault {
    private val map = java.util.concurrent.ConcurrentHashMap<String, String>()
    override fun get(ref: String) = map[ref]
    override fun put(ref: String, key: String) { map[ref] = key }
    override fun remove(ref: String) { map.remove(ref) }
}
```

`EndpointRepository.kt`:
```kotlin
package com.rokid.context.ai

import com.rokid.context.memory.db.EndpointDao
import com.rokid.context.memory.db.EndpointEntity

class EndpointRepository(private val dao: EndpointDao, private val vault: KeyVault) {
    /** [apiKey] null = keep the stored key; blank = remove it (e.g. Ollama without auth). */
    suspend fun save(endpoint: Endpoint, apiKey: String?): Result<Endpoint> = runCatching {
        val url = UrlNormalizer.normalize(endpoint.baseUrl, endpoint.kind).getOrThrow()
        val key = apiKey?.takeIf { it.isNotBlank() }?.let { UrlNormalizer.cleanKey(it).getOrThrow() }
        val clean = endpoint.copy(baseUrl = url, models = endpoint.models.map { it.trim() }.filter { it.isNotEmpty() })
        dao.upsert(clean.toEntity())
        when {
            key != null -> vault.put(keyRef(clean.id), key)
            apiKey != null -> vault.remove(keyRef(clean.id))
        }
        clean
    }

    suspend fun all(): List<Endpoint> = dao.all().map { it.toDomain() }
    suspend fun byId(id: String): Endpoint? = dao.byId(id)?.toDomain()
    fun keyFor(endpoint: Endpoint): String? = vault.get(keyRef(endpoint.id))
    suspend fun delete(id: String) { dao.delete(id); vault.remove(keyRef(id)) }

    companion object {
        fun keyRef(id: String) = "key:$id"

        private fun Endpoint.toEntity() = EndpointEntity(
            id, name, kind.name, baseUrl, apiVersion, models.joinToString(","), keyRef(id), capabilities.joinToString(",") { it.name }, createdAt,
        )

        private fun EndpointEntity.toDomain() = Endpoint(
            id = id, name = name, kind = EndpointKind.valueOf(kind), baseUrl = baseUrl, apiVersion = apiVersion,
            models = modelsCsv.split(',').filter { it.isNotBlank() },
            capabilities = capabilitiesCsv.split(',').filter { it.isNotBlank() }.mapNotNull { runCatching { Capability.valueOf(it) }.getOrNull() }.toSet(),
            createdAt = createdAt,
        )
    }
}
```

- [ ] **Step 6: OpenAI-compatible style in the existing provider**

In `OpenAiCompatProvider.kt`:
- add constructor parameter after `apiVersion`: `private val style: ApiStyle = ApiStyle.AZURE,`
- add inside the class: `enum class ApiStyle { AZURE, OPENAI }`
- in `requestChatCompletion`, replace the `url` line and the `api-key` header with:
```kotlin
        val url = when (style) {
            ApiStyle.AZURE -> "$normalized/openai/deployments/$deployment/chat/completions?api-version=$apiVersion"
            ApiStyle.OPENAI -> "$normalized/chat/completions"
        }
        val body = if (style == ApiStyle.OPENAI) kotlinx.serialization.json.JsonObject(payload + ("model" to JsonPrimitive(deployment))) else payload
        val request = Request.Builder()
            .url(url)
            .apply { if (style == ApiStyle.AZURE) addHeader("api-key", apiKey) else addHeader("Authorization", "Bearer $apiKey") }
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
```
(and remove the old `.post(payload…)` line). Also change `require(apiKey.isNotBlank()) { "AZURE_KG_API_KEY is missing" }` to `if (style == ApiStyle.AZURE) require(apiKey.isNotBlank()) { "AZURE_KG_API_KEY is missing" }` — local OpenAI-compatible servers often need no key. Existing tests still pass (default `AZURE`).

- [ ] **Step 7: `ProviderFactory.kt` + `EndpointTester.kt`**

`ProviderFactory.kt`:
```kotlin
package com.rokid.context.ai

import com.rokid.context.model.ModelProvider
import com.rokid.context.model.provider.LlamaCppNativeProvider
import com.rokid.context.model.provider.OllamaProvider
import com.rokid.context.model.provider.OpenAiCompatProvider

fun interface ProviderBuilder { fun create(endpoint: Endpoint, model: String, apiKey: String?): ModelProvider }

object ProviderFactory : ProviderBuilder {
    override fun create(endpoint: Endpoint, model: String, apiKey: String?): ModelProvider = when (endpoint.kind) {
        EndpointKind.AZURE_OPENAI -> OpenAiCompatProvider(endpoint.baseUrl, apiKey.orEmpty(), model, endpoint.apiVersion ?: "2024-10-21", style = OpenAiCompatProvider.ApiStyle.AZURE)
        EndpointKind.OPENAI_COMPAT -> OpenAiCompatProvider(endpoint.baseUrl, apiKey.orEmpty(), model, endpoint.apiVersion ?: "", style = OpenAiCompatProvider.ApiStyle.OPENAI)
        EndpointKind.OLLAMA -> OllamaProvider(endpoint.baseUrl, model)
        EndpointKind.LLAMACPP_LOCAL -> LlamaCppNativeProvider(endpoint.baseUrl)
    }
}
```

`EndpointTester.kt`:
```kotlin
package com.rokid.context.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class TestResult(val ok: Boolean, val latencyMs: Long, val message: String)

/** spec §4.1 Test: a one-token chat, or one embedding for embedding-only endpoints. Messages are short and human. */
class EndpointTester(private val repo: EndpointRepository, private val providers: ProviderBuilder) {
    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()

    suspend fun test(endpointId: String, model: String): TestResult {
        val e = repo.byId(endpointId) ?: return TestResult(false, 0, "Endpoint not found")
        val key = repo.keyFor(e)
        val start = System.nanoTime()
        return runCatching {
            if (Capability.CHAT !in e.capabilities && Capability.EMBEDDINGS in e.capabilities) {
                val dim = embedProbe(e, model, key)
                TestResult(true, elapsed(start), "OK · $dim dimensions")
            } else {
                val out = providers.create(e, model, key).generate("Reply only with OK")
                TestResult(true, elapsed(start), "OK · ${out.trim().take(20)}")
            }
        }.getOrElse { TestResult(false, elapsed(start), humanize(it)) }
    }

    private fun elapsed(startNs: Long) = (System.nanoTime() - startNs) / 1_000_000

    private suspend fun embedProbe(e: Endpoint, model: String, key: String?): Int = withContext(Dispatchers.IO) {
        val (url, body) = when (e.kind) {
            EndpointKind.OLLAMA -> "${e.baseUrl}/api/embed" to """{"model":"$model","input":["ok"]}"""
            EndpointKind.AZURE_OPENAI -> "${e.baseUrl}/openai/deployments/$model/embeddings?api-version=${e.apiVersion ?: "2024-10-21"}" to """{"input":["ok"]}"""
            EndpointKind.OPENAI_COMPAT -> "${e.baseUrl}/embeddings" to """{"model":"$model","input":["ok"]}"""
            EndpointKind.LLAMACPP_LOCAL -> error("On-device embeddings are tested in Phase 5")
        }
        val req = Request.Builder().url(url).post(body.toRequestBody("application/json".toMediaType())).apply {
            when (e.kind) {
                EndpointKind.AZURE_OPENAI -> key?.let { addHeader("api-key", it) }
                EndpointKind.OPENAI_COMPAT -> key?.let { addHeader("Authorization", "Bearer $it") }
                else -> Unit
            }
        }.build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) error("HTTP ${r.code}")
            val json = Json.parseToJsonElement(text).jsonObject
            val vec = (json["embeddings"]?.jsonArray?.firstOrNull() as? JsonArray)
                ?: json["data"]?.jsonArray?.firstOrNull()?.jsonObject?.get("embedding")?.jsonArray
                ?: error("No embedding in response")
            vec.size
        }
    }

    companion object {
        fun humanize(t: Throwable): String {
            val m = t.message.orEmpty()
            val code = Regex("\\b(4\\d\\d|5\\d\\d)\\b").find(m)?.value
            return when {
                code == "401" || code == "403" -> "Key rejected ($code)"
                code == "404" -> "Not found — check address and model (404)"
                code != null && code.startsWith("5") -> "Server error ($code)"
                t is java.net.UnknownHostException -> "Can't reach that address"
                t is java.net.SocketTimeoutException || t is java.io.InterruptedIOException -> "Timed out"
                m.contains("missing", true) -> "Address or key missing"
                else -> "Test failed"
            }
        }
    }
}
```

- [ ] **Step 8: Run tests**

Run: `./gradlew :services:ai:testDebugUnitTest`
Expected: PASS (UrlNormalizerTest 6, EndpointRepositoryTest 4, EndpointTesterTest 4, plus earlier).

- [ ] **Step 9: Commit**

```bash
git add -A android-companion/services/ai
git commit -m "feat(ai): endpoint registry with encrypted keys, URL normalisation and endpoint test

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Role bindings + `ModelRouter`; workers use the router; seed from current config

**Files:**
- Create: `android-companion/services/ai/src/main/java/com/rokid/context/ai/{Role.kt,RoleBindingRepository.kt,ModelRouter.kt,EndpointSeeder.kt,AiGraph.kt}`
- Modify: `android-companion/services/orchestration/src/main/java/com/rokid/context/work/QueueDrainWorker.kt`
- Modify: `android-companion/phone/src/main/java/com/rokid/context/ContextApp.kt`
- Test: `.../services/ai/src/test/java/com/rokid/context/ai/{RoleBindingRepositoryTest.kt,ModelRouterTest.kt,EndpointSeederTest.kt}`

**Interfaces:**
- Consumes: Task 6 types; `ProviderSettings` (Phase 1).
- Produces: `enum Role {VISION_EXTRACT, EXPLICIT_REMEMBER, SUMMARIZE, CRITIC, ANSWER, NUDGE, EMBED}` with `requiredCapability`; `RoleBinding(role, endpointId, model, temperature, maxTokens, fallbackEndpointId, fallbackModel)`; `RoleBindingRepository(dao, endpoints)` (`get(role)`, `set(binding): Result<Unit>` — rejects endpoints lacking the capability, `eligibleEndpoints(role)`); `ModelRouter(bindings, endpoints, providers)` with `suspend fun generate(role: Role, prompt: String, images: List<ModelImageInput> = emptyList()): String` and `suspend fun generateFromFrames(prompt, images): String`; `EndpointSeeder(endpoints, bindings).seedIfEmpty(settings: ProviderSettings)`; `AiGraph` (`endpoints`, `bindings`, `router`, `tester`, `get(context)`, `resetForTests(graph)`).

- [ ] **Step 1: Write the failing tests**

`services/ai/src/test/java/com/rokid/context/ai/RoleBindingRepositoryTest.kt`:
```kotlin
package com.rokid.context.ai

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.DatabaseFactory
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RoleBindingRepositoryTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val endpoints = EndpointRepository(db.endpointDao(), InMemoryKeyVault())
    private val bindings = RoleBindingRepository(db.roleBindingDao(), endpoints)
    @After fun close() = db.close()

    private suspend fun ep(id: String, vararg caps: Capability) =
        endpoints.save(Endpoint(id, id, EndpointKind.OLLAMA, "http://h:1", null, listOf("m"), caps.toSet(), 1), apiKey = null).getOrThrow()

    @Test fun pickerListsOnlyCapableEndpoints() = runTest {
        ep("chat", Capability.CHAT); ep("vision", Capability.CHAT, Capability.VISION); ep("emb", Capability.EMBEDDINGS)
        assertEquals(listOf("vision"), bindings.eligibleEndpoints(Role.VISION_EXTRACT).map { it.id })
        assertEquals(listOf("emb"), bindings.eligibleEndpoints(Role.EMBED).map { it.id })
        assertEquals(listOf("chat", "vision"), bindings.eligibleEndpoints(Role.ANSWER).map { it.id })
    }

    @Test fun refusesBindingWithoutCapability() = runTest {
        ep("chat", Capability.CHAT)
        assertTrue(bindings.set(RoleBinding(Role.VISION_EXTRACT, "chat", "m")).isFailure)
        assertTrue(bindings.set(RoleBinding(Role.ANSWER, "chat", "m")).isSuccess)
        assertEquals("chat", bindings.get(Role.ANSWER)!!.endpointId)
    }
}
```

`services/ai/src/test/java/com/rokid/context/ai/ModelRouterTest.kt`:
```kotlin
package com.rokid.context.ai

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.model.ModelImageInput
import com.rokid.context.model.ModelProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ModelRouterTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val endpoints = EndpointRepository(db.endpointDao(), InMemoryKeyVault())
    private val bindings = RoleBindingRepository(db.roleBindingDao(), endpoints)
    private val calls = mutableListOf<String>()
    private val failing = mutableSetOf<String>()
    private val providers = ProviderBuilder { e, model, _ ->
        object : ModelProvider {
            override suspend fun generate(prompt: String): String { calls += "${e.id}:text"; if (e.id in failing) error("HTTP 500"); return "${e.id}/$model" }
            override suspend fun generateWithImages(prompt: String, images: List<ModelImageInput>): String { calls += "${e.id}:vision"; if ("${e.id}:vision" in failing) error("vision 400"); return "${e.id}/vision" }
        }
    }
    private val router = ModelRouter(bindings, endpoints, providers)
    @After fun close() = db.close()

    private suspend fun ep(id: String, vararg caps: Capability) =
        endpoints.save(Endpoint(id, id, EndpointKind.OLLAMA, "http://h:1", null, listOf("m"), caps.toSet(), 1), apiKey = null).getOrThrow()
    private val img = listOf(ModelImageInput("image/jpeg", "AAAA"))

    @Test fun usesPrimaryBinding() = runTest {
        ep("a", Capability.CHAT); bindings.set(RoleBinding(Role.ANSWER, "a", "m1")).getOrThrow()
        assertEquals("a/m1", router.generate(Role.ANSWER, "q"))
    }

    @Test fun fallsBackWhenPrimaryFails() = runTest {
        ep("a", Capability.CHAT); ep("b", Capability.CHAT)
        bindings.set(RoleBinding(Role.ANSWER, "a", "m1", fallbackEndpointId = "b", fallbackModel = "m2")).getOrThrow()
        failing += "a"
        assertEquals("b/m2", router.generate(Role.ANSWER, "q"))
    }

    @Test fun visionWhenCapableElseTextAndVisionFailureFallsBackToText() = runTest {
        ep("v", Capability.CHAT, Capability.VISION); ep("t", Capability.CHAT)
        bindings.set(RoleBinding(Role.SUMMARIZE, "t", "m")).getOrThrow()
        assertEquals("t/m", router.generate(Role.SUMMARIZE, "q", img))
        bindings.set(RoleBinding(Role.VISION_EXTRACT, "v", "m")).getOrThrow()
        assertEquals("v/vision", router.generate(Role.VISION_EXTRACT, "q", img))
        failing += "v:vision"
        assertEquals("v/m", router.generate(Role.VISION_EXTRACT, "q", img))
    }

    @Test fun framesUseSummarizeWhenNoVisionBinding() = runTest {
        ep("t", Capability.CHAT); bindings.set(RoleBinding(Role.SUMMARIZE, "t", "m")).getOrThrow()
        assertEquals("t/m", router.generateFromFrames("q", img))
        ep("v", Capability.CHAT, Capability.VISION); bindings.set(RoleBinding(Role.VISION_EXTRACT, "v", "m")).getOrThrow()
        assertEquals("v/vision", router.generateFromFrames("q", img))
    }

    @Test fun unboundRoleFailsWithClearMessage() = runTest {
        val e = runCatching { router.generate(Role.NUDGE, "q") }.exceptionOrNull()!!
        assertTrue(e.message!!.contains("No model is set for NUDGE"))
    }
}
```

`services/ai/src/test/java/com/rokid/context/ai/EndpointSeederTest.kt`:
```kotlin
package com.rokid.context.ai

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.DatabaseFactory
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class EndpointSeederTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val endpoints = EndpointRepository(db.endpointDao(), InMemoryKeyVault())
    private val bindings = RoleBindingRepository(db.roleBindingDao(), endpoints)
    private val seeder = EndpointSeeder(endpoints, bindings)
    @After fun close() = db.close()

    @Test fun seedsAzureFromCurrentConfigAndBindsChatRoles() = runTest {
        seeder.seedIfEmpty(ProviderSettings("openai", "https://az.example", "k", "gpt-5.4", nativeModelPath = "", localModeEnabled = false))
        val e = endpoints.all().single()
        assertEquals(EndpointKind.AZURE_OPENAI, e.kind)
        assertEquals("k", endpoints.keyFor(e))
        for (r in listOf(Role.VISION_EXTRACT, Role.EXPLICIT_REMEMBER, Role.SUMMARIZE, Role.CRITIC, Role.ANSWER, Role.NUDGE)) {
            assertEquals(e.id, bindings.get(r)!!.endpointId)
        }
        assertNull(bindings.get(Role.EMBED))
    }

    @Test fun localModeAddsLlamaCppFallback() = runTest {
        seeder.seedIfEmpty(ProviderSettings("openai", "https://az.example", "k", "gpt-5.4", nativeModelPath = "/m.gguf", localModeEnabled = true))
        val local = endpoints.all().single { it.kind == EndpointKind.LLAMACPP_LOCAL }
        assertEquals(local.id, bindings.get(Role.ANSWER)!!.fallbackEndpointId)
    }

    @Test fun doesNothingWhenEndpointsExist() = runTest {
        seeder.seedIfEmpty(ProviderSettings("ollama", "http://o:11434", "", "llama3.1:8b", "", false))
        seeder.seedIfEmpty(ProviderSettings("openai", "https://az.example", "k", "gpt", "", false))
        assertEquals(EndpointKind.OLLAMA, endpoints.all().single().kind)
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :services:ai:testDebugUnitTest`
Expected: FAIL — unresolved `Role`, `RoleBindingRepository`, `ModelRouter`, `EndpointSeeder`.

- [ ] **Step 3: `Role.kt` + `RoleBindingRepository.kt`**

`Role.kt`:
```kotlin
package com.rokid.context.ai

/** spec §4.2. */
enum class Role(val requiredCapability: Capability) {
    VISION_EXTRACT(Capability.VISION),
    EXPLICIT_REMEMBER(Capability.CHAT),
    SUMMARIZE(Capability.CHAT),
    CRITIC(Capability.CHAT),
    ANSWER(Capability.CHAT),
    NUDGE(Capability.CHAT),
    EMBED(Capability.EMBEDDINGS),
}

data class RoleBinding(
    val role: Role,
    val endpointId: String,
    val model: String,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val fallbackEndpointId: String? = null,
    val fallbackModel: String? = null,
)
```

`RoleBindingRepository.kt`:
```kotlin
package com.rokid.context.ai

import com.rokid.context.memory.db.RoleBindingDao
import com.rokid.context.memory.db.RoleBindingEntity

class RoleBindingRepository(private val dao: RoleBindingDao, private val endpoints: EndpointRepository) {
    suspend fun get(role: Role): RoleBinding? = dao.byRole(role.name)?.let {
        RoleBinding(role, it.endpointId, it.model, it.temperature, it.maxTokens, it.fallbackEndpointId, it.fallbackModel)
    }

    suspend fun eligibleEndpoints(role: Role): List<Endpoint> = endpoints.all().filter { role.requiredCapability in it.capabilities }

    suspend fun set(binding: RoleBinding): Result<Unit> = runCatching {
        val eligible = eligibleEndpoints(binding.role).map { it.id }.toSet()
        require(binding.endpointId in eligible) { "That endpoint can't do ${binding.role.requiredCapability.name.lowercase()}" }
        binding.fallbackEndpointId?.let { require(it in eligible) { "Fallback endpoint can't do ${binding.role.requiredCapability.name.lowercase()}" } }
        dao.upsert(RoleBindingEntity(binding.role.name, binding.endpointId, binding.model, binding.temperature, binding.maxTokens, binding.fallbackEndpointId, binding.fallbackModel))
    }
}
```

- [ ] **Step 4: `ModelRouter.kt`** (replaces `QueueDrainWorker.generateWithFallback` / `generateBatchedSummary` semantics)

```kotlin
package com.rokid.context.ai

import android.util.Log
import com.rokid.context.model.ModelImageInput

/**
 * Routes a role's call to its bound endpoint. Vision is used only when the endpoint has VISION and images are given;
 * a vision failure retries as text on the same endpoint; a primary failure retries on the fallback binding.
 * Error messages keep the provider's text so the queue's retry classifier still works.
 */
class ModelRouter(
    private val bindings: RoleBindingRepository,
    private val endpoints: EndpointRepository,
    private val providers: ProviderBuilder,
) {
    suspend fun generate(role: Role, prompt: String, images: List<ModelImageInput> = emptyList()): String {
        val b = bindings.get(role) ?: error("No model is set for ${role.name}")
        val primary = endpoints.byId(b.endpointId) ?: error("Endpoint for ${role.name} was deleted")
        return runCatching { call(primary, b.model, prompt, images) }.getOrElse { first ->
            val fbId = b.fallbackEndpointId ?: throw first
            val fb = endpoints.byId(fbId) ?: throw first
            runCatching { call(fb, b.fallbackModel ?: b.model, prompt, images) }
                .getOrElse { throw IllegalStateException("Primary+fallback generation failed: ${first.message}; ${it.message}") }
        }
    }

    /**
     * Frame summarisation: VISION_EXTRACT when bound, else SUMMARIZE as text. Keeps text-only setups (e.g. Ollama seeded
     * without VISION) summarising exactly as the legacy worker did.
     */
    suspend fun generateFromFrames(prompt: String, images: List<ModelImageInput>): String =
        if (bindings.get(Role.VISION_EXTRACT) != null) generate(Role.VISION_EXTRACT, prompt, images) else generate(Role.SUMMARIZE, prompt, images)

    private suspend fun call(e: Endpoint, model: String, prompt: String, images: List<ModelImageInput>): String {
        val p = providers.create(e, model, endpoints.keyFor(e))
        if (images.isNotEmpty() && Capability.VISION in e.capabilities) {
            runCatching { return p.generateWithImages(prompt, images) }.onFailure { Log.w(TAG, "vision failed on ${e.name}, retrying as text: ${it.message}") }
        }
        return p.generate(prompt)
    }

    private companion object { const val TAG = "ModelRouter" }
}
```

- [ ] **Step 5: `EndpointSeeder.kt` + `AiGraph.kt`**

`EndpointSeeder.kt`:
```kotlin
package com.rokid.context.ai

/** First run on the registry: turn the legacy single-provider config into endpoints + bindings (spec §4.1 seed). */
class EndpointSeeder(private val endpoints: EndpointRepository, private val bindings: RoleBindingRepository) {
    suspend fun seedIfEmpty(settings: ProviderSettings) {
        if (endpoints.all().isNotEmpty()) return
        val now = System.currentTimeMillis()
        val primary = when (settings.providerType.lowercase()) {
            "ollama" -> Endpoint("ollama", "Ollama", EndpointKind.OLLAMA, settings.endpoint.ifBlank { EnvDefaults.ollamaBaseUrl }, null,
                listOf(settings.deploymentOrModel.ifBlank { EnvDefaults.ollamaModel }), setOf(Capability.CHAT), now)
            "llama_cpp" -> Endpoint("llamacpp", "On-device (llama.cpp)", EndpointKind.LLAMACPP_LOCAL, settings.nativeModelPath, null,
                listOf("local"), setOf(Capability.CHAT), now)
            else -> Endpoint("azure", "Azure OpenAI", EndpointKind.AZURE_OPENAI, settings.endpoint.ifBlank { EnvDefaults.azureEndpoint }, "2024-10-21",
                listOf(settings.deploymentOrModel.ifBlank { EnvDefaults.azureDeployment }), setOf(Capability.CHAT, Capability.VISION, Capability.JSON_SCHEMA), now)
        }
        val saved = endpoints.save(primary, apiKey = settings.apiKey.ifBlank { null }).getOrNull() ?: return
        val local = if (settings.localModeEnabled && settings.nativeModelPath.isNotBlank() && saved.kind != EndpointKind.LLAMACPP_LOCAL) {
            endpoints.save(Endpoint("llamacpp", "On-device (llama.cpp)", EndpointKind.LLAMACPP_LOCAL, settings.nativeModelPath, null, listOf("local"), setOf(Capability.CHAT), now), apiKey = null).getOrNull()
        } else null
        val model = saved.models.firstOrNull().orEmpty()
        for (role in Role.entries) {
            if (role == Role.EMBED || role.requiredCapability !in saved.capabilities) continue
            // Text roles get the on-device fallback (legacy `localFallback`); VISION_EXTRACT can't, the local endpoint has no VISION.
            val fallback = local?.takeIf { role.requiredCapability in it.capabilities }
            bindings.set(RoleBinding(role, saved.id, model, fallbackEndpointId = fallback?.id, fallbackModel = fallback?.models?.firstOrNull()))
        }
    }
}
```

`AiGraph.kt`:
```kotlin
package com.rokid.context.ai

import android.content.Context
import com.rokid.context.memory.MemoryGraph

class AiGraph(memory: MemoryGraph, vault: KeyVault, providers: ProviderBuilder = ProviderFactory) {
    val endpoints = EndpointRepository(memory.db.endpointDao(), vault)
    val bindings = RoleBindingRepository(memory.db.roleBindingDao(), endpoints)
    val router = ModelRouter(bindings, endpoints, providers)
    val tester = EndpointTester(endpoints, providers)
    val seeder = EndpointSeeder(endpoints, bindings)

    companion object {
        @Volatile private var instance: AiGraph? = null
        fun get(context: Context): AiGraph = instance ?: synchronized(this) {
            instance ?: AiGraph(MemoryGraph.get(context), EncryptedKeyVault(context.applicationContext)).also { instance = it }
        }
        fun resetForTests(graph: AiGraph?) { synchronized(this) { instance = graph } }
    }
}
```

- [ ] **Step 6: Run ai tests**

Run: `./gradlew :services:ai:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 7: Workers use the router**

In `services/orchestration/src/main/java/com/rokid/context/work/QueueDrainWorker.kt`:
- Add imports `com.rokid.context.ai.AiGraph`, `com.rokid.context.ai.Role`; remove `ModelProviderResolver` and `toProviderSettings` imports.
- Replace the two private functions `generateWithFallback(...)` and `generateBatchedSummary(...)` with:
```kotlin
    private suspend fun generateWithFallback(role: Role, prompt: String): String =
        AiGraph.get(applicationContext).router.generate(role, prompt)

    private suspend fun generateBatchedSummary(prompt: String, frames: List<com.rokid.context.data.frames.CapturedFrame>): String =
        AiGraph.get(applicationContext).router.generateFromFrames(prompt, images = buildVisionInputs(frames, maxImages = 3))
```
- Update the 4 call sites:
  - in `processBatchedSummarization` and `processPriorityReprocess`: `generateBatchedSummary(prompt, config, frames)` → `generateBatchedSummary(prompt, frames)` (both used the vision-then-text path before; `generateFromFrames` keeps that)
  - in `processExplicitWrite`: `generateWithFallback(prompt, config)` → `generateWithFallback(Role.EXPLICIT_REMEMBER, prompt)`
  - in `processReadContext`: `generateWithFallback(prompt, config)` → `generateWithFallback(Role.ANSWER, prompt)`
- Leave any remaining `val config = AppConfigStore.get(...)` lines that are still used for other fields; delete only those that become unused (the compiler warns).

The phone's Config screen **Test** button keeps using `ModelProviderResolver` with the typed-in values until Phase 5 replaces that screen; `ModelProviderResolver`/`ProviderSettings` stay for that and for seeding.

- [ ] **Step 8: Seed at startup**

In `ContextApp.onCreate`, after the `MemoryGraph.get(...)` line:
```kotlin
        kotlin.concurrent.thread(name = "ai-seed") {
            kotlinx.coroutines.runBlocking {
                com.rokid.context.ai.AiGraph.get(this@ContextApp).seeder.seedIfEmpty(
                    com.rokid.context.data.runtime.AppConfigStore.get(this@ContextApp).toProviderSettings(),
                )
            }
        }
```
Add `import com.rokid.context.data.runtime.toProviderSettings`. (A worker that runs before seeding finishes fails with "No model is set for …", which the queue treats as retryable — see Step 9.)

- [ ] **Step 9: Keep the retry classifier correct**

"No model is set for …" must stay retryable (seeding may finish after the first worker run). `isRetryable` is private to the worker, so check its marker list:
```bash
grep -A25 "private fun isRetryable" services/orchestration/src/main/java/com/rokid/context/work/QueueDrainWorker.kt | grep -iE '"(no model|is set|deleted)' || echo retryable
```
Expected: `retryable`.

- [ ] **Step 10: Run all tests and build**

Run: `./gradlew test :phone:assembleDebug :glasses:assembleDebug -q`
Expected: PASS.

- [ ] **Step 11: Device check (needs the user)**

Install over the existing app; ask the user to trigger **Run Smoke** / **Trigger Summarizer** with the glasses sending frames (or use `tools/sync_rokid_frame.sh`). Then:
```bash
$ADB -s <phone> logcat -d -s QueueDrainWorker ModelRouter AndroidRuntime | tail -40
```
Expected: summarisation completes as before (`TRUSTED` or `NEEDS_REVIEW` traces), no crash; Memory screen shows the new record.

- [ ] **Step 12: Commit**

```bash
git add -A android-companion
git commit -m "feat(ai): role bindings and ModelRouter; workers route by role; seed from legacy config

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

Phase 2 done when: all tests green, both APKs build, upgrade import verified on device, summarisation works through the router. R-40 (logic), R-41 (kinds/capability + embedding probe), R-42 (logic), R-48 (FTS part), R-70, R-71 covered.

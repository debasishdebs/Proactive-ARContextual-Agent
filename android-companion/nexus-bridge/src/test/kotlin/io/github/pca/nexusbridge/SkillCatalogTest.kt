package io.github.pca.nexusbridge

import com.anezium.rokidbus.shared.skills.SkillCatalogParseResult
import com.anezium.rokidbus.shared.skills.SkillCatalogParser
import com.anezium.rokidbus.shared.skills.SkillDataCategory
import com.anezium.rokidbus.shared.skills.SkillEffect
import com.anezium.rokidbus.shared.skills.SkillSchema
import com.anezium.rokidbus.shared.skills.SkillSchemaValidator
import com.anezium.rokidbus.shared.skills.SkillValidation
import io.github.pca.nexusport.SkillOp
import io.github.pca.nexusport.SkillState
import org.json.JSONObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The catalog PCA ships (res/raw/nexus_skills.json) passes Nexus's OWN parser (`SkillCatalogParser` from the pinned `shared` artifact, as the S1 finding requires: `data` non-empty from the fixed vocabulary,
 * string outputs are enums), has exactly four operations, and cannot carry memory content: the only output is the `state` enum.
 */
class SkillCatalogTest {
    private val module = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }.resolve("nexus-bridge")
    private val json = module.resolve("src/main/res/raw/nexus_skills.json").readText()
    private val catalog = (SkillCatalogParser.parse(json) as? SkillCatalogParseResult.Valid ?: error("catalog invalid: " + (SkillCatalogParser.parse(json) as SkillCatalogParseResult.Invalid).reason)).catalog

    @Test fun theCatalogPassesTheSharedParser() {
        assertEquals(SkillCatalogParser.VERSION, catalog.version)
        assertTrue(json.toByteArray().size < 64 * 1024)
    }

    @Test fun exactlyTheFourPortOperationsAndNoOther() {
        assertEquals(SkillOp.entries.map { it.id }.sorted(), catalog.operations.map { it.id }.sorted())
        assertEquals(4, catalog.operations.size)
        for (op in catalog.operations) assertEquals(if (SkillOp.of(op.id)!!.isAction) SkillEffect.ACTION else SkillEffect.READ, op.effect, op.id)
    }

    @Test fun noOperationCanReturnMemoryContent() {
        for (op in catalog.operations) {
            assertEquals(setOf(SkillDataCategory.STATUS), op.dataCategories, op.id)          // never text, place_names, schedules, itinerary...
            assertTrue(op.input.properties.isEmpty(), "${op.id}: no arguments to inject through")
            assertEquals(setOf("state"), op.output.properties.keys, op.id)
            val state = op.output.properties.getValue("state") as SkillSchema.StringType
            assertEquals(SkillState.ALL, state.enumValues.orEmpty().toSet(), op.id)                      // the port's enum and the catalog's enum are one thing
            assertTrue(op.outputReferenceTypes.isEmpty(), "${op.id}: no references")
            assertTrue(op.requires.isEmpty() && op.prerequisites.isEmpty(), op.id)
        }
    }

    @Test fun theSharedValidatorAcceptsEveryStateAndRefusesAnythingElse() {
        for (op in catalog.operations) {
            for (s in SkillState.ALL) assertTrue(SkillSchemaValidator.validate(JSONObject().put("state", s), op.output) is SkillValidation.Valid, "${op.id} $s")
            for (bad in listOf("Charminar", "home", "")) assertTrue(SkillSchemaValidator.validate(JSONObject().put("state", bad), op.output) !is SkillValidation.Valid, "${op.id} $bad")
            assertTrue(SkillSchemaValidator.validate(JSONObject().put("state", "paused").put("text", "memory"), op.output) !is SkillValidation.Valid, "${op.id}: unknown properties")
        }
    }

    @Test fun theValidatorRejectsTheTwoFirstDraftMistakesOfTheSpike() {
        // S1: empty `data` and an open string output made the hub read the catalog as invalid.
        val noData = json.replaceFirst(Regex("\"data\": \\[[^\\]]*\\]"), "\"data\": []")
        assertTrue(noData != json && SkillCatalogParser.parse(noData) is SkillCatalogParseResult.Invalid)
        val openString = json.replaceFirst(Regex("\"enum\": \\[[^\\]]*\\]"), "\"minLength\": 1")
        assertTrue(openString != json && SkillCatalogParser.parse(openString) is SkillCatalogParseResult.Invalid)
    }

    @Test fun theManifestPointsAtTheCatalogAndNothingMoreWasAdded() {
        val manifest = module.resolve("src/main/AndroidManifest.xml").readText()
        assertTrue("android:resource=\"@raw/nexus_skills\"" in manifest && "plugin.SKILLS\"" in manifest)
        assertTrue(module.resolve("src/main/res/raw").listFiles()!!.map { it.name } == listOf("nexus_skills.json"))
    }
}

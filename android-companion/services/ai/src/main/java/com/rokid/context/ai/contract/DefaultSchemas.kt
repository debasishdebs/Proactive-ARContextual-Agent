package com.rokid.context.ai.contract

import com.rokid.context.ai.schema.SchemaRefs
import kotlinx.serialization.json.JsonObject

/**
 * Role default (= contract base) schemas, spec §4.4. `ProposedAction` is one shared `$defs` union (discriminated by a
 * single-value `type` enum) referenced by ANSWER and NUDGE. Dates are ISO-8601 local date-times ("2026-10-12T18:30").
 */
object DefaultSchemas {
    private const val DEFS = "\$defs"
    private const val REF = "\$ref"
    private const val LDT = "^\\\\d{4}-\\\\d{2}-\\\\d{2}T\\\\d{2}:\\\\d{2}(:\\\\d{2})?$"

    private val PROPOSED_ACTION = """
        {"oneOf":[
          {"type":"object","properties":{"type":{"type":"string","enum":["REMINDER"]},
            "title":{"type":"string","minLength":1,"maxLength":80},
            "dueAt":{"type":"string","pattern":"$LDT","description":"Local date-time, e.g. 2026-10-12T18:30; must be in the future"},
            "note":{"type":"string","maxLength":200}},
           "required":["type","title","dueAt"],"additionalProperties":false},
          {"type":"object","properties":{"type":{"type":"string","enum":["SHOPPING_ADD"]},
            "items":{"type":"array","minItems":1,"maxItems":10,"items":{"type":"object","properties":{
              "name":{"type":"string","minLength":1,"maxLength":40},"qty":{"type":"number","exclusiveMinimum":0},"unit":{"type":"string","maxLength":10}},
              "required":["name"],"additionalProperties":false}}},
           "required":["type","items"],"additionalProperties":false},
          {"type":"object","properties":{"type":{"type":"string","enum":["NOTE_ADD"]},
            "text":{"type":"string","minLength":1,"maxLength":500,"description":"The note itself, trimmed, without 'note that', 'make a note' or 'remember that'"}},
           "required":["type","text"],"additionalProperties":false},
          {"type":"object","properties":{"type":{"type":"string","enum":["CALENDAR_EVENT"]},
            "title":{"type":"string","minLength":1,"maxLength":80},
            "startAt":{"type":"string","pattern":"$LDT"},
            "endAt":{"type":"string","pattern":"$LDT","description":"After startAt; default startAt + 30 min"},
            "location":{"type":"string","maxLength":80}},
           "required":["type","title","startAt"],"additionalProperties":false}]}
    """.trimIndent()

    private val FACTS = """
        {"type":"object","properties":{"facts":{"type":"array","maxItems":12,"items":{"type":"object","properties":{
          "text":{"type":"string","minLength":1,"maxLength":200,"description":"One short, specific fact"},
          "type":{"type":"string","maxLength":20,"description":"object, place, person, event or note"},
          "confidence":{"type":"number","minimum":0,"maximum":1},
          "tags":{"type":"array","maxItems":5,"items":{"type":"string","maxLength":30}}},
          "required":["text","type","confidence","tags"],"additionalProperties":false}}},
         "required":["facts"],"additionalProperties":false}
    """.trimIndent()

    private val SUMMARY = """
        {"type":"object","properties":{"summary":{"type":"string","minLength":1,"maxLength":1000},
          "events":{"type":"array","items":{"type":"string"}},"entities":{"type":"array","items":{"type":"string"}},
          "confidence":{"type":"number","minimum":0,"maximum":1},
          "candidate_memory_type":{"type":"string","enum":["short_term","medium_term"]}},
         "required":["summary","events","entities","confidence","candidate_memory_type"],"additionalProperties":false}
    """.trimIndent()

    private val CRITIC = """
        {"type":"object","properties":{"verdict":{"type":"string","enum":["pass","fail"]},
          "reasons":{"type":"array","maxItems":5,"items":{"type":"string","maxLength":200}}},
         "required":["verdict","reasons"],"additionalProperties":false}
    """.trimIndent()

    private val ANSWER = """
        {"type":"object","properties":{"answer":{"type":"string","minLength":1,"maxLength":600},
          "sources":{"type":"array","maxItems":8,"items":{"type":"string"},"description":"Ids of the memories the answer used"},
          "proposedAction":{"$REF":"#/$DEFS/ProposedAction"}},
         "required":["answer","sources"],"additionalProperties":false,
         "$DEFS":{"ProposedAction":$PROPOSED_ACTION}}
    """.trimIndent()

    private val NUDGE = """
        {"type":"object","properties":{"shouldNudge":{"type":"boolean"},"text":{"type":"string","maxLength":120},
          "why":{"type":"string","maxLength":200},"sourceIds":{"type":"array","maxItems":8,"items":{"type":"string"}},
          "priority":{"type":"string","enum":["low","normal","high"]},"proposedAction":{"$REF":"#/$DEFS/ProposedAction"}},
         "required":["shouldNudge","text","why","sourceIds","priority"],"additionalProperties":false,
         "$DEFS":{"ProposedAction":$PROPOSED_ACTION}}
    """.trimIndent()

    fun text(key: PromptKey): String? = when (key) {
        PromptKey.SYSTEM -> null
        PromptKey.VISION_EXTRACT, PromptKey.EXPLICIT_REMEMBER -> FACTS
        PromptKey.SUMMARIZE -> SUMMARY
        PromptKey.CRITIC -> CRITIC
        PromptKey.ANSWER -> ANSWER
        PromptKey.NUDGE -> NUDGE
    }

    fun forKey(key: PromptKey): JsonObject? = text(key)?.let(SchemaRefs::parse)
}

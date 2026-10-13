package com.rokid.context.ai.run

import com.rokid.context.ai.contract.PromptKey
import com.rokid.context.prompt.PromptDraft
import com.rokid.context.prompt.TemplateVariables

/**
 * spec §4.5 "Save as vN & activate": (1) schema compatible, (2) a test run completed, (3) its output matched the draft
 * schema, (4) it decoded through the role contract, (5) it passed the semantic checks — all for this exact draft —
 * plus the template's variables (required present, none unknown). SYSTEM has no output, so only the variables count.
 */
data class SaveGates(val compatible: Boolean, val variables: Boolean, val ran: Boolean, val validated: Boolean, val decoded: Boolean, val semantic: Boolean) {
    val all: Boolean get() = compatible && variables && ran && validated && decoded && semantic

    companion object {
        /** [compat] must be the checker's result for this [draft]; callers whose check may lag use the [checked] overload. */
        fun of(key: PromptKey, draft: PromptDraft, compat: List<String>, run: Pair<PromptDraft, TestBedResult>?): SaveGates =
            of(key, draft, draft to compat, run)

        /**
         * Gate 1 counts only for the draft the checker saw ([checked].first): it is ✕ before any check has finished and
         * goes stale the moment the draft changes (preflight F19).
         */
        fun of(key: PromptKey, draft: PromptDraft, checked: Pair<PromptDraft, List<String>>?, run: Pair<PromptDraft, TestBedResult>?): SaveGates {
            val variables = draft.template.isNotBlank() && TemplateVariables.missing(key, draft.template).isEmpty() && TemplateVariables.unknown(draft.template).isEmpty()
            if (key == PromptKey.SYSTEM) return SaveGates(true, variables, true, true, true, true)
            val compatible = checked != null && checked.first == draft && checked.second.isEmpty()
            val r = run?.takeIf { it.first == draft }?.second
            val e = r?.evaluation
            return SaveGates(compatible, variables, r != null && r.failure == null && e != null, e?.schemaValid == true, e?.decoded == true, e?.semanticOk == true)
        }
    }
}

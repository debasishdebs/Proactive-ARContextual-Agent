package com.rokid.context.memory.forget.lifecycle

/**
 * The families of the Forget lifecycle (design 2026-11-16, section 2.1): every table, every deletable object and every holder of user-derived state belongs to exactly ONE of them.
 * The first group is what a Forget can reach; [AUDIO], [PERSON] and [OBJECT] are reserved (no store yet); the last four never are a deletion target of a user Forget:
 * [PLACE_PACK] (public place text, not derived from a photo), [PLACE_CARD] (an in-memory card), [CONFIG] (settings and user lists) and [CONTROL] (fences, outbox, counters).
 */
enum class Family(val token: String) {
    PHOTO("photo"), MEMORY("memory"), MOMENT("moment"), SCENE("scene"), DERIVED_FIELD("derived"), MARK("mark"), TRACE("trace"), RUN_RESULT("run"),
    ANSWER("answer"), NUDGE("nudge"), PROPOSAL("proposal"),
    AUDIO("audio"), PERSON("person"), OBJECT("object"),
    PLACE_PACK("placepack"), PLACE_CARD("placecard"), CONFIG("config"), CONTROL("control"),
}

/** What a Forget does to a node: ERASE user data; REBUILD = content erased now (row kept, regenerated or left empty); DROP = a regenerable result removed. ERASE beats REBUILD, REBUILD beats DROP. */
enum class Fate { ERASE, REBUILD, DROP }

/** Why one node depends on another (design 2.1). */
enum class EdgeKind { CITES, DERIVED_FROM, CARRIED, CONTEXT, MEMBER, LINEAGE, TIME, ORIGIN }

/** `"<family>:<id>"`; ids are content-free strings (a row id, never a text). */
data class Ref(val family: Family, val id: String) {
    override fun toString() = "${family.token}:$id"
    companion object {
        fun parse(s: String): Ref? {
            val i = s.indexOf(':'); if (i <= 0) return null
            val f = Family.entries.firstOrNull { it.token == s.substring(0, i) } ?: return null
            return Ref(f, s.substring(i + 1))
        }
    }
}

/** A downstream edge of a SET of ids of family [from]: [to] depends on at least one of them (forgetting them reaches [to]). */
data class Edge(val from: Family, val to: Ref, val kind: EdgeKind)

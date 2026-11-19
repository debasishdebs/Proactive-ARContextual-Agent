package com.rokid.context.memory.forget.lifecycle

/** One child item a Forget would reach: its [id] and, for a few-item family (pins, marks) only, the [name] the user knows it by. UI only: a name never goes into a log. */
data class PolicyChild(val id: String, val name: String? = null)

/** What the plan says about ONE child family: the [action] the table gives it for the source, how many items, and (few-item families only) up to [PolicyPlan.NAMES_SHOWN] names and the exact [ids]. */
class PolicyPlanEntry(val child: ForgetChild, val action: PolicyAction, val count: Int, val names: List<String>, val ids: Set<String>) {
    /** Counts and kinds only: never a name or an id. */
    override fun toString() = "${child.name} $action x$count"
}

/**
 * STEP 1 of the Forget redesign: WHAT a Forget of [source] forgets and what it only de-links, per child family, with counts and (pins, marks) names. A value type built by the PURE [of] from the source, the number
 * of targets and the children found, using [ForgetPolicy]. Nothing reads it in production yet (the dialog and the executor are wired to it in step 2), so it changes no behaviour. It is not the planner's
 * [ForgetPlan] (node -> Fate of the rows a Forget touches); it is the user-facing disposition view of the same Forget.
 */
class PolicyPlan private constructor(val source: ForgetSource, val targetCount: Int, val entries: List<PolicyPlanEntry>) {
    private fun of(action: PolicyAction) = entries.filter { it.action == action }
    /** Deleted: named (few-item) or counted in the confirmation. */
    val forgotten: List<PolicyPlanEntry> get() = of(PolicyAction.FORGET)
    /** Only the link is cleared; the child stays. */
    val delinked: List<PolicyPlanEntry> get() = of(PolicyAction.DE_LINK)
    /** Left to the existing planner / executor rules, row by row. */
    val cascaded: List<PolicyPlanEntry> get() = of(PolicyAction.CASCADE_BY_EXISTING_RULES)
    /** Found but untouched by this source. */
    val kept: List<PolicyPlanEntry> get() = of(PolicyAction.KEEP)

    fun entry(child: ForgetChild): PolicyPlanEntry? = entries.firstOrNull { it.child == child }
    /** Items of [child] that are deleted (0 when it is not FORGET). */
    fun forgottenCount(child: ForgetChild): Int = entry(child)?.takeIf { it.action == PolicyAction.FORGET }?.count ?: 0
    fun delinkedCount(child: ForgetChild): Int = entry(child)?.takeIf { it.action == PolicyAction.DE_LINK }?.count ?: 0

    /** Counts and kinds only, safe for a log line. */
    override fun toString() = "PolicyPlan($source targets=$targetCount ${entries.joinToString { it.toString() }})"

    companion object {
        const val NAMES_SHOWN = 5

        /**
         * PURE: the same inputs give the same plan. [children] are the items found per child family (an empty or absent family gives no entry). The action of each family is [ForgetPolicy.action] for the
         * source kind. Few-item families ([ForgetChild.fewItems]) carry up to [NAMES_SHOWN] names (items with no name are skipped) and all their ids; the others carry the count only.
         */
        fun of(source: ForgetSource, targetCount: Int, children: Map<ForgetChild, Collection<PolicyChild>>): PolicyPlan {
            val entries = ForgetChild.entries.mapNotNull { child ->
                val items = children[child].orEmpty().distinctBy { it.id }
                if (items.isEmpty()) return@mapNotNull null
                val few = child.fewItems
                PolicyPlanEntry(
                    child, ForgetPolicy.action(child, source), items.size,
                    if (few) items.mapNotNull { it.name }.take(NAMES_SHOWN) else emptyList(),
                    if (few) items.map { it.id }.toSet() else emptySet(),
                )
            }
            return PolicyPlan(source, targetCount, entries)
        }
    }
}

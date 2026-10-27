package com.rokid.context.memory.forget

/**
 * How much a granular Forget (Forget photo, Forget fact, Forget Moment, Forget Scene) removes (user ruling 2026-10-27): the photo alone, the whole Moment around it, or the whole Scene. [level] orders them; an
 * entry that is itself a Moment or a Scene never forgets less than that ([atLeast]). Delete all and Forget a time range are not scoped.
 */
enum class ForgetScope(val level: Int) {
    PHOTO(0), MOMENT(1), SCENE(2);

    /** The scope a Forget really runs at: the setting, raised to the level of the entry it was invoked on. */
    fun atLeast(entry: ForgetScope): ForgetScope = if (level >= entry.level) this else entry

    companion object {
        /** A stored name; anything that is not exactly one of the three (null, a typo, another case) is the safe default PHOTO. */
        fun parse(name: String?): ForgetScope = values().firstOrNull { it.name == name } ?: PHOTO
    }
}

/** The ONE accessor, like [ForgetRadius]: the phone binds [provider] once at start to the stored setting (Settings > Capture); the default is PHOTO and a throwing provider is PHOTO too. */
object ForgetScopeSetting {
    @Volatile var provider: () -> ForgetScope = { ForgetScope.PHOTO }

    fun get(): ForgetScope = try { provider() } catch (e: Exception) { ForgetScope.PHOTO }
}

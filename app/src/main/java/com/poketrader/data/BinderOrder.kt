package com.poketrader.data

/**
 * The order of the binder bar (Unsorted, the wishlist and the binders), as arranged in "Arrange
 * binders…" and synced with the settings. Binders are known by their sync id, so the order is the same
 * on both phones. Since 1.20.
 */
object BinderOrder {
    const val UNSORTED = "unsorted"
    const val WISHLIST = "wishlist"

    fun key(b: Binder) = "b:" + (b.uid ?: "id${b.id}")

    fun encode(keys: List<String>) = keys.joinToString(",")
    fun decode(s: String?): List<String> = s.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * Every place in the bar in order: those in [saved] first, as arranged, then any not arranged yet in
     * the usual order (Unsorted, the wishlist, then the binders by name). Keys of binders that are gone
     * are dropped.
     */
    fun arrange(binders: List<Binder>, saved: List<String>): List<String> {
        val usual = listOf(UNSORTED, WISHLIST) + binders.sortedBy { it.name.lowercase() }.map(::key)
        val known = usual.toSet()
        val kept = saved.filter { it in known }.distinct()
        return kept + usual.filter { it !in kept }
    }

    /** The binder a key stands for (null for Unsorted and the wishlist). */
    fun binderOf(key: String, binders: List<Binder>): Binder? = binders.firstOrNull { key(it) == key }

    /** [keys] with the one at [index] moved one place up ([by] -1) or down (+1). */
    fun move(keys: List<String>, index: Int, by: Int): List<String> {
        val to = index + by
        if (index !in keys.indices || to !in keys.indices) return keys
        return keys.toMutableList().apply { add(to, removeAt(index)) }
    }
}

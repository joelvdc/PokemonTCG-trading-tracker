package com.poketrader.data

/** What swiping a card in the collection's list or compact view does; chosen in Settings. Since 1.20. */
enum class SwipeAction(val key: String, val label: String) {
    MOVE_TO_BINDER("move", "Move to binder…"),
    ADD_TO_TRADE("trade", "Add to a trade…"),
    ONE_MORE("plus", "One copy more"),
    ONE_LESS("minus", "One copy less"),
    ADD_TO_WISHLIST("wish", "Add to the wishlist"),
    NOTHING("none", "Nothing");

    companion object {
        val DEFAULT_RIGHT = MOVE_TO_BINDER
        val DEFAULT_LEFT = ADD_TO_TRADE
        fun fromKey(key: String?, default: SwipeAction) = entries.firstOrNull { it.key == key } ?: default
    }
}

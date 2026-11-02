package com.example.atlas.ui

/**
 * B8: LazyColumn keys that survive rescans but can never collide. Uses [primary] when present and not yet taken,
 * then [fallback]; if both collide (e.g. two rows with the same BSSID and a reused id) a "#n" suffix is added.
 * Duplicate keys crash LazyColumn, so this always returns distinct strings, one per item, in order.
 */
object StableKeys {
    fun <T> of(items: List<T>, primary: (T) -> String?, fallback: (T) -> String): List<String> {
        val seen = HashSet<String>(items.size * 2)
        return items.map { item ->
            val first = primary(item)?.takeIf { it !in seen }
            var key = first ?: fallback(item)
            if (key in seen) {
                var n = 2
                while ("$key#$n" in seen) n++
                key = "$key#$n"
            }
            seen += key
            key
        }
    }
}

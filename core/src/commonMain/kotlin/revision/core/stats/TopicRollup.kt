package revision.core.stats

import kotlinx.datetime.LocalDate
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.plus
import revision.core.db.Topic

/** Time and revision state for a topic, with its children rolled up into it. */
data class Roll(
    val totalMs: Long,
    val lastRevisedAt: Long?,
    val leafCount: Int,
    val neverRevisedLeaves: Int,
)

/**
 * Rolls stats up the tree: a parent's time is the sum of its children, its last-revised is the
 * newest of theirs. Only leaves are counted for coverage — a parent is a grouping, not something
 * you revise.
 */
fun rollUp(topics: List<Topic>, totalMs: Map<String, Long>, lastRevised: Map<String, Long>): Map<String, Roll> {
    val byParent = topics.groupBy { it.parent_id }
    val out = mutableMapOf<String, Roll>()
    fun visit(t: Topic): Roll {
        val kids = byParent[t.id].orEmpty()
        val roll = if (kids.isEmpty()) {
            val last = lastRevised[t.id]
            Roll(totalMs[t.id] ?: 0L, last, 1, if (last == null) 1 else 0)
        } else {
            val rolls = kids.map { visit(it) }
            Roll(
                totalMs = rolls.sumOf { it.totalMs } + (totalMs[t.id] ?: 0L),
                lastRevisedAt = rolls.mapNotNull { it.lastRevisedAt }.maxOrNull(),
                leafCount = rolls.sumOf { it.leafCount },
                neverRevisedLeaves = rolls.sumOf { it.neverRevisedLeaves },
            )
        }
        out[t.id] = roll
        return roll
    }
    byParent[null].orEmpty().forEach { visit(it) }
    return out
}

data class Activity(val daysActive: Int, val currentRun: Int, val longestRun: Int)

/**
 * Plain facts about how often you revised — no target attached. [currentRun] counts back from
 * today, or from yesterday if today has nothing yet (so an unfinished day doesn't read as a break).
 */
fun activityFrom(activeDays: Set<LocalDate>, today: LocalDate): Activity {
    if (activeDays.isEmpty()) return Activity(0, 0, 0)
    var cursor = if (today in activeDays) today else today.plus(DatePeriod(days = -1))
    var current = 0
    while (cursor in activeDays) { current++; cursor = cursor.plus(DatePeriod(days = -1)) }

    var longest = 0
    var run = 0
    var prev: LocalDate? = null
    for (d in activeDays.sorted()) {
        run = if (prev != null && prev.plus(DatePeriod(days = 1)) == d) run + 1 else 1
        if (run > longest) longest = run
        prev = d
    }
    return Activity(activeDays.size, current, longest)
}

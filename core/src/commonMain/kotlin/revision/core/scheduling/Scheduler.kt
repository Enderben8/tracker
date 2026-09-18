package revision.core.scheduling

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

const val DAY_MS = 86_400_000L
private const val HOUR_MS = 3_600_000L

/** Every tunable number in one place (spec 7.1, 7.2, 7.4). */
data class SchedulerConfig(
    // spaced repetition
    val firstIntervalDays: Double = 1.0,
    val secondIntervalDays: Double = 3.0,   // textbook SM-2 uses 6; 3 fits a GCSE timetable better
    val maxIntervalDays: Double = 120.0,
    val minEaseFactor: Double = 1.3,
    val startingEaseFactor: Double = 2.5,
    val passRating: Int = 3,                // ratings below this count as "struggled"
    // priority weights
    val overdueWeight: Double = 100.0,
    val neverRevisedWeight: Double = 60.0,
    val stalenessWeight: Double = 40.0,
    val examPressureWeight: Double = 50.0,
    val justRevisedPenalty: Double = 30.0,
    // priority shaping
    val stalenessDays: Double = 30.0,
    val examHorizonDays: Double = 180.0,
    val noExamDatePressure: Double = 0.3,
    val justRevisedHours: Long = 24,
    // queue shape
    val queueSize: Int = 10,
    val maxPerSubject: Int = 3,
)

/** The spaced-repetition state of one topic. A topic never revised has `null` for the state. */
data class SrsState(
    val lastRevisedAt: Long?,
    val dueAt: Long?,
    val intervalDays: Double,
    val easeFactor: Double,
    val repetitions: Int,
)

object Sm2 {
    /** Applies a 1-5 rating. */
    fun review(previous: SrsState?, rating: Int, now: Long, config: SchedulerConfig = SchedulerConfig()): SrsState {
        require(rating in 1..5) { "rating must be 1..5" }
        val s = previous ?: SrsState(null, null, 0.0, config.startingEaseFactor, 0)
        val q = rating

        val repetitions: Int
        var interval: Double
        if (q < config.passRating) {
            repetitions = 0
            interval = config.firstIntervalDays
        } else {
            interval = when (s.repetitions) {
                0 -> config.firstIntervalDays
                1 -> config.secondIntervalDays
                else -> s.intervalDays * s.easeFactor
            }
            repetitions = s.repetitions + 1
        }

        val ease = max(
            config.minEaseFactor,
            s.easeFactor + 0.1 - (5 - q) * (0.08 + (5 - q) * 0.02),
        )
        interval = min(interval, config.maxIntervalDays)

        return SrsState(
            lastRevisedAt = now,
            dueAt = now + (interval * DAY_MS).roundToLong(),
            intervalDays = interval,
            easeFactor = ease,
            repetitions = repetitions,
        )
    }

    /** The topic was covered but not rated: a missing rating is not a bad one, so only the date moves. */
    fun touch(previous: SrsState?, now: Long, config: SchedulerConfig = SchedulerConfig()): SrsState {
        val s = previous ?: SrsState(null, null, 0.0, config.startingEaseFactor, 0)
        return s.copy(lastRevisedAt = now)
    }
}

/** One revisable (leaf) topic plus what the scheduler needs to know about it. */
data class Candidate(
    val topicId: String,
    val subjectId: String,
    val subjectName: String,
    val title: String,
    /** Start of the exam day, epoch millis; null if not set yet. */
    val examDate: Long?,
    val state: SrsState?,
)

data class Suggestion(
    val topicId: String,
    val subjectId: String,
    val subjectName: String,
    val title: String,
    val score: Double,
    /** One line saying why it is here, from whichever signal contributed most. */
    val reason: String,
    val lastRevisedAt: Long?,
)

/** Pure ranking: snapshot in, sorted list out. No database, no clock. */
object Scheduler {

    fun rank(candidates: List<Candidate>, now: Long, config: SchedulerConfig = SchedulerConfig()): List<Suggestion> {
        val scored = candidates
            .filterNot { examHasPassed(it.examDate, now) } // a finished subject has nothing left to revise
            .map { score(it, now, config) }
            .sortedWith(compareByDescending<Suggestion> { it.score }.thenBy { it.topicId }) // ties are deterministic

        // No single subject may take over the list.
        val perSubject = mutableMapOf<String, Int>()
        val queue = mutableListOf<Suggestion>()
        for (s in scored) {
            if (queue.size >= config.queueSize) break
            val used = perSubject[s.subjectId] ?: 0
            if (used >= config.maxPerSubject) continue
            perSubject[s.subjectId] = used + 1
            queue += s
        }
        return queue
    }

    /** An exam is over once its whole day has passed. */
    fun examHasPassed(examDate: Long?, now: Long): Boolean = examDate != null && now >= examDate + DAY_MS

    fun score(c: Candidate, now: Long, config: SchedulerConfig): Suggestion {
        val st = c.state

        val overdueDays = if (st?.dueAt != null && st.dueAt < now) (now - st.dueAt) / DAY_MS.toDouble() else 0.0
        val overdue = if (st?.dueAt != null && st.dueAt < now) {
            (overdueDays / max(st.intervalDays, 1.0)).coerceIn(0.0, 1.0)
        } else 0.0

        val never = if (st?.lastRevisedAt == null) 1.0 else 0.0

        val daysSince = st?.lastRevisedAt?.let { (now - it) / DAY_MS.toDouble() }
        val staleness = if (daysSince == null) 0.0 else (daysSince / config.stalenessDays).coerceIn(0.0, 1.0)

        val daysToExam = c.examDate?.let { max(0.0, (it - now) / DAY_MS.toDouble()) }
        val exam = if (daysToExam == null) config.noExamDatePressure
        else ((config.examHorizonDays - daysToExam) / config.examHorizonDays).coerceIn(0.0, 1.0)

        val justRevised = if (st?.lastRevisedAt != null && now - st.lastRevisedAt < config.justRevisedHours * HOUR_MS) 1.0 else 0.0

        val overdueScore = config.overdueWeight * overdue
        val neverScore = config.neverRevisedWeight * never
        val staleScore = config.stalenessWeight * staleness
        val examScore = config.examPressureWeight * exam
        val total = overdueScore + neverScore + staleScore + examScore - config.justRevisedPenalty * justRevised

        // Reason: the largest positive contributor. The exam term only counts when a date is set.
        val reason = when {
            overdueScore > 0 && overdueScore >= neverScore && overdueScore >= staleScore &&
                (daysToExam == null || overdueScore >= examScore) -> overdueText(overdueDays)
            neverScore > 0 && neverScore >= staleScore && (daysToExam == null || neverScore >= examScore) -> "not revised yet"
            staleScore > 0 && (daysToExam == null || staleScore >= examScore) -> "last revised ${daysText(daysSince ?: 0.0)} ago"
            daysToExam != null && examScore > 0 -> "${c.subjectName} exam ${examText(daysToExam)}"
            neverScore > 0 -> "not revised yet"
            else -> "due for a refresh"
        }

        return Suggestion(c.topicId, c.subjectId, c.subjectName, c.title, total, reason, st?.lastRevisedAt)
    }

    private fun overdueText(days: Double): String {
        val d = days.toLong()
        return if (d < 1) "due now" else if (d == 1L) "1 day overdue" else "$d days overdue"
    }

    private fun daysText(days: Double): String {
        val d = days.toLong()
        return if (d == 1L) "1 day" else "$d days"
    }

    private fun examText(days: Double): String {
        val d = days.toLong()
        return if (d == 0L) "is today" else if (d == 1L) "in 1 day" else "in $d days"
    }
}

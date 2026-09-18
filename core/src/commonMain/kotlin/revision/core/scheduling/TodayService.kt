package revision.core.scheduling

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import revision.core.Now
import revision.core.data.SessionRepository
import revision.core.data.SubjectRepository
import revision.core.data.TopicRepository
import revision.core.data.TopicStateRepository
import revision.core.db.RevisionDatabase
import revision.core.stats.TimeSpan
import revision.core.stats.dailyTotals
import revision.core.stats.lastDays

data class DayTotal(val date: LocalDate, val ms: Long)

data class TimeSummary(val todayMs: Long, val days: List<DayTotal>) {
    val last7Ms: Long get() = days.sumOf { it.ms }
}

/** What the Today screen shows. */
class TodayService(
    private val db: RevisionDatabase,
    private val now: Now,
    private val config: SchedulerConfig = SchedulerConfig(),
) {
    private val subjects = SubjectRepository(db, now)
    private val topics = TopicRepository(db, now)
    private val states = TopicStateRepository(db)
    private val sessions = SessionRepository(db, now)

    fun suggestions(nowMs: Long = now()): List<Suggestion> {
        val subjectById = subjects.getAll().associateBy { it.id }   // archived subjects are already excluded
        val allTopics = topics.getAll().filter { it.subject_id in subjectById }
        val parents = allTopics.mapNotNull { it.parent_id }.toSet()
        val stateById = states.getAll().associate { it.topic_id to it.toSrs() }

        val candidates = allTopics
            .filter { it.id !in parents } // only leaves: a grouping is not something you revise
            .map {
                val subject = subjectById.getValue(it.subject_id)
                Candidate(it.id, subject.id, subject.name, it.title, subject.exam_date, stateById[it.id])
            }
        return Scheduler.rank(candidates, nowMs, config)
    }

    fun timeSummary(nowMs: Long = now(), zone: TimeZone = TimeZone.currentSystemDefault()): TimeSummary {
        val spans = sessions.segmentSpans(nowMs)
        val days = lastDays(nowMs, 7, zone)
        val totals = dailyTotals(spans, days, zone)
        val perDay = days.map { DayTotal(it, totals.getValue(it)) }
        return TimeSummary(todayMs = perDay.last().ms, days = perDay)
    }
}

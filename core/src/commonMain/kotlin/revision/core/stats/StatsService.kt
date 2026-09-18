package revision.core.stats

import kotlinx.datetime.TimeZone
import revision.core.Now
import revision.core.data.SessionRepository
import revision.core.data.SubjectRepository
import revision.core.data.TopicRepository
import revision.core.data.TopicStateRepository
import revision.core.db.RevisionDatabase
import revision.core.localDate

data class SubjectStats(
    val subjectId: String,
    val name: String,
    val colour: String,
    val totalMs: Long,
    val leafCount: Int,
    val neverRevised: Int,
)

data class TopicStat(val topicId: String, val title: String, val totalMs: Long, val lastRevisedAt: Long?)

class StatsService(private val db: RevisionDatabase, private val now: Now) {
    private val subjects = SubjectRepository(db, now)
    private val topics = TopicRepository(db, now)
    private val states = TopicStateRepository(db)
    private val sessions = SessionRepository(db, now)

    private fun lastRevised() = states.getAll().mapNotNull { s -> s.last_revised_at?.let { s.topic_id to it } }.toMap()

    fun subjectStats(nowMs: Long = now()): List<SubjectStats> {
        val totals = sessions.totalsByTopic(nowMs)
        val last = lastRevised()
        val allTopics = topics.getAll().groupBy { it.subject_id }
        return subjects.getAll().map { s ->
            val list = allTopics[s.id].orEmpty()
            val rolls = rollUp(list, totals, last)
            val top = list.filter { it.parent_id == null }.mapNotNull { rolls[it.id] }
            SubjectStats(s.id, s.name, s.colour, top.sumOf { it.totalMs }, top.sumOf { it.leafCount }, top.sumOf { it.neverRevisedLeaves })
        }
    }

    /** Leaf topics of one subject, most-studied first. */
    fun topicStats(subjectId: String, nowMs: Long = now()): List<TopicStat> {
        val totals = sessions.totalsByTopic(nowMs)
        val last = lastRevised()
        val list = topics.getBySubject(subjectId)
        val parents = list.mapNotNull { it.parent_id }.toSet()
        return list.filter { it.id !in parents }
            .map { TopicStat(it.id, it.title, totals[it.id] ?: 0L, last[it.id]) }
            .sortedWith(compareByDescending<TopicStat> { it.totalMs }.thenBy { it.title })
    }

    fun activity(nowMs: Long = now(), zone: TimeZone = TimeZone.currentSystemDefault()): Activity {
        val days = sessions.segmentSpans(nowMs).flatMap { span ->
            // a segment can touch two days; count both
            listOf(localDate(span.startMs, zone), localDate(maxOf(span.startMs, span.endMs - 1), zone))
        }.toSet()
        return activityFrom(days, localDate(nowMs, zone))
    }
}

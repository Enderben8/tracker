package revision.core.seed

import revision.core.Now
import revision.core.db.RevisionDatabase

/** The nine subjects, confirmed complete by the user. */
val allSeedSubjects: List<SeedSubject> = listOf(
    biologySeed,
    chemistrySeed,
    physicsSeed,
    computerScienceSeed,
    englishLiteratureSeed,
    geographySeed,
    historySeed,
    mathsSeed,
    frenchSeed,
)

/**
 * Writes [allSeedSubjects] into the database.
 *
 * Row ids are STABLE (built from the subject key and topic titles) rather than random.
 * That way, if two devices each seed independently, they produce identical rows and
 * later sync merges them instead of duplicating all ~300 topics.
 */
object Seeder {

    /** Seeds only if the database has no subjects at all. Returns true if it seeded. */
    fun seedIfEmpty(db: RevisionDatabase, now: Now): Boolean {
        if (db.subjectQueries.countAll().executeAsOne() > 0L) return false
        seed(db, now())
        return true
    }

    private fun seed(db: RevisionDatabase, timestamp: Long) {
        db.transaction {
            allSeedSubjects.forEachIndexed { index, subject ->
                val subjectId = subjectId(subject.key)
                db.subjectQueries.insert(
                    id = subjectId,
                    name = subject.name,
                    colour = subject.colour,
                    exam_board = subject.examBoard,
                    exam_date = null,
                    sort_order = index.toLong(),
                    updated_at = timestamp,
                    deleted = 0L,
                )
                insertTopics(db, subjectId, subject.key, parentId = null, parentPath = "", subject.topics, timestamp)
            }
        }
    }

    private fun insertTopics(
        db: RevisionDatabase,
        subjectId: String,
        subjectKey: String,
        parentId: String?,
        parentPath: String,
        topics: List<SeedTopic>,
        timestamp: Long,
    ) {
        topics.forEachIndexed { index, topic ->
            val path = if (parentPath.isEmpty()) slug(topic.title) else "$parentPath/${slug(topic.title)}"
            val id = "seed:$subjectKey:$path"
            db.topicQueries.insert(
                id = id,
                subject_id = subjectId,
                parent_id = parentId,
                code = topic.code,
                title = topic.title,
                page_start = topic.page?.toLong(),
                sort_order = index.toLong(),
                notes = null,
                updated_at = timestamp,
                deleted = 0L,
            )
            insertTopics(db, subjectId, subjectKey, id, path, topic.children, timestamp)
        }
    }

    fun subjectId(key: String) = "seed:$key"

    internal fun slug(title: String): String =
        title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
}

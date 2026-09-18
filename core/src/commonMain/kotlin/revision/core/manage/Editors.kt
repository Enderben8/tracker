package revision.core.manage

import revision.core.Now
import revision.core.data.SubjectRepository
import revision.core.data.TopicRepository
import revision.core.db.RevisionDatabase
import revision.core.db.Subject
import revision.core.db.Topic

/** Everything the "Subjects & topics" screen can do to the topic tree. */
class TopicEditor(private val db: RevisionDatabase, private val now: Now) {
    private val topics = TopicRepository(db, now)
    private val q = db.topicQueries

    fun rename(topicId: String, title: String) {
        val t = topics.get(topicId) ?: return
        topics.update(t.copy(title = title.trim().ifBlank { return }))
    }

    /** Adds one topic per non-blank line under [parentId] (null = top level of the subject). */
    fun addMany(subjectId: String, parentId: String?, titles: List<String>): List<String> {
        val clean = titles.map { it.trim() }.filter { it.isNotEmpty() }
        val ids = mutableListOf<String>()
        db.transaction { clean.forEach { ids += topics.add(subjectId, parentId, it) } }
        return ids
    }

    /** Archives each topic and everything under it. */
    fun archiveMany(ids: Collection<String>) = db.transaction { ids.forEach { topics.archive(it) } }

    fun archived(): List<Topic> = q.selectArchived().executeAsList()

    /** Un-archives a topic and any archived ancestors, so it does not reappear under nothing. */
    fun restore(topicId: String) {
        var current: Topic? = topics.get(topicId)
        db.transaction {
            while (current != null) {
                if (current!!.deleted == 1L) topics.restore(current!!.id)
                current = current!!.parent_id?.let { topics.get(it) }
            }
        }
    }

    fun moveUp(topicId: String) = move(topicId, -1)
    fun moveDown(topicId: String) = move(topicId, +1)

    private fun move(topicId: String, delta: Int) {
        val t = topics.get(topicId) ?: return
        val siblings = topics.getBySubject(t.subject_id)
            .filter { it.parent_id == t.parent_id }
            .sortedWith(compareBy({ it.sort_order }, { it.title }))
        val from = siblings.indexOfFirst { it.id == topicId }
        val to = from + delta
        if (from < 0 || to !in siblings.indices) return
        val reordered = siblings.toMutableList().also { it.add(to, it.removeAt(from)) }
        val timestamp = now()
        db.transaction {
            reordered.forEachIndexed { i, s -> if (s.sort_order != i.toLong()) q.setSortOrder(i.toLong(), timestamp, s.id) }
        }
    }
}

class SubjectEditor(private val db: RevisionDatabase, private val now: Now) {
    private val subjects = SubjectRepository(db, now)

    /** Changes name, colour, board and exam date. [examDate] is the start of the exam day, or null. */
    fun edit(id: String, name: String, colour: String, board: String?, examDate: Long?) {
        val s = subjects.get(id) ?: return
        subjects.update(s.copy(name = name.trim().ifBlank { s.name }, colour = colour, exam_board = board?.trim()?.ifBlank { null }, exam_date = examDate))
    }

    fun archived(): List<Subject> = db.subjectQueries.selectArchived().executeAsList()

    fun moveUp(id: String) = move(id, -1)
    fun moveDown(id: String) = move(id, +1)

    private fun move(id: String, delta: Int) {
        val all = subjects.getAll().sortedWith(compareBy({ it.sort_order }, { it.name }))
        val from = all.indexOfFirst { it.id == id }
        val to = from + delta
        if (from < 0 || to !in all.indices) return
        val reordered = all.toMutableList().also { it.add(to, it.removeAt(from)) }
        db.transaction { reordered.forEachIndexed { i, s -> if (s.sort_order != i.toLong()) subjects.update(s.copy(sort_order = i.toLong())) } }
    }
}

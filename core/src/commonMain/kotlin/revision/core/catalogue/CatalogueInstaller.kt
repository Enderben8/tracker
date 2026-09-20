package revision.core.catalogue

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import revision.core.Now
import revision.core.data.SettingsRepository
import revision.core.db.RevisionDatabase

/** A subject the user picked, with the decisions they made about it. */
data class SubjectChoice(
    val subject: SpecSubject,
    /** Null installs every topic; otherwise Higher-only content is left out for Foundation. */
    val tier: Tier? = null,
    /** Which optional groups were kept. Groups with no [SpecGroup.choice] are always installed. */
    val groups: Set<String> = emptySet(),
    /** Topics deliberately unticked, by id. Schools differ, so any topic can be dropped. */
    val skipTopicIds: Set<String> = emptySet(),
)

/** What was installed, remembered so it can be restored or re-installed later. */
@Serializable
data class SavedChoice(
    val ref: String,
    val tier: String? = null,
    val groups: List<String> = emptyList(),
    val skipTopicIds: List<String> = emptyList(),
)

object SetupSettings {
    const val COMPLETE = "setup.complete"
    const val SELECTION = "setup.selection"
}

/**
 * Writes a chosen set of specification subjects into the database.
 *
 * Ids are derived from the choice, never random: the same subject on the same board always
 * produces the same ids, on every device. That is what lets two devices that picked the same
 * course merge over sync instead of ending up with two copies of everything.
 */
object CatalogueInstaller {

    private val json = Json { ignoreUnknownKeys = true }

    fun subjectIdFor(subject: SpecSubject): String = "spec:${subject.ref}"

    fun topicIdFor(subject: SpecSubject, path: String): String = "spec:${subject.ref}:$path"

    /**
     * A sensible starting choice: everything compulsory, plus the first option of each set of
     * options, so a subject is never installed empty. The wizard pre-ticks these.
     */
    fun defaultChoice(subject: SpecSubject): SubjectChoice {
        val firstOfEach = subject.groups.filter { it.choice != null }
            .groupBy { it.choice!!.group }
            .values.map { it.first().ref }
        return SubjectChoice(subject, groups = firstOfEach.toSet())
    }

    /** Groups that belong to [choice] — the compulsory ones plus whichever options were kept. */
    fun groupsFor(choice: SubjectChoice): List<SpecGroup> =
        choice.subject.groups.filter { it.choice == null || it.ref in choice.groups }

    /** Topics of [group] that suit the chosen tier. */
    fun topicsFor(choice: SubjectChoice, group: SpecGroup): List<SpecTopic> =
        group.topics.filter { topic ->
            val tierOk = topic.tier == null || choice.tier == null || topic.tier == choice.tier
            tierOk && topicIdFor(choice.subject, pathFor(group, topic)) !in choice.skipTopicIds
        }

    fun pathFor(group: SpecGroup, topic: SpecTopic): String = "${slug(group.ref)}/${slug(topic.code ?: topic.title)}"

    /** Installs [choices], remembers them, and marks setup finished. Returns the rows written. */
    fun install(db: RevisionDatabase, now: Now, choices: List<SubjectChoice>): Int {
        val timestamp = now()
        val settings = SettingsRepository(db, now)
        var written = 0

        db.transaction {
            val startOrder = db.subjectQueries.selectAll().executeAsList().size
            choices.forEachIndexed { index, choice ->
                written += writeSubject(db, choice, (startOrder + index).toLong(), timestamp)
            }
        }
        remember(settings, choices)
        settings.put(SetupSettings.COMPLETE, "1")
        return written
    }

    private fun writeSubject(db: RevisionDatabase, choice: SubjectChoice, sortOrder: Long, timestamp: Long): Int {
        val subject = choice.subject
        val subjectId = subjectIdFor(subject)
        var written = 1

        db.subjectQueries.insert(
            id = subjectId,
            name = subject.name,
            colour = subject.colour,
            exam_board = subject.board.label,
            exam_date = null,
            sort_order = sortOrder,
            updated_at = timestamp,
            deleted = 0L,
        )

        var order = 0L
        groupsFor(choice).forEach { group ->
            val topics = topicsFor(choice, group)
            if (topics.isEmpty()) return@forEach

            val groupId = topicIdFor(subject, slug(group.ref))
            db.topicQueries.insert(
                id = groupId,
                subject_id = subjectId,
                parent_id = null,
                code = group.code,
                title = group.title,
                page_start = null,
                sort_order = order++,
                notes = null,
                updated_at = timestamp,
                deleted = 0L,
            )
            written++

            topics.forEachIndexed { index, topic ->
                db.topicQueries.insert(
                    id = topicIdFor(subject, pathFor(group, topic)),
                    subject_id = subjectId,
                    parent_id = groupId,
                    code = topic.code,
                    title = topic.title,
                    page_start = null,
                    sort_order = index.toLong(),
                    notes = null,
                    updated_at = timestamp,
                    deleted = 0L,
                )
                written++
            }
        }
        return written
    }

    /** Adds back anything from the saved selection that was archived or removed. */
    fun restoreMissing(db: RevisionDatabase, now: Now): Int {
        val timestamp = now()
        val choices = savedChoices(SettingsRepository(db, now))
        var changed = 0

        db.transaction {
            choices.forEach { choice ->
                val subjectId = subjectIdFor(choice.subject)
                val existing = db.subjectQueries.selectById(subjectId).executeAsOneOrNull()
                if (existing == null) {
                    changed += writeSubject(db, choice, db.subjectQueries.selectAll().executeAsList().size.toLong(), timestamp)
                    return@forEach
                }
                if (existing.deleted == 1L) {
                    db.subjectQueries.restore(timestamp, subjectId)
                    changed++
                }
                changed += restoreTopics(db, choice, subjectId, timestamp)
            }
        }
        return changed
    }

    private fun restoreTopics(db: RevisionDatabase, choice: SubjectChoice, subjectId: String, timestamp: Long): Int {
        var changed = 0
        var order = 0L
        groupsFor(choice).forEach { group ->
            val topics = topicsFor(choice, group)
            if (topics.isEmpty()) return@forEach
            val groupId = topicIdFor(choice.subject, slug(group.ref))
            changed += restoreOne(db, groupId, timestamp) {
                db.topicQueries.insert(
                    groupId, subjectId, null, group.code, group.title, null, order, null, timestamp, 0L,
                )
            }
            order++
            topics.forEachIndexed { index, topic ->
                val id = topicIdFor(choice.subject, pathFor(group, topic))
                changed += restoreOne(db, id, timestamp) {
                    db.topicQueries.insert(
                        id, subjectId, groupId, topic.code, topic.title, null, index.toLong(), null, timestamp, 0L,
                    )
                }
            }
        }
        return changed
    }

    /** Inserts the row if it is gone, un-archives it if it is hidden, and leaves edits alone. */
    private fun restoreOne(db: RevisionDatabase, id: String, timestamp: Long, insert: () -> Unit): Int {
        val existing = db.topicQueries.selectById(id).executeAsOneOrNull()
        return when {
            existing == null -> { insert(); 1 }
            existing.deleted == 1L -> { db.topicQueries.restore(timestamp, id); 1 }
            else -> 0
        }
    }

    // ---- the saved selection -------------------------------------------------------------

    /** The saved-selection JSON for [choices]; used when a reset keeps only some subjects. */
    fun encode(choices: List<SubjectChoice>): String = json.encodeToString(
        choices.map { SavedChoice(it.subject.ref, it.tier?.name, it.groups.toList(), it.skipTopicIds.toList()) }
    )

    private fun remember(settings: SettingsRepository, choices: List<SubjectChoice>) {
        val existing = savedRaw(settings).associateBy { it.ref }
        val added = choices.map {
            SavedChoice(it.subject.ref, it.tier?.name, it.groups.toList(), it.skipTopicIds.toList())
        }
        val merged = (existing + added.associateBy { it.ref }).values.toList()
        settings.put(SetupSettings.SELECTION, json.encodeToString(merged))
    }

    private fun savedRaw(settings: SettingsRepository): List<SavedChoice> {
        val stored = settings.get(SetupSettings.SELECTION) ?: return emptyList()
        return runCatching { json.decodeFromString<List<SavedChoice>>(stored) }.getOrDefault(emptyList())
    }

    /** The subjects this user chose at setup, as choices again. */
    fun savedChoices(settings: SettingsRepository): List<SubjectChoice> =
        savedRaw(settings).mapNotNull { saved ->
            val subject = Catalogue.find(saved.ref) ?: return@mapNotNull null
            SubjectChoice(
                subject = subject,
                tier = saved.tier?.let { name -> Tier.entries.firstOrNull { it.name == name } },
                groups = saved.groups.toSet(),
                skipTopicIds = saved.skipTopicIds.toSet(),
            )
        }

    fun isSetUp(settings: SettingsRepository): Boolean = settings.get(SetupSettings.COMPLETE) == "1"
}

/** Lower-case, dashes instead of punctuation: "4.1.1" -> "4-1-1". Ids are built from these. */
internal fun slug(title: String): String =
    title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

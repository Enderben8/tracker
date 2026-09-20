package revision.core.catalogue

import revision.core.Now
import revision.core.data.SettingsRepository
import revision.core.db.RevisionDatabase

/**
 * Starting over: clears the subjects and topics so setup can run again.
 *
 * Everything here is a **soft** delete (`deleted = 1` with a fresh `updated_at`). That matters:
 * a hard `DELETE FROM` does not travel over sync, so the other device would simply push the old
 * topics back and quietly undo the reset. Tombstones travel; deletions stick.
 */
object ResetService {

    /**
     * Archives every subject and topic, and forgets the saved selection so setup starts clean.
     *
     * @param keepHistory true keeps logged sessions, ratings and totals. History does not filter
     *   on archived topics, so past sessions still read correctly even once their topics are gone.
     * @param keepSubjectIds subjects to leave completely alone.
     * @return how many subjects and topics were archived.
     */
    fun startOver(
        db: RevisionDatabase,
        now: Now,
        keepHistory: Boolean = true,
        keepSubjectIds: Set<String> = emptySet(),
    ): Int {
        val timestamp = now()
        val settings = SettingsRepository(db, now)
        var archived = 0

        db.transaction {
            db.subjectQueries.exportAll().executeAsList()
                .filter { it.deleted == 0L && it.id !in keepSubjectIds }
                .forEach { db.subjectQueries.softDelete(timestamp, it.id); archived++ }

            db.topicQueries.exportAll().executeAsList()
                .filter { it.deleted == 0L && it.subject_id !in keepSubjectIds }
                .forEach { db.topicQueries.softDelete(timestamp, it.id); archived++ }

            if (!keepHistory) archiveHistory(db, timestamp, keepSubjectIds)
        }

        val kept = CatalogueInstaller.savedChoices(settings)
            .filter { CatalogueInstaller.subjectIdFor(it.subject) in keepSubjectIds }
        settings.put(SetupSettings.SELECTION, savedSelection(kept))
        settings.put(SetupSettings.COMPLETE, if (kept.isEmpty()) "0" else "1")
        return archived
    }

    private fun archiveHistory(db: RevisionDatabase, timestamp: Long, keepSubjectIds: Set<String>) {
        val sessions = db.sessionQueries.exportSessions().executeAsList()
            .filter { it.deleted == 0L && it.subject_id !in keepSubjectIds }
        val doomed = sessions.map { it.id }.toSet()
        sessions.forEach { db.sessionQueries.softDeleteSession(timestamp, it.id) }

        db.sessionQueries.exportSessionTopics().executeAsList()
            .filter { it.deleted == 0L && it.session_id in doomed }
            .forEach {
                db.sessionQueries.softDeleteSessionTopic(timestamp, it.id)
                db.sessionQueries.softDeleteSegmentsForSessionTopic(timestamp, it.id)
            }
    }

    private fun savedSelection(kept: List<SubjectChoice>): String =
        if (kept.isEmpty()) "[]" else CatalogueInstaller.encode(kept)
}

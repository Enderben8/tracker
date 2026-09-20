package revision.core

import revision.core.catalogue.Catalogue
import revision.core.catalogue.CatalogueInstaller
import revision.core.catalogue.SpecSubject
import revision.core.db.RevisionDatabase

/**
 * Test fixture: a database with catalogue subjects installed, standing in for what a user
 * would pick during setup. Ids are the real ones the installer produces, so tests exercise
 * the same rows the app writes.
 */
object TestCatalogue {

    val biology: SpecSubject = Catalogue.find("aqa/biology-8461")!!
    val physics: SpecSubject = Catalogue.find("aqa/physics-8463")!!
    val french: SpecSubject = Catalogue.find("aqa/french-8652")!!
    val maths: SpecSubject = Catalogue.find("aqa/maths-8300")!!
    val history: SpecSubject = Catalogue.find("aqa/history-8145")!!
    val geography: SpecSubject = Catalogue.find("aqa/geography-8035")!!
    val ocrComputerScience: SpecSubject = Catalogue.find("ocr/computer-science-j277")!!
    val edexcelBiology: SpecSubject = Catalogue.find("edexcel/biology-1bi0")!!
    val englishLiterature: SpecSubject = Catalogue.find("aqa/english-literature-8702")!!

    val BIOLOGY: String = CatalogueInstaller.subjectIdFor(biology)
    val PHYSICS: String = CatalogueInstaller.subjectIdFor(physics)
    val FRENCH: String = CatalogueInstaller.subjectIdFor(french)
    val MATHS: String = CatalogueInstaller.subjectIdFor(maths)
    val HISTORY: String = CatalogueInstaller.subjectIdFor(history)
    val GEOGRAPHY: String = CatalogueInstaller.subjectIdFor(geography)
    val ENGLISH_LITERATURE: String = CatalogueInstaller.subjectIdFor(englishLiterature)

    /** Every subject in the catalogue, as a user who picked all of them would have them. */
    fun installAll(db: RevisionDatabase, now: Now): RevisionDatabase = install(db, now, Catalogue.all)

    fun install(db: RevisionDatabase, now: Now, subjects: List<SpecSubject>): RevisionDatabase {
        CatalogueInstaller.install(db, now, subjects.map { CatalogueInstaller.defaultChoice(it) })
        return db
    }

    /** A fresh in-memory database with the whole catalogue installed. */
    fun database(now: Now): RevisionDatabase = installAll(DatabaseFactory.inMemory(), now)

    /** How many revisable leaves a subject installs (the rows the scheduler queues). */
    fun leafCount(subject: SpecSubject): Int {
        val choice = CatalogueInstaller.defaultChoice(subject)
        return CatalogueInstaller.groupsFor(choice).sumOf { CatalogueInstaller.topicsFor(choice, it).size }
    }

    /** How many topic rows a subject installs: one per group plus its topics. */
    fun topicCount(subject: SpecSubject): Int {
        val choice = CatalogueInstaller.defaultChoice(subject)
        return CatalogueInstaller.groupsFor(choice).sumOf { group ->
            val topics = CatalogueInstaller.topicsFor(choice, group).size
            if (topics == 0) 0 else topics + 1
        }
    }
}

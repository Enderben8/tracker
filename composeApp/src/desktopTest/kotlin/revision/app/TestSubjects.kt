package revision.app

import revision.core.Now
import revision.core.catalogue.Catalogue
import revision.core.catalogue.CatalogueInstaller
import revision.core.db.RevisionDatabase

/**
 * The UI tests' stand-in for a user who has been through setup.
 *
 * `core` has its own copy of this for its tests; a test source set is not visible from another
 * module, and duplicating five lines beats publishing a test artifact.
 */
object TestSubjects {

    val BIOLOGY: String = idOf("aqa/biology-8461")
    val PHYSICS: String = idOf("aqa/physics-8463")
    val GEOGRAPHY: String = idOf("aqa/geography-8035")

    private fun idOf(ref: String) = CatalogueInstaller.subjectIdFor(Catalogue.find(ref)!!)

    /** Installs every catalogue subject, as if the user had ticked all of them. */
    fun installAll(db: RevisionDatabase, now: Now): RevisionDatabase {
        CatalogueInstaller.install(db, now, Catalogue.all.map { CatalogueInstaller.defaultChoice(it) })
        return db
    }
}

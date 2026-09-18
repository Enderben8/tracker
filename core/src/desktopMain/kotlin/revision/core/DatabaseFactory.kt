package revision.core

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import revision.core.db.RevisionDatabase
import java.io.File

object DatabaseFactory {

    /**
     * The real database lives in %LOCALAPPDATA%\RevisionTracker\ — NOT next to the
     * exe (lost on reinstall) and NEVER inside the OneDrive folder (OneDrive syncs
     * whole files and would corrupt SQLite; see PROJECT_SPEC.md section 9.1).
     */
    fun defaultDatabaseFile(): File {
        val base = System.getenv("LOCALAPPDATA")
            ?: (System.getProperty("user.home") + File.separator + ".local" + File.separator + "share")
        val dir = File(base, "RevisionTracker")
        dir.mkdirs()
        return File(dir, "revision.db")
    }

    fun open(file: File = defaultDatabaseFile()): RevisionDatabase =
        create(JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}"))

    fun inMemory(): RevisionDatabase = create(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))

    private fun create(driver: SqlDriver): RevisionDatabase {
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)

        // SQLite keeps a schema version number in the file; 0 means a brand-new database.
        val current = driver.executeQuery(
            identifier = null,
            sql = "PRAGMA user_version",
            mapper = { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getLong(0) ?: 0L)
            },
            parameters = 0,
        ).value

        val target = RevisionDatabase.Schema.version
        if (current == 0L) {
            RevisionDatabase.Schema.create(driver)
            driver.execute(null, "PRAGMA user_version = $target", 0)
        } else if (current < target) {
            RevisionDatabase.Schema.migrate(driver, current, target)
            driver.execute(null, "PRAGMA user_version = $target", 0)
        }
        return RevisionDatabase(driver)
    }
}

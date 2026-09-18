package revision.core

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import revision.core.db.RevisionDatabase

object DatabaseFactory {

    /**
     * The database lives in the app's private storage (never in OneDrive, and never shared with
     * other apps). It is not backed up by moving the file: use Settings -> Export instead.
     */
    fun open(context: Context): RevisionDatabase {
        val driver = AndroidSqliteDriver(
            schema = RevisionDatabase.Schema,
            context = context.applicationContext,
            name = "revision.db",
            callback = object : AndroidSqliteDriver.Callback(RevisionDatabase.Schema) {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    db.setForeignKeyConstraintsEnabled(true)
                }
            },
        )
        return RevisionDatabase(driver)
    }
}

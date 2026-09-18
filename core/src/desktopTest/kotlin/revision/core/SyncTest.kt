package revision.core

import kotlinx.coroutines.runBlocking
import revision.core.data.SettingsRepository
import revision.core.data.TopicRepository
import revision.core.manage.TopicEditor
import revision.core.seed.Seeder
import revision.core.sync.DeviceSettings
import revision.core.sync.SyncEngine
import revision.core.sync.SyncFolder
import revision.core.sync.SyncFolderCheck
import revision.core.timer.HistoryService
import revision.core.timer.ManualTopic
import revision.core.timer.SessionService
import revision.core.timer.TopicRating
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** An in-memory shared folder that remembers who wrote each file. */
private class SharedFolder {
    val files = linkedMapOf<String, String>()          // "dir/name" -> text
    val writers = mutableMapOf<String, MutableSet<String>>()

    inner class View(var actor: String = "?") : SyncFolder {
        override suspend fun list(dir: String) = files.keys.filter { it.startsWith("$dir/") }.map { it.removePrefix("$dir/") }
        override suspend fun read(dir: String, name: String) = files["$dir/$name"]
        override suspend fun write(dir: String, name: String, text: String) {
            files["$dir/$name"] = text
            writers.getOrPut("$dir/$name") { mutableSetOf() } += actor
        }
        override suspend fun delete(dir: String, name: String) { files.remove("$dir/$name") }
    }
}

private class Device(shared: SharedFolder, private val world: () -> Long, compactBytes: Long = 5_000_000L,
                     snapshotEveryMs: Long = 24 * 3_600_000L, snapshotsToKeep: Int = 5) {
    val now: Now = world
    val db = DatabaseFactory.inMemory().also { Seeder.seedIfEmpty(it, now) }
    val view = shared.View()
    val engine = SyncEngine(db, now, view, compactBytes, snapshotEveryMs, snapshotsToKeep).also { view.actor = it.deviceId }
    val history = HistoryService(db, now)
    val sessions = SessionService(db, now)
    val topics = TopicRepository(db, now)
    val editor = TopicEditor(db, now)
    fun sync() = runBlocking { engine.sync() }
    fun bio() = topics.getBySubject("seed:biology")
    fun title(id: String) = topics.get(id)!!.title
}

class SyncTest {
    private var t = 1_000_000_000L
    private val world: () -> Long = { t }
    private val minute = 60_000L
    private val shared = SharedFolder()
    private fun device(compactBytes: Long = 5_000_000L, snapshotEveryMs: Long = 24 * 3_600_000L, keep: Int = 5) =
        Device(shared, world, compactBytes, snapshotEveryMs, keep).also { t += 1000 } // let time pass after seeding

    @Test
    fun aSessionFinishedOnOneDeviceAppearsOnTheOther() {
        val a = device(); val b = device()
        val topic = a.bio()[0].id
        a.history.logManual("seed:biology", t - 3600_000, listOf(ManualTopic(topic, 30, 4)), "at school")
        t += minute
        a.sync(); b.sync()

        val entry = b.history.load().single()
        assertEquals(30 * minute, entry.totalMs)
        assertEquals(4, entry.topics.single().rating)
        assertEquals("at school", entry.notes)
        assertNotNull(b.db.topicStateQueries.selectByTopic(topic).executeAsOneOrNull())   // schedule travelled too
    }

    @Test
    fun aRunningSessionIsNotSentUntilItFinishes_andThenItsOlderRowsFollow() {
        val a = device(); val b = device()
        val id = a.sessions.start("seed:biology", listOf(a.bio()[0].id), t)
        t += 5 * minute
        a.sync(); b.sync()
        assertTrue(b.history.load().isEmpty())
        assertFalse(shared.files.values.any { it.contains("\"table\":\"session\"") })

        t += 10 * minute
        a.sessions.stop(id, at = t)
        t += minute
        a.sync(); b.sync()
        assertEquals(15 * minute, b.history.load().single().totalMs)
    }

    @Test
    fun noFileEverHasTwoWriters() {
        val a = device(); val b = device()
        a.history.logManual("seed:biology", t, listOf(ManualTopic(a.bio()[0].id, 10, 3)), null); t += minute
        b.history.logManual("seed:biology", t, listOf(ManualTopic(b.bio()[1].id, 10, 3)), null); t += minute
        repeat(3) { a.sync(); b.sync(); t += minute }
        assertEquals(2, shared.files.keys.count { it.startsWith("devices/") })
        shared.writers.filterKeys { it.startsWith("devices/") }.forEach { (path, who) ->
            assertEquals(setOf(path.removePrefix("devices/").substringBefore('.')), who, path)
        }
        assertEquals(2, b.history.load().size)
        assertEquals(2, a.history.load().size)
    }

    @Test
    fun theNewerEditWinsOnBothDevices() {
        val a = device(); val b = device()
        val id = a.bio()[0].id
        a.editor.rename(id, "A's wording"); t += minute
        b.editor.rename(id, "B's later wording"); t += minute
        repeat(2) { a.sync(); b.sync() }
        assertEquals("B's later wording", a.title(id))
        assertEquals("B's later wording", b.title(id))
    }

    @Test
    fun anExactTieSettlesOnTheSameWinnerEverywhere() {
        val a = device(); val b = device()
        val id = a.bio()[0].id
        a.editor.rename(id, "from A")
        b.editor.rename(id, "from B")           // same clock value: an exact tie
        repeat(3) { a.sync(); b.sync() }
        assertEquals(a.title(id), b.title(id))
        val expected = if (b.engine.deviceId > a.engine.deviceId) "from B" else "from A"
        assertEquals(expected, a.title(id))
    }

    @Test
    fun anArchiveTravels() {
        val a = device(); val b = device()
        t += minute
        revision.core.data.SubjectRepository(a.db, a.now).archive("seed:french")
        a.sync(); b.sync()
        assertTrue(revision.core.data.SubjectRepository(b.db, b.now).getAll().none { it.name == "French" })
    }

    @Test
    fun aSecondSyncWithNothingNewDoesNothing_andPulledRowsAreNotEchoedBack() {
        val a = device(); val b = device()
        a.history.logManual("seed:biology", t, listOf(ManualTopic(a.bio()[0].id, 20, 5)), null); t += minute
        a.sync()
        val first = b.sync()
        assertTrue(first.applied > 0)
        assertEquals(0, first.pushed, "rows just pulled must not be pushed straight back")
        val again = b.sync()
        assertEquals(0, again.applied); assertEquals(0, again.pushed)
        assertFalse(shared.files.keys.any { it.startsWith("devices/${b.engine.deviceId}") && (shared.files[it]!!.contains("\"table\":\"session\"")) })
    }

    @Test
    fun aDeviceStartingFromNothingCatchesUp() {
        val a = device()
        a.history.logManual("seed:biology", t, listOf(ManualTopic(a.bio()[0].id, 45, 5)), null); t += minute
        a.sync()
        val fresh = device()
        fresh.sync()
        assertEquals(45 * minute, fresh.history.load().single().totalMs)
    }

    @Test
    fun compactionKeepsTheNewestRecordsAndOtherDevicesStartOver() {
        val a = device(compactBytes = 6_000); val b = device()
        val id = a.bio()[0].id
        b.sync()
        repeat(40) { i -> a.editor.rename(id, "version $i"); t += minute; a.sync() }
        val file = shared.files.entries.first { it.key.startsWith("devices/${a.engine.deviceId}") }.value
        assertTrue(file.lines().first().contains("\"generation\":"), "header present")
        assertFalse(file.lines().first().contains("\"generation\":0"), "generation should have advanced")
        assertTrue(file.lines().count { it.contains("\"id\":\"$id\"") } < 40, "old versions of the same row were dropped")

        b.sync()
        assertEquals("version 39", b.title(id))
    }

    @Test
    fun aRecordWhoseParentHasNotArrivedWaitsAndIsRetried() {
        val b = device()
        val topicId = b.bio()[0].id
        fun rec(seq: Int, table: String, id: String, data: String) =
            """{"seq":$seq,"table":"$table","id":"$id","updated_at":${t + 5},"deleted":0,"data":$data}"""
        val child = """{"id":"kid","subjectId":"seed:biology","parentId":"parent","code":null,"title":"Child","pageStart":null,"sortOrder":0,"notes":null,"updatedAt":${t + 5},"deleted":0}"""
        val parent = """{"id":"parent","subjectId":"seed:biology","parentId":null,"code":null,"title":"Parent","pageStart":null,"sortOrder":99,"notes":null,"updatedAt":${t + 5},"deleted":0}"""
        val header = """{"header":true,"format":1,"device":"zzz","generation":0}"""

        shared.files["devices/zzz.jsonl"] = header + "\n" + rec(1, "topic", "kid", child) + "\n"
        val first = b.sync()
        assertEquals(1, first.waiting)
        assertEquals(null, b.topics.get("kid"))

        shared.files["devices/zzz.jsonl"] = header + "\n" + rec(1, "topic", "kid", child) + "\n" + rec(2, "topic", "parent", parent) + "\n"
        val second = b.sync()
        assertEquals(0, second.waiting)
        assertEquals("Child", b.topics.get("kid")!!.title)
        assertEquals("Parent", b.topics.get("parent")!!.title)
        assertNotNull(topicId)
    }

    @Test
    fun aGarbledLineIsSkippedAndTheRestStillApplies() {
        val b = device()
        val good = """{"seq":2,"table":"setting","id":"sched.queueSize","updated_at":${t + 1},"deleted":0,"data":{"key":"sched.queueSize","value":"7","updatedAt":${t + 1}}}"""
        shared.files["devices/zzz.jsonl"] = """{"header":true,"format":1,"device":"zzz","generation":0}""" + "\n{ this is not json\n" + good + "\n"
        b.sync()
        assertEquals("7", SettingsRepository(b.db, b.now).get("sched.queueSize"))
    }

    @Test
    fun hiddenAndTemporaryFilesAreNotTreatedAsDeviceLogs() {
        val b = device()
        shared.files["devices/.abc.jsonl.tmp"] = "half written {{{"
        shared.files["devices/.hidden.jsonl"] = "nonsense"
        shared.files["devices/zzz.jsonl.tmp"] = "nonsense"
        assertEquals(0, b.sync().otherDevices)
    }

    @Test
    fun deviceLocalSettingsNeverTravel() {
        val a = device(); val b = device()
        SettingsRepository(a.db, a.now).put("sched.queueSize", "6")
        SettingsRepository(a.db, a.now).put(DeviceSettings.FOLDER, "C:/secret/path")
        t += minute
        a.sync(); b.sync()
        assertEquals("6", SettingsRepository(b.db, b.now).get("sched.queueSize"))
        assertEquals(null, SettingsRepository(b.db, b.now).get(DeviceSettings.FOLDER))
        assertTrue(a.engine.deviceId != b.engine.deviceId)
        assertFalse(shared.files.values.any { it.contains("C:/secret/path") })
    }

    @Test
    fun snapshotsAreWrittenAndOnlyTheDevicesOwnAreEverPruned() {
        val a = device(snapshotEveryMs = 0, keep = 2); val b = device(snapshotEveryMs = 0, keep = 2)
        repeat(4) { t += minute; a.sync(); b.sync() }
        fun snaps(d: Device) = shared.files.keys.count { it.startsWith("snapshots/${d.engine.deviceId}-") }
        assertEquals(2, snaps(a)); assertEquals(2, snaps(b))
    }

    @Test
    fun aRunningSessionNeverAppearsInASnapshot() {
        val a = device(snapshotEveryMs = 0)
        a.sessions.start("seed:biology", listOf(a.bio()[0].id), t)
        t += minute
        a.sync()
        val snap = shared.files.entries.first { it.key.startsWith("snapshots/") }.value
        assertFalse(snap.contains("\"endedAt\": null"))
    }

    // ---------- the folder check used to test OneDrive on the phone ----------

    @Test
    fun theFolderCheckPassesOnAWellBehavedFolder() {
        val steps = runBlocking { SyncFolderCheck.run(shared.View(), world) }
        assertTrue(steps.all { it.ok }, steps.filter { !it.ok }.joinToString { "${it.name}: ${it.detail}" })
    }

    @Test
    fun theFolderCheckCatchesAProviderThatAppendsInsteadOfReplacing() {
        val appending = object : SyncFolder {
            val f = mutableMapOf<String, String>()
            override suspend fun list(dir: String) = f.keys.filter { it.startsWith("$dir/") }.map { it.removePrefix("$dir/") }
            override suspend fun read(dir: String, name: String) = f["$dir/$name"]
            override suspend fun write(dir: String, name: String, text: String) { f["$dir/$name"] = text + (f["$dir/$name"]?.drop(text.length) ?: "") }
            override suspend fun delete(dir: String, name: String) { f.remove("$dir/$name") }
        }
        val steps = runBlocking { SyncFolderCheck.run(appending, world) }
        assertTrue(steps.any { !it.ok && it.name.contains("SHORTER") }, steps.joinToString { "${it.name}=${it.ok}" })
    }

    // ---------- schema migration ----------

    @Test
    fun anExistingVersionOneDatabaseGainsTheSyncTableWithoutLosingData() {
        val file = File.createTempFile("revision-migrate", ".db").also { it.deleteOnExit() }
        DatabaseFactory.open(file).also { Seeder.seedIfEmpty(it, world) }

        java.sql.DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { c ->
            c.createStatement().use {
                it.execute("DROP TABLE sync_cursor")
                it.execute("PRAGMA user_version = 1")
            }
        }
        val reopened = DatabaseFactory.open(file)
        assertEquals(9L, reopened.subjectQueries.countAll().executeAsOne())
        assertEquals(0, reopened.syncCursorQueries.selectAll().executeAsList().size)
        java.sql.DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { c ->
            c.createStatement().use { st -> st.executeQuery("PRAGMA user_version").use { assertEquals(2, it.getInt(1)) } }
        }
    }
}

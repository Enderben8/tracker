package revision.app

import kotlinx.coroutines.runBlocking
import revision.core.DatabaseFactory
import revision.core.data.TopicRepository
import revision.core.sync.SyncEngine
import revision.core.sync.SyncFolderCheck
import revision.core.timer.HistoryService
import revision.core.timer.ManualTopic
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileSyncFolderTest {
    private var t = 1_700_000_000_000L
    private val now = { t }

    @Test
    fun theFolderCheckPassesOnARealDirectory() {
        val dir = Files.createTempDirectory("revision-sync").toFile()
        val steps = runBlocking { SyncFolderCheck.run(FileSyncFolder(dir), now) }
        assertTrue(steps.all { it.ok }, steps.filter { !it.ok }.joinToString { "${it.name}: ${it.detail}" })
        dir.deleteRecursively()
    }

    @Test
    fun twoDevicesSyncThroughARealFolder_andLeaveNoTempFilesBehind() {
        val dir = Files.createTempDirectory("revision-sync").toFile()
        val folder = FileSyncFolder(dir)
        val a = DatabaseFactory.inMemory().also { TestSubjects.installAll(it, now) }
        t += 1000
        val b = DatabaseFactory.inMemory().also { TestSubjects.installAll(it, now) }
        t += 1000

        val topic = TopicRepository(a, now).getBySubject(TestSubjects.BIOLOGY)[0].id
        HistoryService(a, now).logManual(TestSubjects.BIOLOGY, t, listOf(ManualTopic(topic, 25, 4)), null)
        t += 1000
        runBlocking {
            SyncEngine(a, now, folder).sync()
            SyncEngine(b, now, folder).sync()
        }
        assertEquals(25 * 60_000L, HistoryService(b, now).load().single().totalMs)

        // Each device publishes its own subjects, so both have a log — but each writes only its
        // own file, which is the invariant that keeps a cloud folder free of conflict copies.
        val names = File(dir, "devices").list().orEmpty().toList()
        assertEquals(2, names.size, "each device writes its own log: $names")
        assertEquals(names.size, names.toSet().size)
        assertTrue(names.none { it.endsWith(".tmp") }, "temp files must not be left behind")
        dir.deleteRecursively()
    }
}

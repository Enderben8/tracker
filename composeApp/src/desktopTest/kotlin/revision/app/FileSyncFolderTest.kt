package revision.app

import kotlinx.coroutines.runBlocking
import revision.core.DatabaseFactory
import revision.core.data.TopicRepository
import revision.core.seed.Seeder
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
        val a = DatabaseFactory.inMemory().also { Seeder.seedIfEmpty(it, now) }
        t += 1000
        val b = DatabaseFactory.inMemory().also { Seeder.seedIfEmpty(it, now) }
        t += 1000

        val topic = TopicRepository(a, now).getBySubject("seed:biology")[0].id
        HistoryService(a, now).logManual("seed:biology", t, listOf(ManualTopic(topic, 25, 4)), null)
        t += 1000
        runBlocking {
            SyncEngine(a, now, folder).sync()
            SyncEngine(b, now, folder).sync()
        }
        assertEquals(25 * 60_000L, HistoryService(b, now).load().single().totalMs)

        val names = File(dir, "devices").list().orEmpty().toList()
        assertEquals(1, names.size, "only the device with changes writes a log: $names")
        assertTrue(names.none { it.endsWith(".tmp") })
        dir.deleteRecursively()
    }
}

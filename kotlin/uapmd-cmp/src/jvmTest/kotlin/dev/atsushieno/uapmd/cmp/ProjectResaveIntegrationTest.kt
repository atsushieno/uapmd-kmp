package dev.atsushieno.uapmd.cmp

import dev.atsushieno.uapmd.AppModel
import dev.atsushieno.uapmd.AppProjectResult
import dev.atsushieno.uapmd.DocumentHandle
import dev.atsushieno.uapmd.DocumentIOResult
import dev.atsushieno.uapmd.cleanupAppModel
import dev.atsushieno.uapmd.getAppModel
import dev.atsushieno.uapmd.initJvmEventLoop
import dev.atsushieno.uapmd.instantiateAppModel
import org.junit.Assume.assumeTrue
import java.awt.EventQueue
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What a load does in its completion has to survive a re-save, on the threads the app uses.
 *
 * The completion runs on the event loop's main thread. On macOS that is not the thread the app creates
 * AppModel on, the EDT, so these create it there too, unlike [runAgainstAppModel]. Going through commands
 * here used to lose work, because the model refuses them off its own thread.
 *
 * Run with: ./gradlew :uapmd-cmp:jvmIntegrationTest --tests '*ProjectResaveIntegrationTest'
 */
class ProjectResaveIntegrationTest {
    /** Otherwise the master track's clip, and so the tempo map, stays behind in the extraction folder. */
    @Test
    fun resavedArchiveKeepsTheMasterClip() {
        val archive = repoFile("external/uapmd/samples/mugene-fantasy-suite/1-das-stromreiters.uapmdz")
        assumeTrue("the sample project is checked out", archive.isFile)
        val resaved = tempArchive()
        withModelOnAppThreads { model ->
            load(model, archive)
            save(model, resaved)

            ZipFile(archive).use { original ->
                ZipFile(resaved).use { copy ->
                    val clip = assertNotNull(
                        original.entries().asSequence().firstOrNull { it.name.startsWith("clips/master_clip_") },
                        "the sample has a master clip"
                    )
                    val packed = assertNotNull(copy.getEntry(clip.name), "${clip.name} is packed in the re-save")
                    assertTrue(
                        original.getInputStream(clip).readBytes().contentEquals(copy.getInputStream(packed).readBytes()),
                        "the master clip is unchanged"
                    )
                    val project = copy.getInputStream(copy.getEntry("project.uapmd")).readBytes().decodeToString()
                    assertTrue(
                        project.contains("\"${clip.name}\""),
                        "the project refers to the master clip inside the archive"
                    )
                }
            }
        }
        resaved.delete()
    }

    /** Otherwise the track falls back to its default graph, and the next save overwrites the saved one. */
    @Test
    fun reloadedProjectKeepsItsTrackGraph() {
        val saved = tempArchive()
        val resaved = tempArchive()
        withModelOnAppThreads { model ->
            val added = CompletableFuture<Int>()
            EventQueue.invokeAndWait { model.addTrack { index, _ -> added.complete(index) } }
            val track = added.get(60, TimeUnit.SECONDS)
            var usesEditorGraph = false
            EventQueue.invokeAndWait { usesEditorGraph = model.ensureTrackUsesEditorGraph(track) }
            assertTrue(usesEditorGraph, "the track switched to the editor graph")
            // A save leaves out a track with nothing authored on it.
            var muted = false
            EventQueue.invokeAndWait { muted = model.setTrackMuted(track, true) }
            assertTrue(muted, "the track was muted")
            save(model, saved)
            load(model, saved)
            save(model, resaved)

            // By content rather than by index: a load numbers the tracks it finds from zero.
            val before = trackGraphs(saved)
            assertEquals(1, before.size, "the saved track graphs: $before")
            assertNotNull(
                Regex("\"graph_type\"\\s*:\\s*\"[^\"]+\"").find(before.single()),
                "the saved graph names its type: ${before.single()}"
            )
            assertEquals(before, trackGraphs(resaved), "the track graph after a reload and re-save")
        }
        saved.delete()
        resaved.delete()
    }

    private fun tempArchive() = File.createTempFile("uapmd-resave", ".uapmdz").apply {
        delete()
        deleteOnExit()
    }

    private fun trackGraphs(archive: File) = ZipFile(archive).use { zip ->
        zip.entries().asSequence()
            .filter { Regex("graphs/track\\d+\\.graph\\.json").matches(it.name) }
            .map { zip.getInputStream(it).readBytes().decodeToString() }
            .toList()
    }

    private fun withModelOnAppThreads(body: (AppModel) -> Unit) {
        initJvmEventLoop()
        EventQueue.invokeAndWait {
            instantiateAppModel()
            getAppModel().notifyUiReady()
            getAppModel().notifyPersistentStorageReady()
        }
        try {
            body(getAppModel())
        } finally {
            EventQueue.invokeAndWait { cleanupAppModel() }
        }
    }

    private fun load(model: AppModel, archive: File) {
        val loaded = CompletableFuture<AppProjectResult>()
        model.loadProjectFromResolvedPath(archive.path) { loaded.complete(it) }
        assertEquals(true, loaded.get(120, TimeUnit.SECONDS).success, "the load failed: ${loaded.get().error}")
    }

    /** As the app saves: the model packs a .uapmdz and the document provider writes it. Its handle id is the path. */
    private fun save(model: AppModel, archive: File) {
        val saved = CompletableFuture<DocumentIOResult>()
        model.saveProjectToDocument(DocumentHandle(archive.absolutePath, archive.name, "")) { saved.complete(it) }
        assertEquals(true, saved.get(120, TimeUnit.SECONDS).success, "the save failed: ${saved.get()}")
    }
}

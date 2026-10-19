package com.openautolink.app.carplay

import android.content.Context
import android.content.ContextWrapper
import android.graphics.SurfaceTexture
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.view.Surface
import com.openautolink.app.diagnostics.LogUploader
import com.openautolink.app.diagnostics.UploadResult
import com.openautolink.carplaylab.media.CarPlayDisplayPreferences
import com.openautolink.carplaylab.media.CarPlayDisplaySelection
import com.shilapi.xcertplay.airplay.AudioStreamId
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.AudioOutputSupport
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipInputStream

/** Real sink -> sanitizer/reporter -> manual LogUploader ZIP; only OS focus and HTTP are mocked. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = android.app.Application::class, sdk = [32], manifest = Config.NONE)
class CarPlayFocusOwnerExportTest {
    @Test fun actualPurposeRequestsPendingAndRetirementSurviveManualUploaderZip() {
        val app = RuntimeEnvironment.getApplication()
        assertEquals(android.app.Application::class.java, app.javaClass)
        val requests = mutableListOf<AudioFocusRequest>()
        var abandonResponse = AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        val manager = mockk<AudioManager>(relaxed = true)
        every { manager.requestAudioFocus(any<AudioFocusRequest>()) } answers {
            requests += firstArg<AudioFocusRequest>()
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        every { manager.abandonAudioFocusRequest(any()) } answers { abandonResponse }
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getSystemService(name: String): Any? =
                if (name == Context.AUDIO_SERVICE) manager else super.getSystemService(name)
        }
        val raw = CopyOnWriteArrayList<String>()
        val exported = CopyOnWriteArrayList<String>()
        // Synthetic clock keeps this export-path test independent of the separate rate budget.
        val clock = AtomicLong()
        val reporter = CarPlayAudioDiagnosticReporter(7, { clock.addAndGet(1_000_000_000L) }, exported::add)
        val texture = SurfaceTexture(0)
        val surface = Surface(texture)
        val sink = AndroidMediaSink(context, surface,
            display = CarPlayDisplaySelection(CarPlayDisplayPreferences(), "avc.exact", false),
            onStatus = { raw += it; reporter.record(it) })
        val folder = Files.createTempDirectory(Path.of(System.getProperty("java.io.tmpdir")), "focus-export").toFile()
        try {
            val expected = mutableListOf<String>()
            listOf("media" to "media", "telephony" to "call", "alert" to "ring", "speechrecognition" to "assistant")
                .forEachIndexed { index, (kind, purpose) ->
                    val id = AudioStreamId(100, kind)
                    sink.onAudioStarted(id, requireNotNull(AudioOutputSupport.negotiate(0x4000L, 100, kind)), 0)
                    assertEquals(index + 1, requests.size)
                    val request = requests.last()
                    val suffix = "usage=${request.audioAttributes.usage} content=${request.audioAttributes.contentType} gain=${request.focusGain} revision=${index + 1}"
                    expected += "media stage=audio-focus-owner purpose=$purpose action=request $suffix"
                    // Failed OS abandon retains the actual call owner; close retries that exact debt.
                    abandonResponse = if (purpose == "call") AudioManager.AUDIOFOCUS_REQUEST_FAILED else AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                    sink.onAudioStopped(id)
                    expected += "media stage=audio-focus-owner purpose=$purpose action=${if (purpose == "call") "pending" else "abandon"} $suffix"
                }
            abandonResponse = AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            sink.close()
            assertTrue(sink.awaitRetired(3000))
            expected += "media stage=audio-focus-owner purpose=call action=abandon usage=2 content=1 gain=2 revision=2"
            assertEquals("production sink must generate the exact owner receipts", expected,
                raw.filter { "stage=audio-focus-owner " in it })
            val expectedExport = expected.map { "CARPLAY_AUDIO session=7 $it" }
            assertEquals("actual sink owner receipts must survive reporter sanitizer", expectedExport,
                exported.filter { "stage=audio-focus-owner " in it })

            val hostile = expected.flatMap { listOf(" uid=1001", " address=private", "\n", "\u0000").map { suffix -> it + suffix } }
            val before = exported.toList()
            hostile.forEach(reporter::record)
            assertEquals("private suffixes cannot reach the log sink", before, exported.toList())
            val contents = exported.joinToString("\n", postfix = "\n")
            val log = File(folder, "openautolink/logs/oal_focus.log").apply { parentFile.mkdirs(); writeText(contents) }
            val uploadContext = object : ContextWrapper(app) {
                override fun getExternalFilesDirs(type: String?) = arrayOf(folder)
                override fun getCacheDir() = folder
            }
            val bytes = ByteArrayOutputStream()
            val connection = object : HttpURLConnection(URL("https://logs.example.test/upload")) {
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun getOutputStream() = bytes
                override fun getResponseCode() = 200
            }
            val result = LogUploader(uploadContext, { connection }, { true })
                .upload("https://logs.example.test/upload", "synthetic-focus-token", "test-car")
            assertTrue(result.toString(), result is UploadResult.Success)
            val members = mutableMapOf<String, String>()
            ZipInputStream(bytes.toByteArray().inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    members[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                }
            }
            assertEquals("manual uploader must preserve the complete reporter output", contents, members[log.name])
            assertEquals(expectedExport, members.getValue(log.name).lineSequence().filter { "stage=audio-focus-owner " in it }.toList())
            assertFalse(members.values.any { "synthetic-focus-token" in it || "address=private" in it || "uid=1001" in it })
            println("FOCUS_EXPORT_EVIDENCE ownerReceipts=${expected.size} zipEntry=${log.name} exactReporterZipEquality=true http=mock osFocus=mock")
            expectedExport.forEach(::println)
        } finally {
            abandonResponse = AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            sink.close()
            assertTrue(sink.awaitRetired(3000))
            folder.deleteRecursively()
            surface.release()
            texture.release()
        }
    }
}

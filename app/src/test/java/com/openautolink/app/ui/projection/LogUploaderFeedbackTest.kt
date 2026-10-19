package com.openautolink.app.ui.projection

import android.content.Context
import com.openautolink.app.diagnostics.LogUploader
import com.openautolink.app.diagnostics.OalLog
import io.mockk.*
import java.io.File
import java.io.IOException
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LogUploaderFeedbackTest {
    @get:Rule val temp = TemporaryFolder()
    @After fun cleanup() = unmockkAll()

    @Test fun `real uploader network catch produces a safe persistent NETWORK result not HTTP`() = runTest {
        val volume = temp.newFolder("volume")
        val cache = temp.newFolder("cache")
        val source = File(volume, "openautolink/logs/oal.log").apply {
            parentFile!!.mkdirs(); writeText("retained diagnostic fixture\n")
        }
        val context = mockk<Context>()
        every { context.getExternalFilesDirs(null) } returns arrayOf(volume)
        every { context.cacheDir } returns cache
        mockkObject(OalLog)
        every { OalLog.e(any(), any()) } just Runs
        val uploader = LogUploader(context, {
            throw IOException("https://private.example.test/?token=private-token /private/path")
        }, { true })
        val action = LogUploadAction()
        action.upload(this) { uploader.upload("https://logs.example.test/upload", "private-token", "fixture") }
        runCurrent()
        assertEquals(LogUploadState.ERROR, action.feedback.value.state)
        val message = action.feedback.value.message.orEmpty()
        assertTrue("Actual exception catch needs a stable safe code: $message", message.contains("(NETWORK)"))
        assertFalse(message.contains("HTTP"))
        assertFalse(message.contains("private"))
        assertTrue(message.contains("retained"))
        assertTrue(source.exists())
        assertTrue(cache.listFiles()!!.isEmpty())
        advanceTimeBy(60_000); runCurrent()
        assertEquals(message, action.feedback.value.message)
    }
}

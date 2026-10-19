package com.openautolink.app.ui.projection

import com.openautolink.app.diagnostics.UploadResult
import com.openautolink.app.diagnostics.UploadFailureCode
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LogUploadActionTest {
    @Test fun `typed failure codes ignore raw reasons and only valid actual HTTP status is displayed`() = runTest {
        val action = LogUploadAction()
        for (code in UploadFailureCode.entries) {
            action.upload(this) { UploadResult.Failure("private-token /private/path HTTP 403", code) }
            runCurrent()
            val expected = if (code == UploadFailureCode.UNKNOWN) "UPLOAD_FAILED" else code.name
            val message = action.feedback.value.message.orEmpty()
            assertTrue(message.contains("($expected)"))
            assertFalse(message.contains("private"))
            assertFalse(message.contains("403"))
        }
        for (status in listOf(403, 503, -1, 999, null)) {
            action.upload(this) { UploadResult.Failure("private-token", UploadFailureCode.HTTP, status) }
            runCurrent()
            val expected = if (status != null && status in 100..599) "HTTP_$status" else "HTTP"
            assertTrue(action.feedback.value.message.orEmpty().contains("($expected)"))
        }
    }

    @Test fun `cancellation before dispatch or during upload releases admission for the next manual attempt`() = runTest {
        for (dispatchFirst in listOf(false, true)) {
            val action = LogUploadAction()
            val owner = kotlinx.coroutines.Job()
            val scope = kotlinx.coroutines.CoroutineScope(coroutineContext + owner)
            var entered = false
            action.upload(scope) { entered = true; kotlinx.coroutines.awaitCancellation() }
            if (dispatchFirst) runCurrent()
            owner.cancel(); runCurrent()
            assertEquals(dispatchFirst, entered)
            assertEquals(LogUploadState.ERROR, action.feedback.value.state)
            assertTrue(action.feedback.value.message.orEmpty().contains("(CANCELED)"))
            action.upload(this) { UploadResult.Success(1, 1, "fixture") }
            runCurrent()
            assertEquals(LogUploadState.SUCCESS, action.feedback.value.state)
            advanceTimeBy(60_000); runCurrent()
            assertEquals(LogUploadState.SUCCESS, action.feedback.value.state)
        }
    }

    @Test fun `unknown uploader failure reason is never displayed as arbitrary text`() = runTest {
        val action = LogUploadAction()
        action.upload(this) { UploadResult.Failure("https://private.example/?token=private-token /private/path HTTP 403") }
        runCurrent()
        val message = action.feedback.value.message.orEmpty()
        assertTrue(message.contains("(UPLOAD_FAILED)"))
        assertFalse(message.contains("private"))
        assertFalse(message.contains("403"))
    }

    @Test fun `unexpected upload exception becomes a safe failure rather than leaving uploading stuck`() = runTest {
        val action = LogUploadAction()
        action.upload(this) { throw IllegalStateException("private-token /private/path") }
        runCurrent()
        assertEquals(LogUploadState.ERROR, action.feedback.value.state)
        val message = action.feedback.value.message.orEmpty()
        assertTrue(message.contains("(UPLOAD_FAILED)"))
        assertFalse(message.contains("private"))
    }

    @Test fun `duplicate requests do not upload twice and only a new manual attempt replaces terminal feedback`() = runTest {
        val action = LogUploadAction()
        val completion = kotlinx.coroutines.CompletableDeferred<UploadResult>()
        var requests = 0
        action.upload(this) { requests++; completion.await() }
        action.upload(this) { requests++; UploadResult.Success(1, 1, "fixture") }
        runCurrent()
        assertEquals(1, requests)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(LogUploadState.UPLOADING, action.feedback.value.state)
        completion.complete(UploadResult.Failure("fixture failure"))
        runCurrent()
        assertEquals(LogUploadState.ERROR, action.feedback.value.state)
        val failed = action.feedback.value
        advanceTimeBy(60_000); runCurrent()
        assertEquals(failed, action.feedback.value)
        action.upload(this) { requests++; UploadResult.Success(1, 1, "fixture") }
        assertEquals(LogUploadState.UPLOADING, action.feedback.value.state)
        runCurrent()
        assertEquals(2, requests)
        assertEquals(LogUploadState.SUCCESS, action.feedback.value.state)
        val succeeded = action.feedback.value
        advanceTimeBy(60_000); runCurrent()
        assertEquals(succeeded, action.feedback.value)
    }

    @Test fun `success remains available after a minute until the next manual upload`() = runTest {
        val action = LogUploadAction()
        action.upload(this) { UploadResult.Success(40, 2, "EV 1 included") }
        assertEquals(LogUploadState.UPLOADING, action.feedback.value.state)
        runCurrent()
        assertEquals(LogUploadState.SUCCESS, action.feedback.value.state)
        val terminal = action.feedback.value
        assertEquals("Uploaded: 2 files. EV 1 included", terminal.message)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals("No transient flash or stale reset timer", terminal, action.feedback.value)
    }
}

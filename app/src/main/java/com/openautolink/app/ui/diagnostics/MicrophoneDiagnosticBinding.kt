package com.openautolink.app.ui.diagnostics

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.openautolink.app.diagnostics.MicrophoneDiagnosticLogStore
import com.shilapi.xcertplay.media.MicrophoneDiagnostic
import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** One NavHost-owned engine. UI notifications read current state rather than stale callback snapshots. */
class MicrophoneDiagnosticBinding(
    context: Context,
    eligibility: () -> Boolean,
    automotive: Boolean,
    freshPark: () -> Boolean,
) : Closeable {
    private val main = Handler(Looper.getMainLooper())
    private val logStore = MicrophoneDiagnosticLogStore(context)
    @Volatile private var closed = false
    private lateinit var target: MicrophoneDiagnosticController
    private var lastResult: MicrophoneDiagnosticResult? = null
    private val engine = MicrophoneDiagnostic(context,
        eligibility = { !closed && ::target.isInitialized && target.isEligibleNow() },
        onState = { main.post { if (!closed) changed() } })
    val controller: MicrophoneDiagnosticController get() = target
    init {
        val backend = object : MicrophoneDiagnosticBackend {
            override val state get() = engine.state
            override fun inspect() = !closed && engine.inspect()
            override fun start(comparison: MicrophoneDiagnosticComparison) = !closed && engine.start(comparison)
            override fun cancel() = engine.cancel()
        }
        target = MicrophoneDiagnosticController(backend, automotive, eligibility, freshPark)
    }
    private fun changed() {
        target.refresh()
        val state = engine.state
        val result = state.result
        if (state.phase == MicrophoneDiagnosticPhase.COMPLETE && result != null && result !== lastResult) {
            lastResult = result
            target.summaryStored = null
            // Only the newest completed summary is retained; no audio enters this queue.
            writer.execute {
                val saved = logStore.save(result)
                main.post {
                    if (!closed && engine.state.result === result) target.summaryStored = saved
                }
            }
        }
    }
    override fun close() {
        if (closed) return
        target.updateForeground(false)
        closed = true
        engine.cancel()
    }
    private companion object {
        val writer = ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,ArrayBlockingQueue(1),
            { task -> Thread(task,"oal-mic-summary").apply { isDaemon = true } },
            ThreadPoolExecutor.DiscardOldestPolicy())
    }
}

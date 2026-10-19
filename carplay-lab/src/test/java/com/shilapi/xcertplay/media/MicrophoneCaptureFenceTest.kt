package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class MicrophoneCaptureFenceTest {
    @Test fun diagnosticExcludesEveryNormalReservationUntilExactRelease() {
        val type = runCatching { Class.forName("com.shilapi.xcertplay.media.MicrophoneCaptureFence") }.getOrNull()
        assertNotNull("process capture fence must exist", type)
        val singleton = type!!.getField("INSTANCE").get(null)
        fun acquire(method: String): Any? = type.getMethod(method).invoke(singleton)
        fun release(lease: Any) { lease.javaClass.getMethod("release").invoke(lease) }
        val first = acquire("tryNormal")!!
        val second = acquire("tryNormal")!!
        try {
            assertNull(acquire("tryDiagnostic"))
            release(first)
            release(first) // an old/double release must not retire another owner
            assertNull(acquire("tryDiagnostic"))
        } finally { release(first); release(second) }
        val diagnostic = acquire("tryDiagnostic")!!
        try {
            assertNull(acquire("tryNormal"))
            assertNull(acquire("tryDiagnostic"))
        } finally { release(diagnostic) }
        val successor = acquire("tryNormal")!!
        release(successor)
    }
}

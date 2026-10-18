package com.openautolink.app.carplay

import org.junit.Assert.*
import org.junit.Test

class CarPlayFocusOwnerSchemaTest {
    private val receipt = "media stage=audio-focus-owner purpose=call action=request usage=2 content=1 gain=2 revision=7"

    @Test fun exactFiniteContractAdmitsEachPurposeActionAndNumericEnum() {
        for (purpose in listOf("media", "call", "ring", "assistant"))
            for (action in listOf("request", "abandon", "pending"))
                for (usage in listOf(1, 2, 6, 16))
                    for (content in listOf(1, 2, 4))
                        for (gain in listOf(1, 2)) {
                            val line = "media stage=audio-focus-owner purpose=$purpose action=$action usage=$usage content=$content gain=$gain revision=9223372036854775807"
                            assertEquals("CARPLAY_AUDIO session=1 $line", CarPlayAudioDiagnostics.sanitize(1, line))
                        }
    }

    @Test fun hostileSuffixesUnknownEnumsAndPrivateIdentifiersFailClosedThroughReporter() {
        val hostile = mutableListOf<String>()
        for (suffix in listOf(" uid=1001", " pid=77", " address=00:11:22:33:44:55", " deviceId=9",
            " package=com.private.app", " winner=private", " name=private", " key=private",
            " samples=private", "\n", "\r", "\t", "\u0000", "\u001b", "\u2028", " ")) {
            hostile += receipt + suffix
        }
        for ((field, values) in mapOf(
            "purpose=call" to listOf("unknown", "telephony", "CALL", "navigation", "private", "media|call"),
            "action=request" to listOf("grant", "granted", "denied", "REQUEST", "private", "request|abandon"),
            "usage=2" to listOf("0", "3", "17", "02", "-2", "2.0", "private"),
            "content=1" to listOf("0", "3", "5", "01", "-1", "private"),
            "gain=2" to listOf("0", "3", "4", "02", "-2", "private"),
            "revision=7" to listOf("", "-1", "+1", "1.0", "null", "private", "12345678901234567890")
        )) {
            val name = field.substringBefore('=')
            values.forEach { hostile += receipt.replace(field, "$name=$it") }
        }
        hostile += receipt.replace("action=request usage=2", "usage=2 action=request")
        hostile += receipt.replace(" purpose=call", "")
        hostile += receipt.replace("purpose=call", "purpose=call purpose=media")
        hostile += receipt.replace("purpose=call", "purpose=ca\nll")
        hostile += "private $receipt"
        hostile += receipt + "x".repeat(CarPlayAudioDiagnostics.MAX_INPUT_LENGTH)
        val lines = mutableListOf<String>()
        val reporter = CarPlayAudioDiagnosticReporter(1, { 0L }, lines::add)
        hostile.forEach {
            assertNull(it, CarPlayAudioDiagnostics.sanitize(1, it))
            reporter.record(it)
        }
        assertTrue("rejected text must never reach the diagnostic sink", lines.isEmpty())
        assertNull(CarPlayAudioDiagnostics.sanitize(0, receipt))
        assertNull(CarPlayAudioDiagnostics.sanitize(-1, receipt))
        reporter.record(receipt)
        assertEquals("invalid traffic must not consume the valid-event budget", listOf("CARPLAY_AUDIO session=1 $receipt"), lines)
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.media.MicrophoneCapability
import com.shilapi.xcertplay.media.MicrophoneOwnerSlot
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Actual engine, PairVerify, authenticated UDP and owner slot; no physical AudioRecord claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], manifest = Config.NONE)
class CarPlayInputAdmissionTest {
    private class Capture(val id: MicrophoneStreamId, val config: MicrophoneConfig) : Closeable {
        @Volatile var closed = false
        override fun close() { closed = true; config.key.fill(0) }
    }

    private class Sink : MediaSink, Closeable {
        val captures = CopyOnWriteArrayList<Capture>()
        val received = LinkedBlockingQueue<AudioStreamId>()
        val owners = MicrophoneOwnerSlot<MicrophoneStreamId, Capture>({ it.closed }, { it.closed })
        var microphoneSupported = true
        var audioSupported = true
        override fun supportsAudio(format: AudioFormat) = audioSupported
        override fun supportsMicrophone(format: AudioFormat) = microphoneSupported
        override fun onMicrophoneStarted(id: MicrophoneStreamId, config: MicrophoneConfig) {
            assertTrue(owners.start(id) { Capture(id, config).also { captures.add(it) } })
        }
        override fun onMicrophoneStopped(id: MicrophoneStreamId) = owners.stop(id)
        override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) {
            // AudioStream invokes this after its one-shot audio/microphone onStarted callback.
            received.add(id)
        }
        override fun close() = owners.close()
    }

    private class Fixture(val session: AirPlaySession, val engine: CarPlayMediaEngine, val sink: Sink) {
        private var connectionId = 100L
        fun stream(kind: String? = "default", fields: Map<String, Any?> = emptyMap()): Map<String, Any?> =
            linkedMapOf<String, Any?>("type" to 100, "audioFormat" to 0x40L, "spf" to 480,
                "streamConnectionID" to connectionId++, "dataPort" to 6000).apply {
                if (kind != null) put("audioType", kind)
                putAll(fields)
            }
        fun play(stream: Map<String, Any?>, type: Int = 100) {
            val accepted = engine.onAudio(session, type, stream)
            assertNotNull("output admission rejected", accepted)
            val key = AirPlayCrypto.hkdfSha512(session.sharedSecret!!,
                "DataStream-Salt${stream["streamConnectionID"]}".toByteArray(),
                "DataStream-Output-Encryption-Key".toByteArray(), 32)
            val packet = MicrophonePacketizer.sealPacket(key, MicrophoneCounters(), ByteArray(960), 480)
            DatagramSocket().use { udp ->
                udp.send(DatagramPacket(packet, packet.size, session.remoteAddress,
                    (accepted!!["dataPort"] as Number).toInt()))
            }
            val kind = (stream["audioType"] as? String)?.lowercase(java.util.Locale.ROOT) ?: "default"
            assertEquals("authenticated output did not reach sink", AudioStreamId(type, kind),
                sink.received.poll(3, TimeUnit.SECONDS))
        }
    }

    private fun fixture(microphoneEnabled: Boolean = true,
        capability: MicrophoneCapability = MicrophoneCapability.of(mapOf(
            "default" to setOf(0x40L), "telephony" to setOf(0x40L), "speechrecognition" to setOf(0x40L))),
        test: Fixture.() -> Unit) {
        val peer = InetAddress.getLoopbackAddress()
        ServerSocket(0, 1, peer).use { server -> Socket(peer, server.localPort).use { _ ->
            server.accept().use { control -> Sink().use { sink ->
                val engine = CarPlayMediaEngine(sink, microphoneEnabled)
                val store = PairingStore()
                AirPlaySession(control, AirPlayConfig("test", "id", "mac", "950.7.1",
                    AirPlayDisplayConfig(800, 480), microphone = true), AirPlayIdentity.generate(), store,
                    null, object : AirPlaySessionListener {}, engine, microphoneCapability = capability).use { session ->
                    authenticate(session, store)
                    Fixture(session, engine, sink).test()
                }
            } }
        } }
    }

    private fun authenticate(session: AirPlaySession, store: PairingStore) {
        fun tlv(vararg pairs: Pair<Int, ByteArray>) = Tlv8Codec.encode(pairs.map { Tlv8Item(it.first, it.second) })
        val controller = AirPlayCrypto.ed25519Generate()
        val id = "synthetic-input-admission-controller".toByteArray()
        store.save(id.toString(Charsets.UTF_8), controller.publicKey)
        val ephemeral = AirPlayCrypto.x25519Generate()
        val m2 = Tlv8Codec.decode(session.pairVerify.handle(tlv(6 to byteArrayOf(1), 3 to ephemeral.publicKey)))
        val remote = m2.getValue(3)
        val shared = AirPlayCrypto.x25519Shared(ephemeral.privateKey, remote)
        val key = AirPlayCrypto.hkdfSha512(shared, "Pair-Verify-Encrypt-Salt".toByteArray(),
            "Pair-Verify-Encrypt-Info".toByteArray())
        val signature = AirPlayCrypto.ed25519Sign(controller.privateKey, ephemeral.publicKey + id + remote)
        session.pairVerify.handle(tlv(6 to byteArrayOf(3), 5 to AirPlayCrypto.chachaSeal(key,
            AirPlayCrypto.nonceLabel("PV-Msg03"), tlv(1 to id, 10 to signature))))
        assertTrue(session.finishControlAuthentication())
    }

    @Test fun numericInputUsesCurrentDiPlayIntegerCoercionBeforeAdmission() = fixture {
        // Immutable current DiPlay wantsMicrophone: Number.toInt() != 0, not Boolean text parsing.
        for ((flag, expectedInput) in listOf(
            0 to false, 0L to false, 0.5 to false, -0.5 to false, 4294967296L to false,
            1 to true, -1L to true, 2L to true, 1.5 to true)) {
            val before = sink.captures.size
            play(stream(fields = mapOf("input" to flag)))
            assertEquals("numeric input=$flag", before + if (expectedInput) 1 else 0, sink.captures.size)
        }
    }

    @Test fun explicitTrueAndAbsentInputKeepTheDefaultMicrophoneContract() = fixture {
        for (kind in listOf("default", null)) {
            for (fields in listOf(emptyMap(), mapOf("input" to true))) {
                val before = sink.captures.size
                play(stream(kind, fields))
                assertEquals(before + 1, sink.captures.size)
                assertEquals("default", sink.captures.last().id.audioType)
            }
        }
    }

    @Test fun malformedInputUsesTheSourceBackedPortFallbackNotBooleanTextParsing() = fixture {
        // Current DiPlay's else branch falls back to dataPort, including a present malformed flag.
        for (flag in listOf(null, "false", "true", byteArrayOf(0), listOf(false), mapOf("input" to false))) {
            val before = sink.captures.size
            play(stream(fields = mapOf("input" to flag)))
            assertEquals("valid port fallback must request input", before + 1, sink.captures.size)
            for (port in listOf(null, "6000", 0, 65536, 6000.5)) {
                assertNull("fallback must retain exact port validation", engine.onAudio(session, 100,
                    stream(fields = mapOf("input" to flag, "dataPort" to port))))
            }
        }
    }

    @Test fun falseDefaultIncludingAbsentAudioTypeNeverDisplacesCallCapture() = fixture {
        play(stream("telephony"))
        val call = sink.captures.single()
        for (kind in listOf("default", null, "compatibility")) {
            for (flag in listOf(false, 0L)) {
                play(stream(kind, mapOf("input" to flag)))
                assertEquals(1, sink.captures.size)
                assertFalse(call.closed)
            }
        }
    }

    @Test fun callAndSiriRetainTheirInputDespiteExplicitOptOutOrMalformedFlag() = fixture {
        for (kind in listOf("telephony", "speechRecognition")) {
            for (fields in listOf(emptyMap(), mapOf("input" to false), mapOf("input" to true),
                mapOf("input" to 0L), mapOf("input" to 1L), mapOf("input" to "false"))) {
                val before = sink.captures.size
                play(stream(kind, fields))
                assertEquals(before + 1, sink.captures.size)
                assertEquals(kind.lowercase(java.util.Locale.ROOT), sink.captures.last().config.audioType)
            }
            assertNull(engine.onAudio(session, 100, stream(kind, mapOf("input" to false, "dataPort" to 0))))
        }
    }

    @Test fun compatibilityAndNonMainStreamsDoNotGainMicrophoneAdmission() = fixture {
        for (fields in listOf(emptyMap(), mapOf("input" to true), mapOf("input" to false),
            mapOf("input" to 1L), mapOf("input" to 0L), mapOf("input" to "true"))) {
            play(stream("compatibility", fields))
            play(stream("default", fields + ("type" to 101)), 101)
            play(stream("alert", fields))
            play(stream("media", fields))
            assertTrue(sink.captures.isEmpty())
        }
        assertTrue(session.offeredMicrophone.bits("compatibility").isEmpty())
        assertEquals(setOf(0x40L), session.offeredMicrophone.bits("default"))
    }

    @Test fun explicitOptOutDoesNotRequireMicrophoneEnablementOrOfferedCapability() {
        for (enabled in listOf(false, true)) fixture(enabled, MicrophoneCapability.NONE) {
            play(stream(fields = mapOf("input" to false)))
            assertTrue(sink.captures.isEmpty())
            assertNull(engine.onAudio(session, 100, stream(fields = mapOf("input" to true))))
            assertNull(engine.onAudio(session, 100, stream()))
        }
    }

    @Test fun explicitOptOutBypassesOnlyInputChecksNotOutputAdmission() = fixture {
        sink.microphoneSupported = false
        play(stream(fields = mapOf("input" to false)))
        assertNull(engine.onAudio(session, 100, stream(fields = mapOf("input" to true))))
        sink.microphoneSupported = true
        // Stereo is an existing output candidate, not an implemented microphone format.
        play(stream(fields = mapOf("input" to false, "audioFormat" to 0x80L)))
        assertNull(engine.onAudio(session, 100, stream(fields = mapOf("input" to true, "audioFormat" to 0x80L))))
        for (fields in listOf(mapOf("audioFormat" to 64.5), mapOf("audioFormat" to 0x41L),
            mapOf("audioFormat" to 0x8000000L), mapOf("streamConnectionID" to "123"),
            mapOf("audioType" to false), mapOf("audioType" to "unknown"))) {
            assertNull(engine.onAudio(session, 100, stream(fields = fields + ("input" to false))))
        }
        sink.audioSupported = false
        assertNull(engine.onAudio(session, 100, stream(fields = mapOf("input" to false))))
        assertTrue(sink.captures.isEmpty())
    }

    @Test fun requestedInputStillRequiresAnExactPortFormatAndFrameSize() = fixture {
        for (fields in listOf(emptyMap(), mapOf("input" to true), mapOf("input" to 1L), mapOf("input" to "false"))) {
            for (bad in listOf(mapOf("dataPort" to 0), mapOf("dataPort" to 65536),
                mapOf("dataPort" to 6000.5), mapOf("dataPort" to "6000"), mapOf("spf" to 960),
                mapOf("spf" to 480.5), mapOf("audioFormat" to 0x80L))) {
                assertNull(engine.onAudio(session, 100, stream(fields = fields + bad)))
            }
        }
        assertFalse(engine.owns(session))
        assertTrue(sink.captures.isEmpty())
    }

    @Test fun absentPortRetainsOutputOnlyCompatibilityEvenForTrueInput() = fixture {
        for (fields in listOf(emptyMap(), mapOf("input" to true), mapOf("input" to false),
            mapOf("input" to 1L), mapOf("input" to "true"))) {
            play(stream(fields = fields) - "dataPort")
            assertTrue(sink.captures.isEmpty())
        }
    }

    @Test fun explicitFalseDefaultOutputCannotDisplaceAnActiveCallMicrophone() = fixture {
        play(stream("telephony"))
        val call = sink.captures.single()
        assertFalse(call.closed)
        play(stream("default", mapOf("input" to false)))
        assertEquals("output-only default acquired an unintended microphone", 1, sink.captures.size)
        assertFalse("output-only default displaced the call microphone", call.closed)
    }
}

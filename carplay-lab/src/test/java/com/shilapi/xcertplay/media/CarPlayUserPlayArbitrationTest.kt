package com.shilapi.xcertplay.media

import android.content.Context
import android.content.ContextWrapper
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.openautolink.carplaylab.media.CarPlayDisplayPreferences
import com.openautolink.carplaylab.media.CarPlayDisplaySelection
import com.shilapi.xcertplay.airplay.AudioStreamId
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * Actual production sink, tracks and focus listeners. OS-boundary fixture, NOT Android/GM execution.
 * Pinned Android 12.1/retained GM FocusInteraction rows: VOICE_COMMAND(3)->MUSIC(1)
 * is concurrent, but non-MAY_DUCK adds losers and MEDIA GAIN delivers permanent LOSS.
 * CALL_RING(4)/CALL(5)->MUSIC(1) reject ordinary clients. The native GM call override
 * requires a native/BT focus identity and is not an ordinary OAL permission.
 * Other incoming-purpose arbitration is deliberately outside this narrow fixture.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32], manifest=Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayUserPlayArbitrationTest {
    private class Fixture(val asynchronous: Boolean = true) : AutoCloseable {
        val status = java.util.concurrent.CopyOnWriteArrayList<String>()
        val requests = mutableListOf<AudioFocusRequest>()
        val active = mutableListOf<AudioFocusRequest>()
        var delivered = 0
        var abandonResponse = AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        var response = AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        var duringRequest: (() -> Unit)? = null
        val manager = mock(AudioManager::class.java)
        val sink: AndroidMediaSink
        init {
            `when`(manager.requestAudioFocus(any(AudioFocusRequest::class.java))).thenAnswer {
                val request = it.getArgument<AudioFocusRequest>(0)
                requests += request
                duringRequest?.invoke()
                if (response != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return@thenAnswer response
                if (request.audioAttributes.usage == AudioAttributes.USAGE_MEDIA &&
                    request.focusGain == AudioManager.AUDIOFOCUS_GAIN) {
                    if (active.any { r -> r.audioAttributes.usage in listOf(
                        AudioAttributes.USAGE_NOTIFICATION_RINGTONE,AudioAttributes.USAGE_VOICE_COMMUNICATION) })
                        return@thenAnswer AudioManager.AUDIOFOCUS_REQUEST_FAILED
                    val losers = active.filter { r -> r.audioAttributes.usage == AudioAttributes.USAGE_ASSISTANT }
                    active.removeAll(losers.toSet())
                    losers.forEach { loser ->
                        val deliver = Runnable { change(loser,AudioManager.AUDIOFOCUS_LOSS); delivered++ }
                        if (asynchronous) Handler(Looper.getMainLooper()).post(deliver) else deliver.run()
                    }
                }
                active += request
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
            `when`(manager.abandonAudioFocusRequest(any(AudioFocusRequest::class.java))).thenAnswer {
                if (abandonResponse == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) active.remove(it.getArgument<AudioFocusRequest>(0))
                abandonResponse
            }
            val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
                override fun getApplicationContext(): Context = this
                override fun getSystemService(name: String): Any? =
                    if (name == Context.AUDIO_SERVICE) manager else super.getSystemService(name)
            }
            val surface = mock(Surface::class.java)
            `when`(surface.isValid).thenReturn(true)
            sink = AndroidMediaSink(context,surface,onStatus={ status += it },
                display=CarPlayDisplaySelection(CarPlayDisplayPreferences(),"avc.exact",false))
        }
        fun start(purpose: String): AudioStreamId {
            val id = AudioStreamId(100,purpose)
            sink.onAudioStarted(id,requireNotNull(AudioOutputSupport.negotiate(0x4000L,100,purpose)),0)
            return id
        }
        fun prepareVehicleOrder(): AudioStreamId {
            sink.onAudioStopped(start("media")) // revision 1 retired before Siri starts
            return start("speechrecognition") // revision 2 live and unmuted
        }
        fun volume(id: AudioStreamId): Float {
            val tracks = AndroidMediaSink::class.java.getDeclaredField("tracks").apply { isAccessible=true }.get(sink) as Map<*,*>
            val track = requireNotNull(tracks[id])
            return track.javaClass.getDeclaredField("requestedVolume").apply { isAccessible=true }.getFloat(track)
        }
        fun change(request: AudioFocusRequest, value: Int) {
            val listener = AudioFocusRequest::class.java.getDeclaredMethod("getOnAudioFocusChangeListener")
                .invoke(request) as AudioManager.OnAudioFocusChangeListener
            listener.onAudioFocusChange(value)
        }
        fun drain() { shadowOf(Looper.getMainLooper()).idle() }
        override fun close() { drain(); sink.close(); assertTrue(sink.awaitRetired(3000)) }
    }

    /** Real SETUP replacement and AEAD/UDP start; session and OS are boundary mocks. */
    private class EngineFixture(val f: Fixture) : AutoCloseable {
        val session = mock(com.shilapi.xcertplay.airplay.AirPlaySession::class.java)
        private val peer = java.net.InetAddress.getLoopbackAddress()
        private val shared = ByteArray(32) { 7 }
        private val received = java.util.concurrent.LinkedBlockingQueue<AudioStreamId>()
        private var sequence = 10L
        val engine = com.shilapi.xcertplay.airplay.CarPlayMediaEngine(object : com.shilapi.xcertplay.airplay.MediaSink by f.sink {
            override fun onAudioRtp(id: AudioStreamId, format: com.shilapi.xcertplay.airplay.AudioFormat, rtp: ByteArray, sample: Int) {
                f.sink.onAudioRtp(id,format,rtp,sample)
                received.add(id) // after the real engine's onStarted and the actual sink publication
            }
        })
        init {
            `when`(session.sharedSecret).thenReturn(shared)
            `when`(session.remoteAddress).thenReturn(peer)
        }
        fun setupMedia(): () -> AudioStreamId {
            val connection = sequence++
            val accepted = requireNotNull(engine.onAudio(session,100,mapOf(
                "audioType" to "media", "audioFormat" to 0x4000L, "streamConnectionID" to connection)))
            val key = com.shilapi.xcertplay.airplay.AirPlayCrypto.hkdfSha512(shared,
                "DataStream-Salt$connection".toByteArray(),"DataStream-Output-Encryption-Key".toByteArray(),32)
            return {
                val wire = com.shilapi.xcertplay.airplay.MicrophonePacketizer.sealPacket(key,
                    com.shilapi.xcertplay.airplay.MicrophoneCounters(),ByteArray(960),480)
                java.net.DatagramSocket().use { udp -> udp.send(java.net.DatagramPacket(wire,wire.size,
                    peer,(accepted["dataPort"] as Number).toInt())) }
                val id = AudioStreamId(100,"media")
                assertEquals("actual engine authenticated replacement did not reach the sink",id,
                    received.poll(3,java.util.concurrent.TimeUnit.SECONDS))
                id
            }
        }
        fun startMedia() = setupMedia().invoke()
        override fun close() { engine.onSessionClosed(session) }
    }

    @Test fun engineSetupReplacementCannotRearmCancelledInitialMedia() {
        listOf("after","gap","before").forEach { end -> Fixture().use { f -> EngineFixture(f).use { e ->
            val siri=f.start("speechrecognition")
            val media=e.startMedia()
            assertEquals(0f,f.volume(media),0f)
            f.sink.cancelPendingMediaPlay()
            if (end == "before") f.sink.onAudioStopped(siri)
            val deliver=e.setupMedia()
            if (end == "gap") f.sink.onAudioStopped(siri)
            deliver()
            if (end == "after") f.sink.onAudioStopped(siri)
            assertEquals("SETUP replacement cannot rearm cancelled initial media ($end)",1,f.requests.size)
            assertEquals(0f,f.volume(media),0f)
            repeat(3) { e.startMedia() }
            assertEquals(1,f.requests.size)
            assertTrue(f.sink.prepareUserPlay().outputPermitted)
            assertEquals(2,f.requests.size)
            assertEquals(1f,f.volume(media),0f)
        } } }
    }

    @Test fun engineSetupReplacementPreservesExplicitBlockedMediaRecovery() {
        listOf("after","gap","before").forEach { end -> Fixture().use { f -> EngineFixture(f).use { e ->
            val media=e.startMedia()
            f.change(f.requests.single(),AudioManager.AUDIOFOCUS_LOSS)
            val siri=f.start("speechrecognition")
            repeat(3) { assertEquals(CarPlayPlayDisposition.DEFERRED,f.sink.prepareUserPlay()) }
            if (end == "before") f.sink.onAudioStopped(siri)
            val deliver=e.setupMedia()
            if (end == "gap") f.sink.onAudioStopped(siri)
            deliver()
            if (end == "after") {
                assertEquals("replacement cannot compete with live assistant",2,f.requests.size)
                assertEquals(0f,f.volume(media),0f)
                f.sink.onAudioStopped(siri)
            }
            assertEquals("SETUP alone must not discard explicit recovery ($end)",3,f.requests.size)
            assertEquals(1f,f.volume(media),0f)
            f.change(f.requests.last(),AudioManager.AUDIOFOCUS_LOSS)
            repeat(3) { e.startMedia() }
            assertEquals("replacement cannot replenish consumed PLAY after genuine loss",3,f.requests.size)
            assertEquals(0f,f.volume(media),0f)
        } } }
    }

    @Test fun engineTrueTeardownStillCancelsDeferredRecovery() = Fixture().use { f -> EngineFixture(f).use { e ->
        val media=e.startMedia()
        f.change(f.requests.single(),AudioManager.AUDIOFOCUS_LOSS)
        val siri=f.start("speechrecognition")
        assertEquals(CarPlayPlayDisposition.DEFERRED,f.sink.prepareUserPlay())
        e.engine.onTeardown(e.session,100)
        f.sink.onAudioStopped(siri)
        e.startMedia()
        assertEquals("true TEARDOWN must cancel old PLAY",2,f.requests.size)
        assertEquals(0f,f.volume(media),0f)
    } }

    @Test fun failedEngineReplacementRetiresPendingMediaIntent() = Fixture().use { f -> EngineFixture(f).use { e ->
        val media=e.startMedia()
        f.change(f.requests.single(),AudioManager.AUDIOFOCUS_LOSS)
        val siri=f.start("speechrecognition")
        assertEquals(CarPlayPlayDisposition.DEFERRED,f.sink.prepareUserPlay())
        val binding=mock(com.openautolink.carplaylab.network.CarPlaySocketBinding::class.java)
        `when`(binding.local).thenThrow(IllegalStateException("synthetic bind failure"))
        `when`(e.session.socketBinding).thenReturn(binding)
        assertTrue(runCatching { e.setupMedia() }.isFailure)
        `when`(e.session.socketBinding).thenReturn(null)
        f.sink.onAudioStopped(siri)
        e.startMedia()
        assertEquals("failed replacement is true retirement, not continuity",2,f.requests.size)
        assertEquals(0f,f.volume(media),0f)
    } }

    @Test fun engineTeardownBeforeReplacementFirstPacketCancelsPendingRecovery() = Fixture().use { f -> EngineFixture(f).use { e ->
        val media=e.startMedia()
        f.change(f.requests.single(),AudioManager.AUDIOFOCUS_LOSS)
        val siri=f.start("speechrecognition")
        assertEquals(CarPlayPlayDisposition.DEFERRED,f.sink.prepareUserPlay())
        repeat(3) { e.setupMedia() } // no successor first packet: only one retained output owner
        e.engine.onTeardown(e.session,100)
        f.sink.onAudioStopped(siri)
        e.startMedia()
        assertEquals(2,f.requests.size)
        assertEquals(0f,f.volume(media),0f)
    } }

    @Test fun pruningLastRetiredMediaCancelsDeferredPlay() = retiredMediaPruning(false)

    @Test fun pruningOneOfMultipleMediaMembersPreservesDeferredPlay() = retiredMediaPruning(true)

    private fun retiredMediaPruning(survivingMedia: Boolean) = Fixture().use { f -> EngineFixture(f).use { e ->
        val media=e.startMedia()
        f.change(f.requests.single(),AudioManager.AUDIOFOCUS_LOSS)
        if (survivingMedia) f.start("default")
        val siri=f.start("speechrecognition")
        assertEquals(CarPlayPlayDisposition.DEFERRED,f.sink.prepareUserPlay())
        val tracks=AndroidMediaSink::class.java.getDeclaredField("tracks").apply { isAccessible=true }.get(f.sink) as Map<*,*>
        val retired=requireNotNull(tracks[media])
        (retired as java.io.Closeable).close() // concrete output-worker retirement, not sink STOP
        assertTrue(retired.javaClass.getDeclaredMethod("isRetired").apply { isAccessible=true }.invoke(retired) as Boolean)
        val alternate=AudioStreamId(101,"default")
        f.sink.onAudioStarted(alternate,requireNotNull(AudioOutputSupport.negotiate(0x4000L,101,"default")),0)
        assertEquals("pruning cannot request focus",2,f.requests.size)
        f.sink.onAudioStopped(siri)
        e.startMedia() // production replacement/first-packet callback after the pruning boundary
        assertEquals("only surviving MEDIA membership may retain the earlier PLAY",
            if (survivingMedia) 3 else 2,f.requests.size)
        assertEquals(if (survivingMedia) 1f else 0f,f.volume(media),0f)
        if (!survivingMedia) {
            assertTrue(f.sink.prepareUserPlay().outputPermitted)
            assertEquals(3,f.requests.size)
            assertEquals(1f,f.volume(media),0f)
        }
    } }

    @Test fun playMustNotPermanentlySilenceAlreadyGrantedAssistantAfterQueuedArbitration() = Fixture().use { f ->
        val siri = f.prepareVehicleOrder()
        assertEquals(1f,f.volume(siri),0f)
        f.sink.requestFocusForUserPlay()
        f.drain()
        assertEquals("PLAY must preserve the live assistant; MEDIA GAIN must not steal its focus",1f,f.volume(siri),0f)
    }

    @Test fun playMustNotStrandNextAssistantStreamAfterQueuedArbitration() = Fixture().use { f ->
        val siri = f.prepareVehicleOrder()
        f.sink.requestFocusForUserPlay()
        f.drain()
        f.sink.onAudioStopped(siri)
        val next = f.start("speechrecognition")
        assertEquals("one incidental PLAY must not poison later Siri streams",1f,f.volume(next),0f)
    }

    @Test fun queuedExternalLossStillSticksWithoutAutomaticRequests() = Fixture().use { f ->
        val siri = f.prepareVehicleOrder()
        val assistant = f.requests.last()
        assertFalse("transport-only PLAY is not an output grant",f.sink.requestFocusForUserPlay())
        assertEquals(2,f.requests.size)
        Handler(Looper.getMainLooper()).post { f.change(assistant,AudioManager.AUDIOFOCUS_LOSS) }
        assertEquals(1f,f.volume(siri),0f)
        f.drain()
        assertEquals(0,f.delivered)
        assertEquals(0f,f.volume(siri),0f)
        assertTrue(f.status.contains("media stage=audio-mute reason=focus-change revision=2 volumePercent=0 mute=permanent-loss"))
        f.change(assistant,AudioManager.AUDIOFOCUS_GAIN)
        assertEquals("permanent-loss listener cannot falsely restore itself",0f,f.volume(siri),0f)
        repeat(3) {
            f.sink.onAudioStopped(siri)
            assertEquals(0f,f.volume(f.start("speechrecognition")),0f)
        }
        assertEquals("SETUP must not make unbounded requests",2,f.requests.size)
    }

    @Test fun inlinePolicyAlsoNeedsNoCompetingMediaRequestOrAssistantRetry() = Fixture(asynchronous=false).use { f ->
        val siri = f.prepareVehicleOrder()
        assertFalse(f.sink.requestFocusForUserPlay())
        assertEquals(0,f.delivered)
        assertEquals("prevent competition rather than ping-pong grants",2,f.requests.size)
        assertEquals(AudioAttributes.USAGE_ASSISTANT,f.requests.last().audioAttributes.usage)
        assertEquals(1f,f.volume(siri),0f)
    }

    @Test fun deferredPlayCoalescesUntilInteractionEndAndRealMediaMembership() {
        listOf(false,true).forEach { mediaBeforeEnd -> Fixture().use { f ->
            val media = f.start("media")
            f.change(f.requests.single(),AudioManager.AUDIOFOCUS_LOSS)
            f.sink.onAudioStopped(media)
            val siri = f.start("speechrecognition")
            repeat(5) { assertEquals(CarPlayPlayDisposition.DEFERRED,f.sink.prepareUserPlay()) }
            if (mediaBeforeEnd) {
                f.start("media")
                assertEquals(0f,f.volume(media),0f)
            }
            assertEquals("no MEDIA GAIN during live assistant",2,f.requests.size)
            f.sink.onAudioStopped(siri)
            if (!mediaBeforeEnd) {
                assertEquals("no media membership means no speculative focus request",2,f.requests.size)
                f.start("media")
            }
            assertEquals("one explicit authorization consumed by actual media ownership",3,f.requests.size)
            assertEquals(AudioAttributes.USAGE_MEDIA,f.requests.last().audioAttributes.usage)
            assertEquals(1f,f.volume(media),0f)
            f.change(f.requests.last(),AudioManager.AUDIOFOCUS_LOSS)
            repeat(3) { f.sink.onAudioStopped(media); f.start("media") }
            assertEquals("consumed PLAY cannot override a later external loss",3,f.requests.size)
            assertEquals(0f,f.volume(media),0f)
        } }
    }

    @Test fun firstMediaStartDuringInteractionWaitsForActualOutputFocusAtEnd() {
        listOf("telephony","alert","speechrecognition").forEach { purpose -> Fixture().use { f ->
            val interaction=f.start(purpose)
            val media=f.start("media")
            assertEquals(1,f.requests.size)
            assertEquals("a transport/SETUP is not output permission",0f,f.volume(media),0f)
            assertTrue(f.status.any { "mute=deferred" in it && "audio-mute reason=stream-start" in it && "volumePercent=0" in it })
            repeat(3) { f.start("media") }
            assertEquals(1,f.requests.size)
            f.sink.onAudioStopped(interaction)
            assertEquals(2,f.requests.size)
            assertEquals(1f,f.volume(media),0f)
        } }
    }

    @Test fun retainedMediaInterruptionWaitsForItsOwnCallbackNotEndTriggeredReclaim() {
        listOf("telephony","alert","speechrecognition").forEach { purpose ->
            listOf(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK).forEach { loss ->
                listOf(false,true).forEach { queuedAfterPlay -> Fixture().use { f ->
                    val media=f.start("media"); val request=f.requests.single()
                    val interaction=f.start(purpose)
                    if (!queuedAfterPlay) f.change(request,loss)
                    val result=f.sink.prepareUserPlay()
                    assertTrue(result.transportPermitted)
                    assertEquals(queuedAfterPlay || loss == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,result.outputPermitted)
                    if (queuedAfterPlay) Handler(Looper.getMainLooper()).post { f.change(request,loss) }
                    f.drain()
                    f.sink.onAudioStopped(interaction)
                    assertEquals("ending interaction is not permission to replace a retained transient listener",2,f.requests.size)
                    assertEquals(if (loss == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) 0f else 0.2f,f.volume(media),0f)
                    f.change(request,AudioManager.AUDIOFOCUS_GAIN)
                    assertEquals(1f,f.volume(media),0f)
                    assertEquals(2,f.requests.size)
                } }
            }
        }
    }

    @Test fun nonmediaRetirementDebtStillBlocksMediaAfterLastInteractionStops() = Fixture().use { f ->
        val siri=f.prepareVehicleOrder()
        assertEquals(CarPlayPlayDisposition.DEFERRED,f.sink.prepareUserPlay())
        f.abandonResponse=AudioManager.AUDIOFOCUS_REQUEST_FAILED
        try {
            f.sink.onAudioStopped(siri)
            f.start("media")
            assertEquals("no competing MEDIA request over unresolved assistant retirement",2,f.requests.size)
            repeat(3) { assertEquals(CarPlayPlayDisposition.DEFERRED,f.sink.prepareUserPlay());f.start("media") }
            assertEquals(2,f.requests.size)
        } finally { f.abandonResponse=AudioManager.AUDIOFOCUS_REQUEST_GRANTED }
        // A fresh actual assistant membership and its acknowledged stop retire the debt.
        f.start("speechrecognition")
        assertEquals("separate inactive assistant authorization consumes its own real grant",3,f.requests.size)
        f.sink.onAudioStopped(siri)
        assertEquals(4,f.requests.size)
        assertEquals(AudioAttributes.USAGE_MEDIA,f.requests.last().audioAttributes.usage)
    }

    @Test fun stoppingMediaCancelsPendingRecoveryEvenIfInteractionEndsLater() = Fixture().use { f ->
        val media=f.start("media");f.change(f.requests.single(),AudioManager.AUDIOFOCUS_LOSS)
        val siri=f.start("speechrecognition")
        f.sink.prepareUserPlay()
        f.sink.onAudioStopped(media)
        f.sink.onAudioStopped(siri)
        f.start("media")
        assertEquals("stopped media cannot spend an old PLAY",2,f.requests.size)
        assertEquals(0f,f.volume(media),0f)
    }

    @Test fun stopCommandCancelsInitialDeferredOutputWithoutInventingDenial() = Fixture().use { f ->
        val siri=f.start("speechrecognition");val media=f.start("media")
        f.sink.cancelPendingMediaPlay()
        repeat(3) { f.start("media") }
        f.sink.onAudioStopped(siri)
        assertEquals("STOP cancels even an initial deferred request",1,f.requests.size)
        assertEquals(0f,f.volume(media),0f)
        assertTrue(f.sink.prepareUserPlay().outputPermitted)
        assertEquals(2,f.requests.size)
        assertEquals(1f,f.volume(media),0f)
    }

    @Test fun stopBetweenPendingCheckAndFirstMediaConsumptionWithholdsOutput() = cancelledBeforeConsumption(false)

    @Test fun stopBetweenPendingCheckAndInteractionEndConsumptionPreventsRetry() = cancelledBeforeConsumption(true)

    private fun cancelledBeforeConsumption(mediaBeforeEnd: Boolean) = Fixture().use { f -> EngineFixture(f).use { e ->
        val siri=f.start("speechrecognition") // MEDIA has never requested focus; no denial masks the race.
        assertEquals(CarPlayPlayDisposition.DEFERRED,f.sink.prepareUserPlay())
        val media=AudioStreamId(100,"media")
        if (mediaBeforeEnd) e.startMedia() else f.sink.onAudioStopped(siri)
        val selected=java.util.concurrent.CountDownLatch(1)
        val consume=java.util.concurrent.CountDownLatch(1)
        val workers=java.util.concurrent.Executors.newFixedThreadPool(2)
        f.sink.beforeMediaPlayConsumption = {
            assertTrue("actual entrypoint must hold the sink monitor",Thread.holdsLock(f.sink))
            selected.countDown()
            check(consume.await(3,java.util.concurrent.TimeUnit.SECONDS))
        }
        try {
            val entry=workers.submit {
                if (mediaBeforeEnd) f.sink.onAudioStopped(siri) else e.startMedia()
            }
            assertTrue("actual entrypoint did not select pending PLAY",selected.await(3,java.util.concurrent.TimeUnit.SECONDS))
            // The real STOP-ingress cancellation must finish while the sink monitor is parked.
            workers.submit { f.sink.cancelPendingMediaPlay() }.get(1,java.util.concurrent.TimeUnit.SECONDS)
            consume.countDown()
            entry.get(3,java.util.concurrent.TimeUnit.SECONDS)
        } finally {
            consume.countDown()
            f.sink.beforeMediaPlayConsumption=null
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(3,java.util.concurrent.TimeUnit.SECONDS))
        }
        assertEquals("cancelled-null PLAY must not reach MEDIA Binder",1,f.requests.size)
        assertEquals("cancelled-null PLAY must withhold ungranted output",0f,f.volume(media),0f)
        repeat(3) { e.startMedia() }
        assertEquals("SETUP/packets cannot replenish cancelled-null PLAY",1,f.requests.size)
        assertEquals(0f,f.volume(media),0f)
        e.engine.onTeardown(e.session,100)
        e.startMedia()
        assertEquals("true stop/start cannot bypass the restrictive latch",1,f.requests.size)
        assertEquals(0f,f.volume(media),0f)
        assertTrue("only fresh explicit PLAY can recover",f.sink.prepareUserPlay().outputPermitted)
        assertEquals(2,f.requests.size)
        assertEquals(1f,f.volume(media),0f)
    } }

    @Test fun stopBetweenInitialGenerationCheckAndRequestCannotAdoptNewEpoch() = Fixture().use { f ->
        EngineFixture(f).use { e ->
            val siri=f.start("speechrecognition")
            e.startMedia()
            val media=AudioStreamId(100,"media")
            val selected=java.util.concurrent.CountDownLatch(1)
            val resume=java.util.concurrent.CountDownLatch(1)
            val workers=java.util.concurrent.Executors.newFixedThreadPool(2)
            f.sink.beforeInitialMediaResume = {
                assertTrue(Thread.holdsLock(f.sink))
                selected.countDown()
                check(resume.await(3,java.util.concurrent.TimeUnit.SECONDS))
            }
            try {
                val stop=workers.submit { f.sink.onAudioStopped(siri) }
                assertTrue(selected.await(3,java.util.concurrent.TimeUnit.SECONDS))
                workers.submit { f.sink.cancelPendingMediaPlay() }.get(1,java.util.concurrent.TimeUnit.SECONDS)
                resume.countDown()
                stop.get(3,java.util.concurrent.TimeUnit.SECONDS)
            } finally {
                resume.countDown()
                f.sink.beforeInitialMediaResume=null
                workers.shutdownNow()
                assertTrue(workers.awaitTermination(3,java.util.concurrent.TimeUnit.SECONDS))
            }
            assertEquals("initial admission must retain its original epoch after STOP",1,f.requests.size)
            assertEquals(0f,f.volume(media),0f)
            repeat(3) { e.startMedia() }
            assertEquals("replacement cannot replenish cancelled initial admission",1,f.requests.size)
            assertTrue(f.sink.prepareUserPlay().outputPermitted)
            assertEquals(1f,f.volume(media),0f)
        }
    }

    @Test fun invalidDeferredPlayBeforeBinderWithholdsInitialOutputAndCannotRetry() = invalidBeforeBinder(false)

    @Test fun invalidDeferredPlayBeforeBinderCannotRetryAlreadyDeferredInitialOutput() = invalidBeforeBinder(true)

    private fun invalidBeforeBinder(mediaBeforeEnd: Boolean) {
        Fixture().use { f ->
            val siri=f.start("speechrecognition")
            var current=true
            assertEquals(CarPlayPlayDisposition.DEFERRED,f.sink.prepareUserPlay { current })
            val media=AudioStreamId(100,"media")
            if (mediaBeforeEnd) f.start("media")
            current=false // exact-session revocation before any MEDIA Binder request
            f.sink.onAudioStopped(siri)
            if (!mediaBeforeEnd) f.start("media")
            assertEquals("invalid PLAY must not request focus",1,f.requests.size)
            assertEquals("invalid PLAY cannot publish initial ungranted output",0f,f.volume(media),0f)
            repeat(3) { f.start("media") }
            assertEquals("consumed invalid PLAY cannot become implicit first admission",1,f.requests.size)
            assertEquals(0f,f.volume(media),0f)
            assertTrue(f.sink.prepareUserPlay().outputPermitted)
            assertEquals(2,f.requests.size)
            assertEquals(1f,f.volume(media),0f)
        }
    }

    @Test fun deferredAttemptConsumesAuthorizationOnDenialOrCancellationDuringBinder() {
        listOf("denied","stop","replaced","revoke").forEach { failure ->
            listOf(false,true).forEach { previouslyBlocked -> Fixture().use { f ->
            if (previouslyBlocked) { f.start("media");f.change(f.requests.single(),AudioManager.AUDIOFOCUS_LOSS) }
            val siri=f.start("speechrecognition")
            val media=f.start("media")
            val before=f.requests.size
            var current=true
            assertEquals(CarPlayPlayDisposition.DEFERRED,f.sink.prepareUserPlay { current })
            f.duringRequest = {
                when(failure) {
                    "denied" -> f.response=AudioManager.AUDIOFOCUS_REQUEST_FAILED
                    "stop" -> f.sink.cancelPendingMediaPlay()
                    "replaced" -> current=false
                    "revoke" -> f.sink.revokePendingWork()
                }
            }
            f.sink.onAudioStopped(siri)
            assertEquals(before+1,f.requests.size)
            assertEquals("a late/cancelled result is not output permission",0f,f.volume(media),0f)
            f.duringRequest=null;f.response=AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            repeat(3) { f.start("media") }
            assertEquals("no retry from SETUP after consumed permission",before+1,f.requests.size)
        } } }
    }

    @Test fun liveCallRingAndAssistantKeepMembershipAcrossAllPlayMediaStates() {
        listOf("telephony","alert","speechrecognition").forEach { purpose ->
            listOf("absent","live","blocked").forEach { mediaState ->
                listOf("healthy","transient","blocked").forEach { interactionState ->
                    listOf(false,true).forEach { asynchronous -> Fixture(asynchronous).use { f ->
                        var media: AudioStreamId?=null
                        if (mediaState != "absent") {
                            media=f.start("media")
                            if (mediaState == "blocked") f.change(f.requests.last(),AudioManager.AUDIOFOCUS_LOSS)
                        }
                        val interaction=f.start(purpose);val request=f.requests.last()
                        when(interactionState) {
                            "transient" -> f.change(request,AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
                            "blocked" -> f.change(request,AudioManager.AUDIOFOCUS_LOSS)
                        }
                        val before=f.requests.size
                        val result=f.sink.prepareUserPlay();f.drain()
                        assertTrue(result.transportPermitted)
                        assertEquals(mediaState == "live",result.outputPermitted)
                        assertEquals(before + if (interactionState == "blocked") 1 else 0,f.requests.size)
                        assertEquals(0,f.delivered)
                        assertEquals(if (interactionState == "transient") 0f else 1f,f.volume(interaction),0f)
                        if (mediaState == "blocked") assertEquals(0f,f.volume(requireNotNull(media)),0f)
                    } }
                }
            }
        }
    }

    @Test fun primaryMediaDeferralDoesNotMuteNoFocusAlternateGuidance() = Fixture().use { f ->
        f.start("speechrecognition")
        f.start("media")
        val alternate=AudioStreamId(101,"default")
        f.sink.onAudioStarted(alternate,requireNotNull(AudioOutputSupport.negotiate(0x4000L,101,"default")),0)
        assertEquals("alternate keeps the native/reference no-primary-focus policy",1f,f.volume(alternate),0f)
        assertEquals(1,f.requests.size)
    }

    @Test fun invalidatedCommandCannotFinishOrContinueNonmediaRecovery() = Fixture().use { f ->
        f.response=AudioManager.AUDIOFOCUS_REQUEST_FAILED
        val call=f.start("telephony");val ring=f.start("alert")
        f.response=AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        var current=true
        f.duringRequest={current=false}
        assertEquals(CarPlayPlayDisposition.CLOSED,f.sink.prepareUserPlay { current })
        assertEquals("only the in-flight call request may finish",3,f.requests.size)
        assertEquals(0f,f.volume(call),0f)
        assertEquals(0f,f.volume(ring),0f)
    }

    @Test fun noPlayControlKeepsAssistantHealthyAndCanStartNextStream() = Fixture().use { f ->
        val siri = f.prepareVehicleOrder()
        f.drain()
        assertEquals(0,f.delivered)
        assertEquals(1f,f.volume(siri),0f)
        f.sink.onAudioStopped(siri)
        assertEquals(1f,f.volume(f.start("speechrecognition")),0f)
        assertEquals(3,f.requests.size)
    }
}

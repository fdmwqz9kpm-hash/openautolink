package com.shilapi.xcertplay.media

import android.media.*
import android.media.audiofx.AudioEffect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32],manifest=Config.NONE)
class MicrophoneDiagnosticBoundariesTest {
    private fun descriptor(type: UUID?)=AudioEffect.Descriptor().apply {
        this.type=type;name="private-name";implementor="private-implementor";uuid=UUID.randomUUID()
    }
    private fun effects(config: Result<AudioRecordingConfiguration?>)=MicrophoneEffectDiagnostics.receipts(config,MicrophoneSource.VOICE_COMMUNICATION,24000)
    private fun configuration(config: Result<AudioRecordingConfiguration?>, record: AudioRecord=mock(AudioRecord::class.java), manager: AudioManager?=null)=
        MicrophoneConfigurationDiagnostics.receipt(record,manager,MicrophoneSource.VOICE_COMMUNICATION,24000,config)

    @Test fun absentConfigurationIsNotFailedQueryOrDisabledEffects() {
        val absent=configuration(Result.success(null))
        val failed=configuration(Result.failure(SecurityException("private-message")))
        assertTrue(absent.contains("config=absent client=absent active=absent"))
        assertTrue(absent.endsWith("route=absent"))
        assertTrue(failed.contains("config=unavailable client=unavailable active=unavailable"))
        effects(Result.success(null)).forEach { assertTrue(it.contains("result=absent examined=0 truncated=false aec=unknown ns=unknown agc=unknown")) }
        effects(Result.failure(SecurityException("private-message"))).forEach { assertTrue(it.contains("result=unavailable examined=0 truncated=false aec=unknown ns=unknown agc=unknown"));assertFalse(it.contains("private")) }
    }

    @Test fun completeEmptyListsAreDisabledRatherThanUnavailable() {
        val config=mock(AudioRecordingConfiguration::class.java)
        `when`(config.clientEffects).thenReturn(emptyList())
        `when`(config.effects).thenReturn(emptyList())
        effects(Result.success(config)).forEach { assertTrue(it.endsWith("result=ok examined=0 truncated=false aec=false ns=false agc=false other=0")) }
    }

    @Test fun truncatedListsNeverClaimMissingEffectsDisabledAndNeverTraversePastCap() {
        val config=mock(AudioRecordingConfiguration::class.java);val reads=AtomicInteger()
        val huge=object : AbstractList<AudioEffect.Descriptor>() {
            override val size=1000000
            override fun get(index: Int): AudioEffect.Descriptor {
                assertTrue("descriptor traversal must be bounded",index<16);reads.incrementAndGet()
                return descriptor(if(index==0) AudioEffect.EFFECT_TYPE_AEC else UUID(0,0))
            }
        }
        `when`(config.effects).thenReturn(huge)
        val line=effects(Result.success(config)).last()
        assertEquals(16,reads.get())
        assertTrue(line.endsWith("result=truncated examined=16 truncated=true aec=true ns=unknown agc=unknown other=15"))
    }

    @Test fun malformedDescriptorAndFailedSiblingChainDoNotEraseKnownEnabledEffect() {
        val config=mock(AudioRecordingConfiguration::class.java)
        `when`(config.clientEffects).thenThrow(IllegalStateException("private"))
        `when`(config.effects).thenReturn(listOf(descriptor(null),descriptor(AudioEffect.EFFECT_TYPE_NS)))
        val lines=effects(Result.success(config))
        assertTrue(lines.first().contains("result=unavailable"))
        assertTrue(lines.last().endsWith("result=partial examined=2 truncated=false aec=unknown ns=true agc=unknown other=0"))
    }

    @Test fun unknownObservedValuesDifferFromUnavailableGettersWithoutEchoingIntegers() {
        val record=mock(AudioRecord::class.java);val manager=mock(AudioManager::class.java)
        val config=mock(AudioRecordingConfiguration::class.java)
        `when`(record.audioSource).thenReturn(Int.MAX_VALUE)
        `when`(manager.mode).thenReturn(Int.MAX_VALUE)
        `when`(config.clientAudioSource).thenThrow(SecurityException("private"))
        `when`(config.audioSource).thenReturn(Int.MAX_VALUE)
        `when`(config.clientFormat).thenReturn(null)
        `when`(config.format).thenThrow(IllegalStateException("private"))
        val receipt=configuration(Result.success(config),record,manager)
        assertTrue(receipt.contains("recorder=unknown mode=unknown config=present client=unavailable active=unknown"))
        assertTrue(receipt.contains("clientRate=unavailable deviceRate=unavailable"))
        assertFalse(receipt.contains(Int.MAX_VALUE.toString()));assertFalse(receipt.contains("private"))
        `when`(record.audioSource).thenThrow(IllegalStateException("private"))
        `when`(manager.mode).thenThrow(SecurityException("private"))
        assertTrue(configuration(Result.success(config),record,manager).contains("recorder=unavailable mode=unavailable"))
    }

    @Test fun busRouteIsTypeOnlyAndCannotNameNativeCallInput() {
        val config=mock(AudioRecordingConfiguration::class.java);val device=mock(AudioDeviceInfo::class.java)
        `when`(config.audioDevice).thenReturn(device)
        `when`(device.isSource).thenReturn(true);`when`(device.type).thenReturn(AudioDeviceInfo.TYPE_BUS)
        assertTrue(configuration(Result.success(config)).endsWith("route=bus"))
        verify(device,never()).address;verify(device,never()).id;verify(device,never()).productName
        verify(config,never()).clientAudioSessionId
    }
}

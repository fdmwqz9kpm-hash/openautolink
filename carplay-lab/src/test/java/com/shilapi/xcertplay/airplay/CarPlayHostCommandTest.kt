package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test
import java.net.Socket

class CarPlayHostCommandTest {
    private fun session(listener: AirPlaySessionListener) = AirPlaySession(Socket(),
        AirPlayConfig("test","test","test","950.7.1",AirPlayDisplayConfig(800,480)),
        AirPlayIdentity.generate(),PairingStore(),null,listener,object : AirPlayMediaHandler {})
    private fun command(session: AirPlaySession, params: Map<String, Any?> = emptyMap()): Int? {
        val body=BplistCodec.encode(mapOf("type" to "requestUI", "params" to params))
        val handle=AirPlaySession::class.java.getDeclaredMethod("handle",RtspMessage.Request::class.java).apply { isAccessible=true }
        return (handle.invoke(session,RtspMessage.Request("POST","/command","RTSP/1.0",emptyMap(),body)) as RtspMessage.Response).status
    }
    @Test fun genericReturnAcceptsOnlyAbsentOrEmptyUrlAndKeepsVideoSeparate() {
        var host=0;var video=0
        val session=session(object : AirPlaySessionListener {
            override fun onHostUiRequested(session: AirPlaySession) { host++ }
            override fun onVideoPlaybackUiRequested(session: AirPlaySession) { video++ }
        })
        // Synthetic admitted-control fixture, not cryptographic or physical-phone evidence.
        (AirPlaySession::class.java.getDeclaredField("authenticated").apply { isAccessible=true }.get(session) as java.util.concurrent.atomic.AtomicBoolean).set(true)
        try {
            command(session);command(session,mapOf("url" to ""))
            assertEquals(2,host)
            command(session,mapOf("url" to "videoplayback:"))
            assertEquals(1,video);assertEquals(2,host)
            for (url in listOf<Any>("https://example.invalid/", "VideoPlayback:", " ",42)) command(session,mapOf("url" to url))
            assertEquals("unsupported URLs must not minimize or launch intents",2,host)
        } finally { session.close() }
    }
    @Test fun preAuthenticationCannotRequestHostUi() {
        var host=0
        val session=session(object : AirPlaySessionListener { override fun onHostUiRequested(session: AirPlaySession) { host++ } })
        try { command(session); assertEquals("unverified socket cannot navigate host",0,host) }
        finally { session.close() }
    }
}

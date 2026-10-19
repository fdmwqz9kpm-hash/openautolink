package com.shilapi.xcertplay.airplay

import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class DrivingRestrictionEventTest {
    private fun config(enabled: Boolean) = AirPlayConfig("test","test","test","950.7.1",
        AirPlayDisplayConfig(800,480), cluster=AirPlayDisplayConfig(640,480),drivingRestrictions=enabled)
    private fun field(s: AirPlaySession,n: String)=s.javaClass.getDeclaredField(n).apply {isAccessible=true}
    @Test fun binaryInfoStartsBothDisplaysRestrictedOnlyWhenOptedInAndVideoStaysAbsent() {
        for (enabled in listOf(false,true)) {
            val c=config(enabled)
            val info=BplistCodec.decode(BplistCodec.encode(AirPlayInfoPlist.build(c,emptySet()))) as Map<*,*>
            val displays=info["displays"] as List<*>
            assertEquals(2,displays.size)
            for (d in displays.map {it as Map<*,*>}) {
                if (enabled) {
                    assertEquals(true,d["limitedUI"])
                    assertEquals(listOf("softKeyboard","softPhoneKeypad","nonMusicLists","musicLists"),d["limitedUIElements"])
                } else {assertFalse(d.containsKey("limitedUI"));assertFalse(d.containsKey("limitedUIElements"))}
            }
            assertFalse(c.videoInCar);assertFalse(info.containsKey("videoPlaybackAllowed"))
        }
    }
    @Test fun realAeadEventContainsBooleanAndExactDisplayUuidAndNeverRetainsPreReadyUnlock() {
        for (enabled in listOf(false,true)) {
            val session=AirPlaySession(Socket(),config(enabled),AirPlayIdentity.generate(),PairingStore(),null,
                object:AirPlaySessionListener{},object:AirPlayMediaHandler{})
            ServerSocket(0).use { server -> Socket("127.0.0.1",server.localPort).use { phone -> server.accept().use { event ->
                val key=ByteArray(32){(it+9).toByte()};val peer=ControlCipher(key,key)
                assertFalse(session.setLimitedUIIfEventReady(false) { true })
                field(session,"eventSocket").set(session,event);field(session,"eventCipher").set(session,ControlCipher(key,key))
                assertFalse(session.setLimitedUIIfEventReady(false) { true })
                (field(session,"authenticated").get(session) as AtomicBoolean).set(true)
                assertEquals(enabled,session.setLimitedUIIfEventReady(false) { true });phone.soTimeout=150
                if (!enabled) {
                    try {phone.getInputStream().read();fail("off must write no bytes")}
                    catch (_:java.net.SocketTimeoutException) {}
                } else {
                    fun read(): Map<*,*> {
                        val input=phone.getInputStream();val len=input.readNBytes(2)
                        val n=(len[0].toInt() and 255) or ((len[1].toInt() and 255) shl 8)
                        val plain=peer.decrypt(len+input.readNBytes(n+16)).data
                        val text=plain.toString(Charsets.ISO_8859_1);assertTrue(text.startsWith("POST /command"))
                        return BplistCodec.decode(plain.copyOfRange(text.indexOf("\r\n\r\n")+4,plain.size)) as Map<*,*>
                    }
                    for ((uuid,limited) in listOf(AirPlayInfoPlist.MAIN_UUID to false,AirPlayInfoPlist.ALT_UUID to true)) {
                        val cmd=read();assertEquals(setOf("type","params"),cmd.keys);assertEquals("setLimitedUI",cmd["type"])
                        val params=cmd["params"] as Map<*,*>;assertEquals(setOf("uuid","limitedUI"),params.keys)
                        assertEquals(uuid,params["uuid"]);assertEquals(limited,params["limitedUI"])
                        assertTrue(params["limitedUI"] is Boolean)
                    }
                    assertTrue(session.setLimitedUIIfEventReady(true) { false });assertEquals(true,(read()["params"] as Map<*,*>)["limitedUI"])
                    read()
                    val park=AtomicBoolean(true);val started=java.util.concurrent.CountDownLatch(1)
                    val result=AtomicBoolean(true)
                    val lock=checkNotNull(field(session,"eventWriteLock").get(session))
                    lateinit var worker: Thread
                    synchronized(lock) {
                        worker=Thread {started.countDown();result.set(session.setLimitedUIIfEventReady(false) {park.get()})}
                        worker.start();assertTrue(started.await(1,java.util.concurrent.TimeUnit.SECONDS))
                        park.set(false) // source/owner changed while the real writer was busy
                    }
                    worker.join(1000);assertFalse(worker.isAlive);assertFalse("clamp is not an acknowledged unlock",result.get())
                    assertEquals(true,(read()["params"] as Map<*,*>)["limitedUI"])
                    assertEquals(true,(read()["params"] as Map<*,*>)["limitedUI"])
                }
                session.close();assertFalse(session.setLimitedUIIfEventReady(false) { true })
            } } }
        }
    }
}

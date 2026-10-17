package com.openautolink.app.carplay

import android.content.Context
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import com.openautolink.app.diagnostics.*
import com.shilapi.xcertplay.transport.*
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

class CarPlayUsbNetworkExportTest {
    @get:Rule val temp=TemporaryFolder()
    @After fun cleanup()=unmockkAll()

    @Test fun actualNcmCompletionsCrossReporterAndRealManualUploaderZipPath() {
        val base=temp.newFolder("volume");val cache=temp.newFolder("cache")
        val logs=File(base,"openautolink/logs").apply {mkdirs()};val log=File(logs,"oal_network.log")
        val reporter=CarPlayAudioDiagnosticReporter(524,System::nanoTime) { log.appendText(it+"\n") }
        val connection=mockk<UsbDeviceConnection>(relaxed=true);val endpoint=mockk<UsbEndpoint>()
        var calls=0
        every {connection.bulkTransfer(any(),any<ByteArray>(),any(),any())} answers {
            calls++; if(calls<=6) -1 else thirdArg<Int>()
        }
        // Internal constructor is the production Android boundary, not a replacement sender.
        val constructor=NcmUsbBridge::class.java.declaredConstructors.single {it.parameterCount==7}
        val ncm=constructor.newInstance(connection,endpoint,endpoint,null,emptyList<Any>(),null,reporter::record) as NcmUsbBridge
        try {
            val bridge=com.openautolink.carplaylab.usb.Ipv6NcmBridge(ncm,mockk<android.os.ParcelFileDescriptor>(),ByteArray(6)) {}
            val nd=ByteArray(64).apply {
                this[0]=0x60;this[5]=24;this[6]=58;this[40]=135.toByte()
                this[8]=0xfe.toByte();this[9]=0x80.toByte();this[24]=0xfe.toByte();this[25]=0x80.toByte()
            }
            bridge.javaClass.declaredMethods.single {it.name.startsWith("forwardOutbound")}.invoke(bridge,nd)
            bridge.javaClass.declaredMethods.single {it.name.startsWith("forwardInbound")}.invoke(bridge,
                EthernetIpv6Codec.build(ByteArray(6),ByteArray(6),nd),ByteArrayOutputStream())
            val p=ByteArray(60).apply {this[0]=0x60;this[5]=20;this[6]=6;this[52]=0x50;this[53]=0x12}
            repeat(7) {ncm.send(EthernetIpv6Codec.build(ByteArray(6),ByteArray(6),p),2000)}
            val contents=log.readText()
            assertEquals("sanitizer must not silently discard actual completion receipts",7,contents.lineSequence().count {it.contains("USB network stage=send-end")})
            assertTrue(contents.contains("attempt=5 packetBytes=60 next=tcp icmp=unknown flags=18 result=failed"))
            assertTrue(contents.contains("attempt=7 packetBytes=60 next=tcp icmp=unknown flags=18 result=full"))
            assertTrue(contents.contains("next=icmpv6 icmp=ns flags=unknown peerMacKnown=false decision=mac-unknown-drop"))
            assertTrue(contents.contains("next=icmpv6 icmp=ns flags=unknown peerMacKnown=true decision=forward"))
            val context=mockk<Context>(); every {context.getExternalFilesDirs(null)} returns arrayOf(base)
            every {context.cacheDir} returns cache
            mockkObject(OalLog); every {OalLog.i(any(),any())} returns Unit
            every {OalLog.w(any(),any())} returns Unit;every {OalLog.e(any(),any())} returns Unit
            val wire=ByteArrayOutputStream()
            val conn=object:HttpURLConnection(URL("https://logs.example.test/upload")) {
                override fun connect()=Unit
                override fun usingProxy()=false
                override fun disconnect()=Unit
                override fun getOutputStream()=wire
                override fun getResponseCode()=200
            }
            val result=LogUploader(context,{conn},{true}).upload("https://logs.example.test/upload","synthetic-test-token","test-car")
            assertTrue(result.toString(),result is UploadResult.Success)
            val members=mutableMapOf<String,String>()
            ZipInputStream(wire.toByteArray().inputStream()).use { zip ->
                while(true) {val entry=zip.nextEntry?:break;members[entry.name]=zip.readBytes().toString(Charsets.UTF_8)}
            }
            assertEquals(contents,members[log.name])
            assertFalse(members.values.any {it.contains("synthetic-test-token")})
        } finally {ncm.close()}
    }

    @Test fun completionReceiptsSurviveBusyDetailWindowButStayLifetimeBounded() {
        val lines=mutableListOf<String>();var clock=1_000_000L
        val reporter=CarPlayAudioDiagnosticReporter(524,{clock},lines::add)
        repeat(64) {reporter.record("media stage=audio-focus-change")}
        fun completion(attempt:Int)="USB network stage=send-end attempt=$attempt packetBytes=60 next=tcp icmp=unknown flags=18 result=failed transferred=-1 blockBytes=102 timeoutMs=20 startMonoNs=123 elapsedUs=20 sinceOpenUs=456"
        repeat(10) {for(attempt in 1..16) reporter.record(completion(attempt))}
        assertEquals("already-reserved completions must not disappear behind detail rate limiting",16,lines.count {it.contains("USB network stage=send-end")})
        clock+=2_000_000_000L
        for(attempt in 1..16) reporter.record(completion(attempt))
        assertEquals("completion allowance is per startup owner, not per second",16,lines.count {it.contains("USB network stage=send-end")})
        val full="NCM startup attempt=19 timeoutMs=20 transferred=102 blockBytes=102 ready=1 writeStartMonoNs=123 writeElapsedUs=20 sinceOpenUs=456"
        repeat(100) {reporter.record("media stage=audio-focus-change")}
        repeat(10) {reporter.record(full)}
        assertEquals("first actual full completion survives even after detail cap",1,lines.count {it.contains("NCM startup")})
    }

    @Test fun exactFullStringSchemasRejectPrivateSuffixesUnknownClassesAndOutOfBoundsFields() {
        val lines=listOf(
            "USB network stage=packet direction=in packetBytes=64 next=icmpv6 icmp=ns flags=unknown peerMacKnown=true decision=forward filterDrops=0 macUnknownDrops=0 sinceOpenUs=123",
            "USB network stage=send-start attempt=6 packetBytes=60 next=tcp icmp=unknown flags=18 startMonoNs=123",
            "USB network stage=send-end attempt=6 packetBytes=60 next=tcp icmp=unknown flags=18 result=short transferred=1 blockBytes=102 timeoutMs=20 startMonoNs=123 elapsedUs=20 sinceOpenUs=456")
        lines.forEach {line ->
            assertNotNull(line,CarPlayAudioDiagnostics.sanitize(524,line))
            listOf(" address=fe80::1"," mac=private"," port=1"," sequence=7"," payload=private"," credential=private","\n").forEach {
                assertNull(CarPlayAudioDiagnostics.sanitize(524,line+it))
            }
            assertNull(CarPlayAudioDiagnostics.sanitize(524,line.replace("next=tcp","next=extension").replace("next=icmpv6","next=135")))
        }
        assertNull(CarPlayAudioDiagnostics.sanitize(524,lines[1].replace("attempt=6","attempt=17")))
        assertNull(CarPlayAudioDiagnostics.sanitize(524,lines[1].replace("flags=18","flags=255")))
        assertNull(CarPlayAudioDiagnostics.sanitize(524,lines[0].replace("icmp=ns","icmp=135")))
        assertNull(CarPlayAudioDiagnostics.sanitize(524,lines[0].replace("packetBytes=64","packetBytes=65536")))
    }
}

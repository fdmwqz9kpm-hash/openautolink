package com.openautolink.app.carplay

import android.content.Context
import com.openautolink.app.diagnostics.*
import io.mockk.*
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** Capture the production runtime reporter, then execute the real manual uploader ZIP path. */
internal fun uploadWiredRuntimeReceipts(root: File, exercise: () -> Unit): String {
    val base=File(root,"volume").apply { mkdirs() };val cache=File(root,"cache").apply { mkdirs() }
    val log=File(base,"openautolink/logs/oal_wired.log").apply { requireNotNull(parentFile).mkdirs();writeText("") }
    mockkObject(DiagnosticLog)
    every { DiagnosticLog.i("carplay-audio", any()) } answers { log.appendText(secondArg<String>()+"\n") }
    exercise()
    val contents=log.readText()
    val context=mockk<Context> {
        every { getExternalFilesDirs(null) } returns arrayOf(base)
        every { cacheDir } returns cache
    }
    mockkObject(OalLog)
    every { OalLog.i(any(),any()) } returns Unit
    every { OalLog.w(any(),any()) } returns Unit
    every { OalLog.e(any(),any()) } returns Unit
    val wire=ByteArrayOutputStream()
    val connection=object:HttpURLConnection(URL("https://logs.example.test/upload")) {
        override fun connect()=Unit
        override fun usingProxy()=false
        override fun disconnect()=Unit
        override fun getOutputStream()=wire
        override fun getResponseCode()=200
    }
    val result=LogUploader(context,{connection},{true}).upload("https://logs.example.test/upload","synthetic-test-token","test-car")
    assertTrue(result.toString(),result is UploadResult.Success)
    val members=mutableMapOf<String,String>()
    ZipInputStream(wire.toByteArray().inputStream()).use { zip ->
        while(true) { val entry=zip.nextEntry?:break;members[entry.name]=zip.readBytes().toString(Charsets.UTF_8) }
    }
    assertEquals(contents,members[log.name])
    assertFalse(members.values.any { it.contains("synthetic-test-token") || it.contains("synthetic-same-phone") || it.contains("/dev/bus/usb") })
    return contents
}

package com.openautolink.app.carplay

import org.junit.Assert.*
import org.junit.Test

class CarPlayDrivingRestrictionExportTest {
    @Test fun finiteRestrictionReceiptsSurviveRealReporterAndArchiveWhilePrivateSuffixesFailClosed() {
        val lines=mutableListOf<String>()
        val reporter=CarPlayAudioDiagnosticReporter(44,{1_000_000_000L},lines::add)
        val marker="carplay-restriction reason=actual_park limited=false result=transmitted count=1"
        assertNotNull("restriction write would be invisible in vehicle upload",CarPlayAudioDiagnostics.sanitize(44,marker))
        assertNotNull(CarPlayAudioDiagnostics.sanitize(44,marker.replace("actual_park","user_override")))
        for (bad in listOf("$marker VIN=private", "$marker\nsecret", marker.replace("actual_park","private"),
            marker.replace("count=1","count=-1"),marker.replace("limited=false","limited=0")))
            assertNull(bad,CarPlayAudioDiagnostics.sanitize(44,bad))
        reporter.record(marker)
        assertEquals(listOf("CARPLAY_AUDIO session=44 $marker"),lines)
        val folder=java.nio.file.Files.createTempDirectory(java.nio.file.Path.of(System.getProperty("java.io.tmpdir")),"restriction-export").toFile()
        try {
            val text=lines.joinToString("\n",postfix="\n")
            val log=java.io.File(folder,"oal_restriction.log").apply {writeText(text)}
            val plan=com.openautolink.app.diagnostics.LogUploadArchive.select(listOf(log),log.lastModified())
            com.openautolink.app.diagnostics.LogUploadArchive.create(plan,folder).use { archive ->
                java.util.zip.ZipFile(archive.file).use { zip ->
                    assertEquals(text,zip.getInputStream(zip.getEntry(log.name)).bufferedReader().readText())
                }
            }
        } finally {folder.deleteRecursively()}
    }
}

package com.example

import com.example.data.model.ResolutionQuality
import com.example.data.storage.PdfStorageHelper
import com.example.engine.DriveExtractorEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

  @Test
  fun testSanitizeDriveUrl_fileView() {
    val input = "https://drive.google.com/file/d/1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs74OgvE2upms/view?usp=sharing"
    val result = DriveExtractorEngine.sanitizeDriveUrl(input)
    assertEquals("https://drive.google.com/file/d/1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs74OgvE2upms/preview", result)
  }

  @Test
  fun testSanitizeDriveUrl_openId() {
    val input = "https://drive.google.com/open?id=12345ABCDE"
    val result = DriveExtractorEngine.sanitizeDriveUrl(input)
    assertEquals("https://drive.google.com/file/d/12345ABCDE/preview", result)
  }

  @Test
  fun testUpgradeImageUrlResolution() {
    val lowRes = "https://drive.google.com/viewer/img?id=123&page=1&w=800"
    val upgraded = DriveExtractorEngine.upgradeImageUrlResolution(lowRes, ResolutionQuality.ULTRA)
    assertTrue(upgraded.contains("w=2560"))
  }

  @Test
  fun testGenerateSessionPageUrls_45Pages() {
    val sampleUrl = "https://drive.google.com/viewer/img?id=1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs74OgvE2upms&page=1&w=800"
    val generated = DriveExtractorEngine.generateSessionPageUrls(sampleUrl, 45, ResolutionQuality.ULTRA)
    
    assertEquals(45, generated.size)
    assertEquals(1, generated[0].first)
    assertTrue(generated[0].second.contains("page=1"))
    assertTrue(generated[0].second.contains("w=2560"))
    
    assertEquals(45, generated[44].first)
    assertTrue(generated[44].second.contains("page=45"))
    assertTrue(generated[44].second.contains("w=2560"))
  }

  @Test
  fun testGenerateFileName() {
    val fileName = PdfStorageHelper.generateFileName("Sample Research Paper")
    assertTrue(fileName.startsWith("Drive_Sample_Research_Paper_"))
    assertTrue(fileName.endsWith(".pdf"))
  }
}

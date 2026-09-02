package com.example

import com.example.data.model.ResolutionQuality
import com.example.data.storage.PdfStorageHelper
import com.example.engine.DriveExtractorEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
  fun testExtractFileId() {
    val url = "https://drive.google.com/file/d/1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs74OgvE2upms/view"
    val id = DriveExtractorEngine.extractFileId(url)
    assertEquals("1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs74OgvE2upms", id)
  }

  @Test
  fun testBuildPageUrl_replacesPageAndUpgradesResolution() {
    val sampleUrl = "https://drive.google.com/viewer/img?id=1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs74OgvE2upms&page=1&w=800"
    
    val page5Url = DriveExtractorEngine.buildPageUrl(sampleUrl, 5, ResolutionQuality.ULTRA)
    assertTrue(page5Url.contains("page=5"))
    assertTrue(page5Url.contains("w=2560"))
    
    val page42Url = DriveExtractorEngine.buildPageUrl(sampleUrl, 42, ResolutionQuality.BALANCED)
    assertTrue(page42Url.contains("page=42"))
    assertTrue(page42Url.contains("w=1600"))
  }

  @Test
  fun testExtractPageNumberFromUrl() {
    val url1 = "https://drive.google.com/viewer/img?id=xyz&page=14"
    assertEquals(14, DriveExtractorEngine.extractPageNumberFromUrl(url1))

    val url2 = "https://drive.google.com/viewer/img?id=xyz&pg=3"
    assertEquals(3, DriveExtractorEngine.extractPageNumberFromUrl(url2))
  }

  @Test
  fun testIsDriveViewerImageUrl() {
    val validUrl = "https://drive.google.com/viewer/img?id=xyz&page=1"
    assertTrue(DriveExtractorEngine.isDriveViewerImageUrl(validUrl))
    
    val validViewerUrl = "https://lh3.googleusercontent.com/drive-viewer/AKGpnsc...=w800"
    assertTrue(DriveExtractorEngine.isDriveViewerImageUrl(validViewerUrl))
  }

  @Test
  fun testGenerateFileName() {
    val fileName = PdfStorageHelper.generateFileName("Sample Research Paper")
    assertTrue(fileName.startsWith("Drive_Sample_Research_Paper_"))
    assertTrue(fileName.endsWith(".pdf"))
  }
}

package com.example.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class ImageDownloader(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
) {

    suspend fun downloadPageBitmaps(
        pages: List<Pair<Int, String>>,
        userAgent: String? = null,
        onProgress: (current: Int, total: Int) -> Unit
    ): List<Pair<Int, Bitmap>> = withContext(Dispatchers.IO) {
        val total = pages.size
        var completedCount = 0
        val semaphore = Semaphore(4) // Concurrency limit for memory & bandwidth safety

        coroutineScope {
            val deferredList = pages.map { (pageIndex, url) ->
                async {
                    semaphore.withPermit {
                        val bitmap = downloadSingleBitmap(url, userAgent)
                        synchronized(this@ImageDownloader) {
                            completedCount++
                            onProgress(completedCount, total)
                        }
                        if (bitmap != null) {
                            Pair(pageIndex, bitmap)
                        } else {
                            null
                        }
                    }
                }
            }

            deferredList.awaitAll()
                .filterNotNull()
                .sortedBy { it.first }
        }
    }

    private fun downloadSingleBitmap(url: String, userAgent: String?): Bitmap? {
        return try {
            val cookie = CookieManager.getInstance().getCookie(url)
            val requestBuilder = Request.Builder()
                .url(url)
                .addHeader(
                    "User-Agent",
                    userAgent ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
                )
                .addHeader("Referer", "https://drive.google.com/")
                .addHeader("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")

            if (!cookie.isNullOrBlank()) {
                requestBuilder.addHeader("Cookie", cookie)
            }

            val response = client.newCall(requestBuilder.build()).execute()
            if (!response.isSuccessful) return null

            val bytes = response.body?.bytes() ?: return null
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inPremultiplied = true
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

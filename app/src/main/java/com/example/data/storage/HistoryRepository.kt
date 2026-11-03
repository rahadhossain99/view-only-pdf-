package com.example.data.storage

import android.content.Context
import android.content.SharedPreferences
import com.example.data.model.DownloadHistoryItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class HistoryRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val historyFlow: StateFlow<List<DownloadHistoryItem>> = sharedHistoryFlow.asStateFlow()

    init {
        if (!isLoaded) {
            loadHistory()
            isLoaded = true
        }
    }

    fun loadHistory() {
        val rawJson = prefs.getString(KEY_HISTORY, null)
        if (rawJson.isNullOrBlank()) {
            sharedHistoryFlow.value = emptyList()
            return
        }
        val list = mutableListOf<DownloadHistoryItem>()
        try {
            val jsonArray = JSONArray(rawJson)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val rawTitle = obj.optString("title", "Drive PDF")
                val cleanTitle = PdfStorageHelper.cleanDocumentTitle(rawTitle) ?: rawTitle
                list.add(
                    DownloadHistoryItem(
                        id = obj.getString("id"),
                        title = cleanTitle,
                        uriString = obj.getString("uriString"),
                        pageCount = obj.getInt("pageCount"),
                        fileSizeBytes = obj.getLong("fileSizeBytes"),
                        localPath = obj.optString("localPath", "").takeIf { it.isNotBlank() },
                        timestamp = obj.getLong("timestamp")
                    )
                )
            }
            sharedHistoryFlow.value = list
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun addHistoryItem(item: DownloadHistoryItem) = addItem(item)

    @Synchronized
    fun addItem(item: DownloadHistoryItem) {
        val cleanTitle = PdfStorageHelper.cleanDocumentTitle(item.title) ?: item.title
        val normalizedItem = item.copy(title = cleanTitle)

        val currentList = sharedHistoryFlow.value.toMutableList()
        currentList.removeAll { existing ->
            existing.id == normalizedItem.id ||
                    (normalizedItem.localPath != null && existing.localPath == normalizedItem.localPath) ||
                    existing.uriString == normalizedItem.uriString
        }
        currentList.add(0, normalizedItem)
        val trimmed = currentList.take(100)
        sharedHistoryFlow.value = trimmed
        saveHistory(trimmed)
    }

    @Synchronized
    fun removeItem(id: String) {
        val currentList = sharedHistoryFlow.value.toMutableList()
        currentList.removeAll { it.id == id }
        sharedHistoryFlow.value = currentList
        saveHistory(currentList)
    }

    @Synchronized
    fun clearAll() {
        sharedHistoryFlow.value = emptyList()
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    private fun saveHistory(list: List<DownloadHistoryItem>) {
        val jsonArray = JSONArray()
        for (item in list) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("title", item.title)
                put("uriString", item.uriString)
                put("pageCount", item.pageCount)
                put("fileSizeBytes", item.fileSizeBytes)
                put("localPath", item.localPath ?: "")
                put("timestamp", item.timestamp)
            }
            jsonArray.put(obj)
        }
        prefs.edit().putString(KEY_HISTORY, jsonArray.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "pdf_download_history_prefs"
        private const val KEY_HISTORY = "saved_downloads_history"

        private val sharedHistoryFlow = MutableStateFlow<List<DownloadHistoryItem>>(emptyList())
        @Volatile
        private var isLoaded = false
    }
}

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
        context.getSharedPreferences("pdf_download_history_prefs", Context.MODE_PRIVATE)

    private val _historyFlow = MutableStateFlow<List<DownloadHistoryItem>>(emptyList())
    val historyFlow: StateFlow<List<DownloadHistoryItem>> = _historyFlow.asStateFlow()

    init {
        loadHistory()
    }

    private fun loadHistory() {
        val rawJson = prefs.getString(KEY_HISTORY, null) ?: return
        val list = mutableListOf<DownloadHistoryItem>()
        try {
            val jsonArray = JSONArray(rawJson)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    DownloadHistoryItem(
                        id = obj.getString("id"),
                        title = obj.getString("title"),
                        uriString = obj.getString("uriString"),
                        pageCount = obj.getInt("pageCount"),
                        fileSizeBytes = obj.getLong("fileSizeBytes"),
                        localPath = obj.optString("localPath", null),
                        timestamp = obj.getLong("timestamp")
                    )
                )
            }
            _historyFlow.value = list
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun addHistoryItem(item: DownloadHistoryItem) = addItem(item)

    fun addItem(item: DownloadHistoryItem) {
        val currentList = _historyFlow.value.toMutableList()
        currentList.removeAll { it.id == item.id }
        currentList.add(0, item)
        val trimmed = currentList.take(50)
        _historyFlow.value = trimmed
        saveHistory(trimmed)
    }

    fun removeItem(id: String) {
        val currentList = _historyFlow.value.toMutableList()
        currentList.removeAll { it.id == id }
        _historyFlow.value = currentList
        saveHistory(currentList)
    }

    fun clearAll() {
        _historyFlow.value = emptyList()
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
                put("localPath", item.localPath)
                put("timestamp", item.timestamp)
            }
            jsonArray.put(obj)
        }
        prefs.edit().putString(KEY_HISTORY, jsonArray.toString()).apply()
    }

    companion object {
        private const val KEY_HISTORY = "saved_downloads_history"
    }
}

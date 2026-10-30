package com.example.jptranslator

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await

/** ML Kit 기기 내 번역 (일본어 → 한국어). 결과는 LRU 캐시에 저장. */
class JaKoTranslator {

    private val translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.JAPANESE)
            .setTargetLanguage(TranslateLanguage.KOREAN)
            .build()
    )

    private val cache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 500
    }

    @Volatile private var modelReady = false

    suspend fun ensureModel() {
        if (modelReady) return
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        modelReady = true
    }

    suspend fun translate(text: String): String {
        synchronized(cache) { cache[text] }?.let { return it }
        ensureModel()
        val out = translator.translate(text).await()
        synchronized(cache) { cache[text] = out }
        return out
    }

    fun close() = translator.close()

    companion object {
        /** 일본어·한국어 모델이 모두 기기에 있는지 */
        suspend fun isModelDownloaded(): Boolean {
            val mgr = RemoteModelManager.getInstance()
            val ja = TranslateRemoteModel.Builder(TranslateLanguage.JAPANESE).build()
            val ko = TranslateRemoteModel.Builder(TranslateLanguage.KOREAN).build()
            return mgr.isModelDownloaded(ja).await() && mgr.isModelDownloaded(ko).await()
        }
    }
}

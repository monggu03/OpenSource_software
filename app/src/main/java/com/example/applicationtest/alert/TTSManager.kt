package com.example.applicationtest.alert

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

class TTSManager(context: Context) {

    companion object {
        private const val TAG = "TTSManager"
    }

    private var tts: TextToSpeech? = null
    private var isReady = false
    var initStatus: String = "초기화 중"
        private set

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val korResult = tts?.setLanguage(Locale.KOREAN)
                if (korResult == TextToSpeech.LANG_MISSING_DATA
                    || korResult == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    Log.w(TAG, "한국어 TTS 불가, 영어로 시도")
                    val engResult = tts?.setLanguage(Locale.US)
                    if (engResult == TextToSpeech.LANG_MISSING_DATA
                        || engResult == TextToSpeech.LANG_NOT_SUPPORTED
                    ) {
                        Log.e(TAG, "영어 TTS도 지원하지 않습니다")
                        initStatus = "TTS 언어 없음"
                    } else {
                        isReady = true
                        initStatus = "TTS 준비됨 (영어)"
                    }
                } else {
                    isReady = true
                    initStatus = "TTS 준비됨 (한국어)"
                }
                if (isReady) {
                    tts?.setSpeechRate(1.2f)
                    tts?.setPitch(1.0f)
                    Log.d(TAG, initStatus)
                }
            } else {
                Log.e(TAG, "TTS 엔진 초기화 실패")
                initStatus = "TTS 엔진 실패"
            }
        }
    }

    fun speak(message: String) {
        if (!isReady || message.isBlank()) {
            Log.w(TAG, "speak 무시됨: isReady=$isReady, message='$message'")
            return
        }

        Log.d(TAG, "TTS 발화: $message")
        tts?.speak(
            message,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "safewalk_${System.currentTimeMillis()}"
        )
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }
}
package edu.northwestern.langlearn

import android.util.Log

import org.json.JSONObject

import org.jetbrains.anko.doAsync
import org.jetbrains.anko.uiThread

import java.net.URL

class WordsProvider(val jsonUrl: String) {
    var jsonStartDelay: Long = SleepMode.DEFAULT_START_WORDS_DELAY_MILLIS
        private set
    var jsonWordDelay: Long = SleepMode.DEFAULT_BETWEEN_WORDS_DELAY_MILLIS
        private set
    var jsonSham: Boolean = SleepMode.PLAY_ONLY_WHITE_NOISE_SHAM
        private set
    var jsonError: String = SleepMode.JSON_ERROR_MESSAGE
        private set

    private val TAG = javaClass.simpleName

    fun fetchJSONWords(updateImpl: WordsProviderUpdate): Unit {
        doAsync {
            URL(jsonUrl).readText { text, error ->
                if (text.isNotEmpty()) {
                    Log.d(javaClass.simpleName, text.length.toString())
                    uiThread { updateImpl.updateJSONWords(text) }
                } else {
                    uiThread { updateImpl.openMessageActivity(error ?: "The exception message was null") }
                }
            }
        }
    }

    fun parseJSONWords(wordsJSON: String?): List<Word> {
        val json = wordsJSON ?: """
        {
            "words": [
                {
                    "norm": "velvet",
                    "audio_url": "http://someplace.cool",
                    "word": "velvet"
                }
            ]
        }
        """
        val Words = mutableListOfWords()
        val jsonObj = JSONObject(json.substring(json.indexOf("{"), json.lastIndexOf("}") + 1))

        jsonObj.unless {
            jsonStartDelay = getLong("start_delay") * 1000
            jsonWordDelay = getLong("word_delay") * 1000
        }
        jsonObj.unless { jsonSham = getBoolean("sham") }
        jsonObj.unless { jsonError = getString("error") }
        jsonObj.unless {
            val words = getJSONArray("words")
            var n: String = ""
            var url: String = ""
            var w: String = ""

            for (i in 0..words.length() - 1) {
                words.getJSONObject(i).unless {
                    n = getString("norm")
                    url = getString("audio_url")
                    w = getString("word")
                }

                val word = Word(n, url, w)

                Log.d(TAG, "$i $word")
                Words.add(word)
            }
        }

        return Words
    }
}

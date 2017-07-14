package edu.northwestern.langlearn;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;
import android.support.annotation.NonNull;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;

import android.media.MediaPlayer;
import android.media.AudioManager;
import android.media.MediaPlayer.OnCompletionListener;

import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

import org.jetbrains.anko.ToastsKt;

public class SleepMode extends AppCompatActivity implements WordsProviderUpdate, OnCompletionListener {
    public static final long DEFAULT_START_WORDS_DELAY_MILLIS = 1800000; // 30m
    public static final long DEFAULT_BETWEEN_WORDS_DELAY_MILLIS = 5000; // 5s
    public static final boolean PLAY_ONLY_WHITE_NOISE_SHAM = false;
    public static final String JSON_ERROR_MESSAGE = "";
    public static final boolean PLAY_WHITE_NOISE = true;
    public static final String INACTIVITY_OPTION_PREF_DEFAULT = "1";
    public static final String MESSAGE_INTENT_EXTRA = "message";

    private static final String TAG = "SleepMode";
    private static final int BASE_STILL_ACCEPTANCE_CONFIDENCE = 60;

    private PowerManager.WakeLock wl;
    private WordsProvider wordsProvider;
    private MediaPlayer mediaPlayer;
    private MediaPlayer whiteNoisePlayer;
    private String jsonWords;
    private List<Word> words;
    private Handler playWordsIfStillHandler = new Handler();
    private Handler pauseBetweenWordsHandler = new Handler();
    private long delayMillis = DEFAULT_START_WORDS_DELAY_MILLIS;
    private long delayBetweenWords = DEFAULT_BETWEEN_WORDS_DELAY_MILLIS;
    private String jsonErrorMessage = JSON_ERROR_MESSAGE;
    private float rightAndLeftWhiteNoiseVolume = 0.1f;
    private int wordsIndex = 0;
    private HashMap<String, Integer> lastActivity;
    @Nullable
    private BroadcastReceiver receiver;
    private Runnable checkPlayWordsIfStillRunner = new Runnable() {
        @Override
        public void run() {
            checkAndPlayWordsIfStill();
        }
    };

    public void updateJSONWords(@NonNull String json) {
        Log.d(TAG, "updateJSONWords");
        jsonWords = json;
        words = wordsProvider.parseJSONWords(jsonWords);

        if (wordsProvider.getJsonStartDelay() != DEFAULT_START_WORDS_DELAY_MILLIS) {
            delayMillis = wordsProvider.getJsonStartDelay();
            Log.d(TAG, "delayMillis: " + delayMillis);
        }

        if (wordsProvider.getJsonWordDelay() != DEFAULT_BETWEEN_WORDS_DELAY_MILLIS) {
            delayBetweenWords = wordsProvider.getJsonWordDelay();
            Log.d(TAG, "delayBetweenWords: " + delayBetweenWords);
        }

        ToastsKt.longToast(SleepMode.this, "Words Updated");
        Log.d(TAG, "words.size: " + words.size());

        if (!wordsProvider.getJsonError().isEmpty()) {
            openMessageActivity(wordsProvider.getJsonError());
            return;
        }

        if (!wordsProvider.getJsonSham()) {
            playWordsIfStillHandler.postDelayed(checkPlayWordsIfStillRunner, delayMillis);
        } else {
            Log.i(TAG, "Playing only white noise, sham was true");
        }

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);

        wl = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "LangLearnSleepLock");
        wl.acquire();
    }

    @NonNull
    @Override
    public AppCompatActivity getWordsProviderUpdateActivity() {
        return this;
    }

    public void openMessageActivity(@NonNull String messsage) {
        WordsProviderUpdate.DefaultImpls.openMessageActivity(this, messsage);
    }

    public void onCompletion(MediaPlayer mp) {
        Log.d(TAG, "onCompletion");
        destroyWordsPlayer();

        SharedPreferences sP = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US);
        String dateToStr = format.format(new Date());

        sP.edit().putString(MainActivity.LAST_PRACTICE_TIME_PREF, dateToStr).apply();
        Log.d(TAG, MainActivity.LAST_PRACTICE_TIME_PREF + ": " + dateToStr);

        writeFileLog(dateToStr + "," + words.get(wordsIndex).getWord() + "," + words.get(wordsIndex).getAudio_url(), true);
        wordsIndex++;
        pauseBetweenWordsHandler.postDelayed(checkPlayWordsIfStillRunner, delayBetweenWords);
    }

    @Override
    protected void onStart() {
        Log.d(TAG, "onStart");
        super.onStart();
        LocalBroadcastManager.getInstance(this).registerReceiver(receiver, new IntentFilter(ActivityRecognizedIntentServices.ACTIVITY_NOTIFICATION));
    }

    @Override
    protected void onStop() {
        Log.d(TAG, "onStop");
        LocalBroadcastManager.getInstance(this).unregisterReceiver(receiver);

        if (wl != null) {
            wl.release();
        }

        super.onStop();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "onCreate");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sleep_mode);
        createReceiver();
        writeCSVHeader();

        SharedPreferences sP = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
        String user = sP.getString(MainActivity.USER_PREF, "NA");
        String delayListValue = sP.getString(MainActivity.INACTIVITY_DELAY_PREF, INACTIVITY_OPTION_PREF_DEFAULT);
        String whiteNoiseVolume = sP.getString(MainActivity.VOLUME_WHITE_NOISE_PREF, MainActivity.WHITE_NOISE_VOLUME_PREF_DEFAULT);
        boolean playWhiteNoise = sP.getBoolean(MainActivity.PLAY_WHITE_NOISE_PREF, PLAY_WHITE_NOISE);
        String lastPracticeTime = sP.getString(MainActivity.LAST_PRACTICE_TIME_PREF, MainActivity.NA_PREF);

        setDelayMillisFromPrefs(delayListValue);
        setWhiteNoiseVolumeFromPrefs(whiteNoiseVolume);

        if (playWhiteNoise) {
            playWhiteNoiseRaw();
        }

        if (lastPracticeTime.equalsIgnoreCase("NA")) {
            wordsProvider = new WordsProvider("https://cortical.csl.sri.com/langlearn/user/" + user);
        } else {
            wordsProvider = new WordsProvider("https://cortical.csl.sri.com/langlearn/user/" + user + "/since/" + lastPracticeTime); //.replace(" ", "'T'"));
        }

        wordsProvider.fetchJSONWords(this);
    }

    @Override
    protected void onDestroy() {
        Log.d(TAG, "onDestroy");

        if (playWordsIfStillHandler != null) {
            playWordsIfStillHandler.removeCallbacks(checkPlayWordsIfStillRunner);
            playWordsIfStillHandler = null;
        }

        if (pauseBetweenWordsHandler != null) {
            pauseBetweenWordsHandler.removeCallbacks(checkPlayWordsIfStillRunner);
            pauseBetweenWordsHandler = null;
        }

        if (mediaPlayer != null) {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.stop();
            }

            destroyWordsPlayer();
        }

        if (whiteNoisePlayer != null) {
            whiteNoisePlayer.stop();
            whiteNoisePlayer.release();
            whiteNoisePlayer = null;
        }

        super.onDestroy();
        // finish();
    }

    private void checkAndPlayWordsIfStill() {
        Log.d(TAG, "checkAndPlayWordsIfStill");

        // lastActivity == null means no activity recognized made it to this activity, so it most likely is Still: 100 per Google docs
        if (lastActivity == null || (lastActivity.containsKey(ActivityRecognizedIntentServices.STILL) &&
                lastActivity.get(ActivityRecognizedIntentServices.STILL) > BASE_STILL_ACCEPTANCE_CONFIDENCE)) {
            if (wordsIndex >= words.size()) {
                Log.d(TAG, "Repeating the words list, reached the end");
                wordsIndex = 0;
            }

            // ToastsKt.longToast(SleepMode.this, "Playing " + words.get(wordsIndex).getWord());
            playAudioUrl();
        } else {
            playWordsIfStillHandler.postDelayed(checkPlayWordsIfStillRunner, delayMillis);
        }
    }

    private void playAudioUrl() {
        Log.d(TAG, "playAudioUrl");

        try {
            String url = words.get(wordsIndex).getAudio_url();

            Log.d(TAG, words.get(wordsIndex).getAudio_url());
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mediaPlayer.setDataSource(url);
            mediaPlayer.prepare();
            mediaPlayer.start();
            mediaPlayer.setOnCompletionListener(this);
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    private void playWhiteNoiseRaw() {
        Log.d(TAG, "playWhiteNoiseRaw");
        whiteNoisePlayer = MediaPlayer.create(SleepMode.this, R.raw.bnoise3);
        whiteNoisePlayer.setVolume(rightAndLeftWhiteNoiseVolume, rightAndLeftWhiteNoiseVolume);
        whiteNoisePlayer.seekTo(45000);
        whiteNoisePlayer.setLooping(true);
        whiteNoisePlayer.start();
    }

    private void destroyWordsPlayer() {
        Log.d(TAG, "destroyWordsPlayer");
        mediaPlayer.release();
        mediaPlayer = null;
    }

    private void loadWordsJsonRes() {
        try {
            InputStream is = getResources().openRawResource(R.raw.corticalre);
            int size = is.available();
            byte[ ] buffer = new byte[ size ];
            int num  = is.read(buffer);

            is.close();
            jsonWords = new String(buffer, "UTF-8");
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    private void playMP3Raw() {
         mediaPlayer = MediaPlayer.create(SleepMode.this, R.raw.kvinnan);
         mediaPlayer.start();
    }

    private void writeFileLog(String toLog, boolean append) {
        Log.d(TAG, "writeFileLog");

        try {
            OutputStreamWriter outputStreamWriter;

            if (append) {
                outputStreamWriter = new OutputStreamWriter(getBaseContext().openFileOutput("log-sleep.txt", Context.MODE_APPEND));
                outputStreamWriter.append(toLog);
            } else {
                outputStreamWriter = new OutputStreamWriter(getBaseContext().openFileOutput("log-sleep.txt", Context.MODE_PRIVATE));
                outputStreamWriter.write(toLog);
            }

            outputStreamWriter.close();
        } catch (IOException e) {
            Log.e("Exception", "File write failed: " + e.toString());
        }
    }

    private void writeCSVHeader() {
        writeFileLog("timestamp,word,audio_url\n", false);
    }

    @SuppressWarnings("unchecked")
    private void createReceiver() {
        receiver = new BroadcastReceiver() {

            @Override
            public void onReceive(Context context, Intent intent) {
                Log.d(TAG, "onReceive");

                Object extra = intent.getSerializableExtra(ActivityRecognizedIntentServices.ACTIVITY);

                if (extra instanceof HashMap) {
                    lastActivity = (HashMap<String, Integer>)intent.getSerializableExtra(ActivityRecognizedIntentServices.ACTIVITY);
                    Log.d(TAG, "Activity: " + lastActivity.toString());
                }
            }
        };
    }

    private void setDelayMillisFromPrefs(String delayListValue) {
        long minutes;

        switch (delayListValue) {
            case "2":
                minutes = 45;
                break;
            case "3":
                minutes = 15;
                break;
            case "4":
                minutes = 5;
                break;
            default: // "1"
                minutes = 30;
                // minutes = 1; // local testing
        }

        delayMillis = minutes * 60 * 1000;
        Log.d(TAG, "delayMillis: " + delayMillis);
    }

    private void setWhiteNoiseVolumeFromPrefs(@NonNull String volume) {
        try {
            rightAndLeftWhiteNoiseVolume = Float.parseFloat(volume);
            Log.d(TAG, "rightAndLeftWhiteNoiseVolume: " + rightAndLeftWhiteNoiseVolume);
        } catch (NumberFormatException ex) {
            Log.w(TAG, ex.getMessage());
        }
    }
}

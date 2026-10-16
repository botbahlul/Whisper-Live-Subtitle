package whisper.app.livesubtitle;

import static com.k2fsa.sherpa.onnx.VadKt.getVadModelConfig;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.FeatureConfigKt;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizerKt;
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.SpeechSegment;
import com.k2fsa.sherpa.onnx.Vad;
import com.k2fsa.sherpa.onnx.VadModelConfig;

import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import android.media.AudioAttributes;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.content.Context;

public class AudioCaptureService extends Service {
    private static final String TAG = "SherpaWhisperCapture";
    private AudioRecord audioRecord;
    private MediaProjection mediaProjection;
    private MediaProjection.Callback mediaProjectionCallback;
    private volatile boolean capturePrepared = false;
    private volatile boolean startPending = false;
    private Thread recordingThread;
    private static final int sampleRate = 16000;
    //private static final int PARTIAL_MAX_SAMPLES = 4 * sampleRate;
    //private static final long PARTIAL_INTERVAL_MS = 500;
    //private volatile boolean partialRecognitionRunning = false;
    //private final Object partialAudioLock = new Object();
    //private final ArrayList<Float> partialAudioBuffer = new ArrayList<>();
    //private long lastPartialRecognitionTime = 0;
    private ExecutorService initializationExecutor;
    private ExecutorService recognitionExecutor;
    private ExecutorService translationExecutor;
    private volatile boolean serviceStopping = false;
    private OfflineRecognizer offlineRecognizer;
    private Timer timer;
    private TimerTask timerTask;
    private final int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
    private final int channelConfig = AudioFormat.CHANNEL_IN_MONO;
    //private final int audioSource = MediaRecorder.AudioSource.MIC;
    private Vad vad;
    private String lastVoiceString = "";
    private String translatedLastVoiceString = "";

    public static final String EXTRA_RESULT_CODE = "whisper.app.livesubtitle.MEDIA_PROJECTION_RESULT_CODE";
    public static final String EXTRA_RESULT_DATA = "whisper.app.livesubtitle.MEDIA_PROJECTION_RESULT_DATA";
    public static final String ACTION_PREPARE_CAPTURE = "whisper.app.livesubtitle.ACTION_PREPARE_CAPTURE";
    public static final String ACTION_START_CAPTURE = "whisper.app.livesubtitle.ACTION_START_CAPTURE";
    public static final String ACTION_STOP_CAPTURE = "whisper.app.livesubtitle.ACTION_STOP_CAPTURE";
    public static final String ACTION_LANGUAGE_CHANGED = "whisper.app.livesubtitle.ACTION_LANGUAGE_CHANGED";
    private static final String CAPTURE_CHANNEL_ID = "audio_playback_capture";

    @Override
    public void onCreate() {
        super.onCreate();

        translationExecutor = Executors.newSingleThreadExecutor();

        int h;
        if (Objects.equals(LANGUAGE.SRC, "ja") || Objects.equals(LANGUAGE.SRC, "zh-Hans") || Objects.equals(LANGUAGE.SRC, "zh-Hant")) {
            h = 122;
        } else {
            h = 109;
        }
        MainActivity.voice_text.setHeight((int) (h * getResources().getDisplayMetrics().density));
        MainActivity.full_voice_text.setHeight((int) (h * getResources().getDisplayMetrics().density));
        MainActivity.full_voice_text.setGravity(Gravity.BOTTOM);

        String src_dialect = LANGUAGE.SRC_DIALECT;
        RECOGNIZING_STATUS.STRING = "RECOGNIZING_STATUS.IS_RECOGNIZING = " + RECOGNIZING_STATUS.IS_RECOGNIZING;
        MainActivity.textview_recognizing.setText(RECOGNIZING_STATUS.STRING);
        MIC_BUTTON_STATUS.STRING = "MIC_BUTTON_STATUS.IS_OVERLAYING = " + MIC_BUTTON_STATUS.IS_OVERLAYING;
        MainActivity.textview_overlaying.setText(MIC_BUTTON_STATUS.STRING);
        // =========================================================
        // ENDPOINT TEST
        // =========================================================
        GOOGLE_TRANSLATE_ENDPOINT.CONFIG = 0;
        testGoogleTranslateEndpoints();

        serviceStopping = false;
        initializationExecutor = Executors.newSingleThreadExecutor();
        initializationExecutor.execute(() -> {
            if (serviceStopping) return;
            Log.i(TAG, "Start to initialize model");
            initVadModel();
            Log.i(TAG, "Finished initializing model");
            if (serviceStopping) return;
            Log.i(TAG, "Start to initialize non-streaming recognizer");

            initOfflineRecognizer();

            Log.i(TAG, "Finished initializing non-streaming recognizer");
            if (serviceStopping) return;
            Log.i(TAG, "VAD and Whisper initialization completed");
        });

    }

    @Override
    public int onStartCommand(
            Intent intent,
            int flags,
            int startId
    ) {

        if (intent == null) {
            return START_NOT_STICKY;
        }

        String action = intent.getAction();

        if (ACTION_PREPARE_CAPTURE.equals(action)) {

            preparePlaybackCapture(intent);

        } else if (ACTION_START_CAPTURE.equals(action)) {

            startAudioCapture();

        } else if (ACTION_STOP_CAPTURE.equals(action)) {

            stopAudioCapture();

        } else if (ACTION_LANGUAGE_CHANGED.equals(action)) {

            restartRecognitionForLanguageChange();
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "onDestroy");

        serviceStopping = true;
        RECOGNIZING_STATUS.IS_RECOGNIZING = false;

        // =========================================================
        // STOP AUDIO RECORD
        // =========================================================
        if (audioRecord != null) {
            try {
                if (audioRecord.getRecordingState()
                        == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
            } catch (IllegalStateException e) {
                Log.w(TAG, "AudioRecord.stop() failed", e);
            }

            audioRecord.release();
            audioRecord = null;
        }

        // =========================================================
        // STOP RECORDING THREAD
        // =========================================================
        if (recordingThread != null) {
            recordingThread.interrupt();

            try {
                recordingThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            recordingThread = null;
        }

        // =========================================================
        // STOP WHISPER EXECUTOR
        // =========================================================
        if (recognitionExecutor != null) {
            recognitionExecutor.shutdown();

            try {
                if (!recognitionExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    Log.w(TAG,"Recognition executor did not finish in time");
                    recognitionExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                recognitionExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            recognitionExecutor = null;
        }

        // =========================================================
        // STOP INITIALIZATION EXECUTOR
        // =========================================================
        if (initializationExecutor != null) {
            initializationExecutor.shutdownNow();
            initializationExecutor = null;
        }

        // =========================================================
        // STOP TRANSLATION EXECUTOR
        // =========================================================
        if (translationExecutor != null) {
            translationExecutor.shutdownNow();
            translationExecutor = null;
        }

        // =========================================================
        // RELEASE VAD
        // =========================================================
        if (vad != null) {
            vad.release();
            vad = null;
        }

        // =========================================================
        // RELEASE WHISPER
        // =========================================================
        if (offlineRecognizer != null) {
            offlineRecognizer.release();
            offlineRecognizer = null;
        }

        if (mediaProjection != null) {
            try {
                if (mediaProjectionCallback != null) {
                    mediaProjection.unregisterCallback(
                            mediaProjectionCallback
                    );
                }
            } catch (Exception ignored) {
            }

            try {
                mediaProjection.stop();
            } catch (Exception ignored) {
            }

            mediaProjection = null;
            mediaProjectionCallback = null;
        }

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    public void setText(final TextView tv, final String text){
        new Handler(Looper.getMainLooper()).post(() -> tv.setText(text));
    }

    private void initOfflineRecognizer() {
        // Please change getOfflineModelConfig() to add new models
        // See https://k2-fsa.github.io/sherpa/onnx/pretrained_models/index.html
        // for a list of available models

        int asrModelType = 100;
        String asrRuleFsts = null;
        Log.i(TAG, "Select model type " + asrModelType + " for ASR");

        FeatureConfig featConfig = FeatureConfigKt.getFeatureConfig(sampleRate, 80);

        OfflineModelConfig modelConfig =
                OfflineRecognizerKt.getOfflineModelConfig(
                        asrModelType,
                        LANGUAGE.SRC
                );

        OfflineRecognizerConfig config = new OfflineRecognizerConfig();
        config.setFeatConfig(featConfig);
        if (modelConfig != null) {
            config.setModelConfig(modelConfig);
        }

        if (asrRuleFsts != null) {
            config.setRuleFsts(asrRuleFsts);
        }

        offlineRecognizer = new OfflineRecognizer(getAssets(),config);
    }

    private void initVadModel() {
        int type = 0;
        Log.i(TAG, "Select VAD model type " + type);
        VadModelConfig config = getVadModelConfig(type);
        if (config != null) {
            this.vad = new Vad(getAssets(), config);
        }
    }

    private void testGoogleTranslateEndpoints() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            boolean endpoint1OK = false;
            boolean endpoint2OK = false;
            HttpClient httpClient = null;
            // =========================================================
            // TEST ENDPOINT 1
            // https://translate.googleapis.com/translate_a/single
            // =========================================================
            try {
                String testSentence = URLEncoder.encode("Hello", "UTF-8");
                String url =
                        "https://translate.googleapis.com/translate_a/" +
                                "single?client=gtx" +
                                "&sl=en" +
                                "&tl=id" +
                                "&dt=t" +
                                "&q=" + testSentence;

                Log.d("GoogleTranslator", "Testing endpoint 1: " + url);

                httpClient = new DefaultHttpClient();
                HttpGet httpget = new HttpGet(url);
                HttpResponse response = httpClient.execute(httpget);
                StatusLine statusLine = response.getStatusLine();
                Log.d("GoogleTranslator", "Endpoint 1 HTTP: " + statusLine.getStatusCode());

                if (statusLine.getStatusCode() == 200) {
                    if (response.getEntity() != null) {
                        ByteArrayOutputStream output = new ByteArrayOutputStream();
                        response.getEntity().writeTo(output);
                        String responseString = output.toString("UTF-8");
                        output.close();
                        Log.d("GoogleTranslator", "Endpoint 1 response: " + responseString);
                        // Pastikan response memang JSON array
                        JSONArray jsonArray = new JSONArray(responseString);
                        if (jsonArray.length() > 0) {
                            endpoint1OK = true;
                        }
                    }
                }
            } catch (Exception e) {
                Log.e("GoogleTranslator", "Endpoint 1 FAILED", e);
            } finally {
                if (httpClient != null) {
                    httpClient.getConnectionManager().shutdown();
                }
            }

            // =========================================================
            // If endpoint 1 success,choose endpoint 1
            // =========================================================
            if (endpoint1OK) {
                GOOGLE_TRANSLATE_ENDPOINT.CONFIG = 1;
                Log.d("GoogleTranslator", "SELECTED ENDPOINT = 1");
                executor.shutdown();
                return;
            }

            // =========================================================
            // TEST ENDPOINT 2
            // https://clients5.google.com/translate_a/t
            // =========================================================
            try {

                String testSentence = URLEncoder.encode("Hello", "UTF-8");

                String url =
                        "https://clients5.google.com/translate_a/t" +
                                "?client=dict-chrome-ex" +
                                "&sl=en" +
                                "&tl=id" +
                                "&q=" + testSentence;

                Log.d("GoogleTranslator","Testing endpoint 2: " + url);

                httpClient = new DefaultHttpClient();
                HttpGet httpget = new HttpGet(url);
                httpget.setHeader(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                "Chrome/139.0.0.0 Safari/537.36"
                );

                HttpResponse response = httpClient.execute(httpget);
                StatusLine statusLine = response.getStatusLine();
                Log.d("GoogleTranslator", "Endpoint 2 HTTP: " + statusLine.getStatusCode());
                if (statusLine.getStatusCode() == 200) {
                    if (response.getEntity() != null) {
                        ByteArrayOutputStream output = new ByteArrayOutputStream();
                        response.getEntity().writeTo(output);
                        String responseString = output.toString("UTF-8");
                        output.close();
                        Log.d("GoogleTranslator", "Endpoint 2 response: " + responseString);
                        JSONArray jsonArray = new JSONArray(responseString);
                        if (jsonArray.length() > 0) {
                            endpoint2OK = true;
                        }
                    }
                }
            } catch (Exception e) {
                Log.e("GoogleTranslator", "Endpoint 2 FAILED", e);
            } finally {
                if (httpClient != null) {
                    httpClient.getConnectionManager().shutdown();
                }
            }

            // =========================================================
            // CHOOSE FINAL ENDPOINT
            // =========================================================
            if (endpoint2OK) {
                GOOGLE_TRANSLATE_ENDPOINT.CONFIG = 2;
                Log.d("GoogleTranslator", "SELECTED ENDPOINT = 2");
            } else {
                GOOGLE_TRANSLATE_ENDPOINT.CONFIG = -1;
                Log.e("GoogleTranslator", "BOTH GOOGLE TRANSLATE ENDPOINTS FAILED");
            }
            executor.shutdown();
        });
    }

    private String GoogleTranslate2(String SENTENCE, String SRC, String DST) {
        if (SENTENCE == null || SENTENCE.length() == 0) {
            return "";
        }
        if (!RECOGNIZING_STATUS.IS_RECOGNIZING) {
            return "";
        }
        if (translationExecutor == null || translationExecutor.isShutdown()) {
            Log.e("GoogleTranslator", "translationExecutor is not available");
            return "";
        }

        final Handler handler = new Handler(Looper.getMainLooper());
        final String finalSENTENCE;

        try {
            finalSENTENCE = URLEncoder.encode(SENTENCE, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            Log.e("GoogleTranslate1", "URL encoding failed", e);
            return "";
        }

        Future<String> future = translationExecutor.submit(() -> {
            StringBuilder translation = new StringBuilder();
            HttpClient httpClient = null;
            try {
                String url = "https://translate.googleapis.com/translate_a/";
                String params =
                        "single?client=gtx"
                                + "&sl=" + SRC
                                + "&tl=" + DST
                                + "&dt=t"
                                + "&q=" + finalSENTENCE;

                httpClient = new DefaultHttpClient();
                HttpGet httpGet = new HttpGet(url + params);
                HttpResponse response = httpClient.execute(httpGet);
                StatusLine statusLine = response.getStatusLine();
                if (statusLine.getStatusCode() == 200) {
                    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                    try {
                        response.getEntity().writeTo(byteArrayOutputStream);
                        String responseString = byteArrayOutputStream.toString("UTF-8");
                        Log.d("GoogleTranslator", "GoogleTranslate1 Response: " + responseString);
                        JSONArray jsonArray = new JSONArray(responseString);
                        JSONArray sentences = jsonArray.getJSONArray(0);
                        int length = sentences.length();
                        for (int i = 0; i < length; i++) {
                            JSONArray item = sentences.getJSONArray(i);
                            if (!item.isNull(0)) {
                                translation.append(item.getString(0));
                            }
                        }
                    } finally {
                        byteArrayOutputStream.close();
                    }

                } else {
                    Log.e("GoogleTranslator",
                            "HTTP "
                                    + statusLine.getStatusCode()
                                    + ": "
                                    + statusLine.getReasonPhrase()
                    );

                    if (response.getEntity() != null) {
                        response.getEntity()
                                .getContent()
                                .close();
                    }

                    throw new IOException(
                            "HTTP "
                                    + statusLine.getStatusCode()
                                    + ": "
                                    + statusLine.getReasonPhrase()
                    );
                }

            } catch (Exception e) {
                Log.e("GoogleTranslate1", "Translation error", e);

            } finally {
                if (httpClient != null) {
                    httpClient.getConnectionManager().shutdown();
                }
            }
            return translation.toString();
        });

        try {
            String result = future.get();
            if (result == null) {
                result = "";
            }
            final String translatedText = result;
            Log.d("GoogleTranslator", "GoogleTranslate1 result = " + translatedText );

             // Update overlay on Android main thread.
            handler.post(() -> {
                if (!RECOGNIZING_STATUS.IS_RECOGNIZING) {
                    create_overlay_translation_text.overlay_translation_text.setVisibility(View.INVISIBLE);
                    create_overlay_translation_text.overlay_translation_text_container.setVisibility(View.INVISIBLE);
                    return;
                }

                TRANSLATION_TEXT.STRING = translatedText;

                if (TRANSLATION_TEXT.STRING.length() == 0) {
                    create_overlay_translation_text.overlay_translation_text.setVisibility(View.INVISIBLE);
                    create_overlay_translation_text.overlay_translation_text_container.setVisibility(View.INVISIBLE);
                } else {
                    create_overlay_translation_text.overlay_translation_text_container.setVisibility(View.VISIBLE);
                    create_overlay_translation_text.overlay_translation_text_container.setBackgroundColor(Color.TRANSPARENT);
                    create_overlay_translation_text.overlay_translation_text.setVisibility(View.VISIBLE);
                    create_overlay_translation_text.overlay_translation_text.setBackgroundColor(Color.TRANSPARENT);
                    create_overlay_translation_text.overlay_translation_text.setTextIsSelectable(true);
                    create_overlay_translation_text.overlay_translation_text.setText(TRANSLATION_TEXT.STRING);
                    create_overlay_translation_text.overlay_translation_text.setSelection(create_overlay_translation_text.overlay_translation_text.getText().length());
                }
            });
            return result;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.e("GoogleTranslate1", "Translation interrupted", e);
            return "";

        } catch (ExecutionException e) {
            Log.e("GoogleTranslate1", "Translation execution failed", e);
            return "";
        }
    }

    private String GoogleTranslate1(String SENTENCE, String SRC, String DST) {

        Future<String> future = translationExecutor.submit(() -> {
            HttpClient httpClient = null;
            try {
                String encodedSentence;
                try {
                    encodedSentence = URLEncoder.encode(SENTENCE, "UTF-8");
                } catch (UnsupportedEncodingException e) {
                    Log.e("GoogleTranslator", "UTF-8 encoding failed", e);
                    return "";
                }

                String url = "https://clients5.google.com/translate_a/t";
                String params =
                        "?client=dict-chrome-ex"
                                + "&sl=" + SRC
                                + "&tl=" + DST
                                + "&q=" + encodedSentence;
                httpClient = new DefaultHttpClient();
                HttpGet httpget = new HttpGet(url + params);
                httpget.setHeader(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                                + "AppleWebKit/537.36 (KHTML, like Gecko) "
                                + "Chrome/139.0.0.0 Safari/537.36"
                );
                HttpResponse response = httpClient.execute(httpget);
                StatusLine statusLine = response.getStatusLine();
                int statusCode = statusLine.getStatusCode();
                if (statusCode != 200) {
                    Log.e("GoogleTranslator",
                            "HTTP "
                                    + statusCode
                                    + ": "
                                    + statusLine.getReasonPhrase()
                    );
                    return "";
                }

                if (response.getEntity() == null) {
                    Log.e("GoogleTranslator", "HTTP response entity is null");
                    return "";
                }

                ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();

                try {
                    response.getEntity().writeTo(byteArrayOutputStream);
                    String responseString = byteArrayOutputStream.toString("UTF-8");
                    Log.d("GoogleTranslator", "Response: " + responseString);
                    JSONArray jsonArray = new JSONArray(responseString);
                    StringBuilder translation = new StringBuilder();
                    for (int i = 0; i < jsonArray.length(); i++) {
                        if (!jsonArray.isNull(i)) {
                            translation.append(jsonArray.getString(i));
                        }
                    }
                    String translatedText = translation.toString();
                    Log.d("GoogleTranslator", "TRANSLATION: " + translatedText);
                    return translatedText;
                } finally {
                    byteArrayOutputStream.close();
                }

            } catch (Exception e) {
                Log.e("GoogleTranslator", "Translation error", e);
                return "";

            } finally {
                if (httpClient != null) {
                    httpClient.getConnectionManager().shutdown();}
            }
        });

        try {
            String translatedText = future.get();
             // future.get() has produced String from thread translationExecutor.
             // Now UI update must be done via Main/UI thread
            Handler handler = new Handler(Looper.getMainLooper());
            handler.post(() -> {
                TRANSLATION_TEXT.STRING = translatedText;
                Log.d("GoogleTranslator", "TRANSLATION_TEXT.STRING: " + TRANSLATION_TEXT.STRING);
                if (RECOGNIZING_STATUS.IS_RECOGNIZING) {
                    if (TRANSLATION_TEXT.STRING.length() == 0) {
                        create_overlay_translation_text
                                .overlay_translation_text
                                .setVisibility(
                                        View.INVISIBLE
                                );
                        create_overlay_translation_text
                                .overlay_translation_text_container
                                .setVisibility(
                                        View.INVISIBLE
                                );
                    } else {
                        create_overlay_translation_text
                                .overlay_translation_text_container
                                .setVisibility(
                                        View.VISIBLE
                                );

                        create_overlay_translation_text
                                .overlay_translation_text_container
                                .setBackgroundColor(
                                        Color.TRANSPARENT
                                );

                        create_overlay_translation_text
                                .overlay_translation_text
                                .setVisibility(
                                        View.VISIBLE
                                );

                        create_overlay_translation_text
                                .overlay_translation_text
                                .setBackgroundColor(
                                        Color.TRANSPARENT
                                );

                        create_overlay_translation_text
                                .overlay_translation_text
                                .setTextIsSelectable(
                                        true
                                );

                        create_overlay_translation_text
                                .overlay_translation_text
                                .setText(
                                        TRANSLATION_TEXT.STRING
                                );

                        create_overlay_translation_text
                                .overlay_translation_text
                                .setSelection(
                                        create_overlay_translation_text
                                                .overlay_translation_text
                                                .getText()
                                                .length()
                                );

                        Spannable spannableString = new SpannableStringBuilder(TRANSLATION_TEXT.STRING);
                        int selectionEnd = create_overlay_translation_text.overlay_translation_text.getSelectionEnd();

                        spannableString.setSpan(
                                new ForegroundColorSpan(
                                        Color.YELLOW
                                ),
                                0,
                                selectionEnd,
                                0
                        );

                        spannableString.setSpan(
                                new BackgroundColorSpan(
                                        Color.parseColor(
                                                "#80000000"
                                        )
                                ),
                                0,
                                selectionEnd,
                                0
                        );

                        create_overlay_translation_text
                                .overlay_translation_text
                                .setText(
                                        spannableString
                                );

                        create_overlay_translation_text
                                .overlay_translation_text
                                .setSelection(
                                        create_overlay_translation_text
                                                .overlay_translation_text
                                                .getText()
                                                .length()
                                );
                    }

                } else {
                    create_overlay_translation_text
                            .overlay_translation_text
                            .setVisibility(
                                    View.INVISIBLE
                            );

                    create_overlay_translation_text
                            .overlay_translation_text_container
                            .setVisibility(
                                    View.INVISIBLE
                            );
                }
            });

            return translatedText;

        } catch (InterruptedException e) {
             // Do not swallow the thread interrupt status
            Thread.currentThread().interrupt();
            Log.e("GoogleTranslator", "GoogleTranslate2 interrupted", e);
            return "";

        } catch (ExecutionException e) {
            Log.e("GoogleTranslator", "GoogleTranslate2 failed", e);
            return "";
        }
    }

    private void FullGoogleTranslate1(String SENTENCE, String SRC, String DST) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Handler handler = new Handler(Looper.getMainLooper());
        AtomicReference<String> TRANSLATION = new AtomicReference<>("");

        try {
            SENTENCE = URLEncoder.encode(SENTENCE, "utf-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }

        String finalSENTENCE = SENTENCE;

        executor.execute(() -> {
            HttpClient httpClient = null;
            try {
                String url = "https://clients5.google.com/translate_a/t";
                String params =
                        "?client=dict-chrome-ex"
                                + "&sl=" + SRC
                                + "&tl=" + DST
                                + "&q=" + finalSENTENCE;

                httpClient = new DefaultHttpClient();
                HttpGet httpget = new HttpGet(url + params);
                httpget.setHeader(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                "Chrome/139.0.0.0 Safari/537.36"
                );
                HttpResponse response = httpClient.execute(httpget);
                StatusLine statusLine = response.getStatusLine();

                if (statusLine.getStatusCode() == 200) {
                    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                    response.getEntity().writeTo(byteArrayOutputStream);
                    String responseString = byteArrayOutputStream.toString("UTF-8");
                    byteArrayOutputStream.close();
                    //Log.d("FullGoogleTranslate1", "Response: " + responseString);
                    JSONArray jsonArray = new JSONArray(responseString);
                    for (int i = 0; i < jsonArray.length(); i++) {
                        if (!jsonArray.isNull(i)) {
                            TRANSLATION.set(TRANSLATION.get()+ jsonArray.getString(i));
                        }
                    }
                    //Log.d("FullGoogleTranslate1", "TRANSLATION: " + TRANSLATION.get());

                } else {
                    //Log.e("FullGoogleTranslate1", "HTTP " + statusLine.getStatusCode() + ": " + statusLine.getReasonPhrase());
                    if (response.getEntity() != null) {
                        response.getEntity().getContent().close();
                    }
                    throw new IOException("http " + statusLine.getStatusCode() + ": " + statusLine.getReasonPhrase());
                }

            } catch (Exception e) {
                Log.e("FullGoogleTranslate1", "Translation error", e);

            } finally {
                if (httpClient != null) {
                    httpClient.getConnectionManager().shutdown();
                }
            }

            handler.post(() -> {
                translatedLastVoiceString = TRANSLATION.toString();
                if (RECOGNIZING_STATUS.IS_RECOGNIZING) {
                    if (create_overlay_translation_text.overlay_full_translation_text_container != null) {
                        create_overlay_translation_text.overlay_full_translation_text_container.setVisibility(View.VISIBLE);
                        create_overlay_translation_text.overlay_full_translation_text_container.setBackgroundColor(Color.parseColor("#80000000"));
                        create_overlay_translation_text.overlay_full_translation_text.setVisibility(View.VISIBLE);
                        create_overlay_translation_text.overlay_full_translation_text.setBackgroundColor(Color.TRANSPARENT);
                        create_overlay_translation_text.overlay_full_translation_text.setTextIsSelectable(true);
                        create_overlay_translation_text.overlay_full_translation_text.setHighlightColor(Color.TRANSPARENT);
                        appendText(create_overlay_translation_text.overlay_full_translation_text, translatedLastVoiceString);
                        create_overlay_translation_text.overlay_full_translation_text.setSelection(create_overlay_translation_text.overlay_full_translation_text.getText().length());
                        create_overlay_translation_text.overlay_full_translation_text.setTextColor(Color.parseColor("#FF00FFFF"));
                        create_overlay_translation_text.overlay_full_translation_text.setGravity(Gravity.BOTTOM);
                    }
                } else {
                    if (create_overlay_translation_text.overlay_full_translation_text_container != null) {
                        create_overlay_translation_text.overlay_full_translation_text.setVisibility(View.INVISIBLE);
                        create_overlay_translation_text.overlay_full_translation_text_container.setVisibility(View.INVISIBLE);
                    }
                }
            });
        });
    }

    private String FullGoogleTranslate2(String SENTENCE, String SRC, String DST) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Handler handler = new Handler(Looper.getMainLooper());
        AtomicReference<String> TRANSLATION = new AtomicReference<>("");
        try {
            SENTENCE = URLEncoder.encode(SENTENCE, "utf-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
        String finalSENTENCE = SENTENCE;
        if (RECOGNIZING_STATUS.IS_RECOGNIZING && finalSENTENCE != null) {
            executor.execute(() -> {
                HttpClient httpClient;
                try {
                    String url = "https://translate.googleapis.com/translate_a/";
                    String params = "single?client=gtx&sl=" + SRC + "&tl=" + DST + "&dt=t&q=" + finalSENTENCE;
                    httpClient = new DefaultHttpClient();
                    HttpResponse response = httpClient.execute(new HttpGet(url + params));
                    ByteArrayOutputStream byteArrayOutputStream;
                    StatusLine statusLine = response.getStatusLine();
                    JSONArray jsonArray;
                    if (statusLine.getStatusCode() == 200) {
                        byteArrayOutputStream = new ByteArrayOutputStream();
                        response.getEntity().writeTo(byteArrayOutputStream);
                        String stringOfByteArrayOutputStream = byteArrayOutputStream.toString();
                        try {
                            jsonArray = new JSONArray(Objects.requireNonNull(stringOfByteArrayOutputStream)).getJSONArray(0);
                            //Log.d("FullGoogleTranslate2", "jsonArray = " + jsonArray);
                            int length = jsonArray.length();
                            for (int i = 0; i < length; i++) {
                                TRANSLATION.set(TRANSLATION + new JSONArray(Objects.requireNonNull(stringOfByteArrayOutputStream)).getJSONArray(0).getJSONArray(i).get(0).toString());
                            }
                        }
                        catch(Exception e) {
                            //Log.e("FullGoogleTranslate2", e.getMessage());
                            e.printStackTrace();
                        }
                    } else {
                        response.getEntity().getContent().close();
                        httpClient.getConnectionManager().shutdown();
                        throw new IOException(statusLine.getReasonPhrase());
                    }
                    byteArrayOutputStream.close();
                    httpClient.getConnectionManager().shutdown();
                }

                catch (Exception e) {
                    Log.e("FullGoogleTranslate2", e.getMessage());
                    e.printStackTrace();
                }

                handler.post(() -> {
                    translatedLastVoiceString = TRANSLATION.toString();

                    if (RECOGNIZING_STATUS.IS_RECOGNIZING) {
                        create_overlay_translation_text.overlay_full_translation_text_container.setVisibility(View.VISIBLE);
                        create_overlay_translation_text.overlay_full_translation_text_container.setBackgroundColor(Color.parseColor("#80000000"));
                        create_overlay_translation_text.overlay_full_translation_text.setVisibility(View.VISIBLE);
                        create_overlay_translation_text.overlay_full_translation_text.setBackgroundColor(Color.TRANSPARENT);
                        create_overlay_translation_text.overlay_full_translation_text.setTextIsSelectable(true);
                        create_overlay_translation_text.overlay_full_translation_text.setHighlightColor(Color.TRANSPARENT);
                        appendText(create_overlay_translation_text.overlay_full_translation_text, translatedLastVoiceString);
                        create_overlay_translation_text.overlay_full_translation_text.setSelection(create_overlay_translation_text.overlay_full_translation_text.getText().length());
                        create_overlay_translation_text.overlay_full_translation_text.setTextColor(Color.parseColor("#FF00FFFF"));
                        create_overlay_translation_text.overlay_full_translation_text.setGravity(Gravity.BOTTOM);
                    } else {
                        create_overlay_translation_text.overlay_full_translation_text.setVisibility(View.INVISIBLE);
                        create_overlay_translation_text.overlay_full_translation_text_container.setVisibility(View.INVISIBLE);
                    }
                });
            });
        }
        else {
            executor.shutdown();
        }
        return TRANSLATION.toString();
    }

    private void processSamples() {
        Log.i(TAG, "processing samples");

        int bufferSize = 512;
        short[] buffer = new short[bufferSize];
        AudioRecord record = audioRecord;

        if (record == null) {
            Log.e(TAG, "AudioRecord is null");
            return;
        }

        if (recognitionExecutor == null || recognitionExecutor.isShutdown() || recognitionExecutor.isTerminated()) {
            recognitionExecutor = Executors.newSingleThreadExecutor();
        }

        Handler mainHandler = new Handler(Looper.getMainLooper());

        while (RECOGNIZING_STATUS.IS_RECOGNIZING && !serviceStopping) {
            int ret = record.read(buffer, 0, buffer.length);

            if (ret <= 0) {
                continue;
            }

            float[] samples = new float[ret];

            for (int i = 0; i < ret; i++) {
                samples[i] = buffer[i] / 32768.0f;
            }

            vad.acceptWaveform(samples);

            while (!vad.empty()) {
                SpeechSegment segment = vad.front();

                if (serviceStopping || recognitionExecutor.isShutdown()) {
                    break;
                }

                recognitionExecutor.execute(() -> {
                    try {
                        if (serviceStopping || !RECOGNIZING_STATUS.IS_RECOGNIZING) {
                            return;
                        }

                        String text = cleanText(runSecondPass(segment.getSamples()));

                        if (serviceStopping || !RECOGNIZING_STATUS.IS_RECOGNIZING) {
                            return;
                        }

                        if (text != null && !text.trim().isEmpty()) {
                            final String finalText = text.trim();

                            mainHandler.post(() -> {
                                if (serviceStopping || !RECOGNIZING_STATUS.IS_RECOGNIZING) {
                                    return;
                                }

                                //if (!lastText.isEmpty()) {
                                //    lastText = lastText + "\n";
                                //}

                                //lastText = lastText + finalText;
                                VOICE_TEXT.STRING = finalText;
                                MainActivity.voice_text.setText(VOICE_TEXT.STRING);
                                MainActivity.voice_text.setSelection(MainActivity.voice_text.getText().length());
                                if (!VOICE_TEXT.STRING.isEmpty()) {
                                    lastVoiceString = VOICE_TEXT.STRING + ".\n";
                                    Log.d(TAG, "onEndOfSpeech: lastVoiceString = VOICE_TEXT.STRING = " + lastVoiceString);
                                }
                                if (MainActivity.full_voice_text != null && !lastVoiceString.isEmpty()) {
                                    appendText(MainActivity.full_voice_text, lastVoiceString);
                                    MainActivity.full_voice_text.setSelection(MainActivity.full_voice_text.getText().length());
                                    MainActivity.full_voice_text.setGravity(Gravity.BOTTOM);
                                    if (lastVoiceString != null && !Objects.equals(lastVoiceString, "")) {
                                        // =================================================
                                        // ENDPOINT TEST HAS NOT FINISHED YET
                                        // =================================================
                                        if (GOOGLE_TRANSLATE_ENDPOINT.CONFIG == 0) {
                                            Log.d("testGoogleTranslate", "Waiting for endpoint test...");
                                            return;
                                        }
                                        // =================================================
                                        // ENDPOINT 1 SUCCESS
                                        // =================================================
                                        if (GOOGLE_TRANSLATE_ENDPOINT.CONFIG == 1) {
                                            //Log.d("testGoogleTranslate", "Using FullGoogleTranslate1");
                                            FullGoogleTranslate1(lastVoiceString, LANGUAGE.SRC, LANGUAGE.DST);
                                        }
                                        // =================================================
                                        // ENDPOINT 2 SUCCESS
                                        // =================================================
                                        else if (GOOGLE_TRANSLATE_ENDPOINT.CONFIG == 2) {
                                            //Log.d("testGoogleTranslate", "Using FullGoogleTranslate2");
                                            FullGoogleTranslate2(lastVoiceString, LANGUAGE.SRC, LANGUAGE.DST);

                                        }
                                        // =================================================
                                        // BOTH ENDPOINT FAILED
                                        // =================================================
                                        else if (GOOGLE_TRANSLATE_ENDPOINT.CONFIG == -1) {
                                            Log.e("testGoogleTranslate", "No working Google Translate endpoint");
                                            create_overlay_translation_text.overlay_full_translation_text.setVisibility(View.INVISIBLE);
                                            create_overlay_translation_text.overlay_full_translation_text_container.setVisibility(View.INVISIBLE);
                                        }
                                    }
                                    lastVoiceString = "";
                                }
                            });
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Final recognition error", e);
                    }
                });
                vad.pop();
            }
        }
        Log.i(TAG, "processSamples finished");
    }

    private String cleanText(String text) {
        if (text == null) return "";

        // 1. Change the text to all lowercase so that the filter is case-insensitive (not case sensitive)
        String cleaned = text.toLowerCase().trim();

        // 2. Delete the text inside square, round, and curly brackets, in its entirety.
        // Removing: [music sounds], (sounds of war), {laughter}
        cleaned = cleaned.replaceAll("\\[.*?\\]", "");
        cleaned = cleaned.replaceAll("\\(.*?\\)", "");
        cleaned = cleaned.replaceAll("\\{.*?\\}", "");

        // 3. CLEAN UP THE WORD 'SOUND' THAT IS LEAKING (Due to broken or uncaged brackets)
        // This logic removes the word 'voice' along with any 2 words following it behind
        // Examples: "the sound of music", "the sound of successive wars", "the sound of strong winds" will disappear completely
        cleaned = cleaned.replaceAll("suara\\s+\\w+\\s+\\w+", ""); // Hapus 'suara' + 2 kata di belakang
        cleaned = cleaned.replaceAll("suara\\s+\\w+", "");        // Hapus 'suara' + 1 kata di belakang
        cleaned = cleaned.replaceAll("\\bsuara\\b", "");          // Hapus kata 'suara' mandiri

        // 4. Clean up any remnants of broken or damaged parentheses at the beginning or end
        cleaned = cleaned.replace("[", "")
                .replace("]", "")
                .replace("(", "")
                .replace(")", "")
                .replace("{", "")
                .replace("}", "");

        // 5. Clean up double spaces in the middle of sentences resulting from the text truncation above
        cleaned = cleaned.replaceAll("\\s+", " ");

        return cleaned.trim();
    }

    private String runSecondPass(float[] samples) {
        if (serviceStopping || offlineRecognizer == null) {
            return "";
        }

        OfflineStream stream = null;

        try {
            stream = offlineRecognizer.createStream();

            if (serviceStopping) {
                return "";
            }

            stream.acceptWaveform(samples, sampleRate);

            if (serviceStopping) {
                return "";
            }

            offlineRecognizer.decode(stream);

            if (serviceStopping) {
                return "";
            }

            OfflineRecognizerResult result = offlineRecognizer.getResult(stream);

            return result.getText();

        } finally {
            if (stream != null) {
                stream.release();
            }
        }
    }

    private boolean initPlaybackCapture() {

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG,"RECORD_AUDIO permission not granted");
            return false;
        }

        if (mediaProjection == null) {
            Log.e(TAG, "MediaProjection is null");
            return false;
        }

        int numBytes = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat);
        if (numBytes == AudioRecord.ERROR || numBytes == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid AudioRecord buffer size: "+ numBytes);
            return false;
        }

        Log.i(TAG, "Playback capture buffer size: " + numBytes + " bytes");

        AudioPlaybackCaptureConfiguration captureConfig = new AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build();

        AudioFormat format = new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build();

        try {
            audioRecord = new AudioRecord.Builder()
                            .setAudioFormat(format)
                            .setBufferSizeInBytes(numBytes * 2)
                            .setAudioPlaybackCaptureConfig(captureConfig)
                            .build();

        } catch (Exception e) {
            Log.e(TAG, "Failed to create playback AudioRecord",e);
            audioRecord = null;
            return false;
        }

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize");
            audioRecord.release();
            audioRecord = null;
            return false;
        }

        Log.i(TAG, "AudioPlaybackCapture AudioRecord initialized");
        return true;
    }

    private void startAudioCaptureForeground() {
        NotificationManager notificationManager = (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(CAPTURE_CHANNEL_ID, "Whisper Live Subtitle Audio Capture", NotificationManager.IMPORTANCE_LOW);
        notificationManager.createNotificationChannel(channel);
        Notification notification = new Notification.Builder(this, CAPTURE_CHANNEL_ID)
                        .setContentTitle("Whisper Live Subtitle")
                        .setContentText("Audio playback capture siap")
                        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                        .setOngoing(true)
                        .build();

        startForeground(1001, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
    }

    private void preparePlaybackCapture(Intent intent) {
        if (mediaProjection != null) {
            Log.i(TAG, "MediaProjection already exists");
            return;
        }

        startAudioCaptureForeground();
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1);
        Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        Log.i(
                TAG,
                "MediaProjection prepare:"
                        + " resultCode="
                        + resultCode
                        + " resultData="
                        + resultData
                        + " hasResultCode="
                        + intent.hasExtra(EXTRA_RESULT_CODE)
                        + " hasResultData="
                        + intent.hasExtra(EXTRA_RESULT_DATA)
        );

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            Log.e(TAG, "Invalid MediaProjection permission data");
            stopSelf();
            return;
        }

        try {
            MediaProjectionManager projectionManager =(MediaProjectionManager)getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            mediaProjection = projectionManager.getMediaProjection(resultCode, resultData);

            if (mediaProjection == null) {
                Log.e(TAG, "MediaProjection is null");
                stopSelf();
                return;
            }

            mediaProjectionCallback = new MediaProjection.Callback() {
                        @Override
                        public void onStop() {
                            Log.i(TAG, "MediaProjection stopped");
                            serviceStopping = true;
                            RECOGNIZING_STATUS.IS_RECOGNIZING = false;
                            stopAudioCapture();

                            if (audioRecord != null) {
                                try {
                                    audioRecord.release();
                                } catch (Exception ignored) {
                                }

                                audioRecord = null;
                            }
                            stopSelf();
                        }
                    };

            mediaProjection.registerCallback(mediaProjectionCallback, new Handler(Looper.getMainLooper()));

            /*
             * initializationExecutor already made in onCreate().
             * Because the executor is single-thread, This task automatically waits for initVadModel().
             * and initOfflineRecognizer() is complete.
             */
            initializationExecutor.execute(() -> {
                if (serviceStopping) {
                    return;
                }

                boolean ret = initPlaybackCapture();
                if (!ret) {
                    Log.e(TAG, "Failed to initialize AudioPlaybackCapture");
                    stopSelf();
                    return;
                }

                capturePrepared = true;
                Log.i(TAG, "AudioCaptureService READY");

                if (startPending) {
                    startPending = false;
                    startAudioCapture();
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize MediaProjection", e);
            stopSelf();
        }
    }

    private void restartRecognitionForLanguageChange() {
        boolean wasRecognizing = RECOGNIZING_STATUS.IS_RECOGNIZING;
        Log.i(TAG, "Language changed to " + LANGUAGE.SRC_DIALECT + " / " + LANGUAGE.SRC + ", restarting recognizer");
        // Stop audio processing only.
        stopAudioCapture();

        // Discard the old recognizer
        if (offlineRecognizer != null) {
            try {
                offlineRecognizer.release();
            } catch (Exception e) {
                Log.w(TAG, "Failed to release old OfflineRecognizer", e);
            }
            offlineRecognizer = null;
        }
        // Ensure that the new model is created after the old recognizer has finished
        initializationExecutor.execute(() -> {
            if (serviceStopping) {
                return;
            }
            Log.i(TAG, "Initializing OfflineRecognizer for " + LANGUAGE.SRC);
            initOfflineRecognizer();
            Log.i(TAG, "Finished initializing OfflineRecognizer for " + LANGUAGE.SRC);

            if (serviceStopping) {
                return;
            }

            // If the mic was ON, resume immediately once the new model is ready
            if (wasRecognizing) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (!serviceStopping) {
                        startAudioCapture();
                    }
                });
            }
        });
    }

    private void startAudioCapture() {
        if (serviceStopping) {
            return;
        }
        if (!capturePrepared) {
            Log.i(TAG, "Capture not ready yet; start pending");
            startPending = true;
            return;
        }

        if (audioRecord == null) {
            Log.e(TAG, "AudioRecord is null");
            return;
        }

        if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
            return;
        }

        RECOGNIZING_STATUS.IS_RECOGNIZING = true;
        //lastText = "";
        vad.reset();

        //synchronized (partialAudioLock) {
        //    partialAudioBuffer.clear();
        //}

        //lastPartialRecognitionTime = 0;

        try {
            audioRecord.startRecording();
        } catch (IllegalStateException e) {
            RECOGNIZING_STATUS.IS_RECOGNIZING = false;
            Log.e(TAG, "AudioRecord.startRecording() failed", e);
            return;
        }

        recognitionExecutor = Executors.newSingleThreadExecutor();
        recordingThread = new Thread(this::processSamples,"AudioPlaybackCaptureThread");
        recordingThread.start();
        startTranslationTimer();
        Log.i(TAG, "Started AudioPlaybackCapture recording");
    }

    private void stopAudioCapture() {
        startPending = false;
        RECOGNIZING_STATUS.IS_RECOGNIZING = false;
        stopTranslationTimer();
        if (audioRecord != null) {
            try {
                if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }

            } catch (IllegalStateException e) {
                Log.w(TAG, "AudioRecord.stop() failed", e);
            }
        }

        if (recordingThread != null) {
            recordingThread.interrupt();
            recordingThread = null;
        }

        if (recognitionExecutor != null) {
            recognitionExecutor.shutdownNow();
            recognitionExecutor = null;
        }

        //synchronized (partialAudioLock) {
        //    partialAudioBuffer.clear();
        //}

        //partialRecognitionRunning = false;

        Log.i(TAG, "Stopped AudioPlaybackCapture recording");
    }

    private void startTranslationTimer() {
        timer = new Timer();
        timerTask = new TimerTask() {

            @Override
            public void run() {

                if (VOICE_TEXT.STRING != null && !Objects.equals(VOICE_TEXT.STRING, "")) {
                    // =================================================
                    // ENDPOINT TEST HAS NOT FINISHED YET
                    // =================================================
                    if (GOOGLE_TRANSLATE_ENDPOINT.CONFIG == 0) {
                        Log.d("GoogleTranslator", "Waiting for endpoint test..." );
                        return;
                    }
                    // =================================================
                    // ENDPOINT 1 SUCCESS
                    // =================================================
                    if (GOOGLE_TRANSLATE_ENDPOINT.CONFIG == 1) {
                        Log.d("GoogleTranslator", "Using GoogleTranslate1");
                        GoogleTranslate1(VOICE_TEXT.STRING, LANGUAGE.SRC, LANGUAGE.DST);
                    }
                    // =================================================
                    // ENDPOINT 2 SUCCESS
                    // =================================================
                    else if (GOOGLE_TRANSLATE_ENDPOINT.CONFIG == 2) {
                        Log.d("GoogleTranslator", "Using GoogleTranslate2");
                        GoogleTranslate2(VOICE_TEXT.STRING, LANGUAGE.SRC, LANGUAGE.DST);
                    }
                    // =================================================
                    // BOTH ENDPOINT FAILED
                    // =================================================
                    else if (GOOGLE_TRANSLATE_ENDPOINT.CONFIG == -1) {
                        Log.e("GoogleTranslator", "No working Google Translate endpoint" );
                    }
                }
            }
        };
        timer.schedule(timerTask,0,2000);
    }

    private void stopTranslationTimer() {
        // =========================================================
        // STOP TRANSLATION TIMER
        // =========================================================
        if (timerTask != null) {
            timerTask.cancel();
            timerTask = null;
        }

        if (timer != null) {
            timer.cancel();
            timer.purge();
            timer = null;
        }

    }

    private void appendText(final TextView tv, final String text){
        if (tv != null) {
            new Handler(Looper.getMainLooper()).post(() -> tv.append(text));
        }
    }

}

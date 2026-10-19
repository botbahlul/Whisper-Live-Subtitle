package whisper.app.livesubtitle;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;


public class MainActivity extends AppCompatActivity {

    private CheckBox checkbox_debug_mode;
    private Spinner spinner_src_languages;
    @SuppressLint("StaticFieldLeak")
    public static TextView textview_src;
    private Spinner spinner_dst_languages;
    @SuppressLint("StaticFieldLeak")
    public static TextView textview_dst;
    @SuppressLint("StaticFieldLeak")
    public static EditText voice_text;
    @SuppressLint("StaticFieldLeak")
    public static EditText full_voice_text;
    @SuppressLint("StaticFieldLeak")
    public static TextView textview_recognizing;
    @SuppressLint("StaticFieldLeak")
    public static TextView textview_overlaying;
    @SuppressLint("StaticFieldLeak")
    public static TextView textview_debug;
    @SuppressLint("StaticFieldLeak")
    public static TextView textview_output_messages;

    public static int savedResultCode = -1;
    public static Intent savedDataIntent = null;

    private String [] src_dialects;

    ArrayList<String> arraylist_src_languages = new ArrayList<>();
    ArrayList<String> temp_src_dialects = new ArrayList<>();
    ArrayList<String> arraylist_src_code = new ArrayList<>();
    Map<String, String> map_src_country = new HashMap<>();
    Map<String, String> src_countries_dialects;

    ArrayList<String> arraylist_dst_languages = new ArrayList<>();
    ArrayList<String> arraylist_dst_code = new ArrayList<>();
    Map<String, String> map_dst_country = new HashMap<>();

    private DisplayMetrics display;
    private static final int REQUEST_MEDIA_PROJECTION = 1001;
    @SuppressLint("StaticFieldLeak")
    public static AudioManager audio;
    public static int mStreamVolume;

    private ActivityResultLauncher<Intent> mediaProjectionLauncher;
    private boolean sourceLanguageSpinnerInitialized = false;

    //DON'T FORGET TO MODIFY AndroidManifest.xml
    //         <activity
    //            android:name=".MainActivity"
    //            android:configChanges="keyboardHidden|screenSize|orientation|screenLayout|navigation"

    @SuppressLint({"ClickableViewAccessibility", "QueryPermissionsNeeded", "SetTextI18n"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);
        checkbox_debug_mode = findViewById(R.id.checkbox_debug_mode);
        spinner_src_languages = findViewById(R.id.spinner_src_languages);
        spinner_dst_languages = findViewById(R.id.spinner_dst_languages);
        Button button_toggle_overlay = findViewById(R.id.button_toggle_overlay);
        textview_src = findViewById(R.id.textview_src);
        textview_dst = findViewById(R.id.textview_dst);
        voice_text = findViewById(R.id.voice_text);
        full_voice_text = findViewById(R.id.full_voice_text);
        textview_recognizing = findViewById(R.id.textview_recognizing);
        textview_overlaying = findViewById(R.id.textview_overlaying);
        textview_debug = findViewById(R.id.textview_debug);
        textview_output_messages = findViewById(R.id.textview_output_messages);

        String[][] rawLanguageData = {
                {"Afrikaans", "af"},
                {"Amharic", "am"},
                {"Arabic", "ar"},
                {"Armenian", "hy"},
                {"Azerbaijani", "az"},
                {"Bangla", "bn"},
                {"Basque", "eu"},
                {"Bulgarian", "bg"},
                {"Catalan", "ca"},
                {"Chinese (Mandarin)", "zh"},
                {"Croatian", "hr"},
                {"Czech", "cs"},
                {"Dansk", "da"},
                {"Deutsch", "de"},
                {"Dutch", "nl"},
                {"English", "en"},
                {"Finnish", "fi"},
                {"French", "fr"},
                {"Galician", "gl"},
                {"Georgian", "ka"},
                {"Greek", "el"},
                {"Gujarati", "gu"},
                {"Hindi", "hi"},
                {"Hungarian", "hu"},
                {"Icelandic", "is"},
                {"Indonesian", "id"},
                {"Italian", "it"},
                {"Japanese", "ja"},
                {"Javanese", "jw"},
                {"Kannada", "kn"},
                {"Khmer", "km"},
                {"Kiswahili", "sw"},
                {"Korean", "ko"},
                {"Lao", "lo"},
                {"Latvian", "lv"},
                {"Lithuanian", "lt"},
                {"Malay", "ms"},
                {"Malayalam", "ml"},
                {"Marathi", "mr"},
                {"Myanmar", "my"},
                {"Nepali", "ne"},
                {"Norwegian", "no"},
                {"Polish", "pl"},
                {"Portuguese", "pt"},
                {"Romanian", "ro"},
                {"Russian", "ru"},
                {"Serbian", "sr"},
                {"Sinhala", "si"},
                {"Slovene", "sl"},
                {"Slovak", "sk"},
                {"Spanish", "es"},
                {"Sundanese", "su"},
                {"Swedish", "sv"},
                {"Tamil", "ta"},
                {"Telugu", "te"},
                {"Thai", "th"},
                {"Turkish", "tr"},
                {"Urdu", "ur"},
                {"Vietnamese", "vi"},
                {"Ukrainian", "uk"}
        };

        for (String[] rawLanguageDatum : rawLanguageData) {
            arraylist_src_languages.add(rawLanguageDatum[0]);
            temp_src_dialects.add(rawLanguageDatum[1]);
        }

        src_dialects = temp_src_dialects.toArray(new String[0]);

        for (String dialect : src_dialects) {
            if (dialect != null && dialect.length() >= 2) {
                String baseCode = dialect.split("-")[0];
                arraylist_src_code.add(baseCode.toLowerCase());
            } else {
                arraylist_src_code.add("id");
            }
        }

        for (int i = 0; i < src_dialects.length; i++) {
            src_dialects[i] = src_dialects[i].trim();
        }

        for (int i = 0; i < arraylist_src_languages.size(); i++) {
            map_src_country.put(arraylist_src_languages.get(i), arraylist_src_code.get(i));
        }

        setup_src_spinner(arraylist_src_languages);

        arraylist_dst_code.add("af");
        arraylist_dst_code.add("sq");
        arraylist_dst_code.add("am");
        arraylist_dst_code.add("ar");
        arraylist_dst_code.add("hy");
        arraylist_dst_code.add("as");
        arraylist_dst_code.add("ay");
        arraylist_dst_code.add("az");
        arraylist_dst_code.add("bm");
        arraylist_dst_code.add("eu");
        arraylist_dst_code.add("be");
        arraylist_dst_code.add("bn");
        arraylist_dst_code.add("bho");
        arraylist_dst_code.add("bs");
        arraylist_dst_code.add("bg");
        arraylist_dst_code.add("ca");
        arraylist_dst_code.add("ceb");
        arraylist_dst_code.add("ny");
        arraylist_dst_code.add("zh");
        arraylist_dst_code.add("zh-CN");
        arraylist_dst_code.add("zh-TW");
        arraylist_dst_code.add("co");
        arraylist_dst_code.add("hr");
        arraylist_dst_code.add("cs");
        arraylist_dst_code.add("da");
        arraylist_dst_code.add("dv");
        arraylist_dst_code.add("doi");
        arraylist_dst_code.add("nl");
        arraylist_dst_code.add("en");
        arraylist_dst_code.add("eo");
        arraylist_dst_code.add("et");
        arraylist_dst_code.add("ee");
        arraylist_dst_code.add("fil");
        arraylist_dst_code.add("fi");
        arraylist_dst_code.add("fr");
        arraylist_dst_code.add("fy");
        arraylist_dst_code.add("gl");
        arraylist_dst_code.add("ka");
        arraylist_dst_code.add("de");
        arraylist_dst_code.add("el");
        arraylist_dst_code.add("gn");
        arraylist_dst_code.add("gu");
        arraylist_dst_code.add("ht");
        arraylist_dst_code.add("ha");
        arraylist_dst_code.add("haw");
        arraylist_dst_code.add("he");
        arraylist_dst_code.add("hi");
        arraylist_dst_code.add("hmn");
        arraylist_dst_code.add("hu");
        arraylist_dst_code.add("is");
        arraylist_dst_code.add("ig");
        arraylist_dst_code.add("ilo");
        arraylist_dst_code.add("id");
        arraylist_dst_code.add("ga");
        arraylist_dst_code.add("it");
        arraylist_dst_code.add("ja");
        arraylist_dst_code.add("jv");
        arraylist_dst_code.add("kn");
        arraylist_dst_code.add("kk");
        arraylist_dst_code.add("km");
        arraylist_dst_code.add("rw");
        arraylist_dst_code.add("gom");
        arraylist_dst_code.add("ko");
        arraylist_dst_code.add("kri");
        arraylist_dst_code.add("kmr");
        arraylist_dst_code.add("ckb");
        arraylist_dst_code.add("ky");
        arraylist_dst_code.add("lo");
        arraylist_dst_code.add("la");
        arraylist_dst_code.add("lv");
        arraylist_dst_code.add("ln");
        arraylist_dst_code.add("lt");
        arraylist_dst_code.add("lg");
        arraylist_dst_code.add("lb");
        arraylist_dst_code.add("mk");
        arraylist_dst_code.add("mg");
        arraylist_dst_code.add("ms");
        arraylist_dst_code.add("ml");
        arraylist_dst_code.add("mt");
        arraylist_dst_code.add("mi");
        arraylist_dst_code.add("mr");
        arraylist_dst_code.add("mni-Mtei");
        arraylist_dst_code.add("lus");
        arraylist_dst_code.add("mn");
        arraylist_dst_code.add("my");
        arraylist_dst_code.add("ne");
        arraylist_dst_code.add("no");
        arraylist_dst_code.add("or");
        arraylist_dst_code.add("om");
        arraylist_dst_code.add("ps");
        arraylist_dst_code.add("fa");
        arraylist_dst_code.add("pl");
        arraylist_dst_code.add("pt");
        arraylist_dst_code.add("pa");
        arraylist_dst_code.add("qu");
        arraylist_dst_code.add("ro");
        arraylist_dst_code.add("ru");
        arraylist_dst_code.add("sm");
        arraylist_dst_code.add("sa");
        arraylist_dst_code.add("gd");
        arraylist_dst_code.add("nso");
        arraylist_dst_code.add("sr");
        arraylist_dst_code.add("st");
        arraylist_dst_code.add("sn");
        arraylist_dst_code.add("sd");
        arraylist_dst_code.add("si");
        arraylist_dst_code.add("sk");
        arraylist_dst_code.add("sl");
        arraylist_dst_code.add("so");
        arraylist_dst_code.add("es");
        arraylist_dst_code.add("su");
        arraylist_dst_code.add("sw");
        arraylist_dst_code.add("sv");
        arraylist_dst_code.add("tg");
        arraylist_dst_code.add("ta");
        arraylist_dst_code.add("tt");
        arraylist_dst_code.add("te");
        arraylist_dst_code.add("th");
        arraylist_dst_code.add("ti");
        arraylist_dst_code.add("ts");
        arraylist_dst_code.add("tr");
        arraylist_dst_code.add("tk");
        arraylist_dst_code.add("tw");
        arraylist_dst_code.add("uk");
        arraylist_dst_code.add("ur");
        arraylist_dst_code.add("ug");
        arraylist_dst_code.add("uz");
        arraylist_dst_code.add("vi");
        arraylist_dst_code.add("cy");
        arraylist_dst_code.add("xh");
        arraylist_dst_code.add("yi");
        arraylist_dst_code.add("yo");
        arraylist_dst_code.add("zu");

        arraylist_dst_languages.add("Afrikaans");
        arraylist_dst_languages.add("Albanian");
        arraylist_dst_languages.add("Amharic");
        arraylist_dst_languages.add("Arabic");
        arraylist_dst_languages.add("Armenian");
        arraylist_dst_languages.add("Assamese");
        arraylist_dst_languages.add("Aymara");
        arraylist_dst_languages.add("Azerbaijani");
        arraylist_dst_languages.add("Bambara");
        arraylist_dst_languages.add("Basque");
        arraylist_dst_languages.add("Belarusian");
        arraylist_dst_languages.add("Bengali");
        arraylist_dst_languages.add("Bhojpuri");
        arraylist_dst_languages.add("Bosnian");
        arraylist_dst_languages.add("Bulgarian");
        arraylist_dst_languages.add("Catalan");
        arraylist_dst_languages.add("Cebuano");
        arraylist_dst_languages.add("Chichewa");
        arraylist_dst_languages.add("Chinese");
        arraylist_dst_languages.add("Chinese (Simplified)");
        arraylist_dst_languages.add("Chinese (Traditional)");
        arraylist_dst_languages.add("Corsican");
        arraylist_dst_languages.add("Croatian");
        arraylist_dst_languages.add("Czech");
        arraylist_dst_languages.add("Danish");
        arraylist_dst_languages.add("Dhivehi");
        arraylist_dst_languages.add("Dogri");
        arraylist_dst_languages.add("Dutch");
        arraylist_dst_languages.add("English");
        arraylist_dst_languages.add("Esperanto");
        arraylist_dst_languages.add("Estonian");
        arraylist_dst_languages.add("Ewe");
        arraylist_dst_languages.add("Filipino");
        arraylist_dst_languages.add("Finnish");
        arraylist_dst_languages.add("French");
        arraylist_dst_languages.add("Frisian");
        arraylist_dst_languages.add("Galician");
        arraylist_dst_languages.add("Georgian");
        arraylist_dst_languages.add("German");
        arraylist_dst_languages.add("Greek");
        arraylist_dst_languages.add("Guarani");
        arraylist_dst_languages.add("Gujarati");
        arraylist_dst_languages.add("Haitian Creole");
        arraylist_dst_languages.add("Hausa");
        arraylist_dst_languages.add("Hawaiian");
        arraylist_dst_languages.add("Hebrew");
        arraylist_dst_languages.add("Hindi");
        arraylist_dst_languages.add("Hmong");
        arraylist_dst_languages.add("Hungarian");
        arraylist_dst_languages.add("Icelandic");
        arraylist_dst_languages.add("Igbo");
        arraylist_dst_languages.add("Ilocano");
        arraylist_dst_languages.add("Indonesian");
        arraylist_dst_languages.add("Irish");
        arraylist_dst_languages.add("Italian");
        arraylist_dst_languages.add("Japanese");
        arraylist_dst_languages.add("Javanese");
        arraylist_dst_languages.add("Kannada");
        arraylist_dst_languages.add("Kazakh");
        arraylist_dst_languages.add("Khmer");
        arraylist_dst_languages.add("Kinyarwanda");
        arraylist_dst_languages.add("Konkani");
        arraylist_dst_languages.add("Korean");
        arraylist_dst_languages.add("Krio");
        arraylist_dst_languages.add("Kurdish (Kurmanji)");
        arraylist_dst_languages.add("Kurdish (Sorani)");
        arraylist_dst_languages.add("Kyrgyz");
        arraylist_dst_languages.add("Lao");
        arraylist_dst_languages.add("Latin");
        arraylist_dst_languages.add("Latvian");
        arraylist_dst_languages.add("Lingala");
        arraylist_dst_languages.add("Lithuanian");
        arraylist_dst_languages.add("Luganda");
        arraylist_dst_languages.add("Luxembourgish");
        arraylist_dst_languages.add("Macedonian");
        arraylist_dst_languages.add("Malagasy");
        arraylist_dst_languages.add("Malay");
        arraylist_dst_languages.add("Malayalam");
        arraylist_dst_languages.add("Maltese");
        arraylist_dst_languages.add("Maori");
        arraylist_dst_languages.add("Marathi");
        arraylist_dst_languages.add("Meiteilon (Manipuri)");
        arraylist_dst_languages.add("Mizo");
        arraylist_dst_languages.add("Mongolian");
        arraylist_dst_languages.add("Myanmar (Burmese)");
        arraylist_dst_languages.add("Nepali");
        arraylist_dst_languages.add("Norwegian");
        arraylist_dst_languages.add("Odiya (Oriya)");
        arraylist_dst_languages.add("Oromo");
        arraylist_dst_languages.add("Pashto");
        arraylist_dst_languages.add("Persian");
        arraylist_dst_languages.add("Polish");
        arraylist_dst_languages.add("Portuguese");
        arraylist_dst_languages.add("Punjabi");
        arraylist_dst_languages.add("Quechua");
        arraylist_dst_languages.add("Romanian");
        arraylist_dst_languages.add("Russian");
        arraylist_dst_languages.add("Samoan");
        arraylist_dst_languages.add("Sanskrit");
        arraylist_dst_languages.add("Scots Gaelic");
        arraylist_dst_languages.add("Sepedi");
        arraylist_dst_languages.add("Serbian");
        arraylist_dst_languages.add("Sesotho");
        arraylist_dst_languages.add("Shona");
        arraylist_dst_languages.add("Sindhi");
        arraylist_dst_languages.add("Sinhala");
        arraylist_dst_languages.add("Slovak");
        arraylist_dst_languages.add("Slovenian");
        arraylist_dst_languages.add("Somali");
        arraylist_dst_languages.add("Spanish");
        arraylist_dst_languages.add("Sundanese");
        arraylist_dst_languages.add("Swahili");
        arraylist_dst_languages.add("Swedish");
        arraylist_dst_languages.add("Tajik");
        arraylist_dst_languages.add("Tamil");
        arraylist_dst_languages.add("Tatar");
        arraylist_dst_languages.add("Telugu");
        arraylist_dst_languages.add("Thai");
        arraylist_dst_languages.add("Tigrinya");
        arraylist_dst_languages.add("Tsonga");
        arraylist_dst_languages.add("Turkish");
        arraylist_dst_languages.add("Turkmen");
        arraylist_dst_languages.add("Twi (Akan)");
        arraylist_dst_languages.add("Ukrainian");
        arraylist_dst_languages.add("Urdu");
        arraylist_dst_languages.add("Uyghur");
        arraylist_dst_languages.add("Uzbek");
        arraylist_dst_languages.add("Vietnamese");
        arraylist_dst_languages.add("Welsh");
        arraylist_dst_languages.add("Xhosa");
        arraylist_dst_languages.add("Yiddish");
        arraylist_dst_languages.add("Yoruba");
        arraylist_dst_languages.add("Zulu");

        for (int i = 0; i < arraylist_dst_languages.size(); i++) {
            map_dst_country.put(arraylist_dst_languages.get(i), arraylist_dst_code.get(i));
        }

        setup_dst_spinner(arraylist_dst_languages);

        VOICE_TEXT.STRING = "";
        TRANSLATION_TEXT.STRING = "";

        audio = (AudioManager) getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
        mStreamVolume = audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION);
        setVolumeControlStream(AudioManager.MODE_IN_COMMUNICATION);
        audio.setSpeakerphoneOn(true);

        display = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(display);
        float d = display.density;
        DISPLAY_METRIC.DISPLAY_WIDTH = display.widthPixels;
        DISPLAY_METRIC.DISPLAY_HEIGHT = display.heightPixels;
        DISPLAY_METRIC.DISPLAY_DENSITY = d;

        RECOGNIZING_STATUS.IS_RECOGNIZING = false;
        MIC_BUTTON_STATUS.IS_OVERLAYING = false;
        RECOGNIZING_STATUS.STRING = "RECOGNIZING_STATUS.IS_RECOGNIZING = " + RECOGNIZING_STATUS.IS_RECOGNIZING;
        setText(textview_recognizing, RECOGNIZING_STATUS.STRING);
        MIC_BUTTON_STATUS.STRING = "MIC_BUTTON_STATUS.IS_OVERLAYING = " + MIC_BUTTON_STATUS.IS_OVERLAYING;
        textview_overlaying.setText(MIC_BUTTON_STATUS.STRING);

        NotificationManager notificationManager = (NotificationManager) this.getSystemService(Context.NOTIFICATION_SERVICE);
        if (!notificationManager.isNotificationPolicyAccessGranted()) {
            Intent intent = new Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS);
            startActivity(intent);
        }

        int h;
        if (Objects.equals(LANGUAGE.DST, "ja") || Objects.equals(LANGUAGE.DST, "zh-CN") || Objects.equals(LANGUAGE.DST, "zh-TW")) {
            h = 75;
        }
        else {
            h = 62;
        }
        voice_text.setHeight((int) (h * getResources().getDisplayMetrics().density));
        full_voice_text.setHeight((int) (h * getResources().getDisplayMetrics().density));

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayOptions(ActionBar.DISPLAY_SHOW_CUSTOM);
            getSupportActionBar().setCustomView(R.layout.actionbar_layout);
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        }

        mediaProjectionLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() != Activity.RESULT_OK) {
                        Log.e("MediaProjection", "User denied MediaProjection");
                        MIC_BUTTON_STATUS.IS_OVERLAYING = false;
                        RECOGNIZING_STATUS.IS_RECOGNIZING = false;
                        stop_create_overlay_mic_button();
                        stop_create_overlay_translation_text();
                        return;
                    }
                    Intent data = result.getData();
                    if (data == null) {
                        Log.e("MediaProjection", "MediaProjection data is null");
                        MIC_BUTTON_STATUS.IS_OVERLAYING = false;
                        RECOGNIZING_STATUS.IS_RECOGNIZING = false;
                        stop_create_overlay_mic_button();
                        stop_create_overlay_translation_text();
                        return;
                    }
                    startAudioCaptureServiceReady(result.getResultCode(),data);
                    //Intent serviceIntent = new Intent(this, AudioCaptureService.class);
                    //serviceIntent.putExtra("whisper.app.livesubtitle.MEDIA_PROJECTION_RESULT_CODE", result.getResultCode());
                    //serviceIntent.putExtra("whisper.app.livesubtitle.MEDIA_PROJECTION_RESULT_DATA", data);
                    //startForegroundService(serviceIntent);
                }
        );

        checkbox_debug_mode.setOnClickListener(view -> {
            if(((CompoundButton) view).isChecked()){
                textview_src.setVisibility(View.VISIBLE);
                textview_dst.setVisibility(View.VISIBLE);
                textview_recognizing.setVisibility(View.VISIBLE);
                textview_overlaying.setVisibility(View.VISIBLE);
                textview_debug.setVisibility(View.VISIBLE);

                if (LANGUAGE.SRC != null) {
                    String ls = "LANGUAGE.SRC = " + LANGUAGE.SRC;
                    textview_src.setText(ls);
                }
                else {
                    textview_src.setHint("LANGUAGE.SRC");
                }

                if (LANGUAGE.DST != null) {
                    String ld = "LANGUAGE.DST = " + LANGUAGE.DST;
                    textview_dst.setText(ld);
                }
                else {
                    textview_src.setHint("LANGUAGE.SRC");
                }
            }
            else {
                textview_src.setVisibility(View.GONE);
                textview_dst.setVisibility(View.GONE);
                textview_recognizing.setVisibility(View.GONE);
                textview_overlaying.setVisibility(View.GONE);
                textview_debug.setVisibility(View.GONE);
            }
        });

        if(checkbox_debug_mode.isChecked()){
            textview_src.setVisibility(View.VISIBLE);
            textview_dst.setVisibility(View.VISIBLE);
            textview_recognizing.setVisibility(View.VISIBLE);
            textview_overlaying.setVisibility(View.VISIBLE);
            textview_debug.setVisibility(View.VISIBLE);

            if (LANGUAGE.SRC != null) {
                String ls  = "LANGUAGE.SRC = " + LANGUAGE.SRC;
                textview_src.setText(ls);
            }
            else {
                textview_src.setHint("LANGUAGE.SRC");
            }

            if (LANGUAGE.DST != null) {
                String ld = "LANGUAGE.DST = " + LANGUAGE.DST;
                textview_dst.setText(ld);
            }
            else {
                textview_src.setHint("LANGUAGE.SRC");
            }
        }

        else {
            textview_src.setVisibility(View.GONE);
            textview_dst.setVisibility(View.GONE);
            textview_recognizing.setVisibility(View.GONE);
            textview_overlaying.setVisibility(View.GONE);
            textview_debug.setVisibility(View.GONE);
        }

        spinner_src_languages.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> adapterView,View view, int i, long l) {
                        // Simpan bahasa source sebelum diubah.
                        String oldSrcDialect = LANGUAGE.SRC_DIALECT;

                        // Simpan status mic sebelum perubahan bahasa.
                        // true  = mic_button sedang ON / ASR sedang berjalan
                        // false = mic_button sedang OFF
                        boolean wasRecognizing = RECOGNIZING_STATUS.IS_RECOGNIZING;

                        if (spinner_src_languages != null && spinner_src_languages.getSelectedItem() != null) {
                            LANGUAGE.SRC_COUNTRY = spinner_src_languages.getSelectedItem().toString();
                            LANGUAGE.SRC_DIALECT = src_countries_dialects.get(LANGUAGE.SRC_COUNTRY);
                            if (LANGUAGE.SRC_DIALECT != null) {
                                LANGUAGE.SRC = LANGUAGE.SRC_DIALECT.split("-")[0];
                            }
                            setText(textview_src, LANGUAGE.SRC);
                        }

                        if (spinner_dst_languages != null && spinner_dst_languages.getSelectedItem() != null) {
                            LANGUAGE.DST_COUNTRY = spinner_dst_languages.getSelectedItem().toString();
                            LANGUAGE.DST = map_dst_country.get(LANGUAGE.DST_COUNTRY);
                            setText(textview_dst, LANGUAGE.DST);
                        }

                        int h;
                        if (Objects.equals(LANGUAGE.DST, "ja") || Objects.equals(LANGUAGE.DST, "zh-Hans") || Objects.equals(LANGUAGE.DST, "zh-Hant")) {
                            h = 75;
                        } else {
                            h = 62;
                        }
                        voice_text.setHeight((int) (h * getResources().getDisplayMetrics().density));
                        full_voice_text.setHeight((int) (h * getResources().getDisplayMetrics().density));

                         // Spinner pertama kali memanggil onItemSelected()
                         // Jangan restart AudioCaptureService pada pemanggilan pertama saat Activity baru dibuat.
                        if (!sourceLanguageSpinnerInitialized) {
                            sourceLanguageSpinnerInitialized = true;
                            return;
                        }

                         // CEK APAKAH SOURCE LANGUAGE BENAR-BENAR BERUBAH
                        boolean languageChanged = !Objects.equals(oldSrcDialect, LANGUAGE.SRC_DIALECT);

                        /*
                         * =========================================================
                         * KALAU OVERLAY AKTIF DAN BAHASA BERUBAH
                         *
                         * JANGAN:
                         *
                         * - stop overlay
                         * - membuat overlay lagi
                         * - meminta MediaProjection lagi
                         * - startActivity()
                         *
                         * Cukup beri tahu AudioCaptureService bahwa bahasa berubah.
                         *
                         * AudioCaptureService sendiri akan melihat apakah
                         * RECOGNIZING_STATUS.IS_RECOGNIZING sebelumnya true/false.
                         * =========================================================
                         */

                        if (languageChanged && MIC_BUTTON_STATUS.IS_OVERLAYING) {
                            Intent serviceIntent = new Intent(MainActivity.this, AudioCaptureService.class);
                            serviceIntent.setAction(AudioCaptureService.ACTION_LANGUAGE_CHANGED);
                            startService(serviceIntent);
                        }

                        /*
                         * =========================================================
                         * UPDATE DEBUG STATUS
                         * =========================================================
                         */

                        RECOGNIZING_STATUS.STRING = "RECOGNIZING_STATUS.IS_RECOGNIZING = " + RECOGNIZING_STATUS.IS_RECOGNIZING;
                        setText(textview_recognizing, RECOGNIZING_STATUS.STRING);
                        MIC_BUTTON_STATUS.STRING = "MIC_BUTTON_STATUS.IS_OVERLAYING = " + MIC_BUTTON_STATUS.IS_OVERLAYING;
                        textview_overlaying.setText(MIC_BUTTON_STATUS.STRING);
                    }


                    @Override
                    public void onNothingSelected(AdapterView<?> adapterView) {
                        //
                    }
                });

        spinner_dst_languages.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                if (spinner_src_languages != null && spinner_src_languages.getSelectedItem() != null) {
                    LANGUAGE.SRC_COUNTRY = spinner_src_languages.getSelectedItem().toString();
                    LANGUAGE.SRC_DIALECT = src_countries_dialects.get(LANGUAGE.SRC_COUNTRY);
                    if (LANGUAGE.SRC_DIALECT != null) {
                        LANGUAGE.SRC = LANGUAGE.SRC_DIALECT.split("-")[0];
                    }
                    setText(textview_src, LANGUAGE.SRC);
                }
                if (spinner_dst_languages != null && spinner_dst_languages.getSelectedItem() != null) {
                    LANGUAGE.DST_COUNTRY = spinner_dst_languages.getSelectedItem().toString();
                    LANGUAGE.DST = map_dst_country.get(LANGUAGE.DST_COUNTRY);
                    setText(textview_dst, LANGUAGE.DST);
                }

                int h;
                if (Objects.equals(LANGUAGE.DST, "ja") || Objects.equals(LANGUAGE.DST, "zh-Hans") || Objects.equals(LANGUAGE.DST, "zh-Hant")) {
                    h = 75;
                }
                else {
                    h = 62;
                }
                voice_text.setHeight((int) (h * getResources().getDisplayMetrics().density));
                full_voice_text.setHeight((int) (h * getResources().getDisplayMetrics().density));
            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {
                if (spinner_src_languages != null && spinner_src_languages.getSelectedItem() != null) {
                    LANGUAGE.SRC_COUNTRY = spinner_src_languages.getSelectedItem().toString();
                    LANGUAGE.SRC_DIALECT = src_countries_dialects.get(LANGUAGE.SRC_COUNTRY);
                    if (LANGUAGE.SRC_DIALECT != null) {
                        LANGUAGE.SRC = LANGUAGE.SRC_DIALECT.split("-")[0];
                    }
                    setText(textview_src, LANGUAGE.SRC);
                }
                if (spinner_dst_languages != null && spinner_dst_languages.getSelectedItem() != null) {
                    LANGUAGE.DST_COUNTRY = spinner_dst_languages.getSelectedItem().toString();
                    LANGUAGE.DST = map_dst_country.get(LANGUAGE.DST_COUNTRY);
                    setText(textview_dst, LANGUAGE.DST);
                }
            }
        });

        button_toggle_overlay.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                MIC_BUTTON_STATUS.IS_OVERLAYING = !MIC_BUTTON_STATUS.IS_OVERLAYING;
                MIC_BUTTON_STATUS.STRING =  "MIC_BUTTON_STATUS.IS_OVERLAYING = " + MIC_BUTTON_STATUS.IS_OVERLAYING;
                setText(textview_overlaying, MIC_BUTTON_STATUS.STRING);

                if (MIC_BUTTON_STATUS.IS_OVERLAYING) {
                    if (Settings.canDrawOverlays(getApplicationContext())) {
                        start_create_overlay_mic_button();
                        start_create_overlay_translation_text();
                        // Minta MediaProjection SEKALI untuk seluruh masa hidup overlay
                        requestMediaProjectionForOverlay();
                    } else {
                        Handler handler = new Handler(Looper.getMainLooper());
                        ExecutorService executorService = Executors.newSingleThreadExecutor();
                        Runnable runnable = () -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
                        executorService.execute(runnable);
                        handler.postDelayed(() -> {
                            if (Settings.canDrawOverlays(getApplicationContext())) {
                                start_create_overlay_mic_button();
                                start_create_overlay_translation_text();
                                MIC_BUTTON_STATUS.IS_OVERLAYING = true;
                                String os = "Overlay permission granted";
                                setText(textview_output_messages, os);
                                // Setelah overlay permission tersedia,
                                // baru minta MediaProjection.
                                requestMediaProjectionForOverlay();
                            } else {
                                MIC_BUTTON_STATUS.IS_OVERLAYING = false;
                                String os = "Please retry to tap TOGGLE OVERLAY button again";
                                setText(textview_output_messages, os);
                            }
                            MIC_BUTTON_STATUS.STRING = "MIC_BUTTON_STATUS.IS_OVERLAYING = " + MIC_BUTTON_STATUS.IS_OVERLAYING;
                            setText(textview_overlaying, MIC_BUTTON_STATUS.STRING);
                        }, 15000);
                    }
                } else {
                    stopAudioCaptureService();
                    stop_create_overlay_translation_text();
                    stop_create_overlay_mic_button();
                    RECOGNIZING_STATUS.IS_RECOGNIZING = false;
                    RECOGNIZING_STATUS.STRING = "RECOGNIZING_STATUS.IS_RECOGNIZING = " + RECOGNIZING_STATUS.IS_RECOGNIZING;
                    setText(textview_recognizing, RECOGNIZING_STATUS.STRING);
                    MIC_BUTTON_STATUS.STRING = "MIC_BUTTON_STATUS.IS_OVERLAYING = " + MIC_BUTTON_STATUS.IS_OVERLAYING;
                    setText(textview_overlaying, MIC_BUTTON_STATUS.STRING);
                    setText(textview_output_messages, "");
                    VOICE_TEXT.STRING = "";
                    TRANSLATION_TEXT.STRING = "";
                    setText(voice_text, "");
                    voice_text.setHint("Recognized words");
                    full_voice_text.setHint("Recognized sentences");
                    audio.setStreamVolume(AudioManager.STREAM_NOTIFICATION, (int)Double.parseDouble(String.valueOf((long)(audio.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION) / 2))), 0);
                    if (create_overlay_translation_text.overlay_translation_text != null) {
                        setText(create_overlay_translation_text.overlay_translation_text, "");
                        create_overlay_translation_text.overlay_translation_text.setVisibility(View.INVISIBLE);
                        create_overlay_translation_text.overlay_translation_text_container.setVisibility(View.INVISIBLE);
                    }
                    if (create_overlay_mic_button.mic_button != null) {
                        create_overlay_mic_button.mic_button.setVisibility(View.INVISIBLE);
                    }
                    setText(textview_output_messages, "");
                    VOICE_TEXT.STRING = "";
                    TRANSLATION_TEXT.STRING = "";
                    setText(voice_text, "");
                    RECOGNIZING_STATUS.STRING = "RECOGNIZING_STATUS.IS_RECOGNIZING = " + RECOGNIZING_STATUS.IS_RECOGNIZING;
                    setText(textview_recognizing, RECOGNIZING_STATUS.STRING);
                    MIC_BUTTON_STATUS.STRING = "MIC_BUTTON_STATUS.IS_OVERLAYING = " + MIC_BUTTON_STATUS.IS_OVERLAYING;
                    setText(textview_overlaying, MIC_BUTTON_STATUS.STRING);
                    voice_text.setHint("Recognized words");
                    full_voice_text.setHint("Recognized sentences");
                }
            }
            return false;
        });

    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        stop_create_overlay_translation_text();
        stop_create_overlay_mic_button();
        stop_voice_recognizer();
        audio.setStreamVolume(AudioManager.STREAM_NOTIFICATION, mStreamVolume, AudioManager.ADJUST_SAME);
        finish();
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        stop_create_overlay_translation_text();
        stop_create_overlay_mic_button();
        stop_voice_recognizer();
        audio.setStreamVolume(AudioManager.STREAM_NOTIFICATION, mStreamVolume, AudioManager.ADJUST_SAME);
    }

    public void setup_src_spinner_bak(ArrayList<String> supported_languages)
    {
        src_countries_dialects = new HashMap<>();
        for (int i=0;i<supported_languages.size();i++) {
            src_countries_dialects.put(supported_languages.get(i), src_dialects[i]);
        }
        Collections.sort(supported_languages);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.spinner_textview_align, supported_languages);
        adapter.setDropDownViewResource(R.layout.spinner_textview_align);
        spinner_src_languages.setAdapter(adapter);
        spinner_src_languages.setSelection(supported_languages.indexOf("Indonesian"));
    }

    public void setup_src_spinner(ArrayList<String> supported_languages) {
        src_countries_dialects = new HashMap<>();
        for (int i = 0; i < supported_languages.size(); i++) {
            src_countries_dialects.put(supported_languages.get(i), src_dialects[i]);
        }
        Collections.sort(supported_languages);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, R.layout.spinner_textview_align, supported_languages) {
            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                View view = super.getDropDownView(position, convertView, parent);
                view.setOnTouchListener((v, event) -> {
                    if (event.getAction() == MotionEvent.ACTION_DOWN) {
                        AlphaAnimation blink = new AlphaAnimation(1.0f, 0.2f);
                        blink.setDuration(100);
                        blink.setRepeatMode(Animation.REVERSE);
                        blink.setRepeatCount(2);
                        v.startAnimation(blink);
                    }
                    return false;
                });
                return view;
            }
        };
        adapter.setDropDownViewResource(R.layout.spinner_textview_align);
        spinner_src_languages.setAdapter(adapter);
        spinner_src_languages.setSelection(supported_languages.indexOf("Indonesian"));
    }

    public void setup_dst_spinner_bak(ArrayList<String> supported_languages) {
        Collections.sort(supported_languages);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.spinner_textview_align, supported_languages);
        adapter.setDropDownViewResource(R.layout.spinner_textview_align);
        spinner_dst_languages.setAdapter(adapter);
        spinner_dst_languages.setSelection(supported_languages.indexOf("English"));
    }

    public void setup_dst_spinner(ArrayList<String> supported_languages) {
        Collections.sort(supported_languages);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, R.layout.spinner_textview_align, supported_languages) {
            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                View view = super.getDropDownView(position, convertView, parent);
                view.setOnTouchListener((v, event) -> {
                    if (event.getAction() == MotionEvent.ACTION_DOWN) {
                        AlphaAnimation blink = new AlphaAnimation(1.0f, 0.2f);
                        blink.setDuration(100);
                        blink.setRepeatMode(Animation.REVERSE);
                        blink.setRepeatCount(2);
                        v.startAnimation(blink);
                    }
                    return false;
                });
                return view;
            }
        };

        adapter.setDropDownViewResource(R.layout.spinner_textview_align);
        spinner_dst_languages.setAdapter(adapter);
        spinner_dst_languages.setSelection(supported_languages.indexOf("English"));
    }

    private void start_create_overlay_mic_button() {
        Intent i = new Intent(this, create_overlay_mic_button.class);
        startService(i);
    }

    private void stop_create_overlay_mic_button() {
        stopService(new Intent(this, create_overlay_mic_button.class));
    }

    private void start_create_overlay_translation_text() {
        Intent i = new Intent(this, create_overlay_translation_text.class);
        startService(i);
    }

    private void stop_create_overlay_translation_text() {
        stopService(new Intent(this, create_overlay_translation_text.class));
    }

    private void stop_voice_recognizer() {
        stopService(new Intent(this, AudioCaptureService.class));
    }

    public void setText(final TextView tv, final String text){
        new Handler(Looper.getMainLooper()).post(() -> tv.setText(text));
        //runOnUiThread(() -> tv.setText(text));
    }

    private void requestMediaProjectionForOverlay() {
        MediaProjectionManager projectionManager = (MediaProjectionManager)getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        Intent captureIntent = projectionManager.createScreenCaptureIntent();
        mediaProjectionLauncher.launch(captureIntent);
    }

    private void startAudioCaptureServiceReady(int resultCode, Intent data) {
        Intent serviceIntent = new Intent(this, AudioCaptureService.class);
        serviceIntent.setAction(AudioCaptureService.ACTION_PREPARE_CAPTURE);
        serviceIntent.putExtra(AudioCaptureService.EXTRA_RESULT_CODE, resultCode);
        serviceIntent.putExtra(AudioCaptureService.EXTRA_RESULT_DATA, data);
        startForegroundService(serviceIntent);
    }

    private void stopAudioCaptureService() {
        stopService(new Intent(this, AudioCaptureService.class));
        RECOGNIZING_STATUS.IS_RECOGNIZING = false;
    }

    private int getSpinnerIndexByDialectCode(ArrayList<String> supportedLanguages, String targetDialectCode) {
        if (targetDialectCode == null || targetDialectCode.isEmpty()) return 0;

        if (targetDialectCode.equalsIgnoreCase("zh-CN")) {
            targetDialectCode = "cmn-Hans-CN";
        }

        for (int i = 0; i < supportedLanguages.size(); i++) {
            String displayName = supportedLanguages.get(i);
            String dialectCode = src_countries_dialects.get(displayName); // Menghasilkan "id-ID", "id", atau "en-US"

            if (dialectCode != null) {
                if (dialectCode.equalsIgnoreCase(targetDialectCode)) {
                    return i;
                }
            }
        }
        return 0;
    }

}

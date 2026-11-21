package de.foxdub.app;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.File;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

// Live-Modus: hört den Ton anderer Apps mit (X, YouTube, Instagram …), erkennt Englisch,
// übersetzt und zeigt deutsche Untertitel über der App an. Auf Wunsch spricht er sie auch.
public class LiveService extends Service {

    static volatile boolean running = false;
    static volatile String lastError = null;

    private static final String CH = "foxdub_live";
    private static final int NID = 7;

    private Handler ui;
    private MediaProjection projection;
    private AudioRecord rec;
    private Thread worker;
    private volatile boolean stop = false;

    private WindowManager wm;
    private View overlay;
    private WindowManager.LayoutParams lp;
    private TextView deView, enView;

    private Translator tr;
    private TextToSpeech tts;
    private boolean ttsOk = false;
    private boolean speak, showEn;
    private float rate, textSize;
    private String voice;
    private AudioManager am;
    private AudioFocusRequest focus;
    private final AudioAttributes speechAttr = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
    private final AtomicInteger seq = new AtomicInteger();
    private int shown = 0, speaking = 0;
    private final Runnable clear = () -> { if (deView != null) deView.setText(""); };

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        if (i == null || "stop".equals(i.getAction())) { stopSelf(); return START_NOT_STICKY; }
        if (running) return START_NOT_STICKY;
        ui = new Handler(Looper.getMainLooper());
        speak = i.getBooleanExtra("speak", true);
        showEn = i.getBooleanExtra("showEn", true);
        rate = i.getFloatExtra("rate", 1.1f);
        textSize = i.getFloatExtra("size", 20f);
        voice = i.getStringExtra("voice");
        lastError = null;

        startFg();
        try {
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            Intent data = i.getParcelableExtra("data");
            projection = mpm.getMediaProjection(i.getIntExtra("code", 0), data);
        } catch (Exception e) {
            lastError = "Android hat das Mithören nicht erlaubt: " + e.getMessage();
        }
        if (projection == null) {
            if (lastError == null) lastError = "Android hat das Mithören nicht erlaubt.";
            stopSelf();
            return START_NOT_STICKY;
        }
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { ui.post(LiveService.this::stopSelf); }
        }, ui);
        running = true;

        am = (AudioManager) getSystemService(AUDIO_SERVICE);
        tr = Translation.getClient(new TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.GERMAN).build());
        tr.downloadModelIfNeeded(new DownloadConditions.Builder().build());
        if (speak) initTts();
        showOverlay();
        worker = new Thread(this::loop, "foxdub-live");
        worker.start();
        return START_NOT_STICKY;
    }

    // ------------------------------------------------------------------ Benachrichtigung

    private void startFg() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null) {
            NotificationChannel c = new NotificationChannel(CH, "Live-Übersetzung", NotificationManager.IMPORTANCE_LOW);
            c.setShowBadge(false);
            nm.createNotificationChannel(c);
        }
        int icon = getApplicationInfo().icon;
        PendingIntent stopPi = PendingIntent.getService(this, 1,
                new Intent(this, LiveService.class).setAction("stop"), PendingIntent.FLAG_IMMUTABLE);
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent openPi = open == null ? null : PendingIntent.getActivity(this, 2, open, PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        b.setSmallIcon(icon)
                .setContentTitle("Fox Dub Live")
                .setContentText("Übersetzt den Ton anderer Apps ins Deutsche")
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, icon), "Beenden", stopPi).build());
        if (openPi != null) b.setContentIntent(openPi);
        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION | ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NID, n);
        }
    }

    // ------------------------------------------------------------------ Untertitel-Fenster

    private int dp(float v) { return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics())); }

    @SuppressLint("ClickableViewAccessibility")
    private void showOverlay() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(8), dp(10), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xD9111114);
        bg.setCornerRadius(dp(18));
        box.setBackground(bg);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("Fox Dub · Live");
        title.setTextColor(0xFFFF7A1A);
        title.setTextSize(12);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextColor(Color.WHITE);
        close.setTextSize(18);
        close.setPadding(dp(12), dp(2), dp(4), dp(2));
        close.setContentDescription("Live beenden");
        close.setOnClickListener(v -> stopSelf());
        top.addView(close);
        box.addView(top);

        enView = new TextView(this);
        enView.setTextColor(0xFFA4A4AE);
        enView.setTextSize(13);
        enView.setMaxLines(2);
        enView.setText("Spracherkennung startet …");
        box.addView(enView);

        deView = new TextView(this);
        deView.setTextColor(Color.WHITE);
        deView.setTextSize(textSize);
        deView.setTypeface(Typeface.DEFAULT_BOLD);
        deView.setMaxLines(4);
        deView.setPadding(0, dp(2), 0, 0);
        box.addView(deView);

        FrameLayout wrap = new FrameLayout(this);
        FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        fl.setMargins(dp(10), 0, dp(10), 0);
        wrap.addView(box, fl);

        int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM;
        lp.y = dp(90);

        // Fenster mit dem Finger nach oben oder unten schieben
        final float[] down = new float[2];
        box.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) { down[0] = e.getRawY(); down[1] = lp.y; return true; }
            if (e.getAction() == MotionEvent.ACTION_MOVE) {
                lp.y = Math.max(0, Math.round(down[1] - (e.getRawY() - down[0])));
                try { wm.updateViewLayout(overlay, lp); } catch (Exception ignored) {}
                return true;
            }
            return false;
        });
        overlay = wrap;
        try { wm.addView(overlay, lp); } catch (Exception e) { overlay = null; lastError = "Untertitel-Fenster nicht erlaubt"; }
    }

    private void setEn(String s) { ui.post(() -> { if (enView != null) enView.setText(s); }); }

    // ------------------------------------------------------------------ Stimme

    private void initTts() {
        boolean google = false;
        try { getPackageManager().getPackageInfo("com.google.android.tts", 0); google = true; } catch (Exception ignored) {}
        TextToSpeech.OnInitListener l = st -> {
            ttsOk = st == TextToSpeech.SUCCESS;
            if (!ttsOk) return;
            tts.setLanguage(Locale.GERMANY);
            if (voice != null && !voice.isEmpty()) {
                try {
                    Set<Voice> vs = tts.getVoices();
                    if (vs != null) for (Voice v : vs) if (v.getName().equals(voice)) { tts.setVoice(v); break; }
                } catch (Exception ignored) {}
            }
            tts.setSpeechRate(rate);
            // eigene Stimme als "Assistent" ausgeben, damit sie nicht selbst wieder mitgehört wird
            tts.setAudioAttributes(speechAttr);
        };
        tts = google ? new TextToSpeech(this, l, "com.google.android.tts") : new TextToSpeech(this, l);
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) {}
            @Override public void onDone(String id) { ui.post(LiveService.this::spoke); }
            @Override public void onError(String id) { ui.post(LiveService.this::spoke); }
        });
    }

    private void spoke() {
        speaking = Math.max(0, speaking - 1);
        if (speaking == 0 && focus != null) { am.abandonAudioFocusRequest(focus); focus = null; }
    }

    private void say(String de, int id) {
        if (!ttsOk || tts == null) return;
        int mode = TextToSpeech.QUEUE_ADD;
        if (speaking >= 2) { mode = TextToSpeech.QUEUE_FLUSH; speaking = 0; }   // nicht zu weit hinterherhinken
        if (focus == null && Build.VERSION.SDK_INT >= 26) {
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(speechAttr).build();
            am.requestAudioFocus(focus);   // Video wird leiser, während Deutsch gesprochen wird
        }
        speaking++;
        tts.speak(de, mode, null, "live" + id);
    }

    // ------------------------------------------------------------------ Mithören und erkennen

    @SuppressLint("MissingPermission")
    private void loop() {
        Model model = null;
        Recognizer r = null;
        try {
            model = new Model(new File(getFilesDir(), "vosk-en").getAbsolutePath());
            r = new Recognizer(model, 16000f);
            AudioPlaybackCaptureConfiguration cfg = new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build();
            AudioFormat f = new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build();
            int min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            rec = new AudioRecord.Builder().setAudioFormat(f).setBufferSizeInBytes(Math.max(min * 4, 32000))
                    .setAudioPlaybackCaptureConfig(cfg).build();
            rec.startRecording();
            setEn("Hört zu … Starte jetzt ein Video in X, YouTube & Co.");
            short[] buf = new short[3200];
            long lastLoud = System.currentTimeMillis();
            boolean hinted = false;
            String lastPartial = "";
            while (!stop) {
                int n = rec.read(buf, 0, buf.length);
                if (n <= 0) continue;
                int peak = 0;
                for (int k = 0; k < n; k++) { int a = Math.abs(buf[k]); if (a > peak) peak = a; }
                long now = System.currentTimeMillis();
                if (peak > 300) { lastLoud = now; hinted = false; }
                else if (!hinted && now - lastLoud > 15000) {
                    hinted = true;
                    setEn("Kein Ton zu hören. Läuft ein Video? Manche Apps sperren das Mithören.");
                }
                if (r.acceptWaveForm(buf, n)) {
                    finished(text(r.getResult(), "text"));
                    lastPartial = "";
                } else {
                    String p = text(r.getPartialResult(), "partial");
                    if (!p.equals(lastPartial)) {
                        lastPartial = p;
                        if (showEn && !p.isEmpty()) setEn(p);
                        if (p.split(" ").length >= 20) {          // langer Satz ohne Pause: trotzdem übersetzen
                            finished(text(r.getFinalResult(), "text"));
                            lastPartial = "";
                        }
                    }
                }
            }
        } catch (Throwable e) {
            lastError = e.getMessage();
            setEn("Fehler: " + e.getMessage());
        } finally {
            if (rec != null) { try { rec.stop(); } catch (Exception ignored) {} rec.release(); rec = null; }
            if (r != null) { try { r.close(); } catch (Exception ignored) {} }
            if (model != null) { try { model.close(); } catch (Exception ignored) {} }
        }
    }

    private static String text(String json, String key) {
        try { return new JSONObject(json).optString(key, "").replace("[unk]", "").trim(); } catch (Exception e) { return ""; }
    }

    private static String sentence(String t) {
        t = t.trim().replaceAll("\\bi\\b", "I").replaceAll("\\bi'(m|ve|ll|d)\\b", "I'$1");
        if (t.isEmpty()) return t;
        return Character.toUpperCase(t.charAt(0)) + t.substring(1) + ".";
    }

    private void finished(String en) {
        if (en.length() < 3 || stop) return;
        final int my = seq.incrementAndGet();
        final String s = sentence(en);
        if (showEn) setEn(s);
        ui.post(() -> tr.translate(s).addOnSuccessListener(de -> {
            if (stop || my < shown || de == null) return;
            shown = my;
            if (deView != null) deView.setText(de);
            ui.removeCallbacks(clear);
            ui.postDelayed(clear, 12000);
            if (speak) say(de, my);
        }));
    }

    @Override
    public void onDestroy() {
        stop = true;
        running = false;
        if (worker != null) { try { worker.join(1500); } catch (InterruptedException ignored) {} }
        if (projection != null) { try { projection.stop(); } catch (Exception ignored) {} projection = null; }
        if (overlay != null) { try { wm.removeView(overlay); } catch (Exception ignored) {} overlay = null; }
        if (tts != null) { tts.stop(); tts.shutdown(); tts = null; }
        if (focus != null && am != null) { am.abandonAudioFocusRequest(focus); focus = null; }
        if (tr != null) { tr.close(); tr = null; }
        if (ui != null) ui.removeCallbacksAndMessages(null);
        stopForeground(true);
        super.onDestroy();
    }
}

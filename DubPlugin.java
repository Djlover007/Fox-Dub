package de.foxdub.app;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.media.MediaScannerConnection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.util.Base64;
import android.view.WindowManager;

import androidx.activity.result.ActivityResult;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PermissionState;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.TranslateRemoteModel;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import org.json.JSONArray;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

// Fox Dub: englische Videos automatisch auf Deutsch vertonen, komplett auf dem Handy.
// 1. Ton aus dem Video lesen  2. Englisch erkennen (Vosk, offline)
// 3. Übersetzen (Google ML Kit, offline)  4. Deutsche Stimme (Android-Sprachausgabe)
// 5. Neue Tonspur mischen und mit dem Originalbild zu einer MP4 zusammensetzen.
@CapacitorPlugin(name = "Dub", permissions = {
        @Permission(strings = {Manifest.permission.RECORD_AUDIO}, alias = "mic")
})
public class DubPlugin extends Plugin {

    private static final String MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip";
    private static final String MODEL_DIR = "vosk-en";

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private volatile boolean cancel = false;
    private Model model;
    private Translator translator;

    private TextToSpeech tts;
    private volatile boolean ttsOk = false;
    private CountDownLatch ttsInit;
    private final Map<String, CountDownLatch> ttsWait = new ConcurrentHashMap<>();
    private final Map<String, Boolean> ttsFail = new ConcurrentHashMap<>();

    static class Cancelled extends Exception { Cancelled() { super("Abgebrochen"); } }

    interface PcmSink { void put(short[] buf, int n, int ch, int rate) throws Exception; }

    // ------------------------------------------------------------------ Start

    @Override
    public void load() {
        initTts();
        Activity a = getActivity();
        if (a != null) handleShare(a.getIntent());
    }

    @Override
    protected void handleOnNewIntent(Intent intent) {
        super.handleOnNewIntent(intent);
        handleShare(intent);
    }

    // ------------------------------------------------------------------ "Teilen an Fox Dub"

    private void handleShare(Intent i) {
        if (i == null || !Intent.ACTION_SEND.equals(i.getAction())) return;
        Uri u = i.getParcelableExtra(Intent.EXTRA_STREAM);
        i.setAction(Intent.ACTION_MAIN);          // nur einmal verarbeiten
        if (u == null) {
            JSObject o = new JSObject();
            o.put("error", "Es wurde keine Videodatei geteilt. Bei X oder YouTube wird nur ein Link geteilt – nimm dafür den Live-Modus.");
            notifyListeners("shared", o, true);
            return;
        }
        JSObject s = new JSObject();
        s.put("busy", true);
        notifyListeners("shared", s, true);
        exec.execute(() -> {
            try {
                File f = copyShared(u);
                notifyListeners("shared", videoInfo(Uri.fromFile(f)), true);
            } catch (Exception e) {
                JSObject o = new JSObject();
                o.put("error", "Das geteilte Video konnte nicht übernommen werden: " + msg(e));
                notifyListeners("shared", o, true);
            }
        });
    }

    private File copyShared(Uri u) throws Exception {
        String name = "Geteiltes Video.mp4";
        try (Cursor c = getContext().getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (ni >= 0 && c.getString(ni) != null) name = c.getString(ni);
            }
        } catch (Exception ignored) {}
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        File dir = new File(getContext().getFilesDir(), "shared");
        deleteRec(dir);
        dir.mkdirs();
        File f = new File(dir, name);
        try (InputStream in = getContext().getContentResolver().openInputStream(u); OutputStream out = new FileOutputStream(f)) {
            if (in == null) throw new Exception("Datei nicht lesbar");
            copy(in, out);
        }
        return f;
    }

    private void initTts() {
        ttsInit = new CountDownLatch(1);
        ttsOk = false;
        TextToSpeech.OnInitListener l = status -> { ttsOk = status == TextToSpeech.SUCCESS; ttsInit.countDown(); };
        boolean google = false;
        try { getContext().getPackageManager().getPackageInfo("com.google.android.tts", 0); google = true; } catch (Exception ignored) {}
        tts = google ? new TextToSpeech(getContext(), l, "com.google.android.tts") : new TextToSpeech(getContext(), l);
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) {}
            @Override public void onDone(String id) { done(id, false); }
            @Override public void onError(String id) { done(id, true); }
            @Override public void onError(String id, int code) { done(id, true); }
            private void done(String id, boolean err) {
                if (err) ttsFail.put(id, true);
                CountDownLatch c = ttsWait.remove(id);
                if (c != null) c.countDown();
            }
        });
    }

    private boolean ttsReady() {
        try { ttsInit.await(15, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        return ttsOk;
    }

    private void progress(String phase, double pct, String msg) {
        JSObject o = new JSObject();
        o.put("phase", phase);
        o.put("pct", Math.max(0, Math.min(100, Math.round(pct))));
        o.put("msg", msg);
        notifyListeners("progress", o);
    }

    private void awake(boolean on) {
        Activity a = getActivity();
        if (a == null) return;
        a.runOnUiThread(() -> {
            if (on) a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        });
    }

    private static String msg(Throwable e) {
        if (e instanceof Cancelled) return "Abgebrochen";
        Throwable c = e;
        while (c.getCause() != null && (c.getMessage() == null || c.getMessage().isEmpty())) c = c.getCause();
        String m = c.getMessage();
        return m == null || m.isEmpty() ? c.getClass().getSimpleName() : m;
    }

    @PluginMethod
    public void cancel(PluginCall call) { cancel = true; call.resolve(); }

    // ------------------------------------------------------------------ Video wählen

    @PluginMethod
    public void pickVideo(PluginCall call) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("video/*");
        startActivityForResult(call, i, "pickResult");
    }

    @ActivityCallback
    private void pickResult(PluginCall call, ActivityResult result) {
        if (call == null) return;
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null || result.getData().getData() == null) {
            call.reject("Abgebrochen", "CANCEL");
            return;
        }
        Uri uri = result.getData().getData();
        try { getContext().getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
        exec.execute(() -> {
            try { call.resolve(videoInfo(uri)); } catch (Exception e) { call.reject(msg(e)); }
        });
    }

    @PluginMethod
    public void info(PluginCall call) {
        String u = call.getString("uri");
        if (u == null) { call.reject("uri fehlt"); return; }
        exec.execute(() -> {
            try { call.resolve(videoInfo(Uri.parse(u))); } catch (Exception e) { call.reject("Das Video ist nicht mehr erreichbar: " + msg(e)); }
        });
    }

    private JSObject videoInfo(Uri uri) throws Exception {
        JSObject o = new JSObject();
        o.put("uri", uri.toString());
        String name = "Video";
        long size = 0;
        try (Cursor c = getContext().getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int si = c.getColumnIndex(OpenableColumns.SIZE);
                if (ni >= 0 && c.getString(ni) != null) name = c.getString(ni);
                if (si >= 0) size = c.getLong(si);
            }
        }
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
            File f = new File(uri.getPath());
            name = f.getName();
            size = f.length();
        }
        o.put("name", name);
        o.put("size", size);
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(getContext(), uri);
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            o.put("duration", d == null ? 0 : Long.parseLong(d) / 1000.0);
            o.put("hasAudio", "yes".equals(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)));
            Bitmap b = r.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (b != null) {
                int w = 360, h = Math.max(1, Math.round(b.getHeight() * 360f / b.getWidth()));
                Bitmap s = Bitmap.createScaledBitmap(b, w, h, true);
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                s.compress(Bitmap.CompressFormat.JPEG, 72, bo);
                o.put("thumb", "data:image/jpeg;base64," + Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP));
            }
        } finally {
            try { r.release(); } catch (Exception ignored) {}
        }
        return o;
    }

    // ------------------------------------------------------------------ Sprachpakete

    private File modelDir() { return new File(getContext().getFilesDir(), MODEL_DIR); }
    private boolean sttReady() { return new File(modelDir(), "fox-ok").exists(); }

    private Translator translator() {
        if (translator == null) {
            TranslatorOptions o = new TranslatorOptions.Builder()
                    .setSourceLanguage(TranslateLanguage.ENGLISH)
                    .setTargetLanguage(TranslateLanguage.GERMAN).build();
            translator = Translation.getClient(o);
        }
        return translator;
    }

    @PluginMethod
    public void status(PluginCall call) {
        exec.execute(() -> {
            JSObject o = new JSObject();
            o.put("stt", sttReady());
            boolean tr = false;
            try {
                tr = Tasks.await(RemoteModelManager.getInstance().isModelDownloaded(
                        new TranslateRemoteModel.Builder(TranslateLanguage.GERMAN).build()), 20, TimeUnit.SECONDS);
            } catch (Exception ignored) {}
            o.put("translate", tr);
            o.put("tts", ttsReady());
            call.resolve(o);
        });
    }

    @PluginMethod
    public void downloadModels(PluginCall call) {
        cancel = false;
        exec.execute(() -> {
            awake(true);
            try {
                ensureStt();
                progress("model", 96, "Übersetzer wird geladen …");
                Tasks.await(translator().downloadModelIfNeeded(new DownloadConditions.Builder().build()), 15, TimeUnit.MINUTES);
                progress("model", 100, "Fertig");
                call.resolve();
            } catch (Exception e) {
                call.reject(msg(e));
            } finally { awake(false); }
        });
    }

    @PluginMethod
    public void deleteModels(PluginCall call) {
        exec.execute(() -> {
            if (model != null) { try { model.close(); } catch (Exception ignored) {} model = null; }
            deleteRec(modelDir());
            try {
                Tasks.await(RemoteModelManager.getInstance().deleteDownloadedModel(
                        new TranslateRemoteModel.Builder(TranslateLanguage.GERMAN).build()), 30, TimeUnit.SECONDS);
            } catch (Exception ignored) {}
            call.resolve();
        });
    }

    private static void deleteRec(File f) {
        if (f == null || !f.exists()) return;
        File[] l = f.listFiles();
        if (l != null) for (File c : l) deleteRec(c);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private void ensureStt() throws Exception {
        if (sttReady()) return;
        File zip = new File(getContext().getCacheDir(), "vosk.zip");
        HttpURLConnection c = (HttpURLConnection) new URL(MODEL_URL).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "FoxDub");
        int code = c.getResponseCode();
        if (code != 200) throw new Exception("Download der Spracherkennung fehlgeschlagen (HTTP " + code + ")");
        long total = c.getContentLength();
        try (InputStream in = new BufferedInputStream(c.getInputStream()); OutputStream out = new FileOutputStream(zip)) {
            byte[] b = new byte[65536];
            long got = 0; int n, lastP = -1;
            while ((n = in.read(b)) > 0) {
                if (cancel) throw new Cancelled();
                out.write(b, 0, n);
                got += n;
                int p = total > 0 ? (int) (got * 85 / total) : 0;
                if (p != lastP) { lastP = p; progress("model", p, "Spracherkennung wird geladen … " + (got >> 20) + " MB"); }
            }
        } finally { c.disconnect(); }

        progress("model", 88, "Wird entpackt …");
        File tmp = new File(getContext().getFilesDir(), MODEL_DIR + ".tmp");
        deleteRec(tmp);
        tmp.mkdirs();
        String base = tmp.getCanonicalPath() + File.separator;
        try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
            ZipEntry e;
            byte[] b = new byte[65536];
            while ((e = zin.getNextEntry()) != null) {
                String n = e.getName();
                int s = n.indexOf('/');
                if (s < 0) continue;                 // oberste Ordner-Ebene abschneiden
                n = n.substring(s + 1);
                if (n.isEmpty()) continue;
                File f = new File(tmp, n);
                if (!f.getCanonicalPath().startsWith(base)) continue;
                if (e.isDirectory()) { f.mkdirs(); continue; }
                f.getParentFile().mkdirs();
                try (OutputStream o = new FileOutputStream(f)) {
                    int r;
                    while ((r = zin.read(b)) > 0) o.write(b, 0, r);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        zip.delete();
        if (!new File(tmp, "am").exists() && !new File(tmp, "conf").exists())
            throw new Exception("Das Sprachpaket ist unvollständig. Bitte nochmal laden.");
        deleteRec(modelDir());
        if (!tmp.renameTo(modelDir())) throw new Exception("Sprachpaket konnte nicht gespeichert werden");
        new FileOutputStream(new File(modelDir(), "fox-ok")).close();
    }

    // ------------------------------------------------------------------ Ton dekodieren

    private long decodeAudio(Uri uri, PcmSink sink, String phase, double p0, double p1, String text) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec dec = null;
        try {
            ex.setDataSource(getContext(), uri, null);
            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String m = f.getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("audio/")) { track = i; fmt = f; break; }
            }
            if (track < 0) throw new Exception("Das Video hat keine Tonspur.");
            ex.selectTrack(track);
            long durUs = fmt.containsKey(MediaFormat.KEY_DURATION) ? fmt.getLong(MediaFormat.KEY_DURATION) : 0;
            String mime = fmt.getString(MediaFormat.KEY_MIME);
            dec = MediaCodec.createDecoderByType(mime);
            dec.configure(fmt, null, null, 0);
            dec.start();
            int ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            int rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            boolean isFloat = false;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inEos = false, outEos = false;
            short[] sb = new short[8192];
            int lastP = -1;
            long frames = 0;
            while (!outEos) {
                if (cancel) throw new Cancelled();
                if (!inEos) {
                    int ii = dec.dequeueInputBuffer(10000);
                    if (ii >= 0) {
                        ByteBuffer b = dec.getInputBuffer(ii);
                        int sz = ex.readSampleData(b, 0);
                        if (sz < 0) {
                            dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inEos = true;
                        } else {
                            dec.queueInputBuffer(ii, 0, sz, Math.max(0, ex.getSampleTime()), 0);
                            ex.advance();
                        }
                    }
                }
                int oi = dec.dequeueOutputBuffer(info, 10000);
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = dec.getOutputFormat();
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    if (Build.VERSION.SDK_INT >= 24 && of.containsKey(MediaFormat.KEY_PCM_ENCODING))
                        isFloat = of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT;
                } else if (oi >= 0) {
                    ByteBuffer ob = dec.getOutputBuffer(oi);
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        ByteBuffer o = ob.slice().order(ByteOrder.nativeOrder());
                        int n = isFloat ? info.size / 4 : info.size / 2;
                        if (sb.length < n) sb = new short[n];
                        if (isFloat) {
                            for (int k = 0; k < n; k++) {
                                float v = o.getFloat(k * 4);
                                sb[k] = (short) Math.max(-32768, Math.min(32767, Math.round(v * 32767f)));
                            }
                        } else {
                            o.asShortBuffer().get(sb, 0, n);
                        }
                        sink.put(sb, n, ch, rate);
                        frames += n / Math.max(1, ch);
                        if (durUs > 0) {
                            int p = (int) (p0 + (p1 - p0) * Math.min(1.0, info.presentationTimeUs / (double) durUs));
                            if (p != lastP) { lastP = p; progress(phase, p, text); }
                        }
                    }
                    dec.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outEos = true;
                }
            }
            return frames;
        } finally {
            if (dec != null) { try { dec.stop(); } catch (Exception ignored) {} try { dec.release(); } catch (Exception ignored) {} }
            ex.release();
        }
    }

    // Einfacher, durchgehender Resampler (lineare Interpolation), mono.
    static class Resampler {
        final double step;
        double pos = 1.0;
        short last = 0;
        short[] out = new short[4096];
        Resampler(int inRate, int outRate) { step = inRate / (double) outRate; }
        int process(short[] in, int n) {
            if (n <= 0) return 0;
            int need = (int) Math.ceil(n / step) + 4;
            if (out.length < need) out = new short[need];
            int c = 0;
            double p = pos;
            while (p < n) {               // virtuelles Array: [last, in0 .. in(n-1)]
                int i = (int) p;
                double f = p - i;
                double a = i == 0 ? last : in[i - 1];
                double b = in[i];
                out[c++] = (short) Math.round(a + (b - a) * f);
                p += step;
            }
            pos = p - n;
            last = in[n - 1];
            return c;
        }
        static short[] whole(short[] in, int inRate, int outRate) {
            if (inRate == outRate) return in;
            Resampler r = new Resampler(inRate, outRate);
            int c = r.process(in, in.length);
            short[] o = new short[c];
            System.arraycopy(r.out, 0, o, 0, c);
            return o;
        }
    }

    // ------------------------------------------------------------------ 1+2+3: Erkennen und übersetzen

    static class Word { double s, e; String w; Word(double s, double e, String w) { this.s = s; this.e = e; this.w = w; } }

    @PluginMethod
    public void transcribe(PluginCall call) {
        String u = call.getString("uri");
        if (u == null) { call.reject("uri fehlt"); return; }
        cancel = false;
        exec.execute(() -> {
            awake(true);
            Recognizer rec = null;
            try {
                if (!sttReady()) ensureStt();
                progress("stt", 1, "Spracherkennung startet …");
                if (model == null) model = new Model(modelDir().getAbsolutePath());
                final Recognizer r = new Recognizer(model, 16000f);
                rec = r;
                r.setWords(true);
                final List<Word> words = new ArrayList<>();
                final Resampler[] rs = new Resampler[1];
                final short[][] mono = new short[1][4096];
                decodeAudio(Uri.parse(u), (buf, n, ch, rate) -> {
                    int fr = n / Math.max(1, ch);
                    if (mono[0].length < fr) mono[0] = new short[fr];
                    short[] m = mono[0];
                    for (int i = 0; i < fr; i++) {
                        int s = 0;
                        for (int c = 0; c < ch; c++) s += buf[i * ch + c];
                        m[i] = (short) (s / ch);
                    }
                    if (rs[0] == null) rs[0] = new Resampler(rate, 16000);
                    int c = rs[0].process(m, fr);
                    if (c > 0 && r.acceptWaveForm(rs[0].out, c)) readWords(r.getResult(), words);
                }, "stt", 2, 80, "Englisch wird erkannt …");
                readWords(r.getFinalResult(), words);
                if (words.isEmpty()) throw new Exception("Im Video wurde keine englische Sprache erkannt.");

                List<double[]> spans = new ArrayList<>();
                List<String> texts = new ArrayList<>();
                segment(words, spans, texts);

                progress("tr", 81, "Übersetzer startet …");
                Translator t = translator();
                Tasks.await(t.downloadModelIfNeeded(new DownloadConditions.Builder().build()), 15, TimeUnit.MINUTES);
                JSArray out = new JSArray();
                for (int i = 0; i < texts.size(); i++) {
                    if (cancel) throw new Cancelled();
                    String en = sentence(texts.get(i));
                    String de = Tasks.await(t.translate(en), 60, TimeUnit.SECONDS);
                    JSObject o = new JSObject();
                    o.put("s", Math.round(spans.get(i)[0] * 100) / 100.0);
                    o.put("e", Math.round(spans.get(i)[1] * 100) / 100.0);
                    o.put("en", en);
                    o.put("de", de == null ? "" : de.trim());
                    out.put(o);
                    progress("tr", 81 + 19.0 * (i + 1) / texts.size(), "Übersetze Satz " + (i + 1) + " von " + texts.size() + " …");
                }
                JSObject res = new JSObject();
                res.put("segments", out);
                call.resolve(res);
            } catch (Throwable e) {
                call.reject(msg(e));
            } finally {
                if (rec != null) { try { rec.close(); } catch (Exception ignored) {} }
                awake(false);
            }
        });
    }

    private static void readWords(String json, List<Word> words) {
        try {
            JSONArray a = new JSONObject(json).optJSONArray("result");
            if (a == null) return;
            for (int i = 0; i < a.length(); i++) {
                JSONObject w = a.getJSONObject(i);
                String t = w.optString("word", "");
                if (t.isEmpty() || t.equals("[unk]")) continue;
                words.add(new Word(w.optDouble("start"), w.optDouble("end"), t));
            }
        } catch (Exception ignored) {}
    }

    // Wörter zu Sätzen gruppieren: an Pausen trennen, lange Stücke kürzen.
    private static void segment(List<Word> words, List<double[]> spans, List<String> texts) {
        StringBuilder sb = new StringBuilder();
        double s = -1, e = 0;
        for (int i = 0; i < words.size(); i++) {
            Word w = words.get(i);
            if (s >= 0) {
                double gap = w.s - e, len = e - s;
                if (gap > 0.6 || (len > 6 && gap > 0.25) || len > 11) {
                    spans.add(new double[]{s, e});
                    texts.add(sb.toString());
                    sb.setLength(0);
                    s = -1;
                }
            }
            if (s < 0) s = w.s;
            if (sb.length() > 0) sb.append(' ');
            sb.append(w.w);
            e = w.e;
        }
        if (s >= 0) { spans.add(new double[]{s, e}); texts.add(sb.toString()); }
    }

    private static String sentence(String t) {
        t = t.trim().replaceAll("\\bi\\b", "I").replaceAll("\\bi'(m|ve|ll|d)\\b", "I'$1");
        if (t.isEmpty()) return t;
        return Character.toUpperCase(t.charAt(0)) + t.substring(1) + ".";
    }

    // ------------------------------------------------------------------ Stimmen

    @PluginMethod
    public void voices(PluginCall call) {
        exec.execute(() -> {
            JSArray a = new JSArray();
            if (ttsReady()) {
                try {
                    Set<Voice> vs = tts.getVoices();
                    if (vs != null) {
                        List<Voice> l = new ArrayList<>(vs);
                        Collections.sort(l, (x, y) -> x.getName().compareTo(y.getName()));
                        for (Voice v : l) {
                            Locale lo = v.getLocale();
                            if (lo == null || !"de".equals(lo.getLanguage())) continue;
                            boolean missing = v.getFeatures() != null && v.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED);
                            JSObject o = new JSObject();
                            o.put("name", v.getName());
                            o.put("country", lo.getCountry());
                            o.put("online", v.isNetworkConnectionRequired());
                            o.put("missing", missing);
                            o.put("quality", v.getQuality());
                            a.put(o);
                        }
                    }
                } catch (Exception ignored) {}
            }
            JSObject r = new JSObject();
            r.put("ok", ttsOk);
            r.put("voices", a);
            call.resolve(r);
        });
    }

    private void applyVoice(String name) {
        tts.setLanguage(Locale.GERMANY);
        if (name == null || name.isEmpty()) return;
        try {
            Set<Voice> vs = tts.getVoices();
            if (vs != null) for (Voice v : vs) if (v.getName().equals(name)) { tts.setVoice(v); return; }
        } catch (Exception ignored) {}
    }

    @PluginMethod
    public void preview(PluginCall call) {
        String text = call.getString("text", "Hallo, ich bin dein Fox Dub.");
        String voice = call.getString("voice", "");
        float rate = call.getFloat("rate", 1.0f);
        exec.execute(() -> {
            if (!ttsReady()) { call.reject("Die Sprachausgabe des Handys startet nicht."); return; }
            applyVoice(voice);
            tts.setSpeechRate(rate);
            tts.setPitch(1.0f);
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "pv" + System.nanoTime());
            call.resolve();
        });
    }

    @PluginMethod
    public void stopPreview(PluginCall call) { if (tts != null) tts.stop(); call.resolve(); }

    @PluginMethod
    public void ttsSettings(PluginCall call) {
        try {
            Intent i = new Intent("com.android.settings.TTS_SETTINGS");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            call.resolve();
        } catch (Exception e) { call.reject(msg(e)); }
    }

    static class Pcm { short[] data; int rate; Pcm(short[] d, int r) { data = d; rate = r; } }

    private Pcm synth(String text, float rate, String id) throws Exception {
        File f = new File(getContext().getCacheDir(), "tts_" + id + ".wav");
        //noinspection ResultOfMethodCallIgnored
        f.delete();
        for (int attempt = 0; attempt < 2; attempt++) {
            CountDownLatch l = new CountDownLatch(1);
            String uid = id + "_" + attempt + "_" + System.nanoTime();
            ttsWait.put(uid, l);
            ttsFail.remove(uid);
            tts.setSpeechRate(rate);
            Bundle b = new Bundle();
            int r = tts.synthesizeToFile(text, b, f, uid);
            if (r == TextToSpeech.SUCCESS && l.await(60, TimeUnit.SECONDS) && !ttsFail.containsKey(uid) && f.length() > 44) {
                Pcm p = readWav(f);
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                return trim(p);
            }
            ttsWait.remove(uid);
            Thread.sleep(400);
        }
        throw new Exception("Die deutsche Stimme hat nicht geantwortet. Prüf in den Einstellungen, ob eine deutsche Stimme installiert ist.");
    }

    private static int le16(byte[] b, int o) { return (b[o] & 0xff) | (b[o + 1] << 8); }
    private static int le32(byte[] b, int o) { return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8) | ((b[o + 2] & 0xff) << 16) | ((b[o + 3] & 0xff) << 24); }

    private static Pcm readWav(File f) throws Exception {
        byte[] all = new byte[(int) f.length()];
        try (DataInputStream in = new DataInputStream(new FileInputStream(f))) { in.readFully(all); }
        if (all.length < 12 || all[0] != 'R' || all[1] != 'I' || all[8] != 'W') throw new Exception("Stimme lieferte kein WAV");
        int pos = 12, ch = 1, rate = 22050, bits = 16, dataOff = -1, dataLen = 0;
        while (pos + 8 <= all.length) {
            String id = new String(all, pos, 4, "US-ASCII");
            int len = le32(all, pos + 4);
            if (id.equals("fmt ")) {
                ch = le16(all, pos + 10);
                rate = le32(all, pos + 12);
                bits = le16(all, pos + 22);
            } else if (id.equals("data")) {
                dataOff = pos + 8;
                dataLen = (len <= 0 || dataOff + len > all.length) ? all.length - dataOff : len;
                break;
            }
            if (len < 0) break;
            pos += 8 + len + (len & 1);
        }
        if (dataOff < 0) throw new Exception("Stimme lieferte keine Tondaten");
        if (bits != 16) throw new Exception("Stimme liefert " + bits + "-Bit-Ton, erwartet 16 Bit");
        ch = Math.max(1, ch);
        int frames = dataLen / 2 / ch;
        short[] d = new short[frames];
        ByteBuffer bb = ByteBuffer.wrap(all, dataOff, frames * 2 * ch).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < frames; i++) {
            int s = 0;
            for (int c = 0; c < ch; c++) s += bb.getShort();
            d[i] = (short) (s / ch);
        }
        return new Pcm(d, rate);
    }

    // Stille am Anfang und Ende abschneiden, damit der Satz genau auf seinen Platz passt.
    private static Pcm trim(Pcm p) {
        short[] d = p.data;
        int a = 0, b = d.length - 1, th = 350;
        while (a < d.length && Math.abs(d[a]) < th) a++;
        while (b > a && Math.abs(d[b]) < th) b--;
        if (a >= b) return new Pcm(new short[0], p.rate);
        int pad = p.rate / 25;
        a = Math.max(0, a - pad);
        b = Math.min(d.length - 1, b + pad);
        short[] o = new short[b - a + 1];
        System.arraycopy(d, a, o, 0, o.length);
        return new Pcm(o, p.rate);
    }

    // ------------------------------------------------------------------ 4+5: Vertonen und Video bauen

    static class Clip { long start; short[] pcm; Clip(long s, short[] p) { start = s; pcm = p; } }

    @PluginMethod
    public void render(PluginCall call) {
        String u = call.getString("uri");
        JSArray segs = call.getArray("segments");
        if (u == null || segs == null) { call.reject("Daten fehlen"); return; }
        String voice = call.getString("voice", "");
        float baseRate = call.getFloat("rate", 1.1f);
        float bg = call.getFloat("bg", 0.15f);
        float dubVol = call.getFloat("vol", 1.0f);
        String title = call.getString("name", "Video");
        cancel = false;
        exec.execute(() -> {
            awake(true);
            File pcmFile = new File(getContext().getCacheDir(), "orig.pcm");
            File aacFile = new File(getContext().getCacheDir(), "dub.aac.raw");
            File outFile = new File(getContext().getCacheDir(), "foxdub_out.mp4");
            try {
                if (!ttsReady()) throw new Exception("Die Sprachausgabe des Handys startet nicht.");
                Uri uri = Uri.parse(u);

                // Originalton als rohe Daten zwischenspeichern
                final int[] fmt = {0, 0};   // Kanäle, Rate
                final long[] frames = {0};
                try (OutputStream os = new BufferedOutputStream(new FileOutputStream(pcmFile), 1 << 16)) {
                    final byte[][] tmp = {new byte[16384]};
                    decodeAudio(uri, (buf, n, ch, rate) -> {
                        if (fmt[0] == 0) { fmt[0] = Math.min(2, ch); fmt[1] = rate; }
                        int oc = fmt[0];
                        int fr = n / Math.max(1, ch);
                        int bytes = fr * oc * 2;
                        if (tmp[0].length < bytes) tmp[0] = new byte[bytes];
                        byte[] t = tmp[0];
                        int k = 0;
                        for (int i = 0; i < fr; i++) {
                            for (int c = 0; c < oc; c++) {
                                short s = buf[i * ch + Math.min(c, ch - 1)];
                                t[k++] = (byte) s;
                                t[k++] = (byte) (s >> 8);
                            }
                        }
                        os.write(t, 0, bytes);
                        frames[0] += fr;
                    }, "dub", 0, 12, "Originalton wird gelesen …");
                }
                int ch = fmt[0], rate = fmt[1];
                if (ch == 0 || frames[0] == 0) throw new Exception("Der Originalton ist leer.");
                if (rate > 48000) throw new Exception("Die Tonspur hat " + rate + " Hz. Unterstützt werden bis 48000 Hz.");
                long total = frames[0];

                // Deutsche Sätze sprechen und auf die Zeitachse legen
                applyVoice(voice);
                tts.setPitch(1.0f);
                List<Clip> clips = new ArrayList<>();
                long prevEnd = 0;
                int n = segs.length();
                for (int i = 0; i < n; i++) {
                    if (cancel) throw new Cancelled();
                    JSONObject sg = segs.getJSONObject(i);
                    String de = sg.optString("de", "").trim();
                    progress("dub", 12 + 60.0 * i / Math.max(1, n), "Spreche Satz " + (i + 1) + " von " + n + " …");
                    if (de.isEmpty() || sg.optBoolean("off", false)) continue;
                    double s = sg.optDouble("s", 0);
                    double next = total / (double) rate;
                    for (int j = i + 1; j < n; j++) {
                        JSONObject nx = segs.getJSONObject(j);
                        if (!nx.optString("de", "").trim().isEmpty() && !nx.optBoolean("off", false)) { next = nx.optDouble("s", next); break; }
                    }
                    double slot = Math.max(0.6, next - s - 0.12);
                    float r = baseRate;
                    Pcm p = synth(de, r, "s" + i);
                    double dur = p.data.length / (double) p.rate;
                    if (dur > slot) {
                        float r2 = (float) Math.min(1.9, r * dur / slot * 1.04);
                        if (r2 > r + 0.04) {
                            p = synth(de, r2, "s" + i);
                        }
                    }
                    short[] pcm = Resampler.whole(p.data, p.rate, rate);
                    long st = Math.max(Math.round(s * rate), prevEnd + Math.round(0.06 * rate));
                    if (st >= total) continue;
                    clips.add(new Clip(st, pcm));
                    prevEnd = st + pcm.length;
                }

                // Mischen und als AAC kodieren
                progress("dub", 72, "Neue Tonspur wird gemischt …");
                List<long[]> aIndex = new ArrayList<>();   // pts, size, flags
                MediaFormat aFmt = encodeMix(pcmFile, aacFile, ch, rate, total, clips, bg, dubVol, aIndex);
                //noinspection ResultOfMethodCallIgnored
                pcmFile.delete();

                progress("dub", 92, "Video wird zusammengesetzt …");
                mux(uri, aacFile, aFmt, aIndex, outFile);
                //noinspection ResultOfMethodCallIgnored
                aacFile.delete();

                progress("dub", 97, "In der Galerie speichern …");
                String base = title.replaceAll("\\.[A-Za-z0-9]{2,4}$", "").replaceAll("[^\\p{L}\\p{N} _.-]", "_").trim();
                if (base.isEmpty()) base = "Video";
                String fileName = base + " (Deutsch) " + System.currentTimeMillis() / 1000 % 100000 + ".mp4";
                String saved = saveToGallery(outFile, fileName);
                //noinspection ResultOfMethodCallIgnored
                outFile.delete();
                progress("dub", 100, "Fertig");
                JSObject res = new JSObject();
                res.put("uri", saved);
                res.put("name", fileName);
                call.resolve(res);
            } catch (Throwable e) {
                call.reject(msg(e));
            } finally {
                pcmFile.delete();
                aacFile.delete();
                outFile.delete();
                awake(false);
            }
        });
    }

    private MediaFormat encodeMix(File pcmFile, File aacFile, int ch, int rate, long total, List<Clip> clips,
                                  float bg, float dubVol, List<long[]> index) throws Exception {
        MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, ch);
        f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        f.setInteger(MediaFormat.KEY_BIT_RATE, ch == 1 ? 96000 : 160000);
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 << 16);
        MediaCodec enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        enc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        enc.start();
        MediaFormat outFmt = null;
        int ci = 0;
        long frame = 0;
        final int CHUNK = 2048;   // Frames pro Block
        byte[] raw = new byte[CHUNK * ch * 2];
        byte[] mixed = new byte[CHUNK * ch * 2];
        try (InputStream in = new BufferedInputStream(new FileInputStream(pcmFile), 1 << 16);
             OutputStream out = new BufferedOutputStream(new FileOutputStream(aacFile), 1 << 16)) {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inEos = false, outEos = false;
            int lastP = -1;
            byte[] ob = new byte[8192];
            while (!outEos) {
                if (cancel) throw new Cancelled();
                if (!inEos) {
                    int ii = enc.dequeueInputBuffer(10000);
                    if (ii >= 0) {
                        ByteBuffer b = enc.getInputBuffer(ii);
                        b.clear();
                        int maxFrames = Math.min(CHUNK, b.capacity() / (ch * 2));
                        int want = maxFrames * ch * 2, got = 0;
                        while (got < want) {
                            int r = in.read(raw, got, want - got);
                            if (r < 0) break;
                            got += r;
                        }
                        int fr = got / (ch * 2);
                        if (fr <= 0) {
                            enc.queueInputBuffer(ii, 0, 0, frame * 1_000_000L / rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inEos = true;
                        } else {
                            int k = 0;
                            for (int i = 0; i < fr; i++) {
                                long t = frame + i;
                                while (ci < clips.size() && clips.get(ci).start + clips.get(ci).pcm.length <= t) ci++;
                                float d = 0;
                                if (ci < clips.size()) {
                                    Clip c = clips.get(ci);
                                    if (t >= c.start) d = c.pcm[(int) (t - c.start)] * dubVol;
                                }
                                for (int c = 0; c < ch; c++) {
                                    short o = (short) ((raw[k] & 0xff) | (raw[k + 1] << 8));
                                    float v = o * bg + d;
                                    int s = Math.max(-32768, Math.min(32767, Math.round(v)));
                                    mixed[k] = (byte) s;
                                    mixed[k + 1] = (byte) (s >> 8);
                                    k += 2;
                                }
                            }
                            b.put(mixed, 0, fr * ch * 2);
                            enc.queueInputBuffer(ii, 0, fr * ch * 2, frame * 1_000_000L / rate, 0);
                            frame += fr;
                            int p = (int) (72 + 20.0 * frame / Math.max(1, total));
                            if (p != lastP) { lastP = p; progress("dub", p, "Neue Tonspur wird gemischt …"); }
                        }
                    }
                }
                int oi = enc.dequeueOutputBuffer(info, 10000);
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outFmt = enc.getOutputFormat();
                } else if (oi >= 0) {
                    ByteBuffer b = enc.getOutputBuffer(oi);
                    boolean cfg = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                    if (b != null && info.size > 0 && !cfg) {
                        if (ob.length < info.size) ob = new byte[info.size];
                        b.position(info.offset);
                        b.limit(info.offset + info.size);
                        b.get(ob, 0, info.size);
                        out.write(ob, 0, info.size);
                        index.add(new long[]{info.presentationTimeUs, info.size, info.flags & ~MediaCodec.BUFFER_FLAG_END_OF_STREAM});
                    }
                    enc.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outEos = true;
                }
            }
        } finally {
            try { enc.stop(); } catch (Exception ignored) {}
            enc.release();
        }
        if (outFmt == null) throw new Exception("Der Ton-Encoder hat kein Format geliefert.");
        return outFmt;
    }

    private void mux(Uri uri, File aacFile, MediaFormat aFmt, List<long[]> aIndex, File outFile) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        MediaMuxer mx = null;
        boolean started = false;
        try {
            ex.setDataSource(getContext(), uri, null);
            int vt = -1;
            MediaFormat vf = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String m = f.getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("video/")) { vt = i; vf = f; break; }
            }
            if (vt < 0) throw new Exception("Das Video hat keine Bildspur.");
            ex.selectTrack(vt);
            mx = new MediaMuxer(outFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int rot = 0;
            if (vf.containsKey("rotation-degrees")) rot = vf.getInteger("rotation-degrees");
            else {
                MediaMetadataRetriever r = new MediaMetadataRetriever();
                try {
                    r.setDataSource(getContext(), uri);
                    String s = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
                    if (s != null) rot = Integer.parseInt(s);
                } catch (Exception ignored) {} finally { try { r.release(); } catch (Exception ignored) {} }
            }
            if (rot != 0) mx.setOrientationHint(rot);
            int vOut;
            try { vOut = mx.addTrack(vf); }
            catch (Exception e) { throw new Exception("Dieses Videoformat (" + vf.getString(MediaFormat.KEY_MIME) + ") kann nicht als MP4 gespeichert werden."); }
            int aOut = mx.addTrack(aFmt);
            mx.start();
            started = true;

            int max = vf.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE) ? vf.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) : 0;
            ByteBuffer vb = ByteBuffer.allocateDirect(Math.max(max, 4 << 20));
            ByteBuffer ab = ByteBuffer.allocateDirect(1 << 16);
            MediaCodec.BufferInfo vi = new MediaCodec.BufferInfo(), ai = new MediaCodec.BufferInfo();
            int ap = 0;
            boolean vDone = false;
            long vTime = 0;
            int vSize = -1;
            try (RandomAccessFile af = new RandomAccessFile(aacFile, "r")) {
                byte[] tmp = new byte[1 << 16];
                while (true) {
                    if (cancel) throw new Cancelled();
                    if (!vDone && vSize < 0) {
                        vb.clear();
                        vSize = ex.readSampleData(vb, 0);
                        if (vSize < 0) vDone = true;
                        else vTime = ex.getSampleTime();
                    }
                    boolean aLeft = ap < aIndex.size();
                    if (vDone && !aLeft) break;
                    boolean takeVideo = !vDone && (!aLeft || vTime <= aIndex.get(ap)[0]);
                    if (takeVideo) {
                        int fl = (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                        vi.set(0, vSize, Math.max(0, vTime), fl);
                        mx.writeSampleData(vOut, vb, vi);
                        ex.advance();
                        vSize = -1;
                    } else {
                        long[] e = aIndex.get(ap++);
                        int sz = (int) e[1];
                        if (tmp.length < sz) tmp = new byte[sz];
                        af.readFully(tmp, 0, sz);
                        ab.clear();
                        if (ab.capacity() < sz) ab = ByteBuffer.allocateDirect(sz);
                        ab.put(tmp, 0, sz);
                        ab.flip();
                        ai.set(0, sz, e[0], (int) e[2]);
                        mx.writeSampleData(aOut, ab, ai);
                    }
                }
            } catch (EOFException e) {
                throw new Exception("Tonspur unvollständig");
            }
        } finally {
            if (mx != null) {
                try { if (started) mx.stop(); } catch (Exception ignored) {}
                try { mx.release(); } catch (Exception ignored) {}
            }
            ex.release();
        }
    }

    private String saveToGallery(File src, String name) throws Exception {
        Context ctx = getContext();
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver cr = ctx.getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.Video.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            v.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Fox Dub");
            v.put(MediaStore.Video.Media.IS_PENDING, 1);
            Uri dst = cr.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
            if (dst == null) throw new Exception("Galerie nicht erreichbar");
            try (InputStream in = new FileInputStream(src); OutputStream out = cr.openOutputStream(dst)) {
                if (out == null) throw new Exception("Galerie nicht beschreibbar");
                copy(in, out);
            }
            v.clear();
            v.put(MediaStore.Video.Media.IS_PENDING, 0);
            cr.update(dst, v, null, null);
            return dst.toString();
        } else {
            File dir = new File(ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "Fox Dub");
            dir.mkdirs();
            File dst = new File(dir, name);
            try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) { copy(in, out); }
            MediaScannerConnection.scanFile(ctx, new String[]{dst.getAbsolutePath()}, new String[]{"video/mp4"}, null);
            return FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".foxupdate", dst).toString();
        }
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] b = new byte[1 << 16];
        int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
    }

    // ------------------------------------------------------------------ Live-Modus

    @PluginMethod
    public void liveStart(PluginCall call) {
        if (Build.VERSION.SDK_INT < 29) { call.reject("Der Live-Modus braucht Android 10 oder neuer.", "OLD"); return; }
        if (!sttReady()) { call.reject("Lade zuerst die Sprachpakete.", "MODELS"); return; }
        if (getPermissionState("mic") != PermissionState.GRANTED) {
            requestPermissionForAlias("mic", call, "micResult");
            return;
        }
        liveStep2(call);
    }

    @PermissionCallback
    private void micResult(PluginCall call) {
        if (getPermissionState("mic") == PermissionState.GRANTED) liveStep2(call);
        else call.reject("Ohne die Erlaubnis „Mikrofon“ darf Fox Dub den Ton anderer Apps nicht hören.", "MIC");
    }

    private void liveStep2(PluginCall call) {
        if (!Settings.canDrawOverlays(getContext())) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getContext().getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(i);
            } catch (Exception ignored) {}
            call.reject("Erlaube „Über anderen Apps einblenden“ für Fox Dub.", "OVERLAY");
            return;
        }
        MediaProjectionManager mpm = (MediaProjectionManager) getContext().getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(call, mpm.createScreenCaptureIntent(), "projResult");
    }

    @ActivityCallback
    private void projResult(PluginCall call, ActivityResult result) {
        if (call == null) return;
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) { call.reject("Abgebrochen", "CANCEL"); return; }
        Intent s = new Intent(getContext(), LiveService.class);
        s.putExtra("code", result.getResultCode());
        s.putExtra("data", result.getData());
        s.putExtra("speak", call.getBoolean("speak", true));
        s.putExtra("showEn", call.getBoolean("showEn", true));
        s.putExtra("rate", call.getFloat("rate", 1.1f));
        s.putExtra("size", call.getFloat("size", 20f));
        s.putExtra("voice", call.getString("voice", ""));
        try {
            ContextCompat.startForegroundService(getContext(), s);
            call.resolve();
        } catch (Exception e) { call.reject("Live-Modus startet nicht: " + msg(e)); }
    }

    @PluginMethod
    public void liveStop(PluginCall call) {
        getContext().stopService(new Intent(getContext(), LiveService.class));
        call.resolve();
    }

    @PluginMethod
    public void liveStatus(PluginCall call) {
        JSObject o = new JSObject();
        o.put("running", LiveService.running);
        o.put("supported", Build.VERSION.SDK_INT >= 29);
        if (LiveService.lastError != null) o.put("error", LiveService.lastError);
        call.resolve(o);
    }

    // ------------------------------------------------------------------ Abspielen, Teilen

    @PluginMethod
    public void play(PluginCall call) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(Uri.parse(call.getString("uri")), "video/*");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            call.resolve();
        } catch (Exception e) { call.reject("Kein Videoplayer gefunden"); }
    }

    @PluginMethod
    public void share(PluginCall call) {
        try {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("video/mp4");
            i.putExtra(Intent.EXTRA_STREAM, Uri.parse(call.getString("uri")));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent c = Intent.createChooser(i, "Video teilen");
            c.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(c);
            call.resolve();
        } catch (Exception e) { call.reject(msg(e)); }
    }

    @PluginMethod
    public void shareText(PluginCall call) {
        try {
            String name = call.getString("name", "Untertitel.srt").replaceAll("[\\\\/:*?\"<>|]", "_");
            File f = new File(getContext().getCacheDir(), name);
            try (OutputStream o = new FileOutputStream(f)) { o.write(call.getString("text", "").getBytes("UTF-8")); }
            Uri u = FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".foxupdate", f);
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("application/x-subrip");
            i.putExtra(Intent.EXTRA_STREAM, u);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent c = Intent.createChooser(i, "Untertitel teilen");
            c.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(c);
            call.resolve();
        } catch (Exception e) { call.reject(msg(e)); }
    }

    @PluginMethod
    public void exists(PluginCall call) {
        boolean ok = false;
        try (InputStream in = getContext().getContentResolver().openInputStream(Uri.parse(call.getString("uri")))) { ok = in != null; }
        catch (Exception ignored) {}
        JSObject o = new JSObject();
        o.put("ok", ok);
        call.resolve(o);
    }
}

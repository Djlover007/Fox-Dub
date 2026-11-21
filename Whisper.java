package de.foxdub.app;

// Zugriff auf whisper.cpp (libfoxwhisper.so, gebaut aus cpp/).
public class Whisper {
    public interface Progress { void onProgress(int percent); }

    static boolean loaded = false;
    static String loadError = null;

    static {
        try { System.loadLibrary("foxwhisper"); loaded = true; }
        catch (Throwable t) { loadError = String.valueOf(t.getMessage()); }
    }

    public static native long init(String modelPath);
    public static native String full(long ctx, float[] pcm, int n, int threads, Progress cb);
    public static native void free(long ctx);
    public static native void cancel(boolean on);
}

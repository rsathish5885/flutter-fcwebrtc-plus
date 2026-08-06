package com.cloudwebrtc.webrtc;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Simple file logger for diagnosing beauty (FaceUnity) issues on specific devices.
 *
 * Usage:
 *   BeautyLogger.init(context);
 *   BeautyLogger.log("something happened");
 *   BeautyLogger.error("something failed", exception);
 *
 * Log file location: &lt;app internal storage&gt;/beauty_debug.log
 * Retrieve the path via BeautyLogger.getLogFilePath().
 */
public class BeautyLogger {

    private static final String TAG = "BeautyLogger";
    private static final String LOG_FILE_NAME = "beauty_debug.log";

    private static File logFile;
    private static final SimpleDateFormat DATE_FMT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    /** Call once at startup (e.g. in FlutterRTCBeautyFilters init). Overwrites previous log. */
    public static synchronized void init(Context context) {
        try {
            File dir = context.getFilesDir();
            logFile = new File(dir, LOG_FILE_NAME);

            // Overwrite mode — fresh log every session
            try (BufferedWriter w = new BufferedWriter(new FileWriter(logFile, false))) {
                w.write(""); // truncate
            }

            writeLine("========================================");
            writeLine("Beauty log session started");
            writeLine("Device   : " + Build.MANUFACTURER + " " + Build.MODEL);
            writeLine("OS       : Android " + Build.VERSION.RELEASE
                    + " (SDK " + Build.VERSION.SDK_INT + ")");
            writeLine("Product  : " + Build.PRODUCT);
            writeLine("Hardware : " + Build.HARDWARE);
            writeLine("Log file : " + logFile.getAbsolutePath());
            writeLine("========================================");

            Log.i(TAG, "BeautyLogger initialized: " + logFile.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "BeautyLogger init failed: " + e.getMessage());
        }
    }

    public static void log(String message) {
        String line = "[" + now() + "] [INFO ] " + message;
        Log.i(TAG, message);
        writeLine(line);
    }

    public static void warn(String message) {
        String line = "[" + now() + "] [WARN ] " + message;
        Log.w(TAG, message);
        writeLine(line);
    }

    public static void error(String message) {
        String line = "[" + now() + "] [ERROR] " + message;
        Log.e(TAG, message);
        writeLine(line);
    }

    public static void error(String message, Throwable t) {
        String line = "[" + now() + "] [ERROR] " + message
                + "\n  " + t.getClass().getName() + ": " + t.getMessage()
                + "\n  " + stackSummary(t);
        Log.e(TAG, message, t);
        writeLine(line);
    }

    /** Returns the absolute path to the log file, or null if not initialized. */
    public static String getLogFilePath() {
        return logFile != null ? logFile.getAbsolutePath() : null;
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private static synchronized void writeLine(String line) {
        if (logFile == null) return;
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(logFile, true))) {
            writer.write(line);
            writer.newLine();
        } catch (IOException e) {
            Log.e(TAG, "writeLine failed: " + e.getMessage());
        }
    }

    private static String now() {
        return DATE_FMT.format(new Date());
    }

    private static String stackSummary(Throwable t) {
        StackTraceElement[] stack = t.getStackTrace();
        if (stack == null || stack.length == 0) return "(no stack)";
        // First 5 frames is enough
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(5, stack.length);
        for (int i = 0; i < limit; i++) {
            sb.append("    at ").append(stack[i]).append("\n");
        }
        return sb.toString().trim();
    }
}

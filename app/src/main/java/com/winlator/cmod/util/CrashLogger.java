package com.winlator.cmod.util;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Date;

public class CrashLogger implements Thread.UncaughtExceptionHandler {
    private static String LOG_DIR = "";
    private final Context context;
    private final Thread.UncaughtExceptionHandler defaultHandler;

    public CrashLogger(Context context, Thread.UncaughtExceptionHandler defaultHandler) {
        this.context = context;
        this.defaultHandler = defaultHandler;
    }

    public static void init(Context context) {
        Thread.UncaughtExceptionHandler current = Thread.getDefaultUncaughtExceptionHandler();
        if (current instanceof CrashLogger) return;
        // 用App外部文件目录，确保Android 11+可写
        File externalDir = context.getExternalFilesDir(null);
        LOG_DIR = (externalDir != null ? externalDir.getAbsolutePath() : context.getFilesDir().getAbsolutePath()) + "/logs/";
        CrashLogger logger = new CrashLogger(context, current);
        Thread.setDefaultUncaughtExceptionHandler(logger);
    }

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        try {
            File dir = new File(LOG_DIR);
            if (!dir.exists()) dir.mkdirs();
            StringBuilder sb = new StringBuilder();
            sb.append("=== Winlator Crash Log ===\nTime: ").append(new Date()).append("\n");
            sb.append("Thread: ").append(thread.getName()).append("\nStack:\n");
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            throwable.printStackTrace(pw);
            sb.append(sw.toString()).append("\n\n");
            File logFile = new File(dir, "crash_" + System.currentTimeMillis() + ".txt");
            FileOutputStream fos = new FileOutputStream(logFile);
            fos.write(sb.toString().getBytes());
            fos.close();
        } catch (Throwable ignored) {}
        defaultHandler.uncaughtException(thread, throwable);
    }
}

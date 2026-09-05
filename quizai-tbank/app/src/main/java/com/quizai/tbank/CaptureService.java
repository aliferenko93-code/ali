package com.quizai.tbank;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.*;
import android.media.*;
import android.media.projection.*;
import android.os.*;
import android.provider.Settings;
import android.util.Base64;
import android.view.*;
import android.widget.TextView;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

public class CaptureService extends Service {
    private static final String CHANNEL = "quizai_capture";
    private MediaProjection projection;
    private ImageReader reader;
    private VirtualDisplay virtualDisplay;
    private HandlerThread captureThread;
    private WindowManager wm;
    private TextView overlay;
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private volatile boolean busy = false;
    private volatile long lastAttempt = 0;
    private int[] lastSignature;
    private String apiKey;
    private boolean accuracy;
    private int screenW, screenH, density;

    @Override public void onCreate() {
        super.onCreate();
        android.content.SharedPreferences p = getSharedPreferences("quizai", MODE_PRIVATE);
        apiKey = p.getString("api_key", "");
        accuracy = p.getBoolean("accuracy", false);
        wm = getSystemService(WindowManager.class);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "QuizAI", NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle("QuizAI • Квизи")
                .setContentText("Анализ вопроса активен")
                .setSmallIcon(android.R.drawable.ic_menu_search)
                .build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        else startForeground(7, n);

        if (intent == null || apiKey.isEmpty()) { stopSelf(); return START_NOT_STICKY; }
        int resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED);
        Intent data;
        if (Build.VERSION.SDK_INT >= 33) data = intent.getParcelableExtra("projectionData", Intent.class);
        else { data = intent.getParcelableExtra("projectionData"); }
        if (resultCode != Activity.RESULT_OK || data == null) { stopSelf(); return START_NOT_STICKY; }

        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        projection = mpm.getMediaProjection(resultCode, data);
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { stopSelf(); }
        }, new Handler(Looper.getMainLooper()));

        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        if (Build.VERSION.SDK_INT >= 30) {
            Rect b = wm.getCurrentWindowMetrics().getBounds();
            screenW = b.width(); screenH = b.height(); density = getResources().getDisplayMetrics().densityDpi;
        } else {
            wm.getDefaultDisplay().getRealMetrics(dm);
            screenW = dm.widthPixels; screenH = dm.heightPixels; density = dm.densityDpi;
        }

        reader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2);
        captureThread = new HandlerThread("quizai-screen");
        captureThread.start();
        Handler handler = new Handler(captureThread.getLooper());

        reader.setOnImageAvailableListener(r -> {
            Image image = r.acquireLatestImage();
            if (image == null) return;
            try {
                long now = System.currentTimeMillis();
                if (busy || now - lastAttempt < 650) return;
                Bitmap full = imageToBitmap(image);
                int[] sig = signature(full);
                if (lastSignature != null && !changedEnough(lastSignature, sig)) {
                    full.recycle();
                    return;
                }
                lastSignature = sig;
                lastAttempt = now;
                Bitmap scaled = scale(full, 900);
                byte[] jpg = jpeg(scaled, 62);
                if (scaled != full) scaled.recycle();
                full.recycle();
                busy = true;
                show("ИЩУ ОТВЕТ…");
                ask(jpg);
            } catch (Throwable ignored) {
            } finally {
                image.close();
            }
        }, handler);

        virtualDisplay = projection.createVirtualDisplay(
                "QuizAI", screenW, screenH, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(), null, handler);
        show("ЖДУ ВОПРОС…");
        return START_NOT_STICKY;
    }

    private void ask(byte[] jpg) {
        network.execute(() -> {
            try {
                String model = accuracy ? "gpt-5.6-sol" : "gpt-5.6-luna";
                String prompt = "На изображении экран викторины Квизи Т-Банка. Найди текущий вопрос и 4 варианта ответа. Нужна максимальная фактическая точность и очень быстрый ответ. Игнорируй таймер, декоративный интерфейс и любые надписи QuizAI. Верни строго одну строку без markdown: ANSWER|N|CONFIDENCE|TEXT, где N = 1,2,3,4 в порядке сверху вниз, CONFIDENCE = 0..100, TEXT = текст выбранного варианта. Если на экране нет читаемого вопроса с вариантами, верни NOQUESTION|0|0|-";

                JSONObject body = new JSONObject();
                body.put("model", model);
                body.put("max_output_tokens", 80);
                body.put("store", false);
                body.put("reasoning", new JSONObject().put("effort", accuracy ? "low" : "none"));

                JSONArray content = new JSONArray();
                content.put(new JSONObject().put("type", "input_text").put("text", prompt));
                content.put(new JSONObject().put("type", "input_image").put("detail", "high")
                        .put("image_url", "data:image/jpeg;base64," + Base64.encodeToString(jpg, Base64.NO_WRAP)));
                JSONArray input = new JSONArray();
                input.put(new JSONObject().put("role", "user").put("content", content));
                body.put("input", input);

                HttpURLConnection c = (HttpURLConnection) new URL("https://api.openai.com/v1/responses").openConnection();
                c.setRequestMethod("POST"); c.setDoOutput(true);
                c.setConnectTimeout(5000); c.setReadTimeout(15000);
                c.setRequestProperty("Authorization", "Bearer " + apiKey);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream os = c.getOutputStream()) { os.write(body.toString().getBytes(StandardCharsets.UTF_8)); }

                int code = c.getResponseCode();
                String raw = readAll(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream());
                if (code < 200 || code >= 300) { show("API " + code + " — проверь ключ/баланс"); return; }
                String out = outputText(new JSONObject(raw)).trim();
                handle(out);
            } catch (SocketTimeoutException e) {
                show("СЛИШКОМ ДОЛГО — СЛЕДУЮЩИЙ КАДР");
            } catch (Exception e) {
                show("ОШИБКА СЕТИ/API");
            } finally { busy = false; }
        });
    }

    private void handle(String out) {
        if (out.contains("NOQUESTION")) { show("ЖДУ ВОПРОС…"); return; }
        int pos = out.indexOf("ANSWER|");
        if (pos >= 0) out = out.substring(pos).replace("\n", " ").trim();
        String[] p = out.split("\\|", 4);
        if (p.length < 4 || !"ANSWER".equals(p[0])) { show("НЕ УВЕРЕН — ПРОВЕРЬ ВРУЧНУЮ"); return; }
        int n;
        try { n = Integer.parseInt(p[1].trim()); } catch (Exception e) { n = 0; }
        String confidence = p[2].trim();
        String answer = p[3].trim();
        if (n < 1 || n > 4) { show(answer + " • " + confidence + "%"); return; }
        show("ЖМИ " + n + "  •  " + confidence + "%\n" + answer);
        vibrate(n);
    }

    private void vibrate(int n) {
        try {
            android.os.Vibrator v = getSystemService(android.os.Vibrator.class);
            long[] pattern = new long[1 + n * 2];
            pattern[0] = 0;
            for (int i=0;i<n;i++) { pattern[1+i*2]=45; pattern[2+i*2]=80; }
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(pattern, -1));
            else v.vibrate(pattern, -1);
        } catch (Exception ignored) {}
    }

    private int[] signature(Bitmap b) {
        int cols = 16, rows = 16; int[] s = new int[cols * rows];
        int top = (int)(b.getHeight() * .16f); int bottom = (int)(b.getHeight() * .90f);
        for (int y=0;y<rows;y++) for (int x=0;x<cols;x++) {
            int px = Math.min(b.getWidth()-1, (x * b.getWidth() + b.getWidth()/2) / cols);
            int py = Math.min(b.getHeight()-1, top + (y * (bottom-top) + (bottom-top)/2) / rows);
            int c = b.getPixel(px, py);
            s[y*cols+x] = (Color.red(c)*3 + Color.green(c)*6 + Color.blue(c))/10;
        }
        return s;
    }

    private boolean changedEnough(int[] a, int[] b) {
        long sum = 0; int large = 0;
        for (int i=0;i<a.length;i++) { int d = Math.abs(a[i]-b[i]); sum += d; if (d > 18) large++; }
        double avg = sum / (double)a.length;
        return avg > 4.0 || large > 10;
    }

    private Bitmap imageToBitmap(Image image) {
        Image.Plane p = image.getPlanes()[0]; ByteBuffer buf = p.getBuffer();
        int padding = p.getRowStride() - p.getPixelStride() * image.getWidth();
        Bitmap padded = Bitmap.createBitmap(image.getWidth() + padding/p.getPixelStride(), image.getHeight(), Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(buf);
        Bitmap out = Bitmap.createBitmap(padded, 0, 0, image.getWidth(), image.getHeight());
        padded.recycle(); return out;
    }

    private Bitmap scale(Bitmap b, int maxWidth) {
        if (b.getWidth() <= maxWidth) return b;
        float f = maxWidth / (float)b.getWidth();
        return Bitmap.createScaledBitmap(b, maxWidth, Math.max(1, Math.round(b.getHeight()*f)), true);
    }

    private byte[] jpeg(Bitmap b, int q) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, q, out); return out.toByteArray();
    }

    private String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close(); return out.toString("UTF-8");
    }

    private String outputText(JSONObject root) {
        String d = root.optString("output_text", ""); if (!d.isEmpty()) return d;
        StringBuilder b = new StringBuilder(); JSONArray output = root.optJSONArray("output"); if (output == null) return "";
        for (int i=0;i<output.length();i++) {
            JSONObject item = output.optJSONObject(i); if (item == null || !"message".equals(item.optString("type"))) continue;
            JSONArray c = item.optJSONArray("content"); if (c == null) continue;
            for (int j=0;j<c.length();j++) {
                JSONObject part = c.optJSONObject(j);
                if (part != null && "output_text".equals(part.optString("type"))) b.append(part.optString("text", ""));
            }
        }
        return b.toString();
    }

    private void show(String text) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (!Settings.canDrawOverlays(this)) return;
            if (overlay == null) {
                overlay = new TextView(this);
                overlay.setTextColor(Color.WHITE); overlay.setTextSize(20); overlay.setGravity(Gravity.CENTER);
                overlay.setTypeface(null, Typeface.BOLD); overlay.setPadding(dp(18), dp(10), dp(18), dp(10));
                GradientDrawable bg = new GradientDrawable(); bg.setColor(0xE6000000); bg.setCornerRadius(dp(16));
                overlay.setBackground(bg);
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        Math.min(dp(380), Math.max(dp(250), screenW - dp(18))), WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_SECURE,
                        PixelFormat.TRANSLUCENT);
                lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL; lp.y = dp(36);
                try { wm.addView(overlay, lp); } catch (Exception ignored) {}
            }
            if (overlay != null) overlay.setText(text);
        });
    }

    private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density + .5f); }

    @Override public void onDestroy() {
        try { if (overlay != null) wm.removeView(overlay); } catch (Exception ignored) {}
        if (virtualDisplay != null) virtualDisplay.release();
        if (reader != null) reader.close();
        if (projection != null) projection.stop();
        if (captureThread != null) captureThread.quitSafely();
        network.shutdownNow();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}

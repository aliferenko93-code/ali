package com.quizai.tbank;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.widget.*;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 77;
    private EditText key;
    private CheckBox accuracy;
    private TextView status;
    private MediaProjectionManager projectionManager;
    private android.content.SharedPreferences prefs;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("quizai", MODE_PRIVATE);
        projectionManager = getSystemService(MediaProjectionManager.class);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(30));
        scroll.addView(root);

        TextView title = text("QuizAI • Квизи", 30, true);
        root.addView(title);
        TextView sub = text("Быстрая подсказка ответа поверх Т-Банка", 15, false);
        sub.setTextColor(0xff555555);
        root.addView(sub);

        TextView api = text("OpenAI API key", 14, true);
        api.setPadding(0, dp(20), 0, 0);
        root.addView(api);

        key = new EditText(this);
        key.setHint("sk-proj-…");
        key.setSingleLine(true);
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setText(prefs.getString("api_key", ""));
        root.addView(key, new LinearLayout.LayoutParams(-1, -2));

        accuracy = new CheckBox(this);
        accuracy.setText("Точный режим (Sol, может быть медленнее)");
        accuracy.setChecked(prefs.getBoolean("accuracy", false));
        root.addView(accuracy);

        TextView mode = text("Быстрый режим использует gpt-5.6-luna. Точный — gpt-5.6-sol.", 13, false);
        mode.setTextColor(0xff666666);
        root.addView(mode);

        Button overlay = button("1. Разрешить показ поверх приложений");
        overlay.setOnClickListener(v -> startActivity(new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()))));
        root.addView(overlay, new LinearLayout.LayoutParams(-1, -2));

        Button start = button("2. ЗАПУСТИТЬ");
        start.setTextSize(19);
        start.setOnClickListener(v -> startCapture());
        root.addView(start, new LinearLayout.LayoutParams(-1, -2));

        Button stop = button("Остановить");
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, CaptureService.class));
            status.setText("Статус: остановлено");
        });
        root.addView(stop, new LinearLayout.LayoutParams(-1, -2));

        status = text("Статус: готово", 16, true);
        status.setPadding(0, dp(18), 0, 0);
        root.addView(status);

        TextView help = text(
                "Как использовать:\n1) Вставь API key.\n2) Дай разрешение поверх приложений.\n3) Нажми ЗАПУСТИТЬ и разреши трансляцию экрана.\n4) Открой Т-Банк → Квизи.\n5) Сверху появится: ЖМИ 1/2/3/4 + ответ.",
                15, false);
        help.setPadding(0, dp(18), 0, 0);
        root.addView(help);

        setContentView(scroll);

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 78);
        }
    }

    private void startCapture() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Сначала разреши показ поверх приложений", Toast.LENGTH_LONG).show();
            return;
        }
        String apiKey = key.getText().toString().trim();
        if (apiKey.length() < 20) {
            Toast.makeText(this, "Вставь OpenAI API key", Toast.LENGTH_LONG).show();
            return;
        }
        prefs.edit().putString("api_key", apiKey).putBoolean("accuracy", accuracy.isChecked()).apply();
        status.setText("Статус: запрос разрешения на экран…");
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @SuppressWarnings("deprecation")
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CAPTURE && resultCode == RESULT_OK && data != null) {
            Intent service = new Intent(this, CaptureService.class);
            service.putExtra("resultCode", resultCode);
            service.putExtra("projectionData", data);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service); else startService(service);
            status.setText("Статус: работает — открой Квизи");
            Toast.makeText(this, "QuizAI запущен", Toast.LENGTH_SHORT).show();
        }
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        return b;
    }
    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(sp); t.setTextColor(Color.BLACK);
        if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD);
        return t;
    }
    private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density + .5f); }
}

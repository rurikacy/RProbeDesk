package dev.rprobedesk.fixture;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A disposable target APK for the manual's package, rotation, input and logcat checks. */
public final class TargetsActivity extends Activity {
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        FrameLayout screen = new FrameLayout(this);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            screen.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars()
                        | android.view.WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
        }
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        EditText text = new EditText(this);
        text.setSingleLine(true);
        text.setId(R.id.target_input);
        text.setHint("A1 / 测试");
        form.addView(text);
        TextView result = new TextView(this);
        result.setGravity(Gravity.CENTER);
        result.setText("RProbeDesk QA");
        form.addView(result);
        Button event = new Button(this);
        event.setText("写入测试日志");
        event.setOnClickListener(v -> {
            Log.i("RProbeFixture", "input=" + text.getText());
            Log.e("RProbeFixture", "sample error", new IllegalStateException("QA sample"));
            result.setText(text.getText());
        });
        form.addView(event);
        screen.addView(form, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        addTarget(screen, result, "TL", Gravity.TOP | Gravity.LEFT);
        addTarget(screen, result, "TR", Gravity.TOP | Gravity.RIGHT);
        addTarget(screen, result, "BL", Gravity.BOTTOM | Gravity.LEFT);
        addTarget(screen, result, "BR", Gravity.BOTTOM | Gravity.RIGHT);
        setContentView(screen);
        if (getIntent().getBooleanExtra("qaSeedClipboard", false)) {
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("RProbeDesk QA", "测试"));
        }
    }

    private void addTarget(FrameLayout screen, TextView output, String label, int gravity) {
        Button target = new Button(this);
        target.setText(label);
        target.setOnClickListener((View v) -> {
            output.setText(label);
            Log.i("RProbeFixture", "touch=" + label);
        });
        screen.addView(target, new FrameLayout.LayoutParams(-2, -2, gravity));
    }
}

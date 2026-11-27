package dev.rprobedesk.fixture;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A disposable target APK for the manual's package, rotation, input and logcat checks. */
public final class TargetsActivity extends Activity {
    private float touchX;
    private float touchY;

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            touchX = event.getX();
            touchY = event.getY();
        } else if (event.getActionMasked() == MotionEvent.ACTION_UP &&
                Math.hypot(event.getX() - touchX, event.getY() - touchY) > 32) {
            Log.i("RProbeFixture", "drag=" + Math.round(touchX) + "," + Math.round(touchY)
                    + "," + Math.round(event.getX()) + "," + Math.round(event.getY()));
        }
        return super.dispatchTouchEvent(event);
    }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (getIntent().getBooleanExtra("qaMotion", false)) {
            android.view.SurfaceView surface = new android.view.SurfaceView(this);
            surface.getHolder().addCallback(new android.view.SurfaceHolder.Callback() {
                volatile boolean live;
                public void surfaceCreated(android.view.SurfaceHolder holder) {
                    live = true;
                    new Thread(() -> {
                        int frame = 0;
                        android.graphics.Paint paint = new android.graphics.Paint();
                        long start = android.os.SystemClock.elapsedRealtime();
                        while (live && holder.getSurface().isValid()) {
                            android.graphics.Canvas canvas = holder.lockCanvas();
                            if (canvas == null) break;
                            try {
                                int tone = (++frame * 53) & 255;
                                canvas.drawRGB(tone, tone, tone);
                                paint.setColor(android.graphics.Color.rgb(240, 140, 30));
                                float x = (frame * 11) % canvas.getWidth();
                                canvas.drawRect(x, 10, x + 40, canvas.getHeight() / 3f, paint);
                            } finally { holder.unlockCanvasAndPost(canvas); }
                            if (frame % 60 == 0) Log.i("RProbeMotion", "frames=" + frame + " elapsedMs=" +
                                    (android.os.SystemClock.elapsedRealtime()-start));
                            android.os.SystemClock.sleep(20);
                        }
                    }, "fixture-motion").start();
                }
                public void surfaceChanged(android.view.SurfaceHolder holder, int format, int width, int height) {}
                public void surfaceDestroyed(android.view.SurfaceHolder holder) { live = false; }
            });
            setContentView(surface);
            return;
        }
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

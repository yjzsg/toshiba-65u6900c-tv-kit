package com.iflytek.sample.view;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Scratch pad: focus the field, press the remote's voice key, watch the text appear. */
public class MainActivity extends Activity {

    static final String OUR_IME = "com.iflytek.sample.view/.VkImeService";

    private TextView readout;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(64, 48, 64, 48);
        root.setBackgroundColor(0xFF101010);

        TextView title = new TextView(this);
        title.setText("语音键盘");
        title.setTextSize(26);
        title.setTextColor(0xFFFFFFFF);
        root.addView(title);

        EditText field = new EditText(this);
        field.setId(1001);
        field.setTextSize(24);
        field.setSingleLine(true);
        field.setHint("点这里，然后按遥控器语音键");
        field.setInputType(InputType.TYPE_CLASS_TEXT);
        field.setTextColor(0xFFFFFFFF);
        field.setHintTextColor(0xFF888888);
        root.addView(field, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        Button back = new Button(this);
        back.setText("切回语音键盘");
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    Settings.Secure.putString(getContentResolver(),
                            Settings.Secure.DEFAULT_INPUT_METHOD, OUR_IME);
                    readout.setText("已切回语音键盘");
                } catch (Throwable e) {
                    readout.setText("切换失败：" + e);
                }
            }
        });
        root.addView(back);

        readout = new TextView(this);
        readout.setTextSize(18);
        readout.setTextColor(0xFF8FC7FF);
        readout.setPadding(0, 32, 0, 0);
        root.addView(readout);

        setContentView(root);
        field.requestFocus();

        final Handler h = new Handler(Looper.getMainLooper());
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                String ime = Settings.Secure.getString(getContentResolver(),
                        Settings.Secure.DEFAULT_INPUT_METHOD);
                readout.setText("当前输入法：" + ime
                        + "\n最近一次识别：" + (RelayService.lastText == null ? "（无）" : RelayService.lastText));
                h.postDelayed(this, 1000);
            }
        }, 500);
    }
}

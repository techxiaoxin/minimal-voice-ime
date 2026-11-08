package com.minivoice.ime.ui;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.minivoice.ime.R;
import com.minivoice.ime.service.KeyInterceptorService;

import java.util.List;

public class MainActivity extends AppCompatActivity {

    private TextView tvState;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeUtil.applyToActivity(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvState = findViewById(R.id.tv_state);

        Button btnEnable = findViewById(R.id.btn_enable);
        Button btnSelect = findViewById(R.id.btn_select);
        Button btnSettings = findViewById(R.id.btn_settings);

        btnEnable.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));

        btnSelect.setOnClickListener(v -> {
            InputMethodManager imm =
                    (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showInputMethodPicker();
        });

        btnSettings.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        btnSettings.setOnLongClickListener(v -> {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateState();
    }

    private void updateState() {
        boolean enabled = isImeEnabled();
        boolean current = isImeCurrent();
        boolean intercept = KeyInterceptorService.isEnabled();

        StringBuilder sb = new StringBuilder();
        sb.append(enabled ? "已启用输入法" : "还没启用输入法");
        sb.append("\n");
        sb.append(current ? "当前就是极简语音" : "还不是默认输入法");
        sb.append("\n");
        sb.append(intercept ? "按键拦截已开启" : "按键拦截未开启（可选）");

        tvState.setText(sb.toString());
    }

    private boolean isImeEnabled() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm == null) return false;
        List<InputMethodInfo> list = imm.getEnabledInputMethodList();
        for (InputMethodInfo info : list) {
            if (info.getPackageName().equals(getPackageName())) return true;
        }
        return false;
    }

    private boolean isImeCurrent() {
        String current = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        return current != null && current.startsWith(getPackageName());
    }
}

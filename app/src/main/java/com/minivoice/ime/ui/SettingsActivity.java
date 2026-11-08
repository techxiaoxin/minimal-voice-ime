package com.minivoice.ime.ui;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Base64;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minivoice.ime.R;
import com.minivoice.ime.asr.AsrConfig;
import com.minivoice.ime.asr.AsrEngine;
import com.minivoice.ime.asr.LocalAsrEngine;
import com.minivoice.ime.asr.LocalLlmModel;
import com.minivoice.ime.asr.LocalModelManager;
import com.minivoice.ime.settings.Prefs;
import com.minivoice.ime.sync.LanSyncManager;

import java.io.ByteArrayOutputStream;
import java.net.Proxy;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class SettingsActivity extends AppCompatActivity {

    private EditText etEndpoint;
    private EditText etApikey;
    private EditText etModel;
    private EditText etDevice;
    private EditText etVocab;
    private EditText etPunct;
    private EditText etFilter;
    private CompoundButton swPreferBt;
    private CompoundButton swPolish;
    private RadioGroup rgPolishEngine;
    private RadioButton rbPolishCloud;
    private RadioButton rbPolishLocal;
    private TextView tvLlmStatus;
    private Button btnLlmDownload;
    private LinearLayout llModels;
    private RadioGroup rgLlmTier;
    private RadioButton rbLlmSmall;
    private RadioButton rbLlmBig;
    private LinearLayout llTokens;
    private TextView tvTokens;
    private Button btnTokenReset;
    private Button btnTokenLog;
    private TextView tvPeers;
    private LinearLayout llPeers;
    private ImageButton btnEye;
    private Button btnTest;
    private TextView tabVoice;
    private TextView tabMore;
    private TextView chipAuto;
    private TextView chipLight;
    private TextView chipDark;
    private android.view.View groupVoice;
    private android.view.View groupMore;
    private TextView tvTest;
    private TextView tvKeyResult;
    private TextView chipSpace;
    private TextView chipSend;
    private TextView chipCloud;
    private TextView chipLocal;
    private TextView tvModeHint;
    private TextView tvLocalStatus;
    private EditText etLocalUrl;
    private Button btnDownload;
    private RadioGroup rgModelTier;
    private RadioButton rbTierLight;
    private RadioButton rbTierHq;
    private TextView tvModelSummary;
    /** 程序自己改选中状态时不让监听回调跑，否则会把新档的地址又清一遍。 */
    private boolean bindingTier = false;
    private Button btnLocalTest;
    private android.view.View groupCloud;
    private android.view.View groupLocal;
    private boolean keyVisible = false;
    private boolean voicePage = true;
    private String savedHintText;
    private int savedHintColor;

    private final LanSyncManager lan = new LanSyncManager();
    private final List<LanSyncManager.Peer> peers = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeUtil.applyToActivity(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        if (savedInstanceState != null) {
            voicePage = savedInstanceState.getBoolean("voice_page", true);
        } else {
            voicePage = !"more".equals(Prefs.getString(this, "settings_tab", "voice"));
        }

        etEndpoint = findViewById(R.id.et_endpoint);
        etApikey = findViewById(R.id.et_apikey);
        etModel = findViewById(R.id.et_model);
        etDevice = findViewById(R.id.et_device);
        etVocab = findViewById(R.id.et_vocab);
        etPunct = findViewById(R.id.et_punct);
        etFilter = findViewById(R.id.et_filter);
        swPreferBt = findViewById(R.id.sw_prefer_bt);
        swPolish = findViewById(R.id.sw_polish);
        rgPolishEngine = findViewById(R.id.rg_polish_engine);
        rbPolishCloud = findViewById(R.id.rb_polish_cloud);
        rbPolishLocal = findViewById(R.id.rb_polish_local);
        tvLlmStatus = findViewById(R.id.tv_llm_status);
        btnLlmDownload = findViewById(R.id.btn_llm_download);
        llModels = findViewById(R.id.ll_models);
        rgLlmTier = findViewById(R.id.rg_llm_tier);
        rbLlmSmall = findViewById(R.id.rb_llm_small);
        rbLlmBig = findViewById(R.id.rb_llm_big);
        llTokens = findViewById(R.id.ll_tokens);
        tvTokens = findViewById(R.id.tv_tokens);
        btnTokenReset = findViewById(R.id.btn_token_reset);
        btnTokenLog = findViewById(R.id.btn_token_log);
        btnTokenLog.setOnClickListener(v -> showTokenLog());
        rgLlmTier.setOnCheckedChangeListener((g, id) -> {
            Prefs.putString(this, Prefs.K_LLM_TIER,
                    id == R.id.rb_llm_big ? com.minivoice.ime.asr.LocalLlmModel.TIER_BIG
                            : com.minivoice.ime.asr.LocalLlmModel.TIER_SMALL);
            com.minivoice.ime.asr.LocalLlm.release();
            renderPolishEngine();
            renderModels();
        });
        btnTokenReset.setOnClickListener(v -> {
            Prefs.resetTokens(this);
            Prefs.resetLog(this);
            renderPolishEngine();
            Toast.makeText(this, "云端用量已清零，从现在重新算", Toast.LENGTH_SHORT).show();
        });
        rgPolishEngine.setOnCheckedChangeListener((g, id) -> {
            Prefs.putString(this, Prefs.K_POLISH_ENGINE, id == R.id.rb_polish_local ? "local" : "cloud");
            renderPolishEngine();
        renderModels();
        });
        btnLlmDownload.setOnClickListener(v -> {
            if (LocalLlmModel.ready(this)) runLlmTest();
            else startLlmDownload();
        });
        renderPolishEngine();
        tvPeers = findViewById(R.id.tv_peers);
        llPeers = findViewById(R.id.ll_peers);
        btnEye = findViewById(R.id.btn_eye);
        btnTest = findViewById(R.id.btn_test);
        tabVoice = findViewById(R.id.tab_voice);
        tabMore = findViewById(R.id.tab_more);
        chipAuto = findViewById(R.id.chip_auto);
        chipLight = findViewById(R.id.chip_light);
        chipDark = findViewById(R.id.chip_dark);
        groupVoice = findViewById(R.id.group_voice);
        groupMore = findViewById(R.id.group_more);
        tvTest = findViewById(R.id.tv_test);
        tvKeyResult = findViewById(R.id.tv_key_result);
        chipSpace = findViewById(R.id.chip_space);
        chipSend = findViewById(R.id.chip_send);
        chipSpace.setOnClickListener(v -> setSpaceMode(Prefs.SPACE_INSERT));
        chipSend.setOnClickListener(v -> setSpaceMode(Prefs.SPACE_SEND));
        chipCloud = findViewById(R.id.chip_cloud);
        chipLocal = findViewById(R.id.chip_local);
        tvModeHint = findViewById(R.id.tv_mode_hint);
        tvLocalStatus = findViewById(R.id.tv_local_status);
        etLocalUrl = findViewById(R.id.et_local_url);
        btnDownload = findViewById(R.id.btn_download);
        btnLocalTest = findViewById(R.id.btn_local_test);
        rgModelTier = findViewById(R.id.rg_model_tier);
        rbTierLight = findViewById(R.id.rb_tier_light);
        rbTierHq = findViewById(R.id.rb_tier_hq);
        tvModelSummary = findViewById(R.id.tv_model_summary);
        if (rgModelTier != null) {
            rgModelTier.setOnCheckedChangeListener((group, checkedId) -> {
                if (bindingTier) return;
                boolean hq = checkedId == R.id.rb_tier_hq;
                Prefs.putString(this, Prefs.K_LOCAL_TIER,
                        hq ? LocalModelManager.TIER_HQ : LocalModelManager.TIER_LIGHT);
                // 换档就把手填的地址清掉，回到新档的默认镜像，省得拿旧地址下新模型
                Prefs.putString(this, Prefs.K_LOCAL_URL, "");
                Prefs.putString(this, Prefs.K_LOCAL_URL_SV, "");
                refreshModelUi();
                etLocalUrl.setText(targetUrl());
            });
        }
        groupCloud = findViewById(R.id.group_cloud);
        groupLocal = findViewById(R.id.group_local);

        chipCloud.setOnClickListener(v -> selectMode(false));
        chipLocal.setOnClickListener(v -> selectMode(true));
        btnDownload.setOnClickListener(v -> downloadModel());
        btnLocalTest.setOnClickListener(v -> testLocalModel());

        TextView tvVersion = findViewById(R.id.tv_version);
        try {
            String vn = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            tvVersion.setText("v" + vn);
        } catch (Exception ignored) {
            tvVersion.setText("v?");
        }

        tvTest.setOnClickListener(v -> {
            CharSequence t = tvTest.getText();
            if (TextUtils.isEmpty(t)) return;
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("测试结果", t));
                Toast.makeText(this, "已复制，可以直接粘贴发给我", Toast.LENGTH_SHORT).show();
            }
        });

        btnEye.setOnClickListener(v -> toggleKeyVisible());
        btnTest.setOnClickListener(v -> testConnection());
        tabVoice.setOnClickListener(v -> switchTab(true));
        tabMore.setOnClickListener(v -> switchTab(false));

        chipAuto.setOnClickListener(v -> setTheme(ThemeUtil.AUTO));
        chipLight.setOnClickListener(v -> setTheme(ThemeUtil.LIGHT));
        chipDark.setOnClickListener(v -> setTheme(ThemeUtil.DARK));

        Button btnSave = findViewById(R.id.btn_save);
        Button btnScan = findViewById(R.id.btn_scan);

        load();
        styleThemeChips();
        styleSpaceChips();
        renderMode(AsrConfig.PROTOCOL_LOCAL.equals(Prefs.getString(this, Prefs.K_PROTOCOL, "")));
        switchTab(voicePage);

        btnSave.setOnClickListener(v -> {
            save();
            flashSaved(btnSave);
        });

        btnScan.setOnClickListener(v -> scan());

    }

    @Override
    protected void onSaveInstanceState(android.os.Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("voice_page", voicePage);
    }

    /** 保存按钮原地变绿显示“已保存”，一秒多后自己变回来。 */
    private void flashSaved(Button btn) {
        if (savedHintText == null) {
            savedHintText = btn.getText().toString();
            savedHintColor = btn.getCurrentTextColor();
        }
        btn.setText("已保存");
        btn.setBackgroundResource(R.drawable.bg_btn_success);
        btn.setTextColor(0xFFFFFFFF);
        btn.postDelayed(() -> {
            btn.setText(savedHintText);
            btn.setBackgroundResource(R.drawable.bg_btn_primary);
            btn.setTextColor(savedHintColor);
        }, 1400);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 回到设置页先刷一次用量，识别和整理每次记账后这里都能看到新数字
        renderTokens();
        String name = TextUtils.isEmpty(etDevice.getText())
                ? android.os.Build.MODEL
                : etDevice.getText().toString();
        lan.startAdvertising(this, name);
    }

    @Override
    protected void onStart() {
        super.onStart();
        android.content.SharedPreferences sp = getSharedPreferences("minivoice_prefs", MODE_PRIVATE);
        sp.registerOnSharedPreferenceChangeListener(tokenWatcher);
        renderTokens();
    }

    @Override
    protected void onStop() {
        getSharedPreferences("minivoice_prefs", MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(tokenWatcher);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        lan.stop(this);
        super.onDestroy();
    }

    private void load() {
        AsrConfig c = AsrConfig.load(this);
        etEndpoint.setText(c.endpoint);
        etApikey.setText(c.apiKey);
        etModel.setText(c.model);

        etDevice.setText(Prefs.getString(this, Prefs.K_DEVICE_NAME,
                android.os.Build.MODEL));
        etVocab.setText(String.join("\n", Prefs.getWordList(this)));
        etPunct.setText(Prefs.getString(this, Prefs.K_PUNCT, ""));
        etFilter.setText(Prefs.getString(this, Prefs.K_FILTER, "嗯,呃,啊,那个,就是"));
        swPreferBt.setChecked(Prefs.getBool(this, Prefs.K_PREFER_BT, true));
        swPolish.setChecked(c.polish);
        String savedUrl = Prefs.getString(this, Prefs.K_LOCAL_URL_SV, "");
        etLocalUrl.setText(savedUrl.isEmpty() ? targetUrl() : savedUrl);
        refreshModelUi();
    }

    /** 云端 / 本地切换，点一下就生效，不用等保存。 */
    private void selectMode(boolean local) {
        Prefs.putString(this, Prefs.K_PROTOCOL,
                local ? AsrConfig.PROTOCOL_LOCAL : AsrConfig.PROTOCOL_BAILIAN);
        renderMode(local);
    }

    private void renderMode(boolean local) {
        groupCloud.setVisibility(local ? android.view.View.GONE : android.view.View.VISIBLE);
        groupLocal.setVisibility(local ? android.view.View.VISIBLE : android.view.View.GONE);
        styleChip(chipCloud, !local);
        styleChip(chipLocal, local);
        renderTokens();
        tvModeHint.setText(local
                ? "离线识别，模型要先下好。整理也在本机做，不联网。"
                : "连云端接口，边说边出字。");
        refreshLocalStatus();
    }

    /** 数量上万就换成「万」为单位，小数目照样显示完整数字。 */
    private String formatCount(int n) {
        if (n < 10000) return String.valueOf(n);
        double w = n / 10000.0;
        String text = w >= 10
                ? String.format(java.util.Locale.CHINA, "%.0f万", w)
                : String.format(java.util.Locale.CHINA, "%.1f万", w);
        if (text.contains(".0万")) text = text.replace(".0万", "万");
        return text;
    }

    /** 云端用量只在用到云端的时候露出来，本地纯离线就不用看了。 */
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener tokenWatcher =
            (sp, key) -> {
                if (Prefs.K_TOKEN_TOTAL.equals(key) || Prefs.K_TOKEN_QUESTS.equals(key)
                        || Prefs.K_PROTOCOL.equals(key) || Prefs.K_POLISH_ENGINE.equals(key)) {
                    runOnUiThread(this::renderTokens);
                }
            };

    private void renderTokens() {
        if (tvTokens == null) return;
        boolean cloudAsr = !com.minivoice.ime.asr.AsrConfig.PROTOCOL_LOCAL.equals(
                Prefs.getString(this, Prefs.K_PROTOCOL, com.minivoice.ime.asr.AsrConfig.PROTOCOL_BAILIAN));
        boolean cloudPolish = !"local".equals(Prefs.getString(this, Prefs.K_POLISH_ENGINE, "cloud"));
        int total = Prefs.getInt(this, Prefs.K_TOKEN_TOTAL, 0);
        int count = Prefs.getInt(this, Prefs.K_TOKEN_QUESTS, 0);
        tvTokens.setText(String.format(java.util.Locale.CHINA,
                "云端用量 %s tokens，共 %d 次", formatCount(total), count));
        llTokens.setVisibility(cloudAsr || cloudPolish
                ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    /** 点「明细」弹一块半屏小窗，列最近几次都花在哪了。 */
    private void showTokenLog() {
        String raw = Prefs.getString(this, Prefs.K_TOKEN_LOG, "");
        int total = Prefs.getInt(this, Prefs.K_TOKEN_TOTAL, 0);
        int count = Prefs.getInt(this, Prefs.K_TOKEN_QUESTS, 0);

        int asrSum = 0;
        int polishSum = 0;
        java.util.List<String[]> rows = new java.util.ArrayList<>();
        java.text.SimpleDateFormat fmt =
                new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA);
        for (String line : raw.split("\n")) {
            line = line.trim();
            int i1 = line.indexOf('|');
            if (i1 < 0) continue;
            int i2 = line.indexOf('|', i1 + 1);
            if (i2 < 0) continue;
            long ts;
            int tokens;
            try {
                ts = Long.parseLong(line.substring(0, i1));
                tokens = Integer.parseInt(line.substring(i2 + 1));
            } catch (Exception e) {
                continue;
            }
            String kind = line.substring(i1 + 1, i2);
            if ("识别".equals(kind)) asrSum += tokens;
            else if ("整理".equals(kind)) polishSum += tokens;
            rows.add(new String[]{fmt.format(new java.util.Date(ts)), kind,
                    formatCount(tokens) + " tokens"});
        }

        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (getResources().getDisplayMetrics().density * 18);
        box.setPadding(pad, pad, pad, pad);

        android.widget.TextView head = new android.widget.TextView(this);
        head.setText("最近消耗明细");
        head.setTextSize(16);
        head.setTextColor(getResources().getColor(R.color.text_primary, getTheme()));
        box.addView(head);

        android.widget.TextView sum = new android.widget.TextView(this);
        sum.setText("识别 " + formatCount(asrSum) + "，整理 " + formatCount(polishSum)
                + "\n合计 " + formatCount(total) + " tokens，共 " + count + " 次");
        sum.setTextSize(12);
        sum.setTextColor(getResources().getColor(R.color.text_secondary, getTheme()));
        sum.setPadding(0, pad / 3, 0, pad / 2);
        box.addView(sum);

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        android.widget.LinearLayout list = new android.widget.LinearLayout(this);
        list.setOrientation(android.widget.LinearLayout.VERTICAL);
        sv.addView(list);

        if (rows.isEmpty()) {
            android.widget.TextView empty = new android.widget.TextView(this);
            empty.setText("还没有消耗记录，去说两句话再来");
            empty.setTextSize(13);
            empty.setTextColor(getResources().getColor(R.color.text_secondary, getTheme()));
            empty.setPadding(0, pad, 0, 0);
            list.addView(empty);
        } else {
            int show = Math.min(rows.size(), 8);
            for (int i = 0; i < show; i++) {
                android.widget.LinearLayout row = new android.widget.LinearLayout(this);
                row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                row.setPadding(0, pad / 2, 0, pad / 2);
                android.widget.TextView t1 = new android.widget.TextView(this);
                t1.setText(rows.get(i)[0]);
                t1.setTextSize(12);
                t1.setTextColor(getResources().getColor(R.color.text_secondary, getTheme()));
                t1.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                android.widget.TextView t2 = new android.widget.TextView(this);
                t2.setText(rows.get(i)[1]);
                t2.setTextSize(12);
                t2.setTextColor(getResources().getColor(R.color.text_primary, getTheme()));
                t2.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 0.7f));
                android.widget.TextView t3 = new android.widget.TextView(this);
                t3.setText(rows.get(i)[2]);
                t3.setTextSize(12);
                t3.setTextColor(getResources().getColor(R.color.text_primary, getTheme()));
                t3.setGravity(android.view.Gravity.END);
                t3.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f));
                row.addView(t1);
                row.addView(t2);
                row.addView(t3);
                list.addView(row);
            }
        }
        box.addView(sv, new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        android.app.Dialog d = new android.app.Dialog(this);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(getResources().getColor(R.color.card_bg, getTheme()));
        bg.setCornerRadius(getResources().getDisplayMetrics().density * 16);
        d.getWindow().setBackgroundDrawable(bg);
        d.setContentView(box);
        d.setCanceledOnTouchOutside(true);
        android.view.Window w = d.getWindow();
        w.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.88),
                (int) (getResources().getDisplayMetrics().heightPixels * 0.6));
        w.setGravity(android.view.Gravity.CENTER);
        d.show();
    }

    /** 把规格、说明文字、地址框的提示都刷成当前档的样子。 */
    private void refreshModelUi() {
        if (tvModelSummary == null) return;
        boolean hq = LocalModelManager.isHq(this);
        bindingTier = true;
        rbTierLight.setChecked(!hq);
        rbTierHq.setChecked(hq);
        bindingTier = false;
        tvModelSummary.setText(LocalModelManager.tierSummary(this));
        etLocalUrl.setHint(targetUrl());
        refreshLocalStatus();
    }

    /** 地址框管终稿模型，预览模型固定走预设。 */
    private String targetUrl() {
        return LocalModelManager.defaultFinalUrl(this);
    }

    private void refreshLocalStatus() {
        if (tvLocalStatus == null) return;
        if (LocalModelManager.isReady(this)) {
            tvLocalStatus.setText("模型已就位，" + LocalModelManager.statusText(this));
            btnDownload.setText("重新下载");
        } else {
            tvLocalStatus.setText("状态：" + LocalModelManager.statusText(this));
            btnDownload.setText("下载模型");
        }
    }

    /**
     * 下载完拿模型真加载一遍。能起来就自动切到本地用这个模型，
     * 起不来就提示用户，多半是地址指的不是语音识别模型。
     */
    private void checkModelAndSwitch() {
        tvLocalStatus.setText("下载完了，正在检查模型…");
        new Thread(() -> {
            String err = LocalAsrEngine.probe(this);
            runOnUiThread(() -> {
                refreshLocalStatus();
                if (err == null) {
                    Prefs.putString(this, Prefs.K_PROTOCOL, AsrConfig.PROTOCOL_LOCAL);
                    renderMode(true);
                    Toast.makeText(this, "模型没问题，已经切到本地识别", Toast.LENGTH_SHORT).show();
                } else {
                    showBadModelDialog(err);
                }
            });
        }).start();
    }

    private void showBadModelDialog(String err) {
        String shortErr = err.length() > 60 ? err.substring(0, 60) + "…" : err;
        new android.app.AlertDialog.Builder(this)
                .setTitle("这个模型用不了")
                .setMessage("文件下下来了，但加载不起来，多半不是语音识别模型。\n\n"
                        + "请确认地址指向 sherpa-onnx 的语音识别模型，并且文件名跟本档约定的一致。"
                        + "普通的大模型、图片模型、TTS 模型都不行。\n\n错误信息：" + shortErr)
                .setPositiveButton("知道了", null)
                .show();
    }

    private void downloadModel() {
        Prefs.putString(this, Prefs.K_LOCAL_URL, "");
        Prefs.putString(this, Prefs.K_LOCAL_URL_SV, etLocalUrl.getText().toString().trim());
        btnDownload.setEnabled(false);
        btnLocalTest.setEnabled(false);
        tvLocalStatus.setText("准备下载…");
        new Thread(() -> LocalModelManager.download(this, new LocalModelManager.Listener() {
            @Override
            public void onFile(int index, int total, String name) {
                runOnUiThread(() -> tvLocalStatus.setText("正在下载第 " + (index + 1)
                        + "/" + total + " 个文件：" + name));
            }

            @Override
            public void onProgress(float progress, long downloaded, long total) {
                runOnUiThread(() -> tvLocalStatus.setText(String.format(java.util.Locale.CHINA,
                        "下载中 %.1f%%  %.1fMB / %.1fMB",
                        progress * 100, downloaded / 1024f / 1024f, total / 1024f / 1024f)));
            }

            @Override
            public void onDone() {
                runOnUiThread(() -> {
                    btnDownload.setEnabled(true);
                    btnLocalTest.setEnabled(true);
                    checkModelAndSwitch();
                    Toast.makeText(SettingsActivity.this, "模型下载完成，可以切到本地用了",
                            Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    btnDownload.setEnabled(true);
                    btnLocalTest.setEnabled(true);
                    tvLocalStatus.setText(message);
                });
            }
        })).start();
    }

    /** 真加载一次模型，中文和英文各喂一段语音进去，能出字才算通过。 */
    private void testLocalModel() {
        if (!LocalModelManager.isReady(this)) {
            tvLocalStatus.setText("模型还没下完，先点上面的下载");
            return;
        }
        btnLocalTest.setEnabled(false);
        tvLocalStatus.setText("正在加载模型…");
        new Thread(() -> {
            final int[] resIds = {R.raw.test_voice, R.raw.test_voice_en};
            final String[] labels = {"中文", "英文"};
            StringBuilder sb = new StringBuilder();
            boolean allOk = true;
            for (int k = 0; k < resIds.length; k++) {
                byte[] pcm = probeWav(resIds[k]);
                if (pcm.length == 0) {
                    sb.append(labels[k]).append("：测试语音读不出来\n");
                    allOk = false;
                    continue;
                }
                final String[] out = {""};
                final boolean[] failed = {false};
                LocalAsrEngine engine = new LocalAsrEngine(this);
                engine.start(new AsrEngine.Callback() {
                    @Override
                    public void onPartial(String text) {
                    }

                    @Override
                    public void onFinal(String text) {
                        out[0] = text;
                    }

                    @Override
                    public void onError(String message) {
                        failed[0] = true;
                        out[0] = message;
                    }
                });
                long t0 = System.currentTimeMillis();
                for (int i = 0; i < pcm.length; i += 3200) {
                    int n = Math.min(3200, pcm.length - i);
                    byte[] chunk = new byte[n];
                    System.arraycopy(pcm, i, chunk, 0, n);
                    engine.feed(chunk, n);
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ignored) {
                    }
                }
                engine.finish();
                long waited = 0;
                while (out[0].isEmpty() && waited < 20000) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ignored) {
                    }
                    waited += 100;
                }
                final long cost = System.currentTimeMillis() - t0;
                String text = out[0];
                if (failed[0] || text == null || text.isEmpty()) {
                    allOk = false;
                    sb.append(labels[k]).append("：没出字（")
                            .append(text == null || text.isEmpty() ? "超时或加载失败" : text)
                            .append("）\n");
                } else {
                    if (k == 0) text = com.minivoice.ime.asr.TextNormalizer.toSimplified(text);
                    sb.append(labels[k]).append("：").append(text)
                            .append("（").append(cost / 1000f).append(" 秒）\n");
                }
            }
            final String result = sb.toString().trim();
            final boolean ok = allOk;
            runOnUiThread(() -> {
                btnLocalTest.setEnabled(true);
                tvLocalStatus.setText((ok ? "本地模型正常\n" : "本地模型有问题\n") + result);
            });
        }).start();
    }

    private void toggleKeyVisible() {
        keyVisible = !keyVisible;
        etApikey.setInputType(keyVisible
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        etApikey.setSelection(etApikey.getText().length());
        btnEye.setImageResource(keyVisible ? R.drawable.ic_eye_off : R.drawable.ic_eye);
        btnEye.setContentDescription(getString(
                keyVisible ? R.string.settings_key_hide : R.string.settings_key_show));
    }

    /** 整理引擎的状态显示：本地显示档位、模型状态和下载按钮，云端显示累计用量。 */
    private void renderPolishEngine() {
        if (rgPolishEngine == null) return;
        String engine = Prefs.getString(this, Prefs.K_POLISH_ENGINE, "cloud");
        boolean local = "local".equals(engine);
        rbPolishLocal.setChecked(local);
        rbPolishCloud.setChecked(!local);
        if (local) {
            rgLlmTier.setVisibility(android.view.View.VISIBLE);
            boolean big = com.minivoice.ime.asr.LocalLlmModel.isBig(this);
            rbLlmBig.setChecked(big);
            rbLlmSmall.setChecked(!big);
            boolean ready = com.minivoice.ime.asr.LocalLlmModel.ready(this);
            tvLlmStatus.setText(com.minivoice.ime.asr.LocalLlmModel.statusText(this));
            btnLlmDownload.setVisibility(android.view.View.VISIBLE);
            btnLlmDownload.setEnabled(true);
            btnLlmDownload.setText(ready ? R.string.llm_test : R.string.llm_download);
        } else {
            rgLlmTier.setVisibility(android.view.View.GONE);
            tvLlmStatus.setText("");
            btnLlmDownload.setVisibility(android.view.View.GONE);
        }
        renderTokens();
    }

    private void startLlmDownload() {
        btnLlmDownload.setEnabled(false);
        btnLlmDownload.setText(R.string.llm_downloading);
        LocalLlmModel.download(this, new LocalLlmModel.Listener() {
            @Override
            public void onProgress(float progress, long downloaded, long total) {
                runOnUiThread(() -> tvLlmStatus.setText(String.format(java.util.Locale.CHINA,
                        "下载中 %.0f%%（%.0fMB / %.0fMB）", progress * 100,
                        downloaded / 1024f / 1024f, total / 1024f / 1024f)));
            }

            @Override
            public void onDone(boolean ok, String message) {
                runOnUiThread(() -> {
                    btnLlmDownload.setEnabled(true);
                    btnLlmDownload.setText(R.string.llm_download);
                    Toast.makeText(SettingsActivity.this, message, Toast.LENGTH_LONG).show();
                    if (ok) {
                        Prefs.putString(SettingsActivity.this, Prefs.K_POLISH_ENGINE, "local");
                        com.minivoice.ime.asr.LocalLlm.release();
                    }
                    renderPolishEngine();
                });
            }
        });
    }

    /** 拿两句话喂给本地模型，看看它整理成什么样，顺带报个耗时。 */
    private void runLlmTest() {
        btnLlmDownload.setEnabled(false);
        btnLlmDownload.setText(R.string.llm_testing);
        tvTest.setVisibility(android.view.View.VISIBLE);
        tvTest.setText("正在加载模型…");
        final String[] samples = {
                "嗯那个我今天下午大概三点左右吧去那个福田的 apple 店看一下 iphone 的新机子啊顺便问问 wifi 那个问题",
                "就是说这个东西呢我觉得还行吧但是它那个 type c 的接口啊好像不太稳当然后那个蓝牙也老是断",
                "呃我跟你讲一下上周那个 project 的情况就是我们本来排的是周四上线但是因为 backend 那边 API 一直没冻结所以拖到了下周一然后 QA 那边测试用例也只跑了一半现在最大的风险是 payment 那个模块的兼容性还有就是说老版本的 Android 用户可能会有问题"
        };
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            for (String sample : samples) {
                long t0 = android.os.SystemClock.uptimeMillis();
                String out = com.minivoice.ime.asr.LlmPolisher.polish(SettingsActivity.this, sample);
                long cost = android.os.SystemClock.uptimeMillis() - t0;
                if (out == null || out.isEmpty()) {
                    out = "（模型没给出结果，可能内存不够或者模型文件有问题）";
                }
                sb.append("原文：").append(sample).append("\n整理：").append(out)
                        .append(String.format(java.util.Locale.CHINA, "\n耗时 %.1f 秒\n\n", cost / 1000f));
            }
            final String text = sb.toString().trim();
            runOnUiThread(() -> {
                btnLlmDownload.setEnabled(true);
                btnLlmDownload.setText(R.string.llm_test);
                tvTest.setText(text);
            });
        }).start();
    }

    /** 已下载的模型列表：名字、体积、删除。 */
    private void renderModels() {
        if (llModels == null) return;
        llModels.removeAllViews();
        boolean hq = LocalModelManager.isHq(this);
        addModelRow("语音预览模型", "zh14m 中英双语", LocalModelManager.dir(this), false);
        addModelRow("语音终稿模型", hq ? "whisper small" : "SenseVoice", LocalModelManager.finalDir(this), false);
        addModelRow("整理小模型", "Qwen2.5-0.5B 409MB",
                new java.io.File(getFilesDir(), com.minivoice.ime.asr.LocalLlmModel.SMALL_DIR), true);
        addModelRow("整理大模型", "Qwen2.5-1.5B 718MB",
                new java.io.File(getFilesDir(), com.minivoice.ime.asr.LocalLlmModel.BIG_DIR), true);
    }

    private void addModelRow(String title, String subtitle, java.io.File dir, boolean isLlm) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        texts.setLayoutParams(tp);

        TextView name = new TextView(this);
        name.setText(title + "（" + subtitle + "）");
        name.setTextColor(getColor(com.minivoice.ime.R.color.text_primary));
        name.setTextSize(13);
        texts.addView(name);

        long size = sizeOf(dir);
        TextView info = new TextView(this);
        info.setText(size <= 0 ? "未下载" : String.format(java.util.Locale.CHINA, "%.0fMB", size / 1024f / 1024f));
        info.setTextColor(getColor(com.minivoice.ime.R.color.text_secondary));
        info.setTextSize(11);
        texts.addView(info);
        row.addView(texts);

        if (size > 0) {
            Button del = new Button(this);
            del.setText("删除");
            del.setTextSize(12);
            del.setAllCaps(false);
            del.setMinWidth(dp(64));
            del.setBackgroundResource(com.minivoice.ime.R.drawable.bg_btn_secondary);
            del.setTextColor(getColor(com.minivoice.ime.R.color.text_primary));
            del.setOnClickListener(v -> confirmDelete(title, dir, isLlm));
            row.addView(del);
        }
        llModels.addView(row);
    }

    private void confirmDelete(String title, java.io.File dir, boolean isLlm) {
        new AlertDialog.Builder(this)
                .setMessage("删掉「" + title + "」？下次要用得重新下载。")
                .setNegativeButton("算了", null)
                .setPositiveButton("删除", (d, w) -> {
                    if (isLlm) com.minivoice.ime.asr.LocalLlm.release();
                    deleteRecursive(dir);
                    Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
                    renderModels();
                    renderPolishEngine();
                    refreshLocalStatus();
                })
                .show();
    }

    private void deleteRecursive(java.io.File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            java.io.File[] kids = f.listFiles();
            if (kids != null) for (java.io.File k : kids) deleteRecursive(k);
        }
        f.delete();
    }

    private long sizeOf(java.io.File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return f.length();
        long sum = 0;
        java.io.File[] kids = f.listFiles();
        if (kids != null) for (java.io.File k : kids) sum += sizeOf(k);
        return sum;
    }

    private void save() {
        AsrConfig c = new AsrConfig();
        c.endpoint = etEndpoint.getText().toString().trim();
        c.apiKey = AsrConfig.sanitizeKey(etApikey.getText().toString());
        c.model = etModel.getText().toString().trim();
        if (c.endpoint.isEmpty()) c.endpoint = AsrConfig.BAILIAN_NATIVE_ENDPOINT;
        if (c.apiKey.isEmpty()) c.apiKey = AsrConfig.BAILIAN_KEY;
        if (c.model.isEmpty()) c.model = AsrConfig.BAILIAN_MODEL;
        c.protocol = AsrConfig.PROTOCOL_LOCAL.equals(Prefs.getString(this, Prefs.K_PROTOCOL, ""))
                ? AsrConfig.PROTOCOL_LOCAL : AsrConfig.PROTOCOL_BAILIAN;
        c.polish = swPolish != null && swPolish.isChecked();
        c.save(this);

        Prefs.putString(this, Prefs.K_DEVICE_NAME, etDevice.getText().toString().trim());
        Prefs.setWordList(this, splitLines(etVocab.getText().toString()));
        Prefs.putString(this, Prefs.K_PUNCT, etPunct.getText().toString().trim());
        Prefs.putString(this, Prefs.K_FILTER, etFilter.getText().toString().trim());
        Prefs.putBool(this, Prefs.K_PREFER_BT, swPreferBt.isChecked());
        Prefs.putString(this, Prefs.K_LOCAL_URL, "");
        Prefs.putString(this, Prefs.K_LOCAL_URL_SV, etLocalUrl.getText().toString().trim());
        refreshLocalStatus();
        Prefs.putString(this, Prefs.K_THEME, ThemeUtil.mode(this));
        Prefs.markPendingSync(this, true);
    }


    /** 两页切换，避免一屏塞太多东西。 */
    private void switchTab(boolean voicePage) {
        this.voicePage = voicePage;
        Prefs.putString(this, "settings_tab", voicePage ? "voice" : "more");
        groupVoice.setVisibility(voicePage ? android.view.View.VISIBLE : android.view.View.GONE);
        groupMore.setVisibility(voicePage ? android.view.View.GONE : android.view.View.VISIBLE);
        styleTab(tabVoice, voicePage);
        styleTab(tabMore, !voicePage);
    }

    /** 选中的页签变蓝底白字，另一个是透明底灰字。 */
    private void styleTab(TextView tab, boolean active) {
        tab.setBackgroundResource(active ? R.drawable.bg_chip_active : R.drawable.bg_chip_idle);
        tab.setTextColor(active ? 0xFFFFFFFF : getColor(R.color.text_secondary));
    }

    private void setTheme(String mode) {
        Prefs.putString(this, Prefs.K_THEME, mode);
        styleThemeChips();
        ThemeUtil.applyToActivity(this);
    }

    private void styleThemeChips() {
        String mode = ThemeUtil.mode(this);
        styleChip(chipAuto, ThemeUtil.AUTO.equals(mode));
        styleChip(chipLight, ThemeUtil.LIGHT.equals(mode));
        styleChip(chipDark, ThemeUtil.DARK.equals(mode));
    }

    private void setSpaceMode(String mode) {
        Prefs.putString(this, Prefs.K_SPACE_MODE, mode);
        styleSpaceChips();
    }

    private void styleSpaceChips() {
        String mode = Prefs.getString(this, Prefs.K_SPACE_MODE, Prefs.SPACE_SEND);
        styleChip(chipSpace, Prefs.SPACE_INSERT.equals(mode));
        styleChip(chipSend, Prefs.SPACE_SEND.equals(mode));
    }

    private void styleChip(TextView chip, boolean active) {
        if (chip == null) return;
        chip.setBackgroundResource(active ? R.drawable.bg_chip_active : R.drawable.bg_chip_idle);
        chip.setTextColor(active ? 0xFFFFFFFF : getColor(R.color.text_secondary));
    }

    private static byte[] wrapWav(byte[] pcm) {
        int rate = 16000, ch = 1;
        int dataLen = pcm.length, byteRate = rate * ch * 2;
        byte[] h = new byte[44];
        h[0] = 0x52; h[1] = 0x49; h[2] = 0x46; h[3] = 0x46;
        int riff = 36 + dataLen;
        h[4] = (byte) riff; h[5] = (byte) (riff >> 8);
        h[6] = (byte) (riff >> 16); h[7] = (byte) (riff >> 24);
        h[8] = 0x57; h[9] = 0x41; h[10] = 0x56; h[11] = 0x45;
        h[12] = 0x66; h[13] = 0x6D; h[14] = 0x74; h[15] = 0x20;
        h[16] = 16; h[20] = 1; h[22] = (byte) ch;
        h[24] = (byte) rate; h[25] = (byte) (rate >> 8);
        h[26] = (byte) (rate >> 16); h[27] = (byte) (rate >> 24);
        h[28] = (byte) byteRate; h[29] = (byte) (byteRate >> 8);
        h[30] = (byte) (byteRate >> 16); h[31] = (byte) (byteRate >> 24);
        h[32] = (byte) (ch * 2); h[34] = 16;
        h[36] = 0x64; h[37] = 0x61; h[38] = 0x74; h[39] = 0x61;
        h[40] = (byte) dataLen; h[41] = (byte) (dataLen >> 8);
        h[42] = (byte) (dataLen >> 16); h[43] = (byte) (dataLen >> 24);
        byte[] out = new byte[44 + dataLen];
        System.arraycopy(h, 0, out, 0, 44);
        System.arraycopy(pcm, 0, out, 44, dataLen);
        return out;
    }

    /** 用当前填写的地址、Key、模型真发一次请求，跑通才是绿。 */
    private void testConnection() {
        String url = etEndpoint.getText().toString().trim();
        String rawKey = etApikey.getText().toString();
        String key = AsrConfig.sanitizeKey(rawKey);
        String model = etModel.getText().toString().trim();

        if (key.isEmpty()) {
            showTest("Key 是空的，先填上再测", COLOR_RED);
            return;
        }
        boolean cleaned = !key.equals(rawKey.trim());
        if (url.isEmpty()) {
            showTest("接口地址是空的，先填一下", COLOR_RED);
            return;
        }
        if (model.isEmpty()) {
            showTest("模型名称是空的，先填一下", COLOR_RED);
            return;
        }

        showTest("正在联网测试…", COLOR_GRAY);
        btnTest.setEnabled(false);

        new Thread(() -> {
            String outIp = fetchOutIp();
            String ipNote = outIp.isEmpty()
                    ? "出口IP 查不到"
                    : ("出口IP " + outIp + (outIp.contains(":") ? "（IPv6）" : "（IPv4）"));
            String body;
            if (model.contains("asr")) {
                String dataUri = "data:audio/wav;base64,"
                        + Base64.encodeToString(probeWav(), Base64.NO_WRAP);
                body = "{\"model\":\"" + model + "\","
                        + "\"input\":{\"messages\":[{\"role\":\"user\",\"content\":["
                        + "{\"type\":\"input_audio\",\"input_audio\":{\"data\":\"" + dataUri + "\"}}]}]},"
                        + "\"parameters\":{\"format\":\"wav\",\"sample_rate\":\"16000\","
                        + "\"language_hints\":[\"zh\"]}}";
            } else {
                body = "{\"model\":\"" + model + "\",\"messages\":"
                        + "[{\"role\":\"user\",\"content\":\"ping\"}],\"max_tokens\":8}";
            }

            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(40, TimeUnit.SECONDS)
                    .protocols(java.util.Collections.singletonList(okhttp3.Protocol.HTTP_1_1))
                    .proxy(Proxy.NO_PROXY)
                    .build();
            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer " + key)
                    .addHeader("X-DashScope-SSE", "disable")
                    .post(RequestBody.create(body,
                            MediaType.parse("application/json; charset=utf-8")))
                    .build();

            try (Response r = client.newCall(request).execute()) {
                String text = r.body() == null ? "" : r.body().string();
                int code = r.code();
                if (code == 200) {
                    runOnUiThread(() -> doneTest(true,
                            "连接正常，这个 Key 和模型可以用", COLOR_GREEN));
                } else if (code == 401 || code == 403) {
                    String tail = key.length() > 4 ? key.substring(key.length() - 4) : key;
                    boolean sameAsBuiltin = fp(key).equals(fp(AsrConfig.BAILIAN_KEY));
                    String rid = requestId(text);
                    String note = "Key 被拒（" + code + "）· 长度" + key.length() + " 尾号" + tail
                            + (cleaned ? "（已清多余字符）" : "")
                            + " · " + (sameAsBuiltin ? "与内置 Key 完全一致" : "与内置 Key 不一致")
                            + " · " + (rid.isEmpty() ? "响应里没有服务端编号，不是百炼发出的" : "服务端编号 " + rid)
                            + " · " + ipNote
                            + " · " + extractMsg(text);
                    runOnUiThread(() -> doneTest(false, note, COLOR_RED));
                } else if (code == 400 && text.contains("ASR_RESPONSE_HAVE_NO_WORDS")) {
                    runOnUiThread(() -> doneTest(true,
                            "连接正常，模型能用（测试音频没识别到字）", COLOR_GREEN));
                } else if (code == 400) {
                    runOnUiThread(() -> doneTest(false,
                            "Key 通了，但请求被拒（400）：" + extractMsg(text), COLOR_YELLOW));
                } else {
                    runOnUiThread(() -> doneTest(false,
                            "请求失败（" + code + "）：" + extractMsg(text), COLOR_RED));
                }
            } catch (Exception e) {
                runOnUiThread(() -> doneTest(false, "网络没通：" + e.getMessage(), COLOR_RED));
            }
        }).start();
    }

    private static final int COLOR_GREEN = 0xFF22C55E;
    private static final int COLOR_RED = 0xFFFF4D4F;
    private static final int COLOR_YELLOW = 0xFFF5A623;
    private static final int COLOR_GRAY = 0xFF9BA1AC;

    private void doneTest(boolean pass, String text, int color) {
        btnTest.setEnabled(true);
        showTest((pass ? "✓ " : "✕ ") + text, color);
    }

    /** Key 和模型的测试结果，显示在测试按钮正下方那一行。 */
    private void showTest(String text, int color) {
        tvKeyResult.setVisibility(TextView.VISIBLE);
        tvKeyResult.setText(text);
        tvKeyResult.setTextColor(color);
    }

    /** 取 Key 的 MD5 前 8 位，用来判断输入框里的串和内置串是不是同一个。 */
    private String fp(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return "?";
        }
    }

    /** 查一次这台手机的出口 IP，IPv6 出口常被网关当成陌生来源。 */
    private String fetchOutIp() {
        try {
            OkHttpClient c = new OkHttpClient.Builder()
                    .connectTimeout(6, TimeUnit.SECONDS)
                    .readTimeout(6, TimeUnit.SECONDS)
                    .proxy(Proxy.NO_PROXY)
                    .build();
            Request req = new Request.Builder().url("https://myip.ipip.net").get().build();
            try (Response resp = c.newCall(req).execute()) {
                if (resp.body() == null) return "";
                String t = resp.body().string();
                int i = t.indexOf("IP：");
                if (i < 0) i = t.indexOf("IP:");
                if (i < 0) return "";
                String sub = t.substring(i + 3).trim();
                int sp = sub.indexOf(" ");
                return (sp > 0 ? sub.substring(0, sp) : sub).trim();
            }
        } catch (Exception e) {
            return "";
        }
    }

    /** 真服务端返回的响应一定带 request_id，没有就说明不是百炼发出来的。 */
    private String requestId(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (root.has("request_id")) return root.get("request_id").getAsString();
        } catch (Exception ignored) {
        }
        return "";
    }

    private String extractMsg(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (root.has("error") && root.get("error").isJsonObject()) {
                JsonObject e = root.getAsJsonObject("error");
                if (e.has("message")) return e.get("message").getAsString();
            }
            if (root.has("message")) return root.get("message").getAsString();
        } catch (Exception ignored) {
        }
        return json.length() > 90 ? json.substring(0, 90) : json;
    }

    /** 包内自带的一段中文语音，真发一次识别请求，跑通才算可用。 */
    private byte[] probeWav() {
        return probeWav(R.raw.test_voice);
    }

    private byte[] probeWav(int resId) {
        try (java.io.InputStream in = getResources().openRawResource(resId)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (IOException e) {
            return new byte[0];
        }
    }

    private List<String> splitLines(String raw) {
        List<String> out = new ArrayList<>();
        for (String line : raw.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private void scan() {
        if (!voicePage) return;
        peers.clear();
        llPeers.removeAllViews();
        tvPeers.setText("扫描中…，最多等 4 秒");
        Button btn = findViewById(R.id.btn_scan);
        btn.setEnabled(false);
        lan.discover(this, new LanSyncManager.PeerCallback() {
            @Override
            public void onResult(List<LanSyncManager.Peer> result) {
                if (isFinishing() || isDestroyed()) return;
                btn.setEnabled(true);
                peers.clear();
                peers.addAll(result);
                if (peers.isEmpty()) {
                    tvPeers.setText("没扫到设备。两台手机要连同一个 WiFi，都打开过这个设置页。");
                    llPeers.removeAllViews();
                } else {
                    tvPeers.setText("找到 " + peers.size() + " 台，点下面的设备把设置推过去");
                    renderPeerList();
                }
            }

            @Override
            public void onError(String message) {
                if (isFinishing() || isDestroyed()) return;
                btn.setEnabled(true);
                tvPeers.setText(message);
            }
        });
    }

    /** 扫到的设备一行一个，点了就推配置过去。 */
    private void renderPeerList() {
        llPeers.removeAllViews();
        for (LanSyncManager.Peer p : peers) {
            TextView row = new TextView(this);
            row.setText(p.name + "\n" + p.host);
            row.setTextSize(12);
            row.setTextColor(getColor(R.color.text_primary));
            row.setLineSpacing(2, 1f);
            row.setBackgroundResource(R.drawable.bg_input);
            row.setPadding(dp(12), dp(9), dp(12), dp(9));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(6);
            row.setLayoutParams(lp);
            row.setOnClickListener(v -> confirmPush(p));
            llPeers.addView(row);
        }
    }

    private void confirmPush(LanSyncManager.Peer p) {
        new AlertDialog.Builder(this)
                .setTitle("推送到 " + p.name)
                .setMessage("把接口、Key、模型、主题、词表这些设置推过去，对方的会合并。")
                .setNegativeButton("取消", null)
                .setPositiveButton("推送", (d, w) -> push(p))
                .show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void push(LanSyncManager.Peer peer) {
        Map<String, String> payload = Prefs.exportSyncPayload(this);
        lan.push(peer, payload, (ok, message) -> {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
            if (ok) Prefs.markPendingSync(this, false);
        });
    }
}

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
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minivoice.ime.R;
import com.minivoice.ime.asr.AsrConfig;
import com.minivoice.ime.asr.AsrEngine;
import com.minivoice.ime.asr.BailianAsrEngine;
import com.minivoice.ime.asr.LocalAsrEngine;
import com.minivoice.ime.asr.RealtimeAsrEngine;
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
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class SettingsActivity extends AppCompatActivity {

    private EditText etEndpoint;
    private EditText etApikey;
    private AutoCompleteTextView etModel;
    private AutoCompleteTextView etPolishModel;
    private TextView tvModelHint;
    private ProgressOutlineButton btnFetchModels;
    private ProgressOutlineButton btnPolishFetch;
    private android.widget.ProgressBar pbFetchModels;
    private android.widget.ProgressBar pbPolishFetch;
    private android.widget.LinearLayout llFetchProgress;
    private android.widget.LinearLayout llPolishFetchProgress;
    private TextView tvFetchPct;
    private TextView tvPolishFetchPct;
    private Button btnPolishCloud;
    private Button btnPolishLocal;
    private TextView btnPolishSync;
    private TextView tvModelArrow;
    private TextView tvPolishModelArrow;
    private TextView tvPolishAdvToggle;
    private LinearLayout llPolishSyncRow;
    private LinearLayout groupPolishAdv;
    private boolean polishAdvOpen = false;
    private TextView tvPolishRules;
    private LinearLayout llPolishRules;
    private EditText etPolishRules;
    private Button btnRulesReset;
    private Button btnRulesSave;
    private boolean rulesExpanded = false;
    private String lastSyncEndpoint = "";
    private String lastSyncKey = "";
    private EditText etPolishEndpoint;
    private EditText etPolishKey;
    private ImageButton btnPolishKeyEye;
    private boolean polishKeyVisible = false;
    private Button btnPolishTest;
    private TextView tvPolishResult;
    private android.widget.LinearLayout groupPolishCloud;
    private EditText etDevice;
    private CompoundButton swPolish;
    private TextView tvLlmStatus;
    private Button btnLlmDownload;
    private LinearLayout llModels;
    private RadioGroup rgLlmTier;
    private RadioButton rbLlmSmall;
    private RadioButton rbLlmBig;
    private RadioButton rbLlmCustom;
    private boolean updatingLlmTier;
    private LinearLayout llTokens;
    private TextView tvTokens;
    private Button btnTokenLog;
    private TextView tvPhrasePersonal;
    private TextView tvPhraseQuick;
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
    private TextView tvAsrCheck;
    private TextView tvPolishCheck;
    private TextView chipSpace;
    private TextView chipSend;
    private TextView chipCloud;
    private TextView chipLocal;
    private TextView tvModeHint;
    private TextView tvLocalStatus;
    private Button btnDownload;
    private RadioGroup rgModelTier;
    private RadioButton rbTierLight;
    private RadioButton rbTierHq;
    private RadioButton rbTierCustom;
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
        etPolishModel = findViewById(R.id.et_polish_model);
        tvModelHint = findViewById(R.id.tv_model_hint);
        etModel.setOnItemClickListener((parent, view, position, id) -> save());
        etPolishModel.setOnItemClickListener((parent, view, position, id) -> save());
        etPolishEndpoint = findViewById(R.id.et_polish_endpoint);
        etPolishKey = findViewById(R.id.et_polish_key);
        btnPolishKeyEye = findViewById(R.id.btn_polish_key_eye);
        tvAsrCheck = null;
        tvPolishCheck = findViewById(R.id.tv_polish_check);
        etModel.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s2, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s2, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable e2) {
                updateModelCapability(e2 == null ? "" : e2.toString());
            }
        });
        etPolishModel.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s2, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s2, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable e2) {
                updatePolishCheck(e2 == null ? "" : e2.toString());
            }
        });
        btnFetchModels = findViewById(R.id.btn_fetch_models);
        btnPolishFetch = findViewById(R.id.btn_polish_fetch);
        pbFetchModels = findViewById(R.id.pb_fetch_models);
        pbPolishFetch = findViewById(R.id.pb_polish_fetch);
        llFetchProgress = findViewById(R.id.ll_fetch_progress);
        llPolishFetchProgress = findViewById(R.id.ll_polish_fetch_progress);
        tvFetchPct = findViewById(R.id.tv_fetch_pct);
        tvPolishFetchPct = findViewById(R.id.tv_polish_pct);
        TextView streamLegend = findViewById(R.id.tv_stream_legend);
        if (streamLegend != null) {
            String legend = "黑色字体不支持流式输入    绿色字体支持流式输入";
            android.text.SpannableString sp = new android.text.SpannableString(legend);
            int greenStart = legend.indexOf("绿色字体");
            sp.setSpan(new android.text.style.ForegroundColorSpan(COLOR_GREEN),
                    greenStart, legend.length(),
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            streamLegend.setText(sp);
        }
        etModel.setOnClickListener(v -> showModelDropDown(etModel));
        etPolishModel.setOnClickListener(v -> showModelDropDown(etPolishModel));
        tvModelArrow = findViewById(R.id.tv_model_arrow);
        tvPolishModelArrow = findViewById(R.id.tv_polish_model_arrow);
        tvModelArrow.setOnClickListener(v -> showModelDropDown(etModel));
        tvPolishModelArrow.setOnClickListener(v -> showModelDropDown(etPolishModel));
        btnFetchModels.setOnClickListener(v -> fetchModels(false));
        btnPolishFetch.setOnClickListener(v -> fetchModels(true));
        btnPolishTest = findViewById(R.id.btn_polish_test);
        tvPolishResult = findViewById(R.id.tv_polish_result);
        groupPolishCloud = findViewById(R.id.group_polish_cloud);
        etDevice = findViewById(R.id.et_device);
        swPolish = findViewById(R.id.sw_polish);
        btnPolishCloud = findViewById(R.id.btn_polish_cloud);
        btnPolishLocal = findViewById(R.id.btn_polish_local);
        btnPolishSync = findViewById(R.id.btn_polish_sync);
        tvPolishAdvToggle = findViewById(R.id.tv_polish_adv_toggle);
        llPolishSyncRow = findViewById(R.id.ll_polish_sync_row);
        groupPolishAdv = findViewById(R.id.group_polish_adv);
        tvPolishAdvToggle.setOnClickListener(v -> togglePolishAdv());
        tvLlmStatus = findViewById(R.id.tv_llm_status);
        btnLlmDownload = findViewById(R.id.btn_llm_download);
        llModels = findViewById(R.id.ll_models);
        rgLlmTier = findViewById(R.id.rg_llm_tier);
        rbLlmSmall = findViewById(R.id.rb_llm_small);
        rbLlmBig = findViewById(R.id.rb_llm_big);
        llTokens = findViewById(R.id.ll_tokens);
        tvTokens = findViewById(R.id.tv_tokens);
        btnTokenLog = findViewById(R.id.btn_token_log);
        tvPolishRules = findViewById(R.id.tv_polish_rules);
        llPolishRules = findViewById(R.id.ll_polish_rules);
        etPolishRules = findViewById(R.id.et_polish_rules);
        btnRulesReset = findViewById(R.id.btn_rules_reset);
        btnRulesSave = findViewById(R.id.btn_rules_save);
        tvPolishRules.setOnClickListener(v -> toggleRules());
        btnRulesReset.setOnClickListener(v -> etPolishRules.setText(defaultRulesForNow()));
        btnRulesSave.setOnClickListener(v -> saveRules());
        btnPolishSync.setOnClickListener(v -> {
            String top = etEndpoint.getText().toString().trim();
            String fixed = AsrConfig.adaptEndpointForKey(top,
                    AsrConfig.sanitizeKey(etApikey.getText().toString()));
            if (!fixed.equals(top)) etEndpoint.setText(fixed);
            etPolishEndpoint.setText(AsrConfig.polishEndpoint(fixed));
            etPolishKey.setText(AsrConfig.sanitizeKey(etApikey.getText().toString()));
            lastSyncEndpoint = etPolishEndpoint.getText().toString().trim();
            lastSyncKey = etPolishKey.getText().toString();
            Prefs.putString(this, Prefs.K_POLISH_ENDPOINT, lastSyncEndpoint);
            Prefs.putString(this, Prefs.K_POLISH_KEY, lastSyncKey);
            Toast.makeText(this, "已同步识别模型的接口地址和 API Key", Toast.LENGTH_SHORT).show();
        });
        // 上面改了地址或 Key，下面没手动动过就跟着变
        etEndpoint.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s2, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s2, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable e2) {
                mirrorTopToPolish();
            }
        });
        etApikey.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s2, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s2, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable e2) {
                mirrorTopToPolish();
            }
        });
        tvPhrasePersonal = findViewById(R.id.tv_phrase_personal);
        tvPhrasePersonal.setOnClickListener(v ->
                showPhraseList(com.minivoice.ime.data.PhraseStore.TYPE_PERSONAL));
        tvPhraseQuick = findViewById(R.id.tv_phrase_quick);
        tvPhraseQuick.setOnClickListener(v ->
                showPhraseList(com.minivoice.ime.data.PhraseStore.TYPE_QUICK));
        btnTokenLog.setOnClickListener(v -> showTokenLog());
        rbLlmCustom = findViewById(R.id.rb_llm_custom);
        rgLlmTier.setOnCheckedChangeListener((g, id) -> {
            if (updatingLlmTier) return;
            if (id == R.id.rb_llm_custom) {
                showCustomLlmDialog();
                return;
            }
            Prefs.putString(this, Prefs.K_LLM_TIER,
                    id == R.id.rb_llm_big ? com.minivoice.ime.asr.LocalLlmModel.TIER_BIG
                            : com.minivoice.ime.asr.LocalLlmModel.TIER_SMALL);
            com.minivoice.ime.asr.LocalLlm.release();
            renderPolishEngine();
            renderModels();
        });
        btnPolishCloud.setOnClickListener(v -> selectPolishEngine(false));
        btnPolishLocal.setOnClickListener(v -> selectPolishEngine(true));
        btnLlmDownload.setOnClickListener(v -> {
            if (com.minivoice.ime.asr.LocalLlmModel.TIER_CUSTOM.equals(
                    com.minivoice.ime.asr.LocalLlmModel.tier(this))
                    && com.minivoice.ime.asr.LocalLlmModel.url(this).isEmpty()) {
                showCustomLlmDialog();
                return;
            }
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
        btnDownload = findViewById(R.id.btn_download);
        btnLocalTest = null;
        rgModelTier = findViewById(R.id.rg_model_tier);
        rbTierLight = findViewById(R.id.rb_tier_light);
        rbTierHq = findViewById(R.id.rb_tier_hq);
        rbTierCustom = findViewById(R.id.rb_tier_custom);
        tvModelSummary = findViewById(R.id.tv_model_summary);
        if (rgModelTier != null) {
            rgModelTier.setOnCheckedChangeListener((group, checkedId) -> {
                if (bindingTier) return;
                if (checkedId == R.id.rb_tier_custom) {
                    Prefs.putString(this, Prefs.K_LOCAL_TIER, LocalModelManager.TIER_CUSTOM);
                    showCustomAsrDialog();
                    refreshModelUi();
                    return;
                }
                boolean hq = checkedId == R.id.rb_tier_hq;
                Prefs.putString(this, Prefs.K_LOCAL_TIER,
                        hq ? LocalModelManager.TIER_HQ : LocalModelManager.TIER_LIGHT);
                // 换档就把手填的地址清掉，回到新档的默认镜像，省得拿旧地址下新模型
                Prefs.putString(this, Prefs.K_LOCAL_URL, "");
                Prefs.putString(this, Prefs.K_LOCAL_URL_SV, "");
                refreshModelUi();
            });
        }
        groupCloud = findViewById(R.id.group_cloud);
        groupLocal = findViewById(R.id.group_local);

        chipCloud.setOnClickListener(v -> selectMode(false));
        chipLocal.setOnClickListener(v -> selectMode(true));
        btnDownload.setOnClickListener(v -> onLocalModelButton());

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
        if (btnPolishKeyEye != null) {
            btnPolishKeyEye.setOnClickListener(v -> togglePolishKeyVisible());
        }
        btnTest.setOnClickListener(v -> testConnection());
        btnPolishTest.setOnClickListener(v -> testPolishModel());
        tabVoice.setOnClickListener(v -> switchTab(true));
        tabMore.setOnClickListener(v -> switchTab(false));

        chipAuto.setOnClickListener(v -> setTheme(ThemeUtil.AUTO));
        chipLight.setOnClickListener(v -> setTheme(ThemeUtil.LIGHT));
        chipDark.setOnClickListener(v -> setTheme(ThemeUtil.DARK));

        Button btnSave = findViewById(R.id.btn_save);
        Button btnScan = findViewById(R.id.btn_scan);

        load();
        renderModels();
        styleThemeChips();
        styleSpaceChips();
        renderMode(AsrConfig.PROTOCOL_LOCAL.equals(Prefs.getString(this, Prefs.K_PROTOCOL, "")));
        switchTab(voicePage);
        normalizeButtons(findViewById(android.R.id.content));

        btnSave.setOnClickListener(v -> {
            save();
            flashSaved(btnSave);
        });

        btnScan.setOnClickListener(v -> scan());
        View runLog = findViewById(R.id.btn_run_log);
        if (runLog != null) runLog.setOnClickListener(v -> showAsrDiag());

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
        // 设置页改动只有在键盘真正加载配置后才生效；离开页面时自动落盘一次，
        // 避免“后台测试用的是输入框里的新 Key，前台还在用旧值”。
        if (etEndpoint != null) save();
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
        etPolishModel.setText(Prefs.getString(this, Prefs.K_POLISH_MODEL, AsrConfig.POLISH_MODEL));
        // 整理接口不再单独配置，永远跟识别地址和 Key 走。
        String pe = AsrConfig.polishEndpoint(c.endpoint);
        etPolishEndpoint.setText(pe);
        etPolishKey.setText(c.apiKey);
        lastSyncEndpoint = pe;
        lastSyncKey = c.apiKey;
        Prefs.putString(this, Prefs.K_POLISH_ENDPOINT, pe);
        Prefs.putString(this, Prefs.K_POLISH_KEY, c.apiKey);
        rulesExpanded = false;
        polishAdvOpen = false;
        if (groupPolishAdv != null) groupPolishAdv.setVisibility(android.view.View.GONE);
        if (tvPolishAdvToggle != null) tvPolishAdvToggle.setText(R.string.settings_polish_expand);
        if (llPolishRules != null) llPolishRules.setVisibility(android.view.View.GONE);
        if (tvPolishRules != null) tvPolishRules.setText("查看和修改AI整理规则  ▾");

        etDevice.setText(Prefs.getString(this, Prefs.K_DEVICE_NAME,
                android.os.Build.MODEL));
        swPolish.setChecked(c.polish);
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
        int asr = Prefs.getInt(this, Prefs.K_TOKEN_ASR, 0);
        int polish = Prefs.getInt(this, Prefs.K_TOKEN_POLISH, 0);
        int count = Prefs.getInt(this, Prefs.K_TOKEN_QUESTS, 0);
        String polishText = cloudPolish ? formatCount(polish) : "本地不计";
        tvTokens.setText(String.format(java.util.Locale.CHINA,
                "识别 %s · 整理 %s tokens，共 %d 次", formatCount(asr), polishText, count));
        llTokens.setVisibility(cloudAsr || cloudPolish
                ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    /** 点「明细」弹一块小窗，一次说话算一行：时间、识别、整理、合计。 */
    /** 运行日志里错误行标红，正常行跟随主题文字色。 */
    private android.text.SpannableStringBuilder buildDiagText(String log) {
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        for (String line : log.split("\n", -1)) {
            int start = sb.length();
            sb.append(line).append('\n');
            int color = isDiagErrorLine(line)
                    ? 0xFFE53935 : getColor(R.color.text_primary);
            sb.setSpan(new android.text.style.ForegroundColorSpan(color),
                    start, sb.length(),
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sb;
    }

    private boolean isDiagErrorLine(String line) {
        String u = line == null ? "" : line.toUpperCase(java.util.Locale.ROOT);
        return u.contains("FAILURE") || u.contains("ERROR") || u.contains("DENIED")
                || u.contains("EXCEPTION") || u.contains("失败") || u.contains("错误")
                || u.contains("MODELS_EMPTY") || u.contains("MODELS_NO_USABLE")
                || u.contains("MODELS_NO_KEY")
                || u.matches(".*CODE=(4|5)[0-9][0-9].*");
    }

    private String collectErrorLines(String log) {
        StringBuilder sb = new StringBuilder();
        for (String line : log.split("\n")) {
            if (isDiagErrorLine(line)) sb.append(line).append('\n');
        }
        return sb.toString().trim();
    }

    /** 设置页里的失败也要写进运行日志，不能只弹个 Toast。 */
    private void logSettingsDiag(String line) {
        String old = Prefs.getString(this, "asr_diag", "");
        StringBuilder sb = new StringBuilder(old);
        sb.append(android.os.SystemClock.elapsedRealtime()).append("  ")
                .append(line).append('\n');
        if (sb.length() > 6000) sb.delete(0, sb.length() - 6000);
        Prefs.putString(this, "asr_diag", sb.toString());
    }

    /** 最近一次实时识别的精简事件日志，方便直接复制回来排查。 */
    private void showAsrDiag() {
        final android.app.Dialog d = new android.app.Dialog(this);
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(16), dp(18), dp(14));
        box.setBackgroundResource(R.drawable.bg_card);

        TextView head = new TextView(this);
        head.setText("运行日志");
        head.setTextSize(15);
        head.setTextColor(getColor(R.color.text_primary));
        head.setTypeface(null, android.graphics.Typeface.BOLD);
        box.addView(head);

        String log = Prefs.getString(this, "asr_diag", "");
        if (log.isEmpty()) log = "还没有记录。请按住说话一次，再打开这里。";
        TextView body = new TextView(this);
        body.setText(buildDiagText(log));
        body.setTextSize(10);
        body.setTextColor(getColor(R.color.text_primary));
        body.setTypeface(android.graphics.Typeface.MONOSPACE);
        body.setTextIsSelectable(true);
        body.setLineSpacing(dp(2), 1f);

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.setPadding(0, dp(8), 0, dp(8));
        sv.addView(body);
        box.addView(sv, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        android.widget.Button copy = new android.widget.Button(this);
        copy.setText("复制错误日志");
        copy.setAllCaps(false);
        copy.setTextColor(getColor(R.color.text_primary));
        copy.setBackgroundResource(R.drawable.bg_btn_secondary);
        copy.setElevation(0);
        copy.setStateListAnimator(null);
        android.widget.Button close = new android.widget.Button(this);
        close.setText("关闭");
        close.setAllCaps(false);
        close.setTextColor(0xFFFFFFFF);
        close.setBackgroundResource(R.drawable.bg_btn_primary);
        close.setElevation(0);
        close.setStateListAnimator(null);
        android.widget.LinearLayout.LayoutParams lp =
                new android.widget.LinearLayout.LayoutParams(0, dp(40), 1);
        row.addView(copy, lp);
        android.widget.LinearLayout.LayoutParams lp2 =
                new android.widget.LinearLayout.LayoutParams(0, dp(40), 1);
        lp2.setMarginStart(dp(8));
        row.addView(close, lp2);
        box.addView(row);
        String errorText = collectErrorLines(log);
        if (errorText.isEmpty()) {
            copy.setEnabled(false);
            copy.setAlpha(0.45f);
            copy.setText("没有错误日志");
        } else {
            copy.setOnClickListener(v -> {
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("错误日志", errorText));
                Toast.makeText(this, "已复制错误日志，直接粘贴发给我", Toast.LENGTH_SHORT).show();
            });
        }
        close.setOnClickListener(v -> d.dismiss());
        d.setContentView(box);
        d.show();
        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            android.view.WindowManager.LayoutParams attrs = w.getAttributes();
            attrs.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.9f);
            attrs.height = (int) (getResources().getDisplayMetrics().heightPixels * 0.6f);
            w.setAttributes(attrs);
        }
    }

    private void showTokenLog() {
        String raw = Prefs.getString(this, Prefs.K_TOKEN_LOG, "");
        int total = Prefs.getInt(this, Prefs.K_TOKEN_TOTAL, 0);
        int count = Prefs.getInt(this, Prefs.K_TOKEN_QUESTS, 0);

        int asrSum = 0;
        int polishSum = 0;
        java.util.List<long[]> data = new java.util.ArrayList<>();
        for (String line : raw.split("\n")) {
            String[] p = line.trim().split("\\|");
            if (p.length < 3) continue;
            long ts;
            int a;
            int b;
            try {
                ts = Long.parseLong(p[0]);
                a = Integer.parseInt(p[1].trim());
                b = Integer.parseInt(p[2].trim());
            } catch (Exception e) {
                continue;
            }
            asrSum += a;
            polishSum += b;
            data.add(new long[]{ts, a, b});
        }

        final android.app.Dialog d = new android.app.Dialog(this);
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = dp(18);
        // 表格左右各留出约 5% 的内边距，不让时间和合计顶着弹窗边缘。
        box.setPadding(dp(24), pad, dp(24), pad);

        android.widget.LinearLayout headRow = new android.widget.LinearLayout(this);
        headRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        headRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        android.widget.TextView head = new android.widget.TextView(this);
        head.setText("消耗明细");
        head.setTextSize(16);
        head.setTextColor(getColor(R.color.text_primary));
        head.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        headRow.addView(head);
        box.addView(headRow);

        android.widget.LinearLayout sumRow = new android.widget.LinearLayout(this);
        sumRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        sumRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        sumRow.setPadding(0, dp(6), 0, dp(8));
        android.widget.TextView sum = new android.widget.TextView(this);
        sum.setText("识别 " + formatCount(asrSum) + " · 整理 " + formatCount(polishSum)
                + " tokens，共 " + count + " 次");
        sum.setTextSize(12);
        sum.setMaxLines(2);
        sum.setTextColor(getColor(R.color.text_secondary));
        sum.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        sumRow.addView(sum);
        android.widget.TextView clear = new android.widget.TextView(this);
        clear.setText("清零");
        clear.setTextSize(12);
        clear.setTextColor(getColor(R.color.on_accent));
        clear.setTypeface(null, android.graphics.Typeface.BOLD);
        clear.setGravity(android.view.Gravity.CENTER);
        android.graphics.drawable.GradientDrawable clearBg =
                new android.graphics.drawable.GradientDrawable();
        clearBg.setColor(COLOR_RED);
        clearBg.setCornerRadius(dp(9));
        clear.setBackground(clearBg);
        clear.setMinWidth(dp(72));
        clear.setPadding(dp(16), 0, dp(16), 0);
        clear.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)));
        clear.setOnClickListener(v -> {
            Prefs.resetTokens(this);
            Prefs.resetLog(this);
            renderTokens();
            d.dismiss();
            Toast.makeText(this, "云端用量已清零，从现在重新算", Toast.LENGTH_SHORT).show();
        });
        sumRow.addView(clear);
        box.addView(sumRow);

        android.view.View topLine = new android.view.View(this);
        topLine.setBackgroundColor(getColor(R.color.divider));
        box.addView(topLine, new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp2(0.6f))));

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        android.widget.LinearLayout list = new android.widget.LinearLayout(this);
        list.setOrientation(android.widget.LinearLayout.VERTICAL);
        list.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        sv.addView(list);

        if (data.isEmpty()) {
            android.widget.TextView empty = new android.widget.TextView(this);
            empty.setText("还没有消耗记录，去说两句话再来");
            empty.setTextSize(13);
            empty.setTextColor(getColor(R.color.text_secondary));
            empty.setPadding(0, pad, 0, 0);
            list.addView(empty);
        } else {
            // 表头
            list.addView(logRow("时间", "识别", "整理", "合计", true));
            int show = Math.min(data.size(), 8);
            java.text.SimpleDateFormat fmt =
                    new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA);
            for (int i = 0; i < show; i++) {
                long[] r = data.get(i);
                list.addView(logRow(fmt.format(new java.util.Date(r[0])),
                        String.valueOf(r[1]), String.valueOf(r[2]),
                        String.valueOf(r[1] + r[2]), false));
                if (i < show - 1) {
                    android.view.View line = new android.view.View(this);
                    line.setBackgroundColor(getColor(R.color.divider));
                    list.addView(line, new android.widget.LinearLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            Math.max(1, dp2(0.6f))));
                }
            }
        }
        box.addView(sv, new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 关闭放在弹窗底部正中，离上面的“清零”远一点，避免误触。
        android.widget.LinearLayout closeRow = new android.widget.LinearLayout(this);
        closeRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        closeRow.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        closeRow.setPadding(0, dp(10), 0, 0);
        android.widget.TextView closeX = new android.widget.TextView(this);
        closeX.setText("✕");
        closeX.setTextSize(18);
        closeX.setGravity(android.view.Gravity.CENTER);
        closeX.setIncludeFontPadding(false);
        closeX.setTextColor(getColor(R.color.text_primary));
        closeX.setBackgroundResource(R.drawable.bg_btn_secondary);
        closeX.setContentDescription("关闭消耗明细");
        closeX.setLayoutParams(new android.widget.LinearLayout.LayoutParams(dp(42), dp(42)));
        closeX.setOnClickListener(v -> d.dismiss());
        closeRow.addView(closeX);
        box.addView(closeRow);

        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(getColor(R.color.card_bg));
        bg.setCornerRadius(dp(18));
        d.getWindow().setBackgroundDrawable(bg);
        d.setContentView(box);
        d.setCanceledOnTouchOutside(true);
        android.view.Window w = d.getWindow();
        w.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.9),
                (int) (getResources().getDisplayMetrics().heightPixels * 0.6));
        w.setGravity(android.view.Gravity.CENTER);
        d.show();
    }

    private android.view.View logRow(String c1, String c2, String c3, String c4, boolean head) {
        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(7), 0, dp(7));
        row.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        String[] cols = {c1, c2, c3, c4};
        for (int i = 0; i < 4; i++) {
            android.widget.TextView t = new android.widget.TextView(this);
            t.setText(cols[i]);
            t.setTextSize(11);
            t.setTextColor(getColor(head ? R.color.text_secondary : R.color.text_primary));
            if (i == 0) {
                // 时间列收窄到 62dp，字号也降一档，避免它占掉整张表的大半。
                t.setSingleLine(true);
                t.setTextSize(10);
                t.setLayoutParams(new android.widget.LinearLayout.LayoutParams(dp(62),
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
            } else {
                t.setGravity(android.view.Gravity.END);
                t.setLayoutParams(new android.widget.LinearLayout.LayoutParams(dp(64),
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            row.addView(t);
        }
        return row;
    }

    private int dp2(float v) {
        return (int) (getResources().getDisplayMetrics().density * v);
    }

    /** 整理提示词做成可编辑的，改完点保存立刻生效，也能一键恢复默认。 */
    private void showModelDropDown(AutoCompleteTextView target) {
        target.requestFocus();
        if (target.getAdapter() != null && target.getAdapter().getCount() > 0) {
            target.showDropDown();
        }
    }

    /** 同步那一行右边的箭头，展开或收起网址和 Key。 */
    private void togglePolishAdv() {
        polishAdvOpen = !polishAdvOpen;
        if (groupPolishAdv != null) groupPolishAdv.setVisibility(
                polishAdvOpen ? android.view.View.VISIBLE : android.view.View.GONE);
        if (tvPolishAdvToggle != null) tvPolishAdvToggle.setText(
                polishAdvOpen ? R.string.settings_polish_collapse
                        : R.string.settings_polish_expand);
    }

    private void selectPolishEngine(boolean local) {
        Prefs.putString(this, Prefs.K_POLISH_ENGINE, local ? "local" : "cloud");
        renderPolishEngine();
        renderModels();
        if (rulesExpanded) showRulesEditor();
    }

    /** 顶部地址或 Key 改了，整理那边没手动改过就跟着同步。 */
    private void mirrorTopToPolish() {
        if (etPolishEndpoint == null || etPolishKey == null) return;
        String ep = AsrConfig.polishEndpoint(etEndpoint.getText().toString().trim());
        String key = AsrConfig.sanitizeKey(etApikey.getText().toString());
        if (!ep.equals(etPolishEndpoint.getText().toString().trim())) etPolishEndpoint.setText(ep);
        if (!key.equals(etPolishKey.getText().toString())) etPolishKey.setText(key);
        lastSyncEndpoint = ep;
        lastSyncKey = key;
    }

    /** 规则入口点一下展开，再点收起。 */
    private void toggleRules() {
        rulesExpanded = !rulesExpanded;
        if (llPolishRules != null) llPolishRules.setVisibility(
                rulesExpanded ? android.view.View.VISIBLE : android.view.View.GONE);
        if (tvPolishRules != null) tvPolishRules.setText(
                rulesExpanded ? "查看和修改AI整理规则  ▴" : "查看和修改AI整理规则  ▾");
        if (rulesExpanded) showRulesEditor();
    }

    /** 规则窗口按多行展示，避免整段文字挤成一行看不全。 */
    private void showRulesEditor() {
        if (etPolishRules == null) return;
        String text = currentRules();
        etPolishRules.setSingleLine(false);
        etPolishRules.setHorizontallyScrolling(false);
        etPolishRules.setMaxLines(Integer.MAX_VALUE);
        etPolishRules.setText(text.replace("\\n", "\n"));
        etPolishRules.setSelection(0);
    }

    private boolean localPolish() {
        return "local".equals(Prefs.getString(this, Prefs.K_POLISH_ENGINE, "cloud"));
    }

    private String currentRules() {
        return localPolish()
                ? com.minivoice.ime.asr.LlmPolisher.rulesText(this)
                : com.minivoice.ime.asr.TextPolisher.rulesText(this);
    }

    private String defaultRulesForNow() {
        return localPolish()
                ? com.minivoice.ime.asr.LlmPolisher.defaultRules()
                : com.minivoice.ime.asr.TextPolisher.defaultRules();
    }

    private void saveRules() {
        String key = localPolish() ? Prefs.K_PROMPT_LOCAL : Prefs.K_PROMPT_CLOUD;
        Prefs.putString(this, key, etPolishRules.getText().toString().trim());
        Toast.makeText(this, "整理规则已保存", Toast.LENGTH_SHORT).show();
    }

    /** 把规格和说明文字刷成当前档的样子。 */
    private void refreshModelUi() {
        if (tvModelSummary == null) return;
        boolean custom = LocalModelManager.isCustom(this);
        boolean hq = LocalModelManager.isHq(this);
        bindingTier = true;
        rbTierLight.setChecked(!custom && !hq);
        rbTierHq.setChecked(!custom && hq);
        if (rbTierCustom != null) rbTierCustom.setChecked(custom);
        bindingTier = false;
        tvModelSummary.setText(LocalModelManager.tierSummary(this));
        refreshLocalStatus();
    }

    /** 下载/测试合并成一个按钮：没下就下载，下好了就测试。 */
    private void onLocalModelButton() {
        if (LocalModelManager.isCustom(this)
                && Prefs.getString(this, Prefs.K_LOCAL_URL_SV, "").trim().isEmpty()) {
            showCustomAsrDialog();
            return;
        }
        if (LocalModelManager.isReady(this)) testLocalModel();
        else downloadModel();
    }

    private void refreshLocalStatus() {
        if (tvLocalStatus == null) return;
        boolean ready = LocalModelManager.isReady(this);
        if (ready) {
            tvLocalStatus.setText("模型已就位，" + LocalModelManager.statusText(this));
            btnDownload.setText("本地模型语音识别测试");
        } else {
            tvLocalStatus.setText("状态：" + LocalModelManager.statusText(this));
            btnDownload.setText(LocalModelManager.isCustom(this) ? "填写或下载模型" : "下载模型");
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
        String title = LocalModelManager.isCustom(this)
                ? "这个模型不支持语音识别" : "这个模型用不了";
        showCardDialog(title,
                "文件下下来了，但加载不起来，多半不是语音识别模型。\n\n"
                        + "请确认地址指向 sherpa-onnx 的语音识别模型，并且文件名跟本档约定的一致。"
                        + "普通的大模型、图片模型、TTS 模型都不行。\n\n错误信息：" + shortErr,
                null, "知道了", null);
    }

    /** 自定义语音模型：只接受 sherpa-onnx 的 SenseVoice 目录，下载后真加载校验。 */
    private void showCustomAsrDialog() {
        final android.app.Dialog d = new android.app.Dialog(this);
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(16), dp(18), dp(14));
        box.setBackgroundResource(R.drawable.bg_card);

        android.widget.TextView head = new android.widget.TextView(this);
        head.setText("自定义语音识别模型");
        head.setTextSize(15);
        head.setTextColor(getColor(R.color.text_primary));
        head.setTypeface(null, android.graphics.Typeface.BOLD);
        box.addView(head);

        android.widget.TextView note = new android.widget.TextView(this);
        note.setText("只支持 sherpa-onnx 的 SenseVoice 语音识别模型目录，目录里要有 model.int8.onnx 和 tokens.txt。"
                + "普通大模型、图片模型、TTS 模型都不行，下载后会先加载校验。");
        note.setTextSize(11);
        note.setTextColor(getColor(R.color.text_secondary));
        note.setLineSpacing(dp(2), 1f);
        note.setPadding(0, dp(8), 0, dp(8));
        box.addView(note);

        final android.widget.EditText input = new android.widget.EditText(this);
        input.setSingleLine(true);
        input.setText(Prefs.getString(this, Prefs.K_LOCAL_URL_SV, ""));
        input.setHint("https://…/sherpa-sensevoice/");
        input.setTextSize(12);
        input.setPadding(dp(10), dp(8), dp(10), dp(8));
        input.setBackgroundResource(R.drawable.bg_input);
        box.addView(input);

        android.widget.TextView bottom = new android.widget.TextView(this);
        bottom.setText("地址要以 / 结尾，填模型的目录，不是 zip 文件。");
        bottom.setTextSize(10);
        bottom.setTextColor(getColor(R.color.text_hint));
        bottom.setPadding(0, dp(6), 0, dp(8));
        box.addView(bottom);

        android.widget.LinearLayout buttons = new android.widget.LinearLayout(this);
        buttons.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        android.widget.Button cancel = new android.widget.Button(this);
        cancel.setText("取消");
        cancel.setAllCaps(false);
        cancel.setBackgroundResource(R.drawable.bg_btn_secondary);
        cancel.setTextColor(getColor(R.color.text_primary));
        cancel.setElevation(0);
        cancel.setStateListAnimator(null);
        cancel.setIncludeFontPadding(false);
        android.widget.Button ok = new android.widget.Button(this);
        ok.setText("保存并下载");
        ok.setAllCaps(false);
        ok.setBackgroundResource(R.drawable.bg_btn_primary);
        ok.setTextColor(0xFFFFFFFF);
        ok.setElevation(0);
        ok.setStateListAnimator(null);
        ok.setIncludeFontPadding(false);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                0, dp(40), 1);
        buttons.addView(cancel, lp);
        android.widget.LinearLayout.LayoutParams lp2 = new android.widget.LinearLayout.LayoutParams(
                0, dp(40), 1);
        lp2.setMarginStart(dp(8));
        buttons.addView(ok, lp2);
        box.addView(buttons, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));

        cancel.setOnClickListener(v -> d.dismiss());
        ok.setOnClickListener(v -> {
            String url = input.getText().toString().trim();
            if (!url.endsWith("/")) url += "/";
            Prefs.putString(this, Prefs.K_LOCAL_URL_SV, url);
            Prefs.putString(this, Prefs.K_LOCAL_TIER, LocalModelManager.TIER_CUSTOM);
            d.dismiss();
            refreshModelUi();
            downloadModel();
        });

        d.setContentView(box);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            d.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.9),
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        d.show();
    }

    private void downloadModel() {
        if (!LocalModelManager.isCustom(this)) {
            Prefs.putString(this, Prefs.K_LOCAL_URL, "");
            Prefs.putString(this, Prefs.K_LOCAL_URL_SV, "");
        }
        btnDownload.setEnabled(false);
        if (btnLocalTest != null) btnLocalTest.setEnabled(false);
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
                    if (btnLocalTest != null) btnLocalTest.setEnabled(true);
                    checkModelAndSwitch();
                    Toast.makeText(SettingsActivity.this, "模型下载完成，可以切到本地用了",
                            Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    btnDownload.setEnabled(true);
                    if (btnLocalTest != null) btnLocalTest.setEnabled(true);
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
        btnDownload.setEnabled(false);
        if (btnLocalTest != null) btnLocalTest.setEnabled(false);
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
                btnDownload.setEnabled(true);
                if (btnLocalTest != null) btnLocalTest.setEnabled(true);
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

    private void togglePolishKeyVisible() {
        polishKeyVisible = !polishKeyVisible;
        etPolishKey.setInputType(polishKeyVisible
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        etPolishKey.setSelection(etPolishKey.getText().length());
        btnPolishKeyEye.setImageResource(polishKeyVisible ? R.drawable.ic_eye_off : R.drawable.ic_eye);
        btnPolishKeyEye.setContentDescription(getString(
                polishKeyVisible ? R.string.settings_key_hide : R.string.settings_key_show));
    }

    /** 整理引擎的状态显示：本地显示档位、模型状态和下载按钮，云端显示累计用量。 */
    private void renderPolishEngine() {
        if (btnPolishCloud == null) return;
        String engine = Prefs.getString(this, Prefs.K_POLISH_ENGINE, "cloud");
        boolean local = "local".equals(engine);
        if (groupPolishCloud != null) {
            groupPolishCloud.setVisibility(local ? android.view.View.GONE : android.view.View.VISIBLE);
        }
        // 同步和展开入口已经废弃，云端模式也不再显示。
        if (llPolishSyncRow != null) {
            llPolishSyncRow.setVisibility(android.view.View.GONE);
        }
        if (groupPolishAdv != null) {
            groupPolishAdv.setVisibility(android.view.View.GONE);
        }
        btnPolishCloud.setBackgroundResource(local ? R.drawable.bg_chip_idle : R.drawable.bg_chip_active);
        btnPolishCloud.setTextColor(local ? getColor(R.color.text_secondary) : 0xFFFFFFFF);
        btnPolishLocal.setBackgroundResource(local ? R.drawable.bg_chip_active : R.drawable.bg_chip_idle);
        btnPolishLocal.setTextColor(local ? 0xFFFFFFFF : getColor(R.color.text_secondary));
        if (local) {
            rgLlmTier.setVisibility(android.view.View.VISIBLE);
            String tier = com.minivoice.ime.asr.LocalLlmModel.tier(this);
            boolean big = com.minivoice.ime.asr.LocalLlmModel.TIER_BIG.equals(tier);
            boolean custom = com.minivoice.ime.asr.LocalLlmModel.TIER_CUSTOM.equals(tier);
            updatingLlmTier = true;
            rbLlmBig.setChecked(big);
            rbLlmSmall.setChecked(!big && !custom);
            if (rbLlmCustom != null) rbLlmCustom.setChecked(custom);
            updatingLlmTier = false;
            boolean ready = com.minivoice.ime.asr.LocalLlmModel.ready(this);
            tvLlmStatus.setText(com.minivoice.ime.asr.LocalLlmModel.statusText(this));
            btnLlmDownload.setVisibility(android.view.View.VISIBLE);
            btnLlmDownload.setEnabled(true);
            if (ready) btnLlmDownload.setText(R.string.llm_test_local);
            else if (custom) btnLlmDownload.setText(
                    com.minivoice.ime.asr.LocalLlmModel.url(this).isEmpty()
                            ? R.string.llm_custom_fill : R.string.llm_custom_download);
            else btnLlmDownload.setText(R.string.llm_download);
        } else {
            rgLlmTier.setVisibility(android.view.View.GONE);
            tvLlmStatus.setText("");
            btnLlmDownload.setVisibility(android.view.View.GONE);
        }
        renderTokens();
    }

    /** 自定义整理模型：填一个 GGUF 直链，上下各一句说明。 */
    private void showCustomLlmDialog() {
        final android.app.Dialog d = new android.app.Dialog(this);
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(16), dp(18), dp(14));
        box.setBackgroundResource(R.drawable.bg_card);

        android.widget.TextView head = new android.widget.TextView(this);
        head.setText("自定义整理模型");
        head.setTextSize(15);
        head.setTextColor(getColor(R.color.text_primary));
        head.setTypeface(null, android.graphics.Typeface.BOLD);
        box.addView(head);

        android.widget.TextView top = new android.widget.TextView(this);
        top.setText("只能选 GGUF 格式的模型，推荐 0.5B 到 1.5B 的 instruct 版本，太大手机跑不动。");
        top.setTextSize(11);
        top.setTextColor(getColor(R.color.text_secondary));
        top.setLineSpacing(dp(2), 1f);
        top.setPadding(0, dp(8), 0, dp(8));
        box.addView(top);

        final android.widget.EditText input = new android.widget.EditText(this);
        input.setText(Prefs.getString(this, Prefs.K_LLM_URL, ""));
        input.setHint("https://…/model.gguf");
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);
        input.setBackgroundResource(R.drawable.bg_input);
        input.setPadding(dp(10), 0, dp(10), 0);
        input.setTextColor(getColor(R.color.text_primary));
        input.setHintTextColor(getColor(R.color.text_hint));
        input.setTextSize(12);
        box.addView(input, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(44)));

        android.widget.TextView bottom = new android.widget.TextView(this);
        bottom.setText("填模型的直链下载地址，最好以 .gguf 结尾；下载后按文件名保存，可以随时换。");
        bottom.setTextSize(11);
        bottom.setTextColor(getColor(R.color.text_secondary));
        bottom.setLineSpacing(dp(2), 1f);
        bottom.setPadding(0, dp(8), 0, dp(14));
        box.addView(bottom);

        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.END);
        android.widget.TextView neg = mkChip("取消", false);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                0, dp(36), 1f);
        neg.setLayoutParams(lp);
        neg.setOnClickListener(v -> {
            d.dismiss();
            renderPolishEngine();
        });
        row.addView(neg);
        android.widget.TextView pos = mkChip("下载并使用", true);
        android.widget.LinearLayout.LayoutParams lp2 = new android.widget.LinearLayout.LayoutParams(
                0, dp(36), 1f);
        lp2.setMarginStart(dp(8));
        pos.setLayoutParams(lp2);
        pos.setOnClickListener(v -> {
            String u = input.getText().toString().trim();
            String path = u.split("[?#]")[0].toLowerCase(java.util.Locale.ROOT);
            if (!u.startsWith("http") || !path.endsWith(".gguf")) {
                Toast.makeText(this, "只支持 http 开头的 .gguf 直链", Toast.LENGTH_LONG).show();
                return;
            }
            d.dismiss();
            applyCustomLlm(u);
        });
        row.addView(pos);
        box.addView(row);

        d.setContentView(box);
        // 返回键或点外面取消时，把单选框恢复到实际下载的档位。
        d.setOnCancelListener(x -> renderPolishEngine());
        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                    android.graphics.Color.TRANSPARENT));
            w.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.86),
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        d.show();
    }

    private void applyCustomLlm(String url) {
        String path = url.split("[?#]")[0];
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : "custom.gguf";
        if (name.isEmpty()) name = "custom.gguf";
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");

        java.io.File old = com.minivoice.ime.asr.LocalLlmModel.file(this);
        Prefs.putString(this, Prefs.K_LLM_URL, url);
        Prefs.putString(this, Prefs.K_LLM_CUSTOM_NAME, name);
        Prefs.putLong(this, Prefs.K_LLM_CUSTOM_SIZE, 0L);
        Prefs.putString(this, Prefs.K_LLM_TIER,
                com.minivoice.ime.asr.LocalLlmModel.TIER_CUSTOM);
        com.minivoice.ime.asr.LocalLlm.release();
        if (!old.getName().equals(name)) {
            if (old.exists()) old.delete();
            java.io.File part = new java.io.File(old.getAbsolutePath() + ".part");
            if (part.exists()) part.delete();
        }
        renderPolishEngine();
        renderModels();
        if (!com.minivoice.ime.asr.LocalLlmModel.ready(this)) startLlmDownload();
    }

    private void startLlmDownload() {
        if (com.minivoice.ime.asr.LocalLlmModel.TIER_CUSTOM.equals(
                com.minivoice.ime.asr.LocalLlmModel.tier(this))
                && com.minivoice.ime.asr.LocalLlmModel.url(this).isEmpty()) {
            showCustomLlmDialog();
            return;
        }
        btnLlmDownload.setEnabled(false);
        btnLlmDownload.setText(R.string.llm_downloading);
        LocalLlmModel.download(this, new LocalLlmModel.Listener() {
            @Override
            public void onProgress(float progress, long downloaded, long total) {
                runOnUiThread(() -> {
                    if (total > 0 && progress >= 0) {
                        tvLlmStatus.setText(String.format(java.util.Locale.CHINA,
                                "下载中 %.0f%%（%.0fMB / %.0fMB）", progress * 100,
                                downloaded / 1024f / 1024f, total / 1024f / 1024f));
                    } else {
                        tvLlmStatus.setText(String.format(java.util.Locale.CHINA,
                                "下载中 已收到 %.0fMB", downloaded / 1024f / 1024f));
                    }
                });
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
                btnLlmDownload.setText(R.string.llm_test_local);
                tvTest.setText(styleTestText(text));
            });
        }).start();
    }

    /** 已下载的模型列表：名字、体积、删除。 */
    private void renderModels() {
        if (llModels == null) return;
        llModels.removeAllViews();
        boolean hq = LocalModelManager.isHq(this);
        addModelRow("语音预览模型", "zh14m 中英双语 24MB", LocalModelManager.dir(this), false);
        addModelRow("语音终稿模型", LocalModelManager.isCustom(this)
                ? "自定义 sherpa 语音模型" : (hq ? "whisper medium 903MB" : "SenseVoice 228MB"),
                LocalModelManager.finalDir(this), false);
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
            del.setElevation(0f);
            del.setStateListAnimator(null);
            del.setTextColor(getColor(com.minivoice.ime.R.color.text_primary));
            del.setOnClickListener(v -> confirmDelete(title, dir, isLlm));
            row.addView(del);
        }
        llModels.addView(row);
    }

    private void confirmDelete(String title, java.io.File dir, boolean isLlm) {
        showCardDialog("删掉模型", "删掉「" + title + "」？下次要用得重新下载。",
                "算了", "删除", () -> {
                    if (isLlm) com.minivoice.ime.asr.LocalLlm.release();
                    deleteRecursive(dir);
                    Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
                    renderModels();
                    renderPolishEngine();
                    refreshLocalStatus();
                });
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
        String rawEp = etEndpoint.getText().toString().trim();
        String rawKey = AsrConfig.sanitizeKey(etApikey.getText().toString());
        String key = rawKey;
        String fixedEp = AsrConfig.adaptEndpointForKey(rawEp, key);
        if (!fixedEp.equals(rawEp)) {
            etEndpoint.setText(fixedEp);
        }
        c.endpoint = fixedEp;
        c.apiKey = rawKey;
        c.model = cleanModelId(etModel.getText().toString());
        if (c.endpoint.isEmpty()) c.endpoint = AsrConfig.BAILIAN_NATIVE_ENDPOINT;
        if (c.model.isEmpty()) c.model = AsrConfig.BAILIAN_MODEL;
        c.protocol = AsrConfig.PROTOCOL_LOCAL.equals(Prefs.getString(this, Prefs.K_PROTOCOL, ""))
                ? AsrConfig.PROTOCOL_LOCAL : AsrConfig.PROTOCOL_BAILIAN;
        c.polish = swPolish != null && swPolish.isChecked();
        c.save(this);
        mirrorTopToPolish();

        String pm = cleanModelId(etPolishModel.getText().toString());
        Prefs.putString(this, Prefs.K_POLISH_MODEL,
                pm.isEmpty() ? AsrConfig.POLISH_MODEL : pm);
        Prefs.putString(this, Prefs.K_POLISH_ENDPOINT,
                AsrConfig.polishEndpoint(c.endpoint));
        Prefs.putString(this, Prefs.K_POLISH_KEY, c.apiKey);
        Prefs.putString(this, Prefs.K_DEVICE_NAME, etDevice.getText().toString().trim());
        Prefs.switchTokenModel(this, c.tokenIdentity(this));
        Prefs.putString(this, Prefs.K_LOCAL_URL, "");
        Prefs.putString(this, Prefs.K_LOCAL_URL_SV, "");
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

    /** 用内置的一小段中英文语音真跑一次识别，能转出文字才算通过。 */
    private void testConnection() {
        String url = etEndpoint.getText().toString().trim();
        String rawKey = etApikey.getText().toString();
        String key = AsrConfig.sanitizeKey(rawKey);
        String model = cleanModelId(etModel.getText().toString());
        String fixedUrl = AsrConfig.adaptEndpointForKey(url, key);
        if (!fixedUrl.equals(url)) {
            url = fixedUrl;
            etEndpoint.setText(url);
            showTest("已按 Key 类型切换为百炼公共地址", COLOR_GRAY);
        }

        if (key.isEmpty()) {
            showTest("Key 是空的，先填上再测", COLOR_RED);
            return;
        }
        if (url.isEmpty()) {
            showTest("接口地址是空的，先填一下", COLOR_RED);
            return;
        }
        if (model.isEmpty()) {
            showTest("模型名称是空的，先填一下", COLOR_RED);
            return;
        }

        byte[] pcm = readTestPcm();
        if (pcm.length < 3200) {
            showTest("测试音频没读到，先重新安装一次", COLOR_RED);
            return;
        }

        showTest("正在用中英文语音测试识别…", COLOR_GRAY);
        btnTest.setEnabled(false);

        AsrConfig c = new AsrConfig();
        c.endpoint = url;
        c.apiKey = key;
        c.model = model;
        c.protocol = AsrConfig.PROTOCOL_BAILIAN;

        final boolean[] finished = {false};
        final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        final AsrEngine[] holder = {null};
        Runnable timeout = () -> {
            if (finished[0]) return;
            finished[0] = true;
            if (holder[0] != null) holder[0].cancel();
            doneTest(false, "识别超时了，检查网络或换一个模型", COLOR_RED);
        };

        String low = model.toLowerCase(java.util.Locale.ROOT);
        AsrEngine engine = low.contains("realtime") || low.contains("streaming")
                ? new RealtimeAsrEngine(c, this)
                : new BailianAsrEngine(c, this);
        holder[0] = engine;

        engine.start(new AsrEngine.Callback() {
            @Override
            public void onPartial(String text) {
                // 测试只看最后结果，不刷中间态
            }

            @Override
            public void onFinal(String text) {
                if (finished[0]) return;
                finished[0] = true;
                main.removeCallbacks(timeout);
                finishAsrTest(text);
            }

            @Override
            public void onError(String message) {
                if (finished[0]) return;
                finished[0] = true;
                main.removeCallbacks(timeout);
                doneTest(false, message, COLOR_RED);
            }
        });

        engine.feed(pcm, pcm.length);
        engine.finish();
        main.postDelayed(timeout, 18000);
    }

    private byte[] readTestPcm() {
        try (java.io.InputStream in = getResources().openRawResource(R.raw.asr_probe)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private void finishAsrTest(String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) {
            doneTest(false, "识别没有返回文字，换一个模型再试", COLOR_RED);
            return;
        }
        boolean zh = t.matches(".*[\\u4e00-\\u9fa5].*");
        boolean en = t.matches(".*[A-Za-z]+.*");
        if (zh && en) {
            doneTest(true, "中英文都识别出来了：" + t, COLOR_GREEN);
        } else if (zh || en) {
            doneTest(false, "只识别出一种语言：" + t, COLOR_YELLOW);
        } else {
            doneTest(false, "识别结果不对：" + t, COLOR_YELLOW);
        }
    }

    /** 下拉行会显示“模型名 · 能力”，保存和请求时只取前面的模型名。 */
    private String cleanModelId(String s) {
        String v = s == null ? "" : s.trim();
        int cut = v.indexOf("  · ");
        return cut > 0 ? v.substring(0, cut).trim() : v;
    }

    /** 当前模型到底能不能边说边出字，用颜色和字重明显地写在模型框下面。 */
    private void updateModelCapability(String id) {
        if (tvModelHint == null) return;
        String low = id == null ? "" : id.toLowerCase(java.util.Locale.ROOT);
        if (low.contains("realtime") || low.contains("streaming")) {
            tvModelHint.setText("● 当前模型支持流式输入");
            tvModelHint.setTextColor(COLOR_GREEN);
            tvModelHint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        } else if (low.contains("asr") || low.contains("audio")
                || low.contains("speech") || low.contains("transcri")) {
            tvModelHint.setText("● 当前模型不支持流式输入，松手后才开始转写");
            tvModelHint.setTextColor(getColor(R.color.text_primary));
            tvModelHint.setTypeface(android.graphics.Typeface.DEFAULT);
        } else {
            tvModelHint.setText("● 选好模型后，这里会显示是否支持流式输入");
            tvModelHint.setTextColor(getColor(R.color.text_primary));
            tvModelHint.setTypeface(android.graphics.Typeface.DEFAULT);
        }
    }

    /** 模型下拉：全部用细字；支持流式的绿色，不支持/未知用正文黑色。 */
    private View styleModelRow(View v, String id) {
        if (v instanceof TextView) {
            TextView t = (TextView) v;
            String model = cleanModelId(id);
            boolean stream = model.toLowerCase(java.util.Locale.ROOT).contains("realtime") || model.toLowerCase(java.util.Locale.ROOT).contains("streaming");
            t.setText(model);
            t.setTypeface(android.graphics.Typeface.create(
                    "sans-serif-light", android.graphics.Typeface.NORMAL));
            t.setTextColor(stream ? COLOR_GREEN : getColor(R.color.text_primary));
        }
        return v;
    }

    /** 数字进度条和按钮描边用同一个百分比，一起显示、一起收起。 */
    private void setFetchProgressVisible(boolean polish, boolean visible) {
        android.widget.LinearLayout row = polish ? llPolishFetchProgress : llFetchProgress;
        if (row != null) row.setVisibility(visible
                ? android.view.View.VISIBLE : android.view.View.GONE);
        if (visible) setFetchPct(polish, 0);
    }

    private void setFetchPct(boolean polish, int pct) {
        TextView tv = polish ? tvPolishFetchPct : tvFetchPct;
        if (tv != null) tv.setText(Math.max(0, Math.min(100, pct)) + "%");
    }

    /** 读取模型列表，只留这一类里真能跑通的，再挂成下拉。 */
    private void fetchModels(boolean polish) {
        String endpoint = etEndpoint.getText().toString().trim();
        String key = AsrConfig.sanitizeKey(etApikey.getText().toString());
        String fixedTop = AsrConfig.adaptEndpointForKey(endpoint, key);
        if (!fixedTop.equals(endpoint)) {
            endpoint = fixedTop;
            etEndpoint.setText(endpoint);
        }
        if (polish) {
            String pe = etPolishEndpoint.getText().toString().trim();
            // 整理地址如果是跟着识别地址自动同步的，就继续跟着走，
            // 不要拿旧 workspace 地址去测新 Key。
            boolean followTop = pe.isEmpty() || pe.equals(lastSyncEndpoint);
            endpoint = AsrConfig.polishEndpoint(followTop ? endpoint : pe);
            String pk = AsrConfig.sanitizeKey(etPolishKey.getText().toString());
            key = pk.isEmpty() ? key : pk;
        }
        if (key.isEmpty()) {
            logSettingsDiag("ERROR MODELS_NO_KEY " + (polish ? "polish" : "asr")
                    + " 没有填写 API Key，无法读取模型");
            Toast.makeText(this, "先填 API Key", Toast.LENGTH_SHORT).show();
            return;
        }
        final AutoCompleteTextView target = polish ? etPolishModel : etModel;
        final ProgressOutlineButton btn = polish ? btnPolishFetch : btnFetchModels;
        final android.widget.ProgressBar pb = polish ? pbPolishFetch : pbFetchModels;
        final String ep = endpoint;
        final String apiKey = key;
        btn.setEnabled(false);
        btn.setProgress(0);
        btn.setText(R.string.settings_fetch_models);
        if (pb != null) {
            pb.setProgress(0, true);
        }
        setFetchProgressVisible(polish, true);
        Toast.makeText(this, R.string.models_fetching, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String err = "";
            java.util.List<String> ids = null;
            try {
                ids = listModels(ep, apiKey, polish);
            } catch (Exception e) {
                err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            if (ids == null || ids.isEmpty()) {
                logSettingsDiag("ERROR MODELS_EMPTY " + (polish ? "polish" : "asr")
                        + " host=" + AsrConfig.hostOf(ep)
                        + " 读取模型列表失败"
                        + (err.isEmpty() ? "" : " " + err));
                runOnUiThread(() -> {
                    btn.setEnabled(true);
                    btn.setProgress(0);
                    setFetchProgressVisible(polish, false);
                    btn.setText(R.string.settings_fetch_models);
                    if (polish) {
                        polishResult(false, getString(R.string.models_none));
                    } else {
                        showTest(getString(R.string.models_none), COLOR_RED);
                    }
                    Toast.makeText(this, getString(R.string.models_none), Toast.LENGTH_LONG).show();
                });
                return;
            }

            final int total = ids.size();
            final java.util.concurrent.atomic.AtomicInteger done =
                    new java.util.concurrent.atomic.AtomicInteger(0);
            final java.util.List<String> okIds =
                    java.util.Collections.synchronizedList(new java.util.ArrayList<>());
            java.util.concurrent.ExecutorService pool =
                    java.util.concurrent.Executors.newFixedThreadPool(6);
            for (String id : ids) {
                pool.execute(() -> {
                    boolean ok = polish ? testTextModel(id, ep, apiKey)
                            : testAudioModel(id, ep, apiKey);
                    if (ok) okIds.add(id);
                    int d = done.incrementAndGet();
                    int pct = total <= 0 ? 100 : d * 100 / total;
                    runOnUiThread(() -> {
                        btn.setProgress(pct);
                        if (pb != null) pb.setProgress(pct, true);
                        setFetchPct(polish, pct);
                    });
                });
            }
            pool.shutdown();
            try {
                pool.awaitTermination(10, java.util.concurrent.TimeUnit.MINUTES);
            } catch (InterruptedException ignored) {
            }

            java.util.List<String> result = new java.util.ArrayList<>(okIds);
            java.util.Collections.sort(result, (a, b) -> a.compareToIgnoreCase(b));
            final java.util.List<String> fIds = result;
            runOnUiThread(() -> {
                btn.setEnabled(true);
                btn.setProgress(0);
                btn.setText(R.string.settings_fetch_models);
                setFetchProgressVisible(polish, false);
                if (fIds.isEmpty()) {
                    logSettingsDiag("ERROR MODELS_NO_USABLE " + (polish ? "polish" : "asr")
                            + " host=" + AsrConfig.hostOf(ep) + " total=" + total
                            + " 所有候选模型都不可用，检查 Key 权限或模型是否开通");
                    if (polish) {
                        polishResult(false, getString(R.string.models_none));
                    } else {
                        showTest(getString(R.string.models_none), COLOR_RED);
                    }
                    Toast.makeText(this, getString(R.string.models_none),
                            Toast.LENGTH_LONG).show();
                    return;
                }
                ArrayAdapter<String> ad = new ArrayAdapter<String>(this,
                        android.R.layout.simple_dropdown_item_1line, fIds) {
                    @Override
                    public View getView(int position, View convertView, android.view.ViewGroup parent) {
                        return styleModelRow(super.getView(position, convertView, parent), getItem(position));
                    }

                    @Override
                    public View getDropDownView(int position, View convertView, android.view.ViewGroup parent) {
                        return styleModelRow(super.getDropDownView(position, convertView, parent), getItem(position));
                    }
                };
                target.setAdapter(ad);
                target.requestFocus();
                target.showDropDown();
                if (polish) {
                    polishResult(true, "读取完成，可用 " + fIds.size() + " 个（共读到 " + total + " 个）");
                } else {
                    showTest("读取完成，可用 " + fIds.size() + " 个（共读到 " + total + " 个）",
                            COLOR_GREEN);
                }
                Toast.makeText(this, "可用 " + fIds.size() + " 个（共读到 " + total + " 个）",
                        Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    /** 拉列表，并按识别 / 整理各留各的候选。 */
    private java.util.List<String> listModels(String ep, String key, boolean polish) throws Exception {
        java.util.List<String> urls = new java.util.ArrayList<>();
        String u0 = AsrConfig.modelsUrl(ep);
        if (!u0.isEmpty()) urls.add(u0);
        String base = AsrConfig.providerBase(ep);
        if (!base.isEmpty()) {
            addUrl(urls, base + "/models");
            addUrl(urls, base + "/v1/models");
        }
        String host = AsrConfig.hostOf(ep);
        if (!host.isEmpty()) {
            addUrl(urls, "https://" + host + "/models");
            addUrl(urls, "https://" + host + "/v1/models");
            addUrl(urls, "https://" + host + "/compatible-mode/v1/models");
            if (AsrConfig.isZhipuEndpoint(ep)) {
                addUrl(urls, "https://" + host + "/api/paas/v4/models");
            }
        }
        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
                .connectTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .proxy(Proxy.NO_PROXY)
                .build();
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        for (String u : urls) {
            okhttp3.Request req = new okhttp3.Request.Builder()
                    .url(u)
                    .addHeader("Authorization", "Bearer " + key)
                    .get()
                    .build();
            try (okhttp3.Response r = client.newCall(req).execute()) {
                if (!r.isSuccessful() || r.body() == null) continue;
                JsonObject o = JsonParser.parseString(r.body().string()).getAsJsonObject();
                com.google.gson.JsonArray arr = null;
                if (o.has("data") && o.get("data").isJsonArray()) {
                    arr = o.getAsJsonArray("data");
                } else if (o.has("models") && o.get("models").isJsonArray()) {
                    arr = o.getAsJsonArray("models");
                }
                if (arr == null) continue;
                for (int i = 0; i < arr.size(); i++) {
                    try {
                        com.google.gson.JsonElement e = arr.get(i);
                        String id = null;
                        if (e.isJsonPrimitive()) {
                            id = e.getAsString();
                        } else if (e.isJsonObject()) {
                            JsonObject m = e.getAsJsonObject();
                            if (m.has("id") && !m.get("id").isJsonNull()) {
                                id = m.get("id").getAsString();
                            } else if (m.has("name") && !m.get("name").isJsonNull()) {
                                id = m.get("name").getAsString();
                            }
                        }
                        if (id != null && !id.trim().isEmpty()) ids.add(id.trim());
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        // 接口列表不一定列全，补上各平台确认可用或值得试的内置候选。
        ids.addAll(knownModels(ep, polish));
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String id : ids) {
            if (isModelCandidate(id, polish)) out.add(id);
        }
        // 文本模型目录通常一百多个，全测太慢、下拉也太长。
        // 先按“新模型 / 通用对话 / 低延迟”排序，只验前 40 个，识别侧不截断。
        if (polish && out.size() > 40) {
            java.util.Collections.sort(out, (a, b) ->
                    Integer.compare(rankPolishModel(b), rankPolishModel(a)));
            out = new java.util.ArrayList<>(out.subList(0, 40));
        }
        return out;
    }

    private int rankPolishModel(String id) {
        String low = id.toLowerCase(java.util.Locale.ROOT);
        int score = 0;
        if (low.contains("qwen3.8") || low.contains("qwen3.7")
                || low.contains("qwen3.6")) score += 8;
        if (low.contains("glm-5") || low.contains("kimi-k")
                || low.contains("mimo-v2.6") || low.contains("deepseek-v4")) score += 7;
        if (low.contains("flash")) score += 4;
        if (low.contains("pro") || low.contains("plus") || low.contains("max")) score += 2;
        if (low.contains("mt") || low.contains("coder") || low.contains("math")
                || low.contains("character") || low.contains("research")) score -= 4;
        if (low.contains("qwen2") || low.contains("qwen1") || low.startsWith("qwen-")) score -= 6;
        return score;
    }

    private void addUrl(java.util.List<String> urls, String u) {
        if (!urls.contains(u)) urls.add(u);
    }

    /** 已知平台补充候选，减少“后台有但 /models 不返回”的情况。 */
    private java.util.List<String> knownModels(String ep, boolean polish) {
        java.util.List<String> add = new java.util.ArrayList<>();
        if (AsrConfig.isBailianEndpoint(ep)) {
            if (polish) {
                add.add("qwen3.8-flash");
            } else {
                add.add("qwen-audio-3.1-asr-flash");
                add.add("qwen-audio-3.0-asr-flash");
                add.add("qwen3-asr-flash");
                add.add("qwen3-asr-flash-realtime");
            }
        } else if (AsrConfig.isXiaomiEndpoint(ep)) {
            if (polish) {
                add.add("mimo-v2.6-flash");
                add.add("mimo-v2.6-pro");
            } else {
                add.add("mimo-v2.5-asr");
            }
        } else if (AsrConfig.isZhipuEndpoint(ep) && !polish) {
            add.add("glm-asr-2512");
        }
        return add;
    }

    /** 名称先粗筛，最终是否可用仍由真实请求决定。 */
    private boolean isModelCandidate(String id, boolean polish) {
        String low = id.toLowerCase(java.util.Locale.ROOT);
        if (low.contains("embedding") || low.contains("rerank")
                || low.contains("tts") || low.contains("image")
                || low.contains("video") || low.contains("ocr")) return false;
        if (low.contains("s2s") || low.contains("livetranslate")
                || low.contains("omni")) return false;
        boolean audio = low.contains("asr")
                || low.contains("audio")
                || low.contains("realtime") || low.contains("streaming")
                || low.contains("speech")
                || low.contains("transcri")
                || low.contains("whisper")
                || low.contains("paraformer")
                || low.contains("sensevoice")
                || low.contains("glm-asr");
        // 识别只留语音类；整理只留非语音类，避免把 TTS、识别模型混进整理列表。
        return polish != audio;
    }

    /** 文本模型按整理时真实的提示词和样例试一次，能正常回话才算可用。 */
    private boolean testTextModel(String id, String chatUrl, String key) {
        String sample = "呃我跟你讲一下这个事，就是我们那个 project 本来是周四上线，"
                + "结果 backend 那边 API 一直没冻结，然后就拖到了下周一，嗯 QA 也只跑了一半。";
        com.google.gson.JsonObject body = new com.google.gson.JsonObject();
        body.addProperty("model", id);
        com.google.gson.JsonArray messages = new com.google.gson.JsonArray();
        com.google.gson.JsonObject sys = new com.google.gson.JsonObject();
        sys.addProperty("role", "system");
        sys.addProperty("content", com.minivoice.ime.asr.TextPolisher.rulesText(this));
        messages.add(sys);
        com.google.gson.JsonObject user = new com.google.gson.JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", sample);
        messages.add(user);
        body.add("messages", messages);
        body.addProperty("max_tokens", 300);
        // Kimi 只接受 temperature=1，别的平台用 0.2 更稳。
        body.addProperty("temperature",
                chatUrl.contains("moonshot") || chatUrl.contains("kimi") ? 1.0 : 0.2);
        if (chatUrl.contains("aliyuncs.com") || chatUrl.contains("dashscope")) {
            body.addProperty("enable_thinking", false);
        }
        String out = postJsonResult(chatUrl, key, body.toString());
        if (out == null) return false;
        try {
            com.google.gson.JsonObject root =
                    com.google.gson.JsonParser.parseString(out).getAsJsonObject();
            com.google.gson.JsonArray choices = root.getAsJsonArray("choices");
            if (choices == null || choices.size() == 0) return false;
            com.google.gson.JsonObject msg =
                    choices.get(0).getAsJsonObject().getAsJsonObject("message");
            String txt = msg != null && msg.has("content") && !msg.get("content").isJsonNull()
                    ? msg.get("content").getAsString().trim() : "";
            // 必须真的按整理提示词处理了样例：原样复读、只回一句“测试一下”、
            // 或把关键内容全丢掉，都算不可用，不放进下拉。
            boolean kept = txt.contains("project") || txt.contains("API")
                    || txt.contains("QA") || txt.contains("周四")
                    || txt.contains("周一") || txt.contains("上线")
                    || txt.contains("backend");
            return !txt.isEmpty() && !txt.equals(sample) && kept;
        } catch (Exception e) {
            return false;
        }
    }

    /** 语音模型拿内置的中英文语音真识别一次，转出文字才算可用。 */
    private boolean testAudioModel(String id, String ep, String key) {
        String low = id.toLowerCase(java.util.Locale.ROOT);
        if (low.contains("realtime") || low.contains("streaming")) return testRealtimeModel(id, ep, key);
        if (AsrConfig.isZhipuEndpoint(ep)) return testZhipuAudio(id, ep, key);

        String data = "data:audio/wav;base64,"
                + Base64.encodeToString(probeWav(R.raw.asr_test), Base64.NO_WRAP);
        // 只有百炼域名下的 qwen-audio 系列才走原生 multimodal；
        // 小米、智谱和 qwen3-asr/fun-asr 都走兼容接口。
        if (AsrConfig.isBailianEndpoint(ep) && low.startsWith("qwen-audio-")) {
            String nativeBody = "{\"model\":\"" + escape(id) + "\",\"input\":{\"messages\":["
                    + "{\"role\":\"user\",\"content\":[{\"type\":\"input_audio\","
                    + "\"input_audio\":{\"data\":\"" + data + "\"}}]}]},"
                    + "\"parameters\":{\"format\":\"wav\",\"sample_rate\":\"16000\","
                    + "\"language_hints\":[\"zh\",\"en\"]}}";
            String nativeText = postJsonResult(AsrConfig.nativeEndpoint(ep), key, nativeBody);
            if (looksLikeTranscript(transcriptOf(nativeText))) return true;
        }
        String compatBody = "{\"model\":\"" + escape(id) + "\",\"messages\":["
                + "{\"role\":\"user\",\"content\":[{\"type\":\"input_audio\","
                + "\"input_audio\":{\"data\":\"" + data + "\"}}]}],"
                + "\"asr_options\":{\"language\":\"zh\",\"enable_itn\":true},"
                + "\"stream\":false}";
        return looksLikeTranscript(transcriptOf(
                postJsonResult(AsrConfig.polishEndpoint(ep), key, compatBody)));
    }

    /** 智谱的语音转写是 multipart，和 OpenAI 兼容 chat 完全不是一套。 */
    private boolean testZhipuAudio(String id, String ep, String key) {
        byte[] wav = probeWav(R.raw.asr_test);
        RequestBody file = RequestBody.create(wav, MediaType.parse("audio/wav"));
        MultipartBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", id)
                .addFormDataPart("file", "audio.wav", file)
                .build();
        Request request = new Request.Builder()
                .url(AsrConfig.zhipuTranscriptionEndpoint(ep))
                .addHeader("Authorization", "Bearer " + key)
                .post(body)
                .build();
        try (Response r = probeClient().newCall(request).execute()) {
            if (!r.isSuccessful() || r.body() == null) return false;
            String text = r.body().string();
            return looksLikeTranscript(transcriptOf(text));
        } catch (Exception e) {
            return false;
        }
    }

    /** 实时模型也喂一段真实语音，必须转出预期文字才算可用。 */
    private boolean testRealtimeModel(String id, String ep, String key) {
        byte[] pcm = readTestPcm();
        if (pcm.length < 3200) return false;
        AsrConfig c = new AsrConfig();
        c.endpoint = ep;
        c.apiKey = key;
        c.model = id;
        c.protocol = AsrConfig.PROTOCOL_BAILIAN;

        final java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(1);
        final boolean[] ok = {false};
        RealtimeAsrEngine engine = new RealtimeAsrEngine(c,
                getApplicationContext());
        try {
            engine.start(new AsrEngine.Callback() {
                @Override
                public void onPartial(String text) {
                }

                @Override
                public void onFinal(String text) {
                    ok[0] = looksLikeTranscript(text);
                    latch.countDown();
                }

                @Override
                public void onError(String message) {
                    latch.countDown();
                }
            });
            engine.feed(pcm, pcm.length);
            engine.finish();
            latch.await(20, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ignored) {
        } finally {
            engine.cancel();
        }
        return ok[0];
    }

    private okhttp3.OkHttpClient probeClient;

    private okhttp3.OkHttpClient probeClient() {
        if (probeClient == null) {
            probeClient = new okhttp3.OkHttpClient.Builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
                    .proxy(Proxy.NO_PROXY)
                    .build();
        }
        return probeClient;
    }

    /** 发一次请求，2xx 返回响应体，其余返回 null。 */
    private String postJsonResult(String url, String key, String body) {
        try {
            okhttp3.OkHttpClient client = probeClient();
            okhttp3.Request req = new okhttp3.Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer " + key)
                    .addHeader("X-DashScope-SSE", "disable")
                    .post(okhttp3.RequestBody.create(body,
                            okhttp3.MediaType.parse("application/json; charset=utf-8")))
                    .build();
            try (okhttp3.Response r = client.newCall(req).execute()) {
                if (r.code() < 200 || r.code() >= 300) return null;
                return r.body() == null ? "" : r.body().string();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** 从识别返回里取文字，原生取 output.text，兼容取 choices[0].message.content。 */
    private String transcriptOf(String json) {
        if (json == null || json.trim().isEmpty()) return "";
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonObject output = root.has("output") ? root.getAsJsonObject("output") : null;
            if (output != null && output.has("text")) {
                return output.get("text").getAsString();
            }
            if (root.has("text") && !root.get("text").isJsonNull()) {
                return root.get("text").getAsString();
            }
            if (root.has("result") && root.get("result").isJsonPrimitive()) {
                return root.get("result").getAsString();
            }
            com.google.gson.JsonArray choices = root.getAsJsonArray("choices");
            if (choices != null && choices.size() > 0) {
                JsonObject msg = choices.get(0).getAsJsonObject().getAsJsonObject("message");
                if (msg != null && msg.has("content")) return msg.get("content").getAsString();
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    /** 用固定测试语音的关键词判断是不是真的转写，不是随便返回一段说明就算过。 */
    private boolean looksLikeTranscript(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        String t = text.trim();
        String low = t.toLowerCase(java.util.Locale.ROOT);
        return t.contains("你好") || t.contains("测试") || t.contains("语音")
                || low.contains("hello") || low.contains("voice");
    }

    /** allowBadRequest 只给语音模型用：测试音频可能被拒，但模型本身是存在的。 */
    private boolean postJson(String url, String key, String body, boolean allowBadRequest) {
        try {
            okhttp3.OkHttpClient client = probeClient();
            okhttp3.Request req = new okhttp3.Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer " + key)
                    .addHeader("X-DashScope-SSE", "disable")
                    .post(okhttp3.RequestBody.create(body,
                            okhttp3.MediaType.parse("application/json; charset=utf-8")))
                    .build();
            try (okhttp3.Response r = client.newCall(req).execute()) {
                int code = r.code();
                if (code >= 200 && code < 300) return true;
                if (code == 429) return true;
                String text = r.body() == null ? "" : r.body().string();
                return allowBadRequest && code == 400 && !modelMissing(text);
            }
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean modelMissing(String body) {
        if (body == null) return false;
        String low = body.toLowerCase(java.util.Locale.ROOT);
        if (low.contains("model_not_found")) return true;
        return low.contains("model") && (low.contains("not found") || low.contains("not exist")
                || low.contains("does not exist") || low.contains("invalid"));
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 0.3 秒静音，16k 16bit 单声道，够触发一次识别请求。 */
    private static byte[] tinyWav() {
        int samples = 4800;
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(44 + samples * 2)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        b.putInt(36 + samples * 2);
        b.put("WAVE".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        b.put("fmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        b.putInt(16);
        b.putShort((short) 1);
        b.putShort((short) 1);
        b.putInt(16000);
        b.putInt(32000);
        b.putShort((short) 2);
        b.putShort((short) 16);
        b.put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        b.putInt(samples * 2);
        return b.array();
    }

    /** 云端整理模型单独测一次：塞一句满是口水话的样例，看它到底会不会整理。 */
    private void testPolishModel() {
        String topUrl = etEndpoint.getText().toString().trim();
        String topKey = AsrConfig.sanitizeKey(etApikey.getText().toString());
        String fixedTop = AsrConfig.adaptEndpointForKey(topUrl, topKey);
        if (!fixedTop.equals(topUrl)) etEndpoint.setText(fixedTop);

        String rawUrl = etPolishEndpoint.getText().toString().trim();
        boolean followTop = rawUrl.isEmpty() || rawUrl.equals(lastSyncEndpoint);
        if (followTop) rawUrl = fixedTop;
        final String url = AsrConfig.polishEndpoint(rawUrl);
        String rawKey = AsrConfig.sanitizeKey(etPolishKey.getText().toString());
        if (rawKey.isEmpty() || rawKey.equals(lastSyncKey)) rawKey = topKey;
        final String key = rawKey;
        String model = cleanModelId(etPolishModel.getText().toString());
        if (model.isEmpty()) model = AsrConfig.POLISH_MODEL;
        final String fm = model;
        if (key.isEmpty()) {
            polishResult(false, "API Key 是空的，先填一下");
            return;
        }
        polishResult(true, "正在测试整理模型…");
        btnPolishTest.setEnabled(false);

        new Thread(() -> {
            long t0 = System.currentTimeMillis();
            String[] samples = {
                    "呃我跟你讲一下这个事，就是我们那个 project 本来是周四上线，"
                            + "结果 backend 那边 API 一直没冻结，然后就拖到了下周一，嗯 QA 也只跑了一半，"
                            + "那个 payment 模块的兼容性其实我心里没底，就是说老版本的 Android 用户可能会出问题，"
                            + "啊你帮我看看要不咱们再等等。",
                    "那个周末我打算带家里人去惠州那边看一下房子，就是之前说的德州城，"
                            + "嗯销售说现在有个新的户型，但是具体价格还没出来，然后停车位好像也要另外算钱，"
                            + "你帮我记一下，到时候问清楚物业费和交房时间。"
            };
            String sys = com.minivoice.ime.asr.TextPolisher.rulesText(this).replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n");
            String extra = (url.contains("aliyuncs.com") || url.contains("dashscope"))
                    ? "\"enable_thinking\":false," : "";
            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(40, TimeUnit.SECONDS)
                    .protocols(java.util.Collections.singletonList(okhttp3.Protocol.HTTP_1_1))
                    .proxy(Proxy.NO_PROXY)
                    .build();
            StringBuilder bodyOut = new StringBuilder();
            int okCount = 0;
            int totalTokens = 0;
            String lastError = "";
            for (String sample : samples) {
                String body = "{\"model\":\"" + fm + "\",\"messages\":["
                        + "{\"role\":\"system\",\"content\":\"" + sys + "\"},"
                        + "{\"role\":\"user\",\"content\":\"" + escape(sample) + "\"}],"
                        + extra + "\"max_tokens\":300,\"temperature\":0.2}";
                Request request = new Request.Builder()
                        .url(url)
                        .addHeader("Authorization", "Bearer " + key)
                        .post(RequestBody.create(body,
                                MediaType.parse("application/json; charset=utf-8")))
                        .build();
                try (Response r = client.newCall(request).execute()) {
                    String text = r.body() == null ? "" : r.body().string();
                    if (r.code() == 200) {
                        String out = "";
                        try {
                            com.google.gson.JsonObject o =
                                    com.google.gson.JsonParser.parseString(text).getAsJsonObject();
                            out = o.getAsJsonArray("choices").get(0).getAsJsonObject()
                                    .getAsJsonObject("message").get("content").getAsString().trim();
                            if (o.has("usage") && o.getAsJsonObject("usage").has("total_tokens")) {
                                totalTokens += o.getAsJsonObject("usage").get("total_tokens").getAsInt();
                            }
                        } catch (Exception ignored) {
                        }
                        if (out.length() > 80) out = out.substring(0, 80) + "…";
                        okCount++;
                        bodyOut.append("原文：").append(sample)
                                .append("\n整理后：").append(out.isEmpty() ? "接口通了但没读到正文" : out)
                                .append("\n\n");
                    } else if (r.code() == 401 || r.code() == 403) {
                        lastError = "Key 被拒（" + r.code() + "）：" + extractMsg(text);
                        break;
                    } else {
                        lastError = "请求失败（" + r.code() + "）：" + extractMsg(text);
                        break;
                    }
                } catch (Exception e) {
                    lastError = "网络没通：" + e.getMessage();
                    break;
                }
            }
            long ms = System.currentTimeMillis() - t0;
            final int fc = okCount;
            final int ft = totalTokens;
            final String fbody = bodyOut.toString().trim();
            final String ferr = lastError;
            runOnUiThread(() -> {
                if (fc == 0) {
                    polishResult(false, ferr.isEmpty() ? "整理没有返回内容" : ferr);
                } else {
                    String header = "整理可用，两段测试通过 " + fc + "/" + samples.length
                            + "，总耗时 " + ms + " 毫秒"
                            + (ft > 0 ? "，共 " + ft + " tokens" : "");
                    polishResultBody(true, header, fbody);
                }
            });
        }).start();
    }

    private void polishResult(boolean ok, String text) {
        if (btnPolishTest != null) btnPolishTest.setEnabled(true);
        if (tvPolishResult == null) return;
        tvPolishResult.setVisibility(TextView.VISIBLE);
        tvPolishResult.setText((ok ? "✓ " : "✕ ") + text);
        tvPolishResult.setTextColor(ok ? COLOR_GREEN : COLOR_RED);
    }

    /** 云端整理测试：原文和整理结果分开上色，原文灰、整理结果加粗。 */
    private void polishResultBody(boolean ok, String header, String body) {
        if (btnPolishTest != null) btnPolishTest.setEnabled(true);
        if (tvPolishResult == null) return;
        String full = (ok ? "✓ " : "✕ ") + header + "\n" + body;
        tvPolishResult.setVisibility(TextView.VISIBLE);
        tvPolishResult.setTextColor(ok ? COLOR_GREEN : COLOR_RED);
        tvPolishResult.setText(styleTestText(full));
    }

    /** 原文用灰色常规字，整理结果用主题色加粗，一眼能看出差别。 */
    private android.text.Spannable styleTestText(String text) {
        android.text.SpannableStringBuilder sb =
                new android.text.SpannableStringBuilder(text);
        int accent = getColor(R.color.accent);
        int gray = getColor(R.color.text_secondary);
        int from = 0;
        while (true) {
            int start = text.indexOf("原文：", from);
            if (start < 0) break;
            int lineEnd = text.indexOf('\n', start);
            if (lineEnd < 0) lineEnd = text.length();
            sb.setSpan(new android.text.style.ForegroundColorSpan(gray), start, lineEnd,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                    start, Math.min(start + 3, lineEnd),
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            from = lineEnd + 1;
        }
        from = 0;
        while (true) {
            int p = text.indexOf("整理后：", from);
            if (p < 0) p = text.indexOf("整理：", from);
            if (p < 0) break;
            int lineEnd = text.indexOf('\n', p);
            if (lineEnd < 0) lineEnd = text.length();
            sb.setSpan(new android.text.style.ForegroundColorSpan(accent), p, lineEnd,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                    p, lineEnd, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            from = lineEnd + 1;
        }
        return sb;
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

    /** 取 Key 的 MD5 前 8 位，用来判断输入框里的 Key 有没有变化。 */
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
        showCardDialog("推送到 " + p.name,
                "把接口、Key、模型、主题、词表这些设置推过去，对方的会合并。",
                "取消", "推送", () -> push(p));
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

    // ---------------- 个人词库 / 快捷输入 ----------------


    /** 模型名一看就知道是不是识别模型：百炼的语音识别模型都带 asr、audio 或 realtime。 */
    private boolean looksLikeAsr(String model) {
        String m = model == null ? "" : model.trim().toLowerCase(java.util.Locale.ROOT);
        return m.contains("asr") || m.contains("audio") || m.contains("realtime");
    }

    private void updateAsrCheck(String model) {
        if (tvAsrCheck == null) return;
        String m = model == null ? "" : model.trim();
        if (m.isEmpty()) {
            tvAsrCheck.setVisibility(android.view.View.GONE);
            return;
        }
        tvAsrCheck.setVisibility(android.view.View.VISIBLE);
        if (looksLikeAsr(m)) {
            tvAsrCheck.setText(R.string.check_asr_ok);
            tvAsrCheck.setTextColor(COLOR_GREEN);
        } else {
            tvAsrCheck.setText(R.string.check_asr_unknown);
            tvAsrCheck.setTextColor(COLOR_YELLOW);
        }
    }

    private void updatePolishCheck(String model) {
        if (tvPolishCheck == null) return;
        String m = model == null ? "" : model.trim();
        if (m.isEmpty()) {
            tvPolishCheck.setVisibility(android.view.View.GONE);
            return;
        }
        tvPolishCheck.setVisibility(android.view.View.VISIBLE);
        if (looksLikeAsr(m)) {
            tvPolishCheck.setText(R.string.check_polish_bad);
            tvPolishCheck.setTextColor(COLOR_RED);
        } else {
            tvPolishCheck.setText(R.string.check_polish_ok);
            tvPolishCheck.setTextColor(COLOR_GREEN);
        }
    }

    /** 统一风格的提示框，底色、标题、按钮全部跟设置页一套色，浅色深色各走各的。 */
    /** 统一按钮的视觉：去掉阴影，文字单行居中，避免固定高度里换行或裁字。 */
    private void normalizeButtons(android.view.View v) {
        // EditText 也继承 TextView 且默认可点击，不能把它当按钮处理，
        // 否则规则输入框会被强制 singleLine，整段规则挤成一行。
        if (v instanceof android.widget.EditText) return;
        if (v instanceof android.widget.RadioButton || v instanceof android.widget.CheckBox) {
            // 本地模型档位要用两行显示“标准 / 252兆”，不能跟着普通按钮被压成单行。
            android.widget.TextView t = (android.widget.TextView) v;
            t.setSingleLine(false);
            t.setMaxLines(2);
            t.setEllipsize(null);
            t.setIncludeFontPadding(false);
            t.setLineSpacing(dp(1), 1f);
            t.setGravity(android.view.Gravity.CENTER);
            t.setElevation(0);
            t.setStateListAnimator(null);
        } else if (v instanceof android.widget.Button) {
            android.widget.Button b = (android.widget.Button) v;
            b.setAllCaps(false);
            b.setSingleLine(true);
            b.setEllipsize(android.text.TextUtils.TruncateAt.END);
            b.setIncludeFontPadding(false);
            b.setGravity(android.view.Gravity.CENTER);
            b.setElevation(0);
            b.setStateListAnimator(null);
        } else if (v instanceof android.widget.TextView && v.isClickable()
                && v.getBackground() != null) {
            android.widget.TextView t = (android.widget.TextView) v;
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setIncludeFontPadding(false);
            t.setGravity(android.view.Gravity.CENTER);
            t.setElevation(0);
            t.setStateListAnimator(null);
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) normalizeButtons(g.getChildAt(i));
        }
    }

    private void showCardDialog(String title, String message, String negText,
                                String posText, Runnable onPos) {
        final android.app.Dialog d = new android.app.Dialog(this);
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(16), dp(18), dp(14));
        box.setBackgroundResource(R.drawable.bg_card);

        android.widget.TextView head = new android.widget.TextView(this);
        head.setText(title);
        head.setTextSize(15);
        head.setTextColor(getColor(R.color.text_primary));
        head.setTypeface(null, android.graphics.Typeface.BOLD);
        box.addView(head);

        android.widget.TextView body = new android.widget.TextView(this);
        body.setText(message);
        body.setTextSize(12);
        body.setTextColor(getColor(R.color.text_secondary));
        body.setLineSpacing(dp(2), 1f);
        body.setPadding(0, dp(8), 0, dp(14));
        box.addView(body);

        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.END);

        if (negText != null) {
            android.widget.TextView neg = mkChip(negText, false);
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                    0, dp(36), 1f);
            neg.setLayoutParams(lp);
            neg.setOnClickListener(v -> d.dismiss());
            row.addView(neg);
        }

        android.widget.TextView pos = mkChip(posText, true);
        android.widget.LinearLayout.LayoutParams lp2 = new android.widget.LinearLayout.LayoutParams(
                negText == null ? android.widget.LinearLayout.LayoutParams.MATCH_PARENT : 0,
                dp(36), negText == null ? 0f : 1f);
        if (negText != null) lp2.setMarginStart(dp(8));
        pos.setLayoutParams(lp2);
        pos.setOnClickListener(v -> {
            d.dismiss();
            if (onPos != null) onPos.run();
        });
        row.addView(pos);
        box.addView(row);

        d.setContentView(box);
        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                    android.graphics.Color.TRANSPARENT));
            w.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.86),
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        d.show();
    }

    /** 统一的圆角小按钮，跟主题里那些 chip 一个风格，不用系统 Button 那种阴影。 */
    private TextView mkChip(String text, boolean primary) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setGravity(android.view.Gravity.CENTER);
        tv.setIncludeFontPadding(false);
        tv.setBackgroundResource(primary ? R.drawable.bg_btn_primary : R.drawable.bg_btn_secondary);
        tv.setTextColor(getColor(primary ? R.color.on_accent : R.color.text_primary));
        tv.setPadding(dp(14), 0, dp(14), 0);
        return tv;
    }

    /** 个人词库：一个多行输入框，一行一个准确写法，识别和整理都拿它当校准标准。 */
    private void showPersonalVocab() {
        final android.app.Dialog d = new android.app.Dialog(this);
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(14), dp(16), dp(14));
        box.setBackgroundColor(getColor(R.color.card_bg));

        android.widget.LinearLayout headRow = new android.widget.LinearLayout(this);
        headRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        headRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        android.widget.TextView head = new android.widget.TextView(this);
        head.setText("个人词库");
        head.setTextSize(16);
        head.setTextColor(getColor(R.color.accent));
        head.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        headRow.addView(head);
        android.widget.TextView close = new android.widget.TextView(this);
        close.setText("关闭");
        close.setTextSize(12);
        close.setTextColor(getColor(R.color.text_secondary));
        close.setPadding(dp(10), dp(4), 0, dp(4));
        headRow.addView(close);
        box.addView(headRow);

        android.widget.TextView tip = new android.widget.TextView(this);
        tip.setText("一行一个词，写准确写法。识别完先按这些词校准，AI 整理时也把它们当标准，不许改字。");
        tip.setTextSize(11);
        tip.setTextColor(getColor(R.color.text_secondary));
        tip.setPadding(0, dp(8), 0, dp(8));
        box.addView(tip);

        android.widget.EditText et = new android.widget.EditText(this);
        et.setHint("辛静\n极简语音\n魏牌蓝山");
        et.setText(String.join("\n", Prefs.getWordList(this)));
        et.setTextSize(13);
        et.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        et.setBackgroundResource(R.drawable.bg_input);
        et.setPadding(dp(12), dp(10), dp(12), dp(10));
        et.setTextColor(getColor(R.color.text_primary));
        et.setHintTextColor(getColor(R.color.text_hint));
        et.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        box.addView(et, new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView save = mkChip("保存", true);
        android.widget.LinearLayout.LayoutParams saveLp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        saveLp.setMargins(0, dp(12), 0, 0);
        box.addView(save, saveLp);

        save.setOnClickListener(v -> {
            java.util.List<String> words = new java.util.ArrayList<>();
            for (String line : et.getText().toString().split("\n")) {
                for (String one : line.split("[，,、;；]+")) {
                    String w = one.trim();
                    if (!w.isEmpty() && !words.contains(w)) words.add(w);
                }
                if (words.size() >= 60) break;
            }
            Prefs.setWordList(this, words);
            android.widget.Toast.makeText(this, "已保存 " + words.size() + " 个词",
                    android.widget.Toast.LENGTH_SHORT).show();
            d.dismiss();
        });
        close.setOnClickListener(v -> d.dismiss());

        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(getColor(R.color.card_bg));
        bg.setCornerRadius(dp(18));
        d.getWindow().setBackgroundDrawable(bg);
        d.setContentView(box);
        d.setCanceledOnTouchOutside(true);
        android.view.Window w = d.getWindow();
        w.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.92),
                (int) (getResources().getDisplayMetrics().heightPixels * 0.66));
        w.setGravity(android.view.Gravity.CENTER);
        d.show();
    }

    /** 两类词条的弹窗：顶部标题固定，内容区往下留白，两类用颜色和标签区分。 */
    private void showPhraseList(String type) {
        if (com.minivoice.ime.data.PhraseStore.TYPE_PERSONAL.equals(type)) {
            showPersonalVocab();
            return;
        }
        final boolean personal = com.minivoice.ime.data.PhraseStore.TYPE_PERSONAL.equals(type);
        final int accent = getColor(personal ? R.color.accent : R.color.text_secondary);
        final android.app.Dialog d = new android.app.Dialog(this);
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setBackgroundColor(getColor(R.color.card_bg));

        // ---- 顶部标题区，固定不动 ----
        android.widget.LinearLayout headBox = new android.widget.LinearLayout(this);
        headBox.setOrientation(android.widget.LinearLayout.VERTICAL);
        headBox.setPadding(dp(16), dp(14), dp(16), dp(10));
        headBox.setBackgroundColor(getColor(R.color.card_bg));

        android.widget.LinearLayout headRow = new android.widget.LinearLayout(this);
        headRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        headRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

        android.widget.TextView tag = new android.widget.TextView(this);
        tag.setText(personal ? "识别校准" : "整段文字");
        tag.setTextSize(10);
        tag.setIncludeFontPadding(false);
        tag.setTextColor(accent);
        tag.setBackgroundResource(R.drawable.bg_chip_idle);
        tag.setPadding(dp(8), dp(3), dp(8), dp(3));
        headRow.addView(tag);

        android.widget.TextView head = new android.widget.TextView(this);
        head.setText(personal ? "个人词库" : "快捷输入");
        head.setTextSize(16);
        head.setTextColor(accent);
        android.widget.LinearLayout.LayoutParams headLp = new android.widget.LinearLayout.LayoutParams(
                0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        headLp.setMargins(dp(8), 0, 0, 0);
        headRow.addView(head, headLp);

        android.widget.TextView close = new android.widget.TextView(this);
        close.setText("关闭");
        close.setTextSize(12);
        close.setTextColor(getColor(R.color.text_secondary));
        close.setPadding(dp(10), dp(4), 0, dp(4));
        headRow.addView(close);
        headBox.addView(headRow);

        android.widget.TextView sub = new android.widget.TextView(this);
        sub.setText(personal
                ? "人名、公司名、产品名这类容易识别错的词。加进来之后，识别结果会按你写的写法校准。"
                : "常用回复、地址、套话这类整段文字。打缩写就出，点了整段上屏。");
        sub.setTextSize(11);
        sub.setTextColor(getColor(R.color.text_secondary));
        sub.setPadding(0, dp(8), 0, 0);
        headBox.addView(sub);
        box.addView(headBox);

        android.view.View headLine = new android.view.View(this);
        headLine.setBackgroundColor(getColor(R.color.divider));
        box.addView(headLine, new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp2(0.5f)));

        // ---- 列表区，往下留白，从这里开始滚 ----
        android.widget.LinearLayout listBox = new android.widget.LinearLayout(this);
        listBox.setOrientation(android.widget.LinearLayout.VERTICAL);
        listBox.setPadding(dp(16), dp(14), dp(16), dp(10));
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(listBox);
        box.addView(sv, new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        final Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            listBox.removeAllViews();
            java.util.List<com.minivoice.ime.data.PhraseStore.Phrase> items =
                    com.minivoice.ime.data.PhraseStore.byType(this, type);
            if (items.isEmpty()) {
                android.widget.TextView empty = new android.widget.TextView(this);
                empty.setText(personal
                        ? "还没有内容。点「新增一条」加词，或者粘一段文字让 AI 挑出里面的专有名词。"
                        : "还没有内容。点下面的「新增一条」，或者粘一段文字让 AI 整理。");
                empty.setTextSize(12);
                empty.setTextColor(getColor(R.color.text_hint));
                empty.setPadding(0, dp(16), 0, dp(16));
                listBox.addView(empty);
                return;
            }
            for (com.minivoice.ime.data.PhraseStore.Phrase p : items) {
                android.widget.LinearLayout row = new android.widget.LinearLayout(this);
                row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp(10), 0, dp(10));

                android.widget.LinearLayout col = new android.widget.LinearLayout(this);
                col.setOrientation(android.widget.LinearLayout.VERTICAL);

                android.widget.LinearLayout line1 = new android.widget.LinearLayout(this);
                line1.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                line1.setGravity(android.view.Gravity.CENTER_VERTICAL);

                if (personal) {
                    // 个人词库是校准表，直接显示正确写法，不带缩写
                    android.widget.TextView word = new android.widget.TextView(this);
                    word.setText(p.text);
                    word.setTextSize(15);
                    word.setTextColor(getColor(R.color.text_primary));
                    line1.addView(word, new android.widget.LinearLayout.LayoutParams(
                            0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                } else {
                    android.widget.TextView code = new android.widget.TextView(this);
                    code.setText(p.code.isEmpty() ? "—" : p.code);
                    code.setTextSize(11);
                    code.setIncludeFontPadding(false);
                    code.setTextColor(getColor(R.color.on_accent));
                    code.setBackgroundResource(R.drawable.bg_chip_active);
                    code.setPadding(dp(7), dp(2), dp(7), dp(2));
                    line1.addView(code);

                    if (!p.label.isEmpty() && !p.label.equalsIgnoreCase(p.code)) {
                        android.widget.TextView name = new android.widget.TextView(this);
                        name.setText(p.label);
                        name.setTextSize(13);
                        name.setTextColor(getColor(R.color.text_primary));
                        android.widget.LinearLayout.LayoutParams nl = new android.widget.LinearLayout.LayoutParams(
                                0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                        nl.setMargins(dp(8), 0, 0, 0);
                        line1.addView(name, nl);
                    }
                }
                col.addView(line1);

                android.widget.TextView preview = new android.widget.TextView(this);
                if (personal) {
                    preview.setText(p.alias == null || p.alias.trim().isEmpty()
                            ? "识别时按这个写法校准"
                            : "常被识别成：" + p.alias);
                    preview.setMaxLines(2);
                } else {
                    String one = p.text.replace("\n", " · ");
                    if (one.length() > 40) one = one.substring(0, 40) + "…";
                    preview.setText(one);
                    preview.setMaxLines(2);
                }
                preview.setTextSize(11);
                preview.setTextColor(getColor(R.color.text_secondary));
                preview.setPadding(0, dp(4), 0, 0);
                col.addView(preview);

                row.addView(col, new android.widget.LinearLayout.LayoutParams(
                        0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                android.widget.TextView del = new android.widget.TextView(this);
                del.setText("删除");
                del.setTextSize(11);
                del.setIncludeFontPadding(false);
                del.setTextColor(getColor(R.color.text_secondary));
                del.setBackgroundResource(R.drawable.bg_chip_idle);
                del.setPadding(dp(10), dp(4), dp(10), dp(4));
                del.setOnClickListener(v -> {
                    com.minivoice.ime.data.PhraseStore.remove(this, p);
                    refresh[0].run();
                });
                row.addView(del);

                row.setOnClickListener(v -> showPhraseEdit(p, type, refresh[0]));
                listBox.addView(row);

                android.view.View line = new android.view.View(this);
                line.setBackgroundColor(getColor(R.color.divider));
                listBox.addView(line, new android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp2(0.5f)));
            }
        };
        refresh[0].run();

        // ---- 底部按钮区，固定 ----
        android.widget.LinearLayout btnBox = new android.widget.LinearLayout(this);
        btnBox.setOrientation(android.widget.LinearLayout.VERTICAL);
        btnBox.setPadding(dp(16), dp(10), dp(16), dp(14));
        btnBox.setBackgroundColor(getColor(R.color.card_bg));

        android.view.View btnLine = new android.view.View(this);
        btnLine.setBackgroundColor(getColor(R.color.divider));
        btnBox.addView(btnLine, new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp2(0.5f)));

        android.widget.LinearLayout btnRow = new android.widget.LinearLayout(this);
        btnRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, dp(10), 0, 0);

        TextView add = mkChip("新增一条", false);
        btnRow.addView(add, new android.widget.LinearLayout.LayoutParams(0, dp(40), 1f));

        TextView ai = mkChip("AI 整理导入", true);
        android.widget.LinearLayout.LayoutParams aiLp = new android.widget.LinearLayout.LayoutParams(
                0, dp(40), 1f);
        aiLp.setMargins(dp(8), 0, 0, 0);
        btnRow.addView(ai, aiLp);
        btnBox.addView(btnRow);
        box.addView(btnBox);

        add.setOnClickListener(v -> {
            com.minivoice.ime.data.PhraseStore.Phrase np =
                    new com.minivoice.ime.data.PhraseStore.Phrase("", "", "", type);
            showPhraseEdit(np, type, refresh[0]);
        });
        ai.setOnClickListener(v -> showPhraseImport(type, refresh[0]));
        close.setOnClickListener(v -> d.dismiss());

        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(getColor(R.color.card_bg));
        bg.setCornerRadius(dp(18));
        d.getWindow().setBackgroundDrawable(bg);
        d.setContentView(box);
        d.setCanceledOnTouchOutside(true);
        android.view.Window w = d.getWindow();
        w.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.92),
                (int) (getResources().getDisplayMetrics().heightPixels * 0.72));
        w.setGravity(android.view.Gravity.CENTER);
        d.show();
    }

    /** 加一条或改一条。个人词库填正确写法和常被识别成，快捷输入填缩写和正文。 */
    private void showPhraseEdit(com.minivoice.ime.data.PhraseStore.Phrase p,
                                String type, Runnable refresh) {
        final boolean personal = com.minivoice.ime.data.PhraseStore.TYPE_PERSONAL.equals(type);
        final android.app.Dialog d = new android.app.Dialog(this);
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(14), dp(16), dp(14));

        android.widget.LinearLayout headRow = new android.widget.LinearLayout(this);
        headRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        headRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        android.widget.TextView head = new android.widget.TextView(this);
        head.setText(personal ? "个人词库 · 编辑" : "快捷输入 · 编辑");
        head.setTextSize(16);
        head.setTextColor(getColor(personal ? R.color.accent : R.color.text_primary));
        head.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        headRow.addView(head);
        android.widget.TextView close = new android.widget.TextView(this);
        close.setText("关闭");
        close.setTextSize(12);
        close.setTextColor(getColor(R.color.text_secondary));
        close.setPadding(dp(10), dp(4), 0, dp(4));
        headRow.addView(close);
        box.addView(headRow);

        android.view.View line = new android.view.View(this);
        line.setBackgroundColor(getColor(R.color.divider));
        android.widget.LinearLayout.LayoutParams lineLp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp2(0.5f));
        lineLp.setMargins(0, dp(10), 0, dp(10));
        box.addView(line, lineLp);

        android.widget.TextView tip = new android.widget.TextView(this);
        tip.setText(personal
                ? "写上正确写法，识别和整理都会按这个写法校准。下面那行可以填它常被识别成的样子，留空也行。"
                : "缩写打完这几个字母就出这一整段，别跟别的撞了。");
        tip.setTextSize(11);
        tip.setTextColor(getColor(R.color.text_secondary));
        tip.setPadding(0, 0, 0, dp(8));
        box.addView(tip);

        final android.widget.EditText etCode;
        final android.widget.EditText etText;
        final android.widget.EditText etAlias;
        if (personal) {
            etCode = null;
            etAlias = new android.widget.EditText(this);
            etText = new android.widget.EditText(this);
            etText.setHint("正确写法，比如 辛静、极简语音、魏牌蓝山");
            etText.setText(p.text);
            etText.setTextSize(13);
            etText.setSingleLine(true);
            etText.setBackgroundResource(R.drawable.bg_input);
            etText.setPadding(dp(10), 0, dp(10), 0);
            etText.setTextColor(getColor(R.color.text_primary));
            etText.setHintTextColor(getColor(R.color.text_hint));
            box.addView(etText, new android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

            etAlias.setHint("常被识别成，多个用逗号隔开，可留空");
            etAlias.setText(p.alias);
            etAlias.setTextSize(12);
            etAlias.setSingleLine(true);
            etAlias.setBackgroundResource(R.drawable.bg_input);
            etAlias.setPadding(dp(10), 0, dp(10), 0);
            etAlias.setTextColor(getColor(R.color.text_primary));
            etAlias.setHintTextColor(getColor(R.color.text_hint));
            android.widget.LinearLayout.LayoutParams aliasLp =
                    new android.widget.LinearLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
            aliasLp.setMargins(0, dp(8), 0, 0);
            box.addView(etAlias, aliasLp);
        } else {
            etAlias = null;
            etCode = new android.widget.EditText(this);
            etCode.setHint("缩写，比如 fp 或 sg");
            etCode.setText(p.code);
            etCode.setTextSize(12);
            etCode.setSingleLine(true);
            etCode.setBackgroundResource(R.drawable.bg_input);
            etCode.setPadding(dp(10), 0, dp(10), 0);
            etCode.setTextColor(getColor(R.color.text_primary));
            etCode.setHintTextColor(getColor(R.color.text_hint));
            box.addView(etCode, new android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));

            etText = new android.widget.EditText(this);
            etText.setHint("正文，点一下要上屏的整段话");
            etText.setText(p.text);
            etText.setTextSize(12);
            etText.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
            etText.setBackgroundResource(R.drawable.bg_input);
            etText.setPadding(dp(10), dp(8), dp(10), dp(8));
            etText.setTextColor(getColor(R.color.text_primary));
            etText.setHintTextColor(getColor(R.color.text_hint));
            android.widget.LinearLayout.LayoutParams textLp =
                    new android.widget.LinearLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            textLp.setMargins(0, dp(8), 0, 0);
            box.addView(etText, textLp);
        }

        TextView save = mkChip("保存", true);
        android.widget.LinearLayout.LayoutParams saveLp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        saveLp.setMargins(0, dp(12), 0, 0);
        box.addView(save, saveLp);

        save.setOnClickListener(v -> {
            String text = etText.getText().toString().trim();
            if (text.isEmpty()) {
                android.widget.Toast.makeText(this, personal ? "正确写法要填" : "缩写和正文都要填",
                        android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            com.minivoice.ime.data.PhraseStore.Phrase np;
            if (personal) {
                np = new com.minivoice.ime.data.PhraseStore.Phrase("", "", text, type);
                np.alias = etAlias == null ? "" : etAlias.getText().toString().trim();
            } else {
                String code = etCode.getText().toString().trim().toLowerCase()
                        .replaceAll("[^a-z0-9]", "");
                if (code.isEmpty()) {
                    android.widget.Toast.makeText(this, "缩写和正文都要填",
                            android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                np = new com.minivoice.ime.data.PhraseStore.Phrase(code, code, text, type);
            }
            if (!p.text.isEmpty()) {
                com.minivoice.ime.data.PhraseStore.remove(this, p);
            }
            com.minivoice.ime.data.PhraseStore.addAll(this,
                    java.util.Collections.singletonList(np));
            d.dismiss();
            if (refresh != null) refresh.run();
        });
        close.setOnClickListener(v -> d.dismiss());

        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(getColor(R.color.card_bg));
        bg.setCornerRadius(dp(18));
        d.getWindow().setBackgroundDrawable(bg);
        d.setContentView(box);
        d.setCanceledOnTouchOutside(true);
        android.view.Window w = d.getWindow();
        w.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.92),
                (int) (getResources().getDisplayMetrics().heightPixels * (personal ? 0.46f : 0.62f)));
        w.setGravity(android.view.Gravity.CENTER);
        d.show();
    }

    /** 粘一段杂七杂八的内容，交给模型拆成一条条词库。 */
    private void showPhraseImport(String type, Runnable refresh) {
        final boolean personal = com.minivoice.ime.data.PhraseStore.TYPE_PERSONAL.equals(type);
        final android.app.Dialog d = new android.app.Dialog(this);
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(14), dp(16), dp(14));

        android.widget.LinearLayout headRow = new android.widget.LinearLayout(this);
        headRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        headRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        android.widget.TextView head = new android.widget.TextView(this);
        head.setText(personal ? "个人词库 · AI 整理" : "快捷输入 · AI 整理");
        head.setTextSize(16);
        head.setTextColor(getColor(personal ? R.color.accent : R.color.text_primary));
        head.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        headRow.addView(head);
        android.widget.TextView close = new android.widget.TextView(this);
        close.setText("关闭");
        close.setTextSize(12);
        close.setTextColor(getColor(R.color.text_secondary));
        close.setPadding(dp(10), dp(4), 0, dp(4));
        headRow.addView(close);
        box.addView(headRow);

        android.view.View line = new android.view.View(this);
        line.setBackgroundColor(getColor(R.color.divider));
        android.widget.LinearLayout.LayoutParams lineLp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp2(0.5f));
        lineLp.setMargins(0, dp(10), 0, dp(10));
        box.addView(line, lineLp);

        android.widget.TextView tip = new android.widget.TextView(this);
        tip.setText(personal
                ? "把一大段文字粘进来，模型会挑出里面的人名、公司名、产品名，一行一个。"
                : "把一堆信息粘进来，模型会拆成缩写、名称、正文三段。手机号、税号这些数字原样保留。");
        tip.setTextSize(11);
        tip.setTextColor(getColor(R.color.text_secondary));
        tip.setPadding(0, 0, 0, dp(8));
        box.addView(tip);

        android.widget.EditText et = new android.widget.EditText(this);
        et.setHint("粘在这里，一行一条也行，一大段也行");
        et.setTextSize(12);
        et.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        et.setBackgroundResource(R.drawable.bg_input);
        et.setPadding(dp(10), dp(8), dp(10), dp(8));
        et.setTextColor(getColor(R.color.text_primary));
        et.setHintTextColor(getColor(R.color.text_hint));
        box.addView(et, new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        android.widget.TextView status = new android.widget.TextView(this);
        status.setTextSize(11);
        status.setTextColor(getColor(R.color.text_secondary));
        status.setPadding(0, dp(8), 0, 0);
        box.addView(status);

        TextView go = mkChip("开始整理", true);
        android.widget.LinearLayout.LayoutParams goLp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        goLp.setMargins(0, dp(10), 0, 0);
        box.addView(go, goLp);

        go.setOnClickListener(v -> {
            String raw = et.getText().toString().trim();
            if (raw.isEmpty()) {
                status.setText("先粘点内容进来");
                return;
            }
            go.setEnabled(false);
            status.setText("正在整理…");
            com.minivoice.ime.data.PhraseAi.extract(this, type, raw, (entries, note) -> {
                go.setEnabled(true);
                status.setText(note);
                if (!entries.isEmpty()) {
                    com.minivoice.ime.data.PhraseStore.addAll(this, entries);
                    if (refresh != null) refresh.run();
                    d.dismiss();
                    android.widget.Toast.makeText(this, note,
                            android.widget.Toast.LENGTH_SHORT).show();
                }
            });
        });
        close.setOnClickListener(v -> d.dismiss());

        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(getColor(R.color.card_bg));
        bg.setCornerRadius(dp(18));
        d.getWindow().setBackgroundDrawable(bg);
        d.setContentView(box);
        d.setCanceledOnTouchOutside(true);
        android.view.Window w = d.getWindow();
        w.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.92),
                (int) (getResources().getDisplayMetrics().heightPixels * 0.66));
        w.setGravity(android.view.Gravity.CENTER);
        d.show();
    }
}

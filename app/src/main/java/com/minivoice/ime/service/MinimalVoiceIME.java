package com.minivoice.ime.service;

import android.view.WindowManager;
import android.app.Dialog;
import android.Manifest;
import android.content.Context;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.SystemClock;
import android.os.Vibrator;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import com.minivoice.ime.ui.MarqueeDrawable;
import com.minivoice.ime.ui.ProgressRingView;
import com.minivoice.ime.ui.WaveformDrawable;

import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import java.io.ByteArrayOutputStream;

import com.minivoice.ime.R;
import com.minivoice.ime.asr.AsrConfig;
import com.minivoice.ime.asr.AsrEngine;
import com.minivoice.ime.asr.BailianAsrEngine;
import com.minivoice.ime.asr.HttpAsrEngine;
import com.minivoice.ime.asr.LocalAsrEngine;
import com.minivoice.ime.asr.LocalLlmModel;
import com.minivoice.ime.asr.LlmPolisher;
import com.minivoice.ime.asr.LocalModelManager;
import com.minivoice.ime.asr.RealtimeAsrEngine;
import com.minivoice.ime.asr.TermFix;
import com.minivoice.ime.asr.TextNormalizer;
import com.minivoice.ime.asr.TextPolisher;
import com.minivoice.ime.asr.WsAsrEngine;
import com.minivoice.ime.audio.AudioRecorder;
import com.minivoice.ime.audio.BluetoothAudioRouter;
import com.minivoice.ime.settings.Prefs;
import com.minivoice.ime.ui.PermissionActivity;
import com.minivoice.ime.ui.SettingsActivity;
import com.minivoice.ime.ui.ThemeUtil;

/**
 * 极简语音输入法主服务。面板只保留删除、语音、发送、设置四个键，高度 70dp。
 */
public class MinimalVoiceIME extends InputMethodService {

    private static final String TAG = "MinimalVoiceIME";

    private static MinimalVoiceIME instance;
    /** 当前是否真的在用它输入。切到别的输入法时为 false，按键拦截据此放行。 */
    private static volatile boolean active = false;

    private View btnVoice;
    /** 全键盘时顶替原中英切换键的删除键，和小键盘的删除键功能完全一样。 */
    private ImageButton btnDeleteFull;
    private ImageButton btnDelete;
    private ImageButton btnSend;
    private ImageButton btnSettings;
    private FrameLayout btnMode;
    private TextView tvModeLabel;
    private ImageView ivModeIcon;
    private TextView tvStatus;
    private TextView tvSwipeHint;
    private ImageView ivMic;
    private LinearLayout llNumbers;
    private ProgressBar pbInline;
    private ProgressRingView ringVoice;
    /** 点设置键展开的快捷面板，里面是换输入法和进设置两条。 */
    private LinearLayout llQuick;
    private ImageButton btnQuickMenu;
    private ImageButton btnQuickIme;
    private ImageButton btnQuickSettings;
    /** 当前挂上去的整个键盘根布局，只用来调最小高度，绝不碰它的 LayoutParams。 */
    private View inputRoot;
    /** 微博才显示的展开条和右侧小箭头，点一下切换表情区高度。 */
    private FrameLayout flExpand;
    private ImageView ivExpand;
    private ValueAnimator polishAnim;
    private ValueAnimator waveAnim;
    private WaveformDrawable waveDrawable;
    /** 刚转完文字的时间点，用来判断单击语音条是不是发送。 */
    private long lastVoiceDoneAt = 0;

    /** 按当前主题算出来的 Context，键盘上所有颜色都从这里取。 */
    private Context uiCtx;
    private String themeKey = "";

    private static final long WAIT_TIMEOUT_MS = 20000L;
    /** 只有微博需要这条展开条，别扩散到其他 App。 */
    private static final String PKG_WEIBO = "com.sina.weibo";
    /** 微博里点开表情区之后，输入法窗口放到多高。 */
    private static final int PANEL_MAX_DP = 320;

    private boolean numbersOpen = false;

    // 全键盘打字：默认中文拼音，点中英键切成英文直出
    private LinearLayout llFull;
    private LinearLayout llCandBar;
    private android.widget.HorizontalScrollView hsCand;
    private int candBarH = 36;
    private LinearLayout llFullRows;
    private View flKeysRow;
    /** 0 语音极简，1 全键盘中文，2 全键盘英文。模式键按这个顺序循环。 */
    private int inputMode = 0;
    private static final int MODE_VOICE = 0;
    private static final int MODE_FULL_CN = 1;
    private static final int MODE_FULL_EN = 2;
    private boolean fullMode = false;
    private PopupWindow keyPopup;
    private TextView keyPopupUpper;
    private TextView keyPopupSymbol;
    private TextView keyPopupLower;
    private float keyPopupLeftRaw;
    private int keyPopupWidthPx;
    private int keyPopupHeightPx;
    /** 长按卡片当前选中的格子：0 大写，1 符号，2 小写。 */
    private int keyPopupSelected = 1;
    private String keyPopupUpperText;
    private String keyPopupSymbolText;
    private String keyPopupLowerText;
    /** 手指刚按下时的小气泡，显示当前键的主字符，松开就收起。 */
    private PopupWindow tapPopup;
    private TextView tapPopupText;
    private boolean pinyinMode = true;
    private boolean shiftOn = false;
    private final StringBuilder composing = new StringBuilder();
    private final java.util.List<String> candList = new java.util.ArrayList<>();
    /** 候选条里哪些名字是词库条目，上屏时换成对应的正文。 */
    private final java.util.Map<String, String> phraseText = new java.util.HashMap<>();
    /** 英文状态没有拼音串，自己记着最近敲的字母，好用缩写换词库。 */
    private String enBuf = "";
    /** 微博里是否已经点开了表情区。切到别的 App 会自动收回。 */
    private boolean weiboExpanded = false;
    private String currentPkg = null;
    private boolean holdStarted = false;
    /** 录音时上滑取消，松手不再转写。 */
    private boolean voiceCanceled = false;
    private float downY = 0f;
    private boolean hasComposing = false;
    /** 流式识别挂在输入框里的那段文字，删除键要能自己处理掉。 */
    private String voiceComposing = "";
    private Runnable holdStarter;

    private final AudioRecorder recorder = new AudioRecorder();
    private AsrEngine engine;
    private boolean recording = false;
    private long voiceStartedAt = 0;
    private boolean waiting = false;
    /** 最近一次录音的 PCM，转写失败后点一下可以拿它重新转，不用重录。 */
    private final ByteArrayOutputStream retryAudio = new ByteArrayOutputStream();
    private boolean retryReady = false;
    private static final int MIN_POLISH_CHARS = 10;
    /** 最多留 120 秒音频做重试，再长就不占内存了。 */
    private static final int MAX_RETRY_AUDIO_BYTES = 16000 * 2 * 120;

    private final Handler main = new Handler(Looper.getMainLooper());

    public static MinimalVoiceIME peek() {
        return instance;
    }

    /** 只有当前正在用极简语音输入时返回 true。 */
    public static boolean isActive() {
        return instance != null && active;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override
    public void onDestroy() {
        instance = null;
        active = false;
        stopEverything();
        super.onDestroy();
    }

    @Override
    public void onWindowShown() {
        super.onWindowShown();
        // 键盘以外的触摸不被输入法窗口吞掉，交给系统处理，荣耀指关节截屏才收得到手势
        try {
            Dialog d = getWindow();
            if (d != null) {
                d.getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public View onCreateInputView() {
        return buildInputView();
    }

    /** 按当前主题重新建一次键盘，主题切换后靠它刷新。 */
    private View buildInputView() {
        // 输入法窗口不做自己的滑入滑出动画，收起时干脆一点，不拖泥带水
        if (getWindow() != null && getWindow().getWindow() != null) {
            getWindow().getWindow().setWindowAnimations(0);
        }
        // 去掉输入法窗口自己的滑入滑出动画，发送后补回来时不会看到收起再展开的过程

        uiCtx = themedContext();
        themeKey = ThemeUtil.mode(this) + "/" + ThemeUtil.isLight(this);

        LayoutInflater inflater = LayoutInflater.from(uiCtx);
        View root = inflater.inflate(R.layout.keyboard_view, null, false);

        btnVoice = root.findViewById(R.id.btn_voice);
        // 全键盘里原来中英切换的位置，现在和小键盘一样当删除键用
        btnDeleteFull = root.findViewById(R.id.btn_delete_full);
        btnDelete = root.findViewById(R.id.btn_delete);
        btnSend = root.findViewById(R.id.btn_send);
        btnSettings = root.findViewById(R.id.btn_settings);
        btnMode = root.findViewById(R.id.btn_mode);
        tvModeLabel = root.findViewById(R.id.tv_mode_label);
        ivModeIcon = root.findViewById(R.id.iv_mode_icon);
        llNumbers = root.findViewById(R.id.ll_numbers);
        llQuick = root.findViewById(R.id.ll_quick);
        btnQuickMenu = root.findViewById(R.id.btn_quick_menu);
        btnQuickIme = root.findViewById(R.id.btn_quick_ime);
        btnQuickSettings = root.findViewById(R.id.btn_quick_settings);
        flExpand = root.findViewById(R.id.fl_expand);
        ivExpand = root.findViewById(R.id.iv_expand);
        tvStatus = root.findViewById(R.id.tv_status);
        ivMic = btnVoice.findViewById(R.id.iv_mic);
        tvSwipeHint = root.findViewById(R.id.tv_swipe_hint);
        pbInline = btnVoice.findViewById(R.id.pb_inline);
        ringVoice = root.findViewById(R.id.ring_voice);
        if (ringVoice != null) {
            ringVoice.setRingStyle(3f, 26f);
            ringVoice.setShowPercent(false);
        }

        // 图标本身是白的，键盘不是用 AppCompat 的 inflater 建的，app:tint 不生效，
        // 所以按主题在代码里上色，浅色主题下才不会白图标配白底。
        int iconTint = uiCtx.getColor(R.color.text_primary);
        btnSettings.setColorFilter(iconTint);
        btnDelete.setColorFilter(iconTint);
        if (btnDeleteFull != null) btnDeleteFull.setColorFilter(iconTint);
        btnSend.setColorFilter(iconTint);
        ivMic.setColorFilter(iconTint);
        ivModeIcon.setColorFilter(iconTint);
        ivExpand.setColorFilter(iconTint);
        btnQuickMenu.setColorFilter(iconTint);
        btnQuickIme.setColorFilter(iconTint);
        btnQuickSettings.setColorFilter(iconTint);
        btnQuickMenu.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            hideQuickPanel();
        });
        btnQuickIme.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            switchIme();
        });
        btnQuickSettings.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            hideQuickPanel();
            openSettings();
        });

        buildNumberPad();
        buildFullKeyboard(root);
        bindVoiceButton();
        bindDeleteButton();
        bindSendButton();
        bindSettingsButton();
        bindModeButton();
        tintNavigationBar();

        // 重进键盘时保持当前的语音、中文全键盘或英文全键盘状态
        applyInputMode(inputMode);
        refreshStatus(getString(R.string.voice_hint));
        inputRoot = root;
        return root;
    }

    /**
     * 微博的表情面板是按输入法窗口高度排版的，想让它变大，只能把窗口垫高。
     * 这里只动最小高度，坚决不碰 LayoutParams：根视图是 inflate 出来的，没有父容器，
     * 一旦给它塞 LinearLayout 参数，真正的 FrameLayout 父容器强转会直接崩，
     * 那样所有 App 的键盘都弹不出来。其他 App 一律不垫高度。
     */
    private void applyPanelMode() {
        if (inputRoot == null) return;
        boolean weibo = PKG_WEIBO.equals(currentPkg);
        // 展开的小箭头撤了，微博要看表情就直接进全键盘，靠全键盘把它撑开
        if (flExpand != null) flExpand.setVisibility(View.GONE);
        int target = 0;
        if (weibo && fullMode) {
            target = Math.min(getResources().getDisplayMetrics().heightPixels * 44 / 100, dp(PANEL_MAX_DP));
        }
        inputRoot.setMinimumHeight(target);
        if (inputRoot instanceof LinearLayout) {
            ((LinearLayout) inputRoot).setGravity(weibo && fullMode ? Gravity.BOTTOM : Gravity.TOP);
        }
    }

    /** 把手机当前的亮暗配置换成主题要的那一套，布局和颜色都走它。 */
    private Context themedContext() {
        Configuration cfg = new Configuration(getResources().getConfiguration());
        int night = ThemeUtil.nightMode(this);
        cfg.uiMode = (cfg.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | night;
        return createConfigurationContext(cfg);
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        active = true;
        String now = ThemeUtil.mode(this) + "/" + ThemeUtil.isLight(this);
        if (!now.equals(themeKey) && getWindow() != null) {
            setInputView(buildInputView());
        }
        if (!recording && !waiting) {
            refreshStatus(getString(R.string.voice_hint));
            showBusy(false);
        }
        currentPkg = info != null ? info.packageName : null;
        if (fullMode) clearComposing();
        hideQuickPanel();
        applyPanelMode();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 跟随系统时，手机换亮暗主题要立刻跟上
        if (ThemeUtil.AUTO.equals(ThemeUtil.mode(this)) && getWindow() != null) {
            setInputView(buildInputView());
        }
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        dismissKeyPopup();
        active = false;
        stopEverything();
        super.onFinishInputView(finishingInput);
    }

    /** 输入法永远不要全屏模式，横屏也不要。 */
    @Override
    public boolean onEvaluateFullscreenMode() {
        return false;
    }

    @Override
    public boolean onEvaluateInputViewShown() {
        super.onEvaluateInputViewShown();
        return true;
    }

    // ---------------- 按键 ----------------

    /** 按住 300 毫秒才算开始录音，单击只提示，不触发识别。 */
    private void bindVoiceButton() {
        btnVoice.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setPressed(true);
                    holdStarted = false;
                    voiceCanceled = false;
                    downY = event.getY();
                    if (holdStarter != null) main.removeCallbacks(holdStarter);
                    holdStarter = () -> {
                        holdStarted = true;
                        startVoice();
                    };
                    main.postDelayed(holdStarter, 300);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (holdStarted) {
                        float limit = 52 * getResources().getDisplayMetrics().density;
                        boolean inCancelZone = downY - event.getY() > limit;
                        if (inCancelZone != voiceCanceled) {
                            // 滑进取消区只提示，松手才真的取消；滑回来还能继续
                            voiceCanceled = inCancelZone;
                            if (tvSwipeHint != null) {
                                tvSwipeHint.setVisibility(inCancelZone
                                        ? View.VISIBLE : View.INVISIBLE);
                            }
                            if (inCancelZone) vibrate(40);
                        }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    if (holdStarter != null) {
                        main.removeCallbacks(holdStarter);
                        holdStarter = null;
                    }
                    if (voiceCanceled) {
                        voiceCanceled = false;
                        holdStarted = false;
                        if (tvSwipeHint != null) tvSwipeHint.setVisibility(View.INVISIBLE);
                        cancelVoice();
                        vibrate(40);
                        Toast.makeText(this, "已取消这次输入", Toast.LENGTH_SHORT).show();
                        return true;
                    }
                    if (tvSwipeHint != null) tvSwipeHint.setVisibility(View.INVISIBLE);
                    if (holdStarted) {
                        holdStarted = false;
                        stopVoice();
                    } else if (retryReady) {
                        // 上一次录音失败了，这条中间大键先当“重新转写”用，
                        // 不执行空格或发送，避免把失败状态直接发出去。
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                        retryVoice();
                    } else if (fullMode) {
                        // 全键盘里空格键也跟后台设置走：设成发送就点击发送，
                        // 设成空格才继续走补空格/选候选；不会在语音结束后自动发送。
                        if (!isSpaceMode() && composing.length() == 0) {
                            if (editorHasText()) {
                                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                                performSend();
                            } else {
                                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                            }
                        } else {
                            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                            fullSpace();
                        }
                    } else if (editorHasText()) {
                        // 字已经有了：按设置决定点一下是补空格还是直接发送
                        if (isSpaceMode()) {
                            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                            insertSpace();
                        } else {
                            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                            performSend();
                        }
                    } else {
                        // 什么都没有的时候不出空格，提示一下按住的用法
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                        Toast.makeText(this, "按住说话", Toast.LENGTH_SHORT).show();
                    }
                    return true;
            }
            return false;
        });
    }

    /** 文字输入到哪一步了，决定底下大按键是空格、发送还是按住说话。 */
    private boolean editorHasText() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return false;
        try {
            ExtractedTextRequest req = new ExtractedTextRequest();
            req.flags = 0;
            req.hintMaxChars = 0;
            req.hintMaxLines = 0;
            ExtractedText et = ic.getExtractedText(req, 0);
            return et != null && et.text != null && et.text.length() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void updateVoiceBar() {
        if (tvStatus == null || btnVoice == null) return;
        if (recording) return;
        String label;
        boolean retry = retryReady;
        if (retry) {
            // 失败后不提示按住说话，先把中间这条大键变成重试入口。
            label = getString(R.string.voice_retry);
        } else if (lastVoiceDoneAt > 0) {
            // 刚说完这一句，按钮马上切成“单击动作，长按继续说话”。
            label = getString(isSpaceMode()
                    ? R.string.voice_tap_space_hold : R.string.voice_tap_send_hold);
        } else if (fullMode && !editorHasText()) {
            label = "按住说话";
        } else if (editorHasText()) {
            // 输入框里已经有字，单击就按后台设置执行空格或发送；长按仍然是说话。
            label = getString(isSpaceMode() ? R.string.voice_tap_space : R.string.voice_tap_send);
        } else {
            label = "按住说话";
        }
        if (!label.contentEquals(tvStatus.getText())) tvStatus.setText(label);
        // 说完之后文案变长，字号收小一点，保证一行完整显示；
        // 麦克风只在默认“按住说话”时出现，进入发送/空格/重试状态就收掉。
        boolean justDone = !retry && lastVoiceDoneAt > 0;
        tvStatus.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
                retry ? 13 : (justDone ? 11 : 14));
        if (ivMic != null) ivMic.setVisibility(
                (!retry && !justDone && "按住说话".equals(label) && !fullMode)
                        ? View.VISIBLE : View.GONE);
        if (btnDeleteFull != null) {
            btnDeleteFull.setVisibility(fullMode ? View.VISIBLE : View.GONE);
        }
        if (btnDelete != null) btnDelete.setVisibility(fullMode ? View.GONE : View.VISIBLE);
        updateModeButton();
    }

    /** 模式键三态：语音显示 ABC，全键盘中文显示中，全键盘英文显示英。 */
    private void updateModeButton() {
        if (tvModeLabel == null) return;
        tvModeLabel.setVisibility(View.VISIBLE);
        String label;
        String desc;
        switch (inputMode) {
            case MODE_FULL_CN:
                label = "中";
                desc = "切换到全键盘英文";
                break;
            case MODE_FULL_EN:
                label = "英";
                desc = "切换到语音输入";
                break;
            default:
                label = "ABC";
                desc = "切换到全键盘中文";
                break;
        }
        if (!label.contentEquals(tvModeLabel.getText())) tvModeLabel.setText(label);
        if (ivModeIcon != null) ivModeIcon.setVisibility(View.GONE);
        if (btnMode != null) btnMode.setContentDescription(desc);
    }

    private Handler deleteHandler;
    private Runnable deleteRunnable;

    /** 后台选的是“打一个空格”还是“说完后发送”。 */
    private boolean isSpaceMode() {
        return Prefs.getString(this, Prefs.K_SPACE_MODE, Prefs.SPACE_SEND)
                .equals(Prefs.SPACE_INSERT);
    }

    private void bindDeleteButton() {
        // 小键盘和大全键盘的两个删除位共用同一套触摸节奏
        bindRepeatDelete(btnDelete);
        bindRepeatDelete(btnDeleteFull);
    }

    private void bindRepeatDelete(View v) {
        if (v == null) return;
        // 用触摸事件自己控制节奏：按下删一个，按住 400ms 后每 60ms 删一个
        v.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    deleteOneStep();
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    startRepeatDelete();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    stopRepeatDelete();
                    return true;
                default:
                    return false;
            }
        });
    }

    /** 删一次：有拼音串就退字母；英文候选条里的字母也要同步退掉；都没有才删输入框里的字。 */
    private void deleteOneStep() {
        if (composing.length() > 0 || (fullMode && !enBuf.isEmpty())) {
            fullDelete();
            updateVoiceBar();
            return;
        }
        if (!voiceComposing.isEmpty()) {
            String rest = voiceComposing.substring(0,
                    voiceComposing.offsetByCodePoints(voiceComposing.length(), -1));
            voiceComposing = rest;
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) {
                if (rest.isEmpty()) {
                    ic.setComposingText("", 1);
                    ic.finishComposingText();
                    hasComposing = false;
                } else {
                    ic.setComposingText(rest, 1);
                }
            }
            updateVoiceBar();
            return;
        }
        deleteOnce();
        updateVoiceBar();
    }

    /** 把还挂在输入框里的语音文字收尾，别留一段删不掉的下划线。 */
    private void settleComposing() {
        if (voiceComposing.isEmpty()) return;
        String t = voiceComposing;
        voiceComposing = "";
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            if (t.isEmpty()) {
                ic.setComposingText("", 1);
            } else {
                ic.setComposingText(t, 1);
            }
            ic.finishComposingText();
        }
        hasComposing = false;
    }

    private void startRepeatDelete() {
        if (deleteHandler == null) deleteHandler = new Handler(Looper.getMainLooper());
        stopRepeatDelete();
        deleteRunnable = new Runnable() {
            private int times = 0;

            @Override
            public void run() {
                deleteOneStep();
                times++;
                deleteHandler.postDelayed(this, times < 10 ? 60 : 35);
            }
        };
        deleteHandler.postDelayed(deleteRunnable, 320);
    }

    private void stopRepeatDelete() {
        if (deleteHandler != null && deleteRunnable != null) {
            deleteHandler.removeCallbacks(deleteRunnable);
        }
        deleteRunnable = null;
    }

    /**
     * 一次只删一个字。有选区就整段清掉，没有就删光标前一个字。
     * 以前这里还会补发一次 KEYCODE_DEL 做兜底，结果在大部分输入框里等于删两次，已经去掉。
     */
    private void deleteOnce() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;

        CharSequence sel = ic.getSelectedText(0);
        if (sel != null && sel.length() > 0) {
            ic.commitText("", 1);
            return;
        }

        ic.deleteSurroundingText(1, 0);
    }

    /** 中间条不按住的时候就当空格键用。 */
    private void insertSpace() {
        enBuf = "";
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        ic.commitText(" ", 1);
        lastVoiceDoneAt = 0;
        updateVoiceBar();
        vibrate(8);
    }

    private void performSend() {
        // 发送后输入框会清空，候选条里记的英文缩写也要一起清掉，否则会越积越多
        clearComposing();
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        EditorInfo ei = getCurrentInputEditorInfo();
        int action = ei == null ? EditorInfo.IME_ACTION_NONE
                : (ei.imeOptions & EditorInfo.IME_MASK_ACTION);
        boolean noEnterAction = ei != null
                && (ei.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0;
        if (ei != null && action != EditorInfo.IME_ACTION_NONE && !noEnterAction) {
            // 输入框自己声明了发送/搜索这类动作，就照它说的做
            ic.performEditorAction(action);
        } else if (isChatPackage(ei != null ? ei.packageName : null)) {
            // 聊天类输入框即使没声明动作，也认发送动作；只发回车键它们当换行
            ic.performEditorAction(EditorInfo.IME_ACTION_SEND);
        } else {
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
        }

        lastVoiceDoneAt = 0;
        updateVoiceBar();
        refreshStatus(getString(R.string.voice_hint));
        // 发送后不再自己弹键盘。App 收键盘是它自己的行为，硬弹回来只会造成收起又展开。
    }

    /** 常见聊天类 App，回车在它们那里默认当发送。 */
    private boolean isChatPackage(String pkg) {
        if (pkg == null) return false;
        switch (pkg) {
            case "com.tencent.mm":
            case "com.tencent.mobileqq":
            case "com.tencent.tim":
            case "com.tencent.wework":
            case "com.ss.android.ugc.aweme":
            case "com.xingin.xhs":
            case "com.alibaba.android.rimet":
                return true;
            default:
                return false;
        }
    }

    private void bindSendButton() {
        // 长按回车写一个换行，短按发送
        btnSend.setOnLongClickListener(v -> {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) {
                ic.commitText("\n", 1);
            }
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            return true;
        });
        btnSend.setOnClickListener(v -> {
            performSend();
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        });
    }


    private void bindSettingsButton() {
        btnSettings.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            if (llQuick == null) {
                openSettings();
                return;
            }
            if (llQuick.getVisibility() == View.VISIBLE) hideQuickPanel();
            else showQuickPanel();
        });
    }

    /** 展开快捷面板时先回语音态，和原来一样把全键盘收起来。 */
    private void showQuickPanel() {
        if (llQuick == null) return;
        numbersOpen = false;
        applyInputMode(MODE_VOICE);
        // 面板整条把三横杠那个键也包进来，看起来是一整块，不是飘在旁边的两块
        if (btnSettings != null) btnSettings.setVisibility(View.INVISIBLE);
        llQuick.setVisibility(View.VISIBLE);
        llQuick.bringToFront();
    }

    private void hideQuickPanel() {
        if (llQuick != null && llQuick.getVisibility() != View.GONE) {
            llQuick.setVisibility(View.GONE);
        }
        if (btnSettings != null) btnSettings.setVisibility(View.VISIBLE);
    }

    /** 切到下一个输入法，切不动就弹系统的输入法选择器。 */
    private void switchIme() {
        hideQuickPanel();
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm == null) return;
            // 切输入法要用输入法服务自己的这个方法，系统管理器上那个同名方法签名不一样。
            // 这方法 API 28 才有，低版本直接弹系统选择器。
            boolean ok = false;
            if (Build.VERSION.SDK_INT >= 28) ok = switchToNextInputMethod(false);
            if (!ok) imm.showInputMethodPicker();
        } catch (Throwable t) {
            try {
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showInputMethodPicker();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 模式键三态循环：语音极简 → 全键盘中文 → 全键盘英文 → 语音极简。 */
    private void bindModeButton() {
        btnMode.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            int next;
            if (inputMode == MODE_VOICE) {
                next = MODE_FULL_CN;
            } else if (inputMode == MODE_FULL_CN) {
                next = MODE_FULL_EN;
            } else {
                next = MODE_VOICE;
            }
            setInputMode(next);
        });
    }

    private void setInputMode(int mode) {
        if (mode < MODE_VOICE || mode > MODE_FULL_EN) mode = MODE_VOICE;
        if (mode == inputMode) return;
        shiftOn = false;
        applyInputMode(mode);
        clearComposing();
        rebuildFullRows();
        applyPanelMode();
        vibrate(10);
    }

    private void applyInputMode(int mode) {
        if (mode < MODE_VOICE || mode > MODE_FULL_EN) mode = MODE_VOICE;
        inputMode = mode;
        fullMode = mode != MODE_VOICE;
        pinyinMode = mode == MODE_FULL_CN;
        if (llNumbers != null) {
            llNumbers.setVisibility(View.GONE);
        }
        if (llFull != null) {
            llFull.setVisibility(fullMode ? View.VISIBLE : View.GONE);
        }
        if (flKeysRow != null) {
            flKeysRow.setVisibility(View.VISIBLE);
        }
        if (btnDeleteFull != null) {
            btnDeleteFull.setVisibility(fullMode ? View.VISIBLE : View.GONE);
        }
        if (btnDelete != null) {
            btnDelete.setVisibility(fullMode ? View.GONE : View.VISIBLE);
        }
        updateModeButton();
        updateVoiceBar();
    }

    /** 数字行：1 到 0 一排，每个键右上角挂一个常用标点，往上滑就打标点。 */
    private void buildNumberPad() {
        if (llNumbers == null || llNumbers.getChildCount() > 0) return;
        Context c = uiCtx != null ? uiCtx : this;
        int keyColor = c.getColor(R.color.key_text);
        int subColor = c.getColor(R.color.text_secondary);
        String[] digits = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"};
        String[] marks = {"，", "。", "？", "！", "、", "：", "；", "～", "（", "）"};
        for (int i = 0; i < digits.length; i++) {
            final String digit = digits[i];
            final String mark = marks[i];

            LinearLayout cell = new LinearLayout(c);
            cell.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
            lp.setMargins(dp(2), 0, dp(2), 0);
            cell.setLayoutParams(lp);
            cell.setBackgroundResource(R.drawable.bg_btn);

            // 标点在上，单独占一条固定高度的行，和数字不会挤在一起
            TextView sub = new TextView(c);
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(13));
            sub.setLayoutParams(subLp);
            sub.setGravity(Gravity.CENTER);
            sub.setText(mark);
            sub.setTextColor(subColor);
            sub.setTextSize(9);
            cell.addView(sub);

            // 数字占剩下的空间，垂直居中偏下一点
            TextView tv = new TextView(c);
            LinearLayout.LayoutParams digitLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
            tv.setLayoutParams(digitLp);
            tv.setGravity(Gravity.CENTER);
            tv.setText(digit);
            tv.setTextColor(keyColor);
            tv.setTextSize(15);
            cell.addView(tv);

            cell.setOnTouchListener(new android.view.View.OnTouchListener() {
                private float downY;

                @Override
                public boolean onTouch(android.view.View v, MotionEvent e) {
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            downY = e.getY();
                            v.setPressed(true);
                            return true;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            v.setPressed(false);
                            boolean up = downY - e.getY() > dp(14);
                            if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                                if (up) {
                                    commitKey(mark);
                                } else {
                                    commitKey(digit);
                                }
                            }
                            return true;
                        default:
                            return true;
                    }
                }
            });
            llNumbers.addView(cell);
        }
    }



    // ============ 全键盘打字 ============

    /** 全键盘：候选条 + 数字行 + 三排字母（末排带空格和退格），叠在语音条上方。 */
    private void buildFullKeyboard(View root) {
        flKeysRow = root.findViewById(R.id.fl_keys);
        if (flKeysRow == null || !(flKeysRow.getParent() instanceof LinearLayout)) return;
        LinearLayout parent = (LinearLayout) flKeysRow.getParent();

        llFull = new LinearLayout(uiCtx);
        llFull.setOrientation(LinearLayout.VERTICAL);
        llFull.setVisibility(View.GONE);

        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(uiCtx);
        hs.setHorizontalScrollBarEnabled(false);
        hsCand = hs;
        hs.setBackgroundColor(uiCtx.getColor(R.color.panel_bg));
        llCandBar = new LinearLayout(uiCtx);
        llCandBar.setOrientation(LinearLayout.HORIZONTAL);
        llCandBar.setGravity(Gravity.CENTER_VERTICAL);
        llCandBar.setPadding(dp(8), 0, dp(8), 0);
        hs.addView(llCandBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        llFull.addView(hs, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(36)));

        llFullRows = new LinearLayout(uiCtx);
        llFullRows.setOrientation(LinearLayout.VERTICAL);
        llFullRows.setClipChildren(false);
        llFullRows.setClipToPadding(false);
        llFull.addView(llFullRows, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        rebuildFullRows();
        installFullKeyTouch();
        refreshCand();

        new Thread(() -> com.minivoice.ime.input.PinyinDict.get(getApplicationContext())).start();

        int idx = parent.indexOfChild(flKeysRow);
        parent.addView(llFull, idx, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void rebuildFullRows() {
        if (llFullRows == null) return;
        llFullRows.removeAllViews();
        addDigitRow();
        addLetterRow(new String[]{"q", "w", "e", "r", "t", "y", "u", "i", "o", "p"},
                new String[]{"%", "&", "*", "+", "=", "$", "^", "_", "[", "]"});
        addLetterRow(new String[]{"a", "s", "d", "f", "g", "h", "j", "k", "l"},
                new String[]{"-", "/", ":", ";", "(", ")", "~", "“", "”"});
        addLastRow();
    }

    private void addDigitRow() {
        LinearLayout row = newRow(34);
        for (int i = 1; i <= 9; i++) {
            row.addView(mkKey(String.valueOf(i), 1f, 16, false, null,
                    onLetterRunnable(String.valueOf(i)), null));
        }
        row.addView(mkKey("0", 1f, 16, false, null, onLetterRunnable("0"), null));
        llFullRows.addView(row);
    }

    private void addLetterRow(String[] letters, String[] puncts) {
        LinearLayout row = newRow(46);
        for (int i = 0; i < letters.length; i++) {
            final String letter = letters[i];
            boolean upper = shiftOn && !pinyinMode;
            row.addView(mkKey(upper ? letter.toUpperCase() : letter, 1f, 20, true,
                    () -> onShiftLetter(letter), () -> onShiftLetter(letter), puncts[i]));
        }
        llFullRows.addView(row);
    }

    /** 最后一排：左边大小写切换，中间 z 到 m，右边删除。 */
    private void addLastRow() {
        LinearLayout row = newRow(46);
        View shift = mkKey(shiftOn ? "⇪" : "⇧", 1.1f, 17, false, null, () -> {
            shiftOn = !shiftOn;
            // 中文拼音态按大小写，等于进英文态，模式键也跟着变英
            if (shiftOn && inputMode == MODE_FULL_CN) {
                applyInputMode(MODE_FULL_EN);
                clearComposing();
            }
            rebuildFullRows();
        }, null);
        if (shiftOn) {
            TextView st = shift.findViewById(R.id.tv_key);
            if (st != null) st.setTextColor(uiCtx.getColor(R.color.accent));
        }
        row.addView(shift);
        String[] mid = {"z", "x", "c", "v", "b", "n", "m"};
        String[] midPuncts = {"@", ".", "#", "`", "?", "!", "…"};
        for (int i = 0; i < mid.length; i++) {
            final String letter = mid[i];
            boolean upper = shiftOn && !pinyinMode;
            row.addView(mkKey(upper ? letter.toUpperCase() : letter, 1f, 20, true,
                    null, () -> onShiftLetter(letter), midPuncts[i]));
        }
        // M 右边这一格放删除，长按可以连续删
        View del = mkKey("删除", 1.1f, 12, false, null, () -> {
            deleteOneStep();
            if (llFullRows != null) llFullRows.performHapticFeedback(
                    HapticFeedbackConstants.KEYBOARD_TAP);
        }, null);
        KeySpec ds = (KeySpec) del.getTag();
        if (ds != null) ds.repeatDelete = true;
        row.addView(del);
        llFullRows.addView(row);
    }

    private LinearLayout newRow(int heightDp) {
        LinearLayout row = new LinearLayout(uiCtx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp)));
        return row;
    }

    private Runnable onLetterRunnable(final String key) {
        return () -> onFullLetter(key);
    }

    /**
     * 按键统一走 item_ime_key：字母大号居中，顶部小字放数字或符号，
     * 小字和字母共用一条中轴。
     * 短按走 action，长按弹窗选大写或符号，字母键上滑也能打标点。
     */
    private View mkKey(String label, float weight, int textSizeSp,
                       boolean longPressUppercase,
                       Runnable longAction, Runnable action, String punct) {
        FrameLayout box = (FrameLayout) LayoutInflater.from(uiCtx)
                .inflate(R.layout.item_ime_key, null, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, weight);
        lp.setMargins(dp(2), dp(1), dp(2), dp(1));
        box.setLayoutParams(lp);
        TextView key = box.findViewById(R.id.tv_key);
        key.setText(label);
        key.setTextSize(textSizeSp);
        TextView hint = box.findViewById(R.id.tv_punc);
        if (punct == null || punct.isEmpty()) {
            hint.setVisibility(View.GONE);
        } else {
            hint.setText(punct);
        }

        KeySpec spec = new KeySpec();
        spec.action = action;
        spec.mainLabel = label;
        // 中文模式长按不出大写：拼音串里突然落一个大写字母，后面全乱
        spec.longAction = (longPressUppercase && longAction != null && !pinyinMode)
                ? longAction : null;
        spec.punct = punct;
        spec.upper = longPressUppercase ? label.toUpperCase() : null;
        spec.showLongPopup = longPressUppercase && punct != null && !punct.isEmpty();
        box.setTag(spec);
        return box;
    }

    /** 每个键上挂的东西：短按做什么、长按做什么、上滑打什么标点。 */
    private static class KeySpec {
        Runnable action;
        Runnable longAction;
        String punct;
        String upper;
        String mainLabel;
        boolean showLongPopup;
        boolean repeatDelete;
    }

    /** 一整块键盘收触摸：按歪了吸到最近的键，手指滑过去能换键，抬起才上屏。 */
    private void installFullKeyTouch() {
        if (llFullRows == null) return;
        final View[] pressed = {null};
        final View[] startKey = {null};
        final Runnable[] longTask = {null};
        final boolean[] longFired = {false};
        final float[] downRawY = {0f};
        final float[] downRawX = {0f};

        llFullRows.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    View k = keyAt(e.getX(), e.getY());
                    pressed[0] = k;
                    startKey[0] = k;
                    longFired[0] = false;
                    longTask[0] = null;
                    downRawY[0] = e.getRawY();
                    downRawX[0] = e.getRawX();
                    if (k != null) {
                        pressKey(k);
                        showTapPopup(k);
                        scheduleLong(k, longTask, longFired);
                    }
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (longFired[0]) {
                        updateKeyPopupSelection(e.getRawX());
                        return true;
                    }
                    View k = keyAt(e.getX(), e.getY());
                    if (k != pressed[0]) {
                        if (longTask[0] != null) main.removeCallbacks(longTask[0]);
                        longTask[0] = null;
                        if (longFired[0]) stopRepeatDelete();
                        longFired[0] = false;
                        if (pressed[0] != null) releaseKey(pressed[0]);
                        pressed[0] = k;
                        if (k != null) {
                            pressKey(k);
                            showTapPopup(k);
                            k.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                            scheduleLong(k, longTask, longFired);
                        } else {
                            dismissTapPopup();
                        }
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP: {
                    if (longTask[0] != null) main.removeCallbacks(longTask[0]);
                    stopRepeatDelete();
                    dismissTapPopup();
                    View k = pressed[0];
                    View downKey = startKey[0];
                    pressed[0] = null;
                    startKey[0] = null;
                    if (k != null) releaseKey(k);
                    if (downKey != null && downKey != k) releaseKey(downKey);
                    if (longFired[0]) {
                        commitKeyPopup();
                        return true;
                    }
                    // 往上滑打符号时，以按下去的那颗键为准，别被中途经过的键带偏
                    boolean mostlyVertical = Math.abs(e.getRawX() - downRawX[0]) < dp(22);
                    View source = (mostlyVertical && downKey != null) ? downKey : k;
                    if (source == null) return true;
                    KeySpec spec = (KeySpec) source.getTag();
                    if (spec == null || spec.action == null) return true;
                    source.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    if (spec.punct != null && !spec.punct.isEmpty()
                            && downRawY[0] - e.getRawY() > dp(10)) {
                        commitPunct(spec.punct);
                        return true;
                    }
                    spec.action.run();
                    return true;
                }
                case MotionEvent.ACTION_CANCEL: {
                    if (longTask[0] != null) main.removeCallbacks(longTask[0]);
                    stopRepeatDelete();
                    if (pressed[0] != null) releaseKey(pressed[0]);
                    if (startKey[0] != null && startKey[0] != pressed[0]) releaseKey(startKey[0]);
                    pressed[0] = null;
                    startKey[0] = null;
                    dismissTapPopup();
                    dismissKeyPopup();
                    return true;
                }
                default:
                    return false;
            }
        });
    }

    /** 按下就冒出来的小预览，显示当前键的主字符，松手马上收起。 */
    private void showTapPopup(View key) {
        KeySpec spec = (KeySpec) key.getTag();
        if (spec == null || spec.mainLabel == null || spec.mainLabel.isEmpty()) {
            dismissTapPopup();
            return;
        }
        dismissTapPopup();

        TextView tv = new TextView(uiCtx);
        tv.setText(spec.mainLabel);
        tv.setGravity(Gravity.CENTER);
        tv.setIncludeFontPadding(false);
        tv.setTextColor(uiCtx.getColor(R.color.text_primary));
        tv.setBackgroundResource(R.drawable.bg_key_popup);
        boolean wide = spec.mainLabel.length() > 1;
        tv.setTextSize(wide ? 13 : 22);

        int w = dp(wide ? 56 : 44);
        int h = dp(46);
        tapPopup = new PopupWindow(tv, w, h, false);
        tapPopup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        tapPopup.setOutsideTouchable(false);
        tapPopup.setFocusable(false);
        tapPopup.setElevation(dp(6));
        tapPopup.showAsDropDown(key, (key.getWidth() - w) / 2,
                -(h + key.getHeight() + dp(2)));
        tapPopupText = tv;
    }

    private void dismissTapPopup() {
        if (tapPopup != null) {
            tapPopup.dismiss();
            tapPopup = null;
        }
        tapPopupText = null;
    }

    private void showKeyPopup(View key, KeySpec spec) {
        dismissTapPopup();
        dismissKeyPopup();
        keyPopupUpperText = spec.upper == null ? "" : spec.upper;
        keyPopupSymbolText = spec.punct == null ? "" : spec.punct;
        keyPopupLowerText = spec.mainLabel == null ? "" : spec.mainLabel;
        // 和微信输入法一样，横排三格，默认先把中间的符号选中；
        // 手指往左滑选大写，往右滑选小写。
        keyPopupSelected = 1;

        LinearLayout card = new LinearLayout(uiCtx);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER);
        card.setBackgroundResource(R.drawable.bg_key_popup);
        card.setPadding(dp(3), dp(3), dp(3), dp(3));
        keyPopupUpper = popupOption(keyPopupUpperText, 19);
        keyPopupSymbol = popupOption(keyPopupSymbolText, 18);
        keyPopupLower = popupOption(keyPopupLowerText, 14);
        keyPopupLower.setTextColor(uiCtx.getColor(R.color.text_secondary));
        LinearLayout.LayoutParams optionLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        card.addView(keyPopupUpper, optionLp);
        card.addView(keyPopupSymbol, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        card.addView(keyPopupLower, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        int w = dp(156);
        keyPopupWidthPx = w;
        keyPopupHeightPx = dp(50);
        keyPopup = new PopupWindow(card, w, keyPopupHeightPx, false);
        keyPopup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        keyPopup.setOutsideTouchable(false);
        keyPopup.setFocusable(false);
        keyPopup.setElevation(dp(8));

        int[] loc = new int[2];
        key.getLocationOnScreen(loc);
        int offsetX = (key.getWidth() - w) / 2;
        keyPopupLeftRaw = loc[0] + offsetX;
        keyPopup.showAsDropDown(key, offsetX,
                -(keyPopupHeightPx + key.getHeight() + dp(6)));
        renderKeyPopupSelection();
    }

    private TextView popupOption(String text, int textSizeSp) {
        TextView tv = new TextView(uiCtx);
        tv.setText(text);
        tv.setTextSize(textSizeSp);
        tv.setGravity(Gravity.CENTER);
        tv.setIncludeFontPadding(false);
        tv.setTextColor(uiCtx.getColor(R.color.text_primary));
        tv.setBackgroundResource(R.drawable.bg_chip_idle);
        return tv;
    }

    private void updateKeyPopupSelection(float rawX) {
        if (keyPopup == null) return;
        // 横排三格：左格大写，中间符号，右格小写。
        // 按手指所在的横向区间判断，默认选中中间符号。
        float third = keyPopupWidthPx / 3f;
        int selected;
        if (rawX < keyPopupLeftRaw + third) {
            selected = 0;
        } else if (rawX < keyPopupLeftRaw + third * 2f) {
            selected = 1;
        } else {
            selected = 2;
        }
        if (selected != keyPopupSelected) {
            keyPopupSelected = selected;
            renderKeyPopupSelection();
            if (uiCtx != null) keyPopup.getContentView().performHapticFeedback(
                    HapticFeedbackConstants.KEYBOARD_TAP);
        }
    }

    private void renderKeyPopupSelection() {
        if (keyPopupUpper == null || keyPopupSymbol == null || keyPopupLower == null) return;
        keyPopupUpper.setBackgroundResource(
                keyPopupSelected == 0 ? R.drawable.bg_chip_active : R.drawable.bg_chip_idle);
        keyPopupUpper.setTextColor(uiCtx.getColor(
                keyPopupSelected == 0 ? R.color.on_accent : R.color.text_primary));
        keyPopupSymbol.setBackgroundResource(
                keyPopupSelected == 1 ? R.drawable.bg_chip_active : R.drawable.bg_chip_idle);
        keyPopupSymbol.setTextColor(uiCtx.getColor(
                keyPopupSelected == 1 ? R.color.on_accent : R.color.text_primary));
        keyPopupLower.setBackgroundResource(
                keyPopupSelected == 2 ? R.drawable.bg_chip_active : R.drawable.bg_chip_idle);
        keyPopupLower.setTextColor(uiCtx.getColor(
                keyPopupSelected == 2 ? R.color.on_accent : R.color.text_secondary));
    }

    private void commitKeyPopup() {
        if (keyPopup == null) return;
        int selected = keyPopupSelected;
        String upper = keyPopupUpperText;
        String punct = keyPopupSymbolText;
        String lower = keyPopupLowerText;
        dismissKeyPopup();
        if (selected == 1) {
            commitPunct(punct);
        } else if (selected == 0 && upper != null && !upper.isEmpty()) {
            // 中文拼音状态下长大写，先切到英文，免得大写被拼进拼音串
            if (pinyinMode) {
                applyInputMode(MODE_FULL_EN);
                shiftOn = false;
                clearComposing();
                rebuildFullRows();
            }
            onFullLetter(upper);
        } else if (selected == 2 && lower != null && !lower.isEmpty()) {
            onFullLetter(lower);
        }
    }

    private void dismissKeyPopup() {
        dismissTapPopup();
        if (keyPopup != null) {
            keyPopup.dismiss();
            keyPopup = null;
        }
        keyPopupUpper = null;
        keyPopupSymbol = null;
        keyPopupLower = null;
        keyPopupSelected = 1;
        keyPopupUpperText = null;
        keyPopupSymbolText = null;
        keyPopupLowerText = null;
        keyPopupWidthPx = 0;
        keyPopupHeightPx = 0;
        keyPopupLeftRaw = 0;
    }

    /** 触摸点落在哪个键上，行与键都是按坐标自己算，不依赖子控件的监听。 */
    private View keyAt(float x, float y) {
        if (llFullRows == null) return null;
        for (int i = 0; i < llFullRows.getChildCount(); i++) {
            View row = llFullRows.getChildAt(i);
            if (y < row.getTop() || y >= row.getBottom()) continue;
            if (!(row instanceof ViewGroup)) continue;
            ViewGroup g = (ViewGroup) row;
            float rx = x - row.getLeft();
            float ry = y - row.getTop();
            for (int j = 0; j < g.getChildCount(); j++) {
                View key = g.getChildAt(j);
                if (rx >= key.getLeft() && rx < key.getRight()
                        && ry >= key.getTop() && ry < key.getBottom()) return key;
            }
        }
        return null;
    }

    private void pressKey(View k) {
        k.setBackgroundResource(R.drawable.bg_ime_key_pressed);
        k.animate().cancel();
        k.animate().scaleX(1.04f).scaleY(1.04f).alpha(0.94f).setDuration(45).start();
    }

    private void releaseKey(View k) {
        k.setBackgroundResource(R.drawable.bg_ime_key);
        k.animate().cancel();
        k.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(70).start();
    }

    private void scheduleLong(final View k, Runnable[] holder, boolean[] fired) {
        KeySpec spec = (KeySpec) k.getTag();
        if (spec == null) return;
        if (spec.repeatDelete) {
            holder[0] = () -> {
                dismissTapPopup();
                fired[0] = true;
                k.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                startRepeatDelete();
            };
            main.postDelayed(holder[0], 360);
            return;
        }
        if (spec.showLongPopup) {
            holder[0] = () -> {
                dismissTapPopup();
                fired[0] = true;
                k.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                showKeyPopup(k, spec);
            };
            main.postDelayed(holder[0], 360);
            return;
        }
        if (spec.longAction == null) return;
        holder[0] = () -> {
            dismissTapPopup();
            fired[0] = true;
            k.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            spec.longAction.run();
        };
        main.postDelayed(holder[0], 360);
    }

    /** 空格键：轻点有候选就选第一个，没有就打空格；按住 300 毫秒开始说话，上滑取消。 */
    private void onFullLetter(String key) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        char c0 = key.charAt(0);
        boolean isLetter = (c0 >= 'a' && c0 <= 'z') || (c0 >= 'A' && c0 <= 'Z');
        if (!pinyinMode || !isLetter) {
            ic.commitText(key, 1);
            if (isLetter) {
                enBuf = enBuf + key.toLowerCase();
                if (enBuf.length() > 12) enBuf = enBuf.substring(enBuf.length() - 12);
            } else {
                enBuf = "";
            }
            refreshCand();
            return;
        }
        enBuf = "";
        // 拼音串太长先落第一个候选，免得后面越打越乱
        if (composing.length() >= 24) {
            if (!candList.isEmpty()) {
                commitCandidate(candList.get(0));
            } else if (composing.length() > 0) {
                ic.commitText(composing.toString(), 1);
                composing.setLength(0);
            }
        }
        composing.append(key.toLowerCase());
        ic.setComposingText(composing.toString(), 1);
        refreshCand();
    }

    /** 字母键统一入口：大写开关打开后就一直保持，直到再点一下大小写键。 */
    private void onShiftLetter(String letter) {
        boolean upper = shiftOn && !pinyinMode;
        onFullLetter(upper ? letter.toUpperCase() : letter);
    }

    /** 词库候选要两行才铺得开，其他时候保持一条细条。 */
    private void setCandBarHeight(int hDp) {
        if (hsCand == null || candBarH == hDp) return;
        candBarH = hDp;
        android.view.ViewGroup.LayoutParams lp = hsCand.getLayoutParams();
        lp.height = dp(hDp);
        hsCand.setLayoutParams(lp);
    }

    private void commitEnPhrase(String cand) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        String body = phraseText.containsKey(cand) ? phraseText.get(cand) : cand;
        if (!enBuf.isEmpty()) {
            ic.deleteSurroundingText(enBuf.length(), 0);
        }
        ic.commitText(body, 1);
        enBuf = "";
        candList.clear();
        refreshCand();
    }

    private void commitPunct(String p) {
        enBuf = "";
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if (composing.length() > 0) {
            ic.commitText(composing.toString(), 1);
            composing.setLength(0);
        }
        candList.clear();
        enBuf = "";
        ic.commitText(p, 1);
        refreshCand();
    }

    private void fullDelete() {
        if (!pinyinMode && !enBuf.isEmpty()) {
            enBuf = enBuf.substring(0, enBuf.length() - 1);
        }
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if (composing.length() > 0) {
            composing.setLength(composing.length() - 1);
            ic.setComposingText(composing.length() == 0 ? "" : composing.toString(), 1);
            refreshCand();
            return;
        }
        deleteOnce();
        // 英文模式没有拼音串，退掉 enBuf 后也要重画候选条，
        // 否则输入框已经少一个字，候选条还停在原来的缩写上。
        refreshCand();
    }

    private void fullSpace() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if (composing.length() > 0) {
            if (!candList.isEmpty()) {
                commitCandidate(candList.get(0));
            } else {
                ic.commitText(composing.toString(), 1);
                composing.setLength(0);
                candList.clear();
                refreshCand();
            }
            return;
        }
        insertSpace();
    }

    private void fullEnter() {
        clearComposing();
        performSend();
    }

    private void clearComposing() {
        composing.setLength(0);
        candList.clear();
        enBuf = "";
        refreshCand();
    }

    private void refreshCand() {
        if (llCandBar == null) return;
        llCandBar.removeAllViews();
        candList.clear();
        updateVoiceBar();
        if (composing.length() == 0 && (pinyinMode || enBuf.isEmpty())) {
            setCandBarHeight(36);
            TextView hint = new TextView(uiCtx);
            hint.setText(pinyinMode ? "拼音输入，点候选上屏" : "英文输入");
            hint.setTextSize(11);
            hint.setTextColor(uiCtx.getColor(R.color.text_hint));
            hint.setGravity(Gravity.CENTER_VERTICAL);
            llCandBar.addView(hint, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
            updateVoiceBar();
            return;
        }
        String letters = composing.toString();
        if (!pinyinMode) {
            phraseText.clear();
            java.util.List<com.minivoice.ime.data.PhraseStore.Phrase> hits =
                    com.minivoice.ime.data.PhraseStore.match(this, enBuf, 4);
            if (!hits.isEmpty()) {
                for (com.minivoice.ime.data.PhraseStore.Phrase p : hits) {
                    String show = p.text.replace("\n", " · ");
                    if (candList.contains(show)) continue;
                    candList.add(show);
                    phraseText.put(show, p.text);
                }
                setCandBarHeight(60);
                int n0 = Math.min(candList.size(), 6);
                for (int i = 0; i < n0; i++) {
                    final String cand = candList.get(i);
                    TextView chip = new TextView(uiCtx);
                    chip.setText(cand);
                    chip.setTextSize(12);
                    chip.setMaxLines(3);
                    chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    chip.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.75));
                    chip.setGravity(Gravity.CENTER_VERTICAL);
                    chip.setIncludeFontPadding(false);
                    chip.setTextColor(uiCtx.getColor(R.color.accent));
                    chip.setGravity(Gravity.CENTER);
                    chip.setPadding(dp(10), 0, dp(10), 0);
                    chip.setOnClickListener(v -> {
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                        commitEnPhrase(cand);
                    });
                    llCandBar.addView(chip, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
                }
                updateVoiceBar();
                return;
            }
            setCandBarHeight(36);
            TextView tv = new TextView(uiCtx);
            tv.setText(enBuf.isEmpty() ? "英文输入" : enBuf);
            tv.setTextSize(14);
            tv.setTextColor(uiCtx.getColor(R.color.text_primary));
            tv.setGravity(Gravity.CENTER_VERTICAL);
            llCandBar.addView(tv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
            updateVoiceBar();
            return;
        }
        updateVoiceBar();
        phraseText.clear();
        // 个人词库和快捷输入排在候选最前面：打 fp 出来的就是发票抬头
        boolean hasPhrase = false;
        for (com.minivoice.ime.data.PhraseStore.Phrase p :
                com.minivoice.ime.data.PhraseStore.match(this, letters, 3)) {
            String show = p.text.replace("\n", " · ");
            if (candList.contains(show)) continue;
            candList.add(show);
            phraseText.put(show, p.text);
            hasPhrase = true;
        }
        for (String c : com.minivoice.ime.input.PinyinDict.get(this).candidates(letters, 18)) {
            if (!candList.contains(c)) candList.add(c);
        }
        if (candList.isEmpty()) {
            TextView tv = new TextView(uiCtx);
            tv.setText(letters + " 没有候选");
            tv.setTextSize(11);
            tv.setTextColor(uiCtx.getColor(R.color.text_hint));
            tv.setGravity(Gravity.CENTER_VERTICAL);
            llCandBar.addView(tv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return;
        }
        setCandBarHeight(hasPhrase ? 60 : 36);
        int n = Math.min(candList.size(), 14);
        for (int i = 0; i < n; i++) {
            final String cand = candList.get(i);
            boolean isPhrase = phraseText.containsKey(cand);
            TextView tv = new TextView(uiCtx);
            tv.setText(cand);
            tv.setTextSize(isPhrase ? 12 : 16);
            tv.setIncludeFontPadding(false);
            tv.setTextColor(uiCtx.getColor(isPhrase ? R.color.accent : R.color.text_primary));
            tv.setGravity(Gravity.CENTER_VERTICAL);
            tv.setMaxLines(isPhrase ? 3 : 1);
            tv.setEllipsize(isPhrase ? android.text.TextUtils.TruncateAt.END : null);
            tv.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.75));
            tv.setPadding(dp(10), 0, dp(isPhrase ? 14 : 10), 0);
            tv.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                commitCandidate(cand);
            });
            llCandBar.addView(tv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    private void commitCandidate(String cand) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        String body = phraseText.containsKey(cand) ? phraseText.get(cand) : cand;
        ic.commitText(body, 1);
        composing.setLength(0);
        candList.clear();
        enBuf = "";
        refreshCand();
        updateVoiceBar();
    }
    private void commitKey(String key) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if ("⌫".equals(key)) {
            ic.deleteSurroundingText(1, 0);
        } else {
            ic.commitText(key, 1);
        }
        vibrate(8);
    }

    /** 输入法窗口下方的系统导航栏跟着面板走，浅色主题用浅色图标，避免露出一条白边或黑边。 */
    private void tintNavigationBar() {
        try {
            Window win = getWindow().getWindow();
            if (win == null) return;
            int navColor = uiCtx != null
                    ? uiCtx.getColor(R.color.nav_bar) : 0xFF14161A;
            win.setNavigationBarColor(navColor);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                win.setNavigationBarContrastEnforced(false);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                View decor = win.getDecorView();
                int flags = decor.getSystemUiVisibility();
                if (ThemeUtil.isLight(this)) {
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                } else {
                    flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                }
                decor.setSystemUiVisibility(flags);
            }
        } catch (Exception ignored) {
            // 老 ROM 上拿不到窗口就算了，不影响输入
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    // ---------------- 录音与识别 ----------------

    public void startVoice() {
        lastVoiceDoneAt = 0;
        refreshStatus(getString(R.string.voice_hint));
        if (recording || waiting) return;
        resetRetryAudio();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            refreshStatus(getString(R.string.voice_need_setup));
            Intent it = new Intent(this, PermissionActivity.class);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(it);
            return;
        }

        AsrConfig config = AsrConfig.load(this);
        Prefs.switchTokenModel(this, config.tokenIdentity(this));
        if (!config.isUsable()) {
            refreshStatus(getString(R.string.voice_need_setup));
            Toast.makeText(this, "请先在设置里填写语音识别接口地址", Toast.LENGTH_SHORT).show();
            openSettings();
            return;
        }

        if (AsrConfig.PROTOCOL_LOCAL.equals(config.protocol)
                && !LocalModelManager.isReady(this)) {
            refreshStatus("本地模型还没下好");
            Toast.makeText(this, "本地模型还没下载，先去设置页点下载，或者切回云端",
                    Toast.LENGTH_LONG).show();
            openSettings();
            return;
        }

        engine = createEngine(config);

        engine.start(buildVoiceCallback(config));

        // 本地识别由系统自己开麦克风，不能再起外部录音器，否则两路抢麦
        if (engine.managesOwnAudio()) {
            recording = true;
            voiceStartedAt = android.os.SystemClock.elapsedRealtime();
            refreshStatus(getString(R.string.voice_release));
            flashAccent(true);
            vibrate(30);
            return;
        }

        // 蓝牙麦那套入口已经从设置里删了，统一用本机麦克风，避免 SCO 没建好录到一片静音
        boolean bt = false;

        boolean ok = recorder.start(this, bt, new AudioRecorder.Listener() {
            @Override
            public void onPcm(byte[] data, int length) {
                if (engine != null) engine.feed(data, length);
                appendRetryAudio(data, length);
                updateWaveLevel(data, length);
            }

            @Override
            public void onError(String message) {
                refreshStatus("录音异常");
                Toast.makeText(MinimalVoiceIME.this, message, Toast.LENGTH_SHORT).show();
                stopVoice();
            }
        });

        if (!ok) {
            if (bt) BluetoothAudioRouter.leaveSco(this);
            Toast.makeText(this, "麦克风启动失败，检查一下录音权限", Toast.LENGTH_LONG).show();
            flashAccent(false);
            return;
        }

        recording = true;
        voiceStartedAt = android.os.SystemClock.elapsedRealtime();
        refreshStatus(engine instanceof com.minivoice.ime.asr.RealtimeAsrEngine
                ? "按住说话，流式输入" : getString(R.string.voice_release));
        flashAccent(true);
        vibrate(30);
    }

    /** 识别回调统一放这里，正常录音和失败重试共用一套收尾逻辑。 */
    private AsrEngine.Callback buildVoiceCallback(final AsrConfig config) {
        return new AsrEngine.Callback() {
            @Override
            public void onPartial(String text) {
                if (TextUtils.isEmpty(text)) return;
                InputConnection ic = getCurrentInputConnection();
                if (ic != null) {
                    voiceComposing = text;
                    ic.setComposingText(text, 1);
                    hasComposing = true;
                }
                // 状态栏也显示当前中间稿，用户不用只靠输入框判断有没有在流式出字。
                boolean stream = engine instanceof com.minivoice.ime.asr.RealtimeAsrEngine;
                String live = (stream ? "流式输入：" : "识别中：") + (text.length() > 16
                        ? text.substring(text.length() - 16) : text);
                refreshStatus(live);
            }

            @Override
            public void onFinal(String text) {
                if (TextUtils.isEmpty(text)) {
                    markVoiceFailure("没听清", true);
                    return;
                }
                // 本地模型偶尔吐繁体字，这里统一压成简体
                final String raw = TextNormalizer.toSimplified(clean(text));

                // 流式识别时输入框里已经挂着一段 composing 文本，
                // 这里先把它替换成完整稿，整理完再替换一次，不会重复上屏。
                InputConnection ic = getCurrentInputConnection();
                if (ic != null) {
                    voiceComposing = raw;
                    ic.setComposingText(raw, 1);
                }

                // 少于 10 个字符的短句直接上屏，不触发整理，也不走本地规则清理。
                if (config.polish && shouldPolish(raw) && startPolish(raw)) {
                    return;
                }
                String plain = (config.polish && shouldPolish(raw))
                        ? TermFix.apply(MinimalVoiceIME.this, TextPolisher.polishLocal(raw))
                        : raw;
                finishWithText(plain);
            }

            @Override
            public void onError(String message) {
                Toast.makeText(MinimalVoiceIME.this, message, Toast.LENGTH_LONG).show();
                markVoiceFailure(message, true);
            }
        };
    }

    /** 只数非空白字符，少于 10 个就不整理。 */
    private boolean shouldPolish(String raw) {
        if (TextUtils.isEmpty(raw)) return false;
        int count = 0;
        for (int i = 0; i < raw.length(); ) {
            int codePoint = raw.codePointAt(i);
            if (!Character.isWhitespace(codePoint)) count++;
            if (count >= MIN_POLISH_CHARS) return true;
            i += Character.charCount(codePoint);
        }
        return false;
    }

    /** 网络失败后保留这次录音，点一下中间大键就重新转，不用重录。 */
    private void retryVoice() {
        if (!retryReady || recording || waiting) return;
        byte[] pcm = snapshotRetryAudio();
        if (pcm.length < 3200) {
            resetRetryAudio();
            refreshStatus("说得太短了，按住多说一会儿");
            updateVoiceBar();
            return;
        }

        AsrConfig config = AsrConfig.load(this);
        Prefs.switchTokenModel(this, config.tokenIdentity(this));
        if (!config.isUsable()) {
            refreshStatus(getString(R.string.voice_need_setup));
            Toast.makeText(this, "请先在设置里填写语音识别接口地址", Toast.LENGTH_SHORT).show();
            return;
        }
        if (AsrConfig.PROTOCOL_LOCAL.equals(config.protocol)
                && !LocalModelManager.isReady(this)) {
            refreshStatus("本地模型还没下好");
            Toast.makeText(this, "本地模型还没下载，先去设置页点下载，或者切回云端",
                    Toast.LENGTH_LONG).show();
            return;
        }

        clearVoiceComposingForRetry();
        retryReady = false;
        lastVoiceDoneAt = 0;
        if (engine != null) engine.cancel();
        engine = createRetryEngine(config);
        waiting = true;
        refreshStatus(getString(R.string.voice_retrying));
        showBusy(true);
        flashAccent(false);
        engine.start(buildVoiceCallback(config));

        // 整段喂给批处理引擎，失败重试不用再占着麦克风重新录一遍。
        for (int offset = 0; offset < pcm.length; offset += 3200) {
            int len = Math.min(3200, pcm.length - offset);
            byte[] chunk = new byte[len];
            System.arraycopy(pcm, offset, chunk, 0, len);
            engine.feed(chunk, len);
        }
        engine.finish();

        long recordedMs = pcm.length / 32;
        long waitMs = Math.max(WAIT_TIMEOUT_MS,
                Math.min(90000L, recordedMs * 2 + 15000L));
        armWaitTimeout(waitMs);
    }

    private AsrEngine createRetryEngine(AsrConfig config) {
        if (AsrConfig.PROTOCOL_LOCAL.equals(config.protocol)) {
            return new LocalAsrEngine(this);
        }
        if (AsrConfig.PROTOCOL_BAILIAN.equals(config.protocol)) {
            // 重试走整段上传，别把一大段音频再塞进实时 WebSocket。
            return new BailianAsrEngine(config, this);
        }
        if (AsrConfig.PROTOCOL_WS.equals(config.protocol)) {
            return new WsAsrEngine(config);
        }
        return new HttpAsrEngine(config);
    }

    private void markVoiceFailure(String message, boolean allowRetry) {
        waiting = false;
        showBusy(false);
        flashAccent(false);
        lastVoiceDoneAt = 0;
        if (engine != null) {
            engine.cancel();
            engine = null;
        }
        retryReady = allowRetry && retryAudioSize() >= 3200;
        updateVoiceBar();
        if (!retryReady) {
            refreshStatus(TextUtils.isEmpty(message) ? "没听清，再按住说一次" : message);
        }
    }

    private void appendRetryAudio(byte[] data, int length) {
        if (data == null || length <= 0) return;
        synchronized (retryAudio) {
            int room = MAX_RETRY_AUDIO_BYTES - retryAudio.size();
            if (room <= 0) return;
            retryAudio.write(data, 0, Math.min(room, length));
        }
    }

    private int retryAudioSize() {
        synchronized (retryAudio) {
            return retryAudio.size();
        }
    }

    private byte[] snapshotRetryAudio() {
        synchronized (retryAudio) {
            return retryAudio.toByteArray();
        }
    }

    private void resetRetryAudio() {
        retryReady = false;
        synchronized (retryAudio) {
            retryAudio.reset();
        }
    }

    private void clearVoiceComposingForRetry() {
        if (!hasComposing && voiceComposing.isEmpty()) return;
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            ic.setComposingText("", 1);
            ic.finishComposingText();
        }
        hasComposing = false;
        voiceComposing = "";
    }

    /** 算这一小段 PCM 的音量，喂给波形，让它跟着说话起伏。 */
    private void updateWaveLevel(byte[] data, int length) {
        WaveformDrawable wd = waveDrawable;
        if (wd == null) return;
        double sum = 0;
        int samples = 0;
        for (int i = 0; i + 1 < length; i += 2) {
            short v = (short) ((data[i] & 0xFF) | (data[i + 1] << 8));
            sum += (double) v * v;
            samples++;
        }
        if (samples == 0) return;
        float rms = (float) Math.sqrt(sum / samples);
        float level = Math.min(1f, rms / 7000f);
        if (wd != null) wd.setLevel(level);
    }

    public void stopVoice() {
        if (!recording) return;
        recording = false;
        // 松手后先把红色波形收掉，再进入等待进度，避免波形和进度条同时叠在语音条上
        flashAccent(false);

        if (engine != null && engine.managesOwnAudio()) {
            waiting = true;
            refreshStatus(getString(R.string.voice_thinking));
            showBusy(true);
            engine.finish();
            return;
        }

        recorder.stop();
        BluetoothAudioRouter.leaveSco(this);
        waiting = true;
        refreshStatus(getString(R.string.voice_thinking));
        showBusy(true);
        if (engine != null) {
            engine.finish();
        }
        long recordedMs = voiceStartedAt > 0
                ? android.os.SystemClock.elapsedRealtime() - voiceStartedAt : 0;
        // 长录音要走分段上传，20 秒固定超时会把还在转写的请求掐掉。
        long waitMs = Math.max(WAIT_TIMEOUT_MS, Math.min(90000L, recordedMs * 2 + 15000L));
        armWaitTimeout(waitMs);
    }

    public void cancelVoice() {
        recording = false;
        waiting = false;
        if (hasComposing) {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) {
                ic.setComposingText("", 1);
                ic.finishComposingText();
            }
            hasComposing = false;
        }
        voiceComposing = "";
        recorder.stop();
        BluetoothAudioRouter.leaveSco(this);
        if (engine != null) {
            engine.cancel();
            engine = null;
        }
        showBusy(false);
        refreshStatus("");
        flashAccent(false);
        resetRetryAudio();
    }

    private void stopEverything() {
        cancelVoice();
    }

    private AsrEngine createEngine(AsrConfig config) {
        if (AsrConfig.PROTOCOL_LOCAL.equals(config.protocol)) {
            return new LocalAsrEngine(this);
        }
        if (AsrConfig.PROTOCOL_BAILIAN.equals(config.protocol)) {
            // 百炼默认走实时流式，边说边出字；WebSocket 建不起来时引擎会自己
            // 退回整段上传，所以不需要为了“怕失败”把默认模型改成一次性。
            if (AsrConfig.isBailianEndpoint(config.endpoint)) {
                return new RealtimeAsrEngine(config, this);
            }
            // 小米、智谱等其它兼容平台暂不支持这家的实时协议，走整段识别。
            return new BailianAsrEngine(config, this);
        }
        if (AsrConfig.PROTOCOL_WS.equals(config.protocol)) {
            return new WsAsrEngine(config);
        }
        return new HttpAsrEngine(config);
    }

    /** 过滤语气词、套用标点偏好。 */
    private String clean(String text) {
        String out = text.trim();
        // 原来的语气词替换（默认表里带「就是」）会把「就是说」切成「说」，已经去掉，不再做这种盲删。
        return TermFix.apply(this, TextPolisher.tidy(out));
    }

    /** 发出去之后最多等 20 秒，超时就复位，别一直挂着“识别中”。 */
    private void armWaitTimeout(long timeoutMs) {
        main.postDelayed(() -> {
            if (!waiting) return;
            clearVoiceComposingForRetry();
            markVoiceFailure("识别超时了", true);
        }, timeoutMs);
    }

    private void refreshStatus(String text) {
        main.post(() -> {
            if (tvStatus != null) tvStatus.setText(text);
        });
    }

    /** 识别时在语音条边缘跑一圈细弧线，整理时收掉，换成整条渐变跑马灯。 */
    private void showVoiceRing(boolean on) {
        if (ringVoice == null) return;
        if (on) {
            ringVoice.setIndeterminate(false);
            ringVoice.setRingVisible(true);
            ringVoice.setIndeterminate(true);
        } else {
            ringVoice.setIndeterminate(false);
            ringVoice.setRingVisible(false);
        }
    }

    /** 识别和整理期间，语音条里的麦克风换成转圈，完事再换回来。 */
    private void showBusy(boolean on) {
        main.post(() -> {
            // 识别阶段统一用语音条边缘的细圆环，不再显示条内横向进度
            if (pbInline != null) pbInline.setVisibility(View.GONE);
            if (ivMic != null) {
                ivMic.setVisibility(on ? View.GONE : View.VISIBLE);
            }
            showVoiceRing(on);
            if (!on) stopPolishProgress();
        });
    }

    /** 整理期间，整条语音按钮跑彩色跑马灯，一眼能看出还在处理。 */
    private void startPolishProgress() {
        main.post(() -> {
            if (btnVoice == null) return;
            showVoiceRing(false);
            if (pbInline != null) pbInline.setVisibility(View.GONE);
            android.content.Context c = uiCtx != null ? uiCtx : MinimalVoiceIME.this;
            MarqueeDrawable md = new MarqueeDrawable(c, 26 * getResources().getDisplayMetrics().density);
            md.reshuffle();
            btnVoice.setBackground(md);
            if (polishAnim != null) polishAnim.cancel();
            polishAnim = ValueAnimator.ofFloat(0f, 1f);
            polishAnim.setDuration(1400);
            polishAnim.setRepeatCount(ValueAnimator.INFINITE);
            polishAnim.setRepeatMode(ValueAnimator.REVERSE);
            polishAnim.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
            polishAnim.addUpdateListener(a -> md.setPhase((float) a.getAnimatedValue()));
            polishAnim.start();
        });
    }

    private void stopPolishProgress() {
        if (polishAnim != null) {
            polishAnim.cancel();
            polishAnim = null;
        }
        main.post(() -> {
            if (btnVoice == null) return;
            showVoiceRing(false);
            btnVoice.setBackgroundResource(R.drawable.bg_voice_bar);
            if (ivMic != null) ivMic.setVisibility(View.VISIBLE);
            if (tvStatus != null) tvStatus.setVisibility(View.VISIBLE);
        });
    }

    /** 转写完成，绿色和原底色交替闪两次。 */
    private void flashDone() {
        main.post(() -> {
            if (btnVoice == null || recording) return;
            final int half = 70;
            for (int i = 0; i < 2; i++) {
                final int idx = i;
                main.postDelayed(() -> {
                    if (btnVoice == null) return;
                    btnVoice.setBackgroundResource(R.drawable.bg_voice_bar_success);
                    main.postDelayed(() -> {
                        if (btnVoice == null) return;
                        btnVoice.setBackgroundResource(R.drawable.bg_voice_bar);
                    }, half);
                }, idx * half * 2);
            }
        });
    }

    private void flashAccent(boolean on) {
        main.post(() -> {
            if (btnVoice == null) return;
            if (on) {
                showVoiceRing(false);
                // 录音时是红底加白色波形，文字和麦克风都藏起来
                android.content.Context c = uiCtx != null ? uiCtx : MinimalVoiceIME.this;
                WaveformDrawable wd = new WaveformDrawable(c, 26 * getResources().getDisplayMetrics().density);
                waveDrawable = wd;
                btnVoice.setBackground(wd);
                if (waveAnim != null) waveAnim.cancel();
                waveAnim = ValueAnimator.ofFloat(0f, 1f);
                waveAnim.setDuration(1500);
                waveAnim.setRepeatCount(ValueAnimator.INFINITE);
                waveAnim.setInterpolator(new android.view.animation.LinearInterpolator());
                waveAnim.addUpdateListener(a -> wd.setPhase((float) a.getAnimatedValue()));
                waveAnim.start();
            } else {
                showVoiceRing(false);
                if (waveAnim != null) {
                    waveAnim.cancel();
                    waveAnim = null;
                }
                waveDrawable = null;
                btnVoice.setBackgroundResource(R.drawable.bg_voice_bar);
            }
            if (ivMic != null) ivMic.setVisibility(on ? View.GONE : View.VISIBLE);
            if (tvStatus != null) tvStatus.setVisibility(on ? View.GONE : View.VISIBLE);
            if (tvSwipeHint != null && on) tvSwipeHint.setVisibility(View.INVISIBLE);
            if (pbInline != null && on) pbInline.setVisibility(View.GONE);
            // 文字和图标一直跟着主题走，不再因为录音变成白色
            int color = uiCtx != null ? uiCtx.getColor(R.color.text_primary) : 0xFF1A1D21;
            if (ivMic != null) ivMic.setColorFilter(color);
            if (tvStatus != null) tvStatus.setTextColor(color);
        });
    }


    private void vibrate(int ms) {
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            v.vibrate(ms);
        }
    }

    /** 按用户选的引擎整理。异步的返回 true，同步的让它走规则清理。 */
    private boolean startPolish(String raw) {
        String engine = Prefs.getString(this, Prefs.K_POLISH_ENGINE, "cloud");
        if ("local".equals(engine)) {
            if (!LocalLlmModel.ready(this)) return false;
            refreshStatus("整理中…");
            startPolishProgress();
            final String src = raw;
            new Thread(() -> {
                String out = LlmPolisher.polish(MinimalVoiceIME.this, src);
                final String done = (out == null || out.isEmpty())
                        ? TermFix.apply(MinimalVoiceIME.this, TextPolisher.polishLocal(src))
                        : TermFix.apply(MinimalVoiceIME.this, out);
                main.post(() -> finishWithText(done));
            }).start();
            return true;
        }
        refreshStatus("整理中…");
        startPolishProgress();
        TextPolisher.polish(this, raw, result -> {
            String done = TermFix.apply(MinimalVoiceIME.this,
                    (result == null || result.isEmpty()) ? TextPolisher.polishLocal(raw) : result);
            finishWithText(done);
        });
        return true;
    }

    /** 统一把整理好的文字上屏，收尾动效也在这。 */
    private void finishWithText(String text) {
        waiting = false;
        showBusy(false);
        InputConnection c = getCurrentInputConnection();
        if (c != null) {
            c.setComposingText(text, 1);
            c.finishComposingText();
        }
        hasComposing = false;
        voiceComposing = "";
        lastVoiceDoneAt = SystemClock.uptimeMillis();
        resetRetryAudio();
        updateVoiceBar();
        flashAccent(false);
        flashDone();
    }

    public void openSettings() {
        Intent it = new Intent(this, SettingsActivity.class);
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(it);
    }
}

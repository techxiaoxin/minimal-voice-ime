package com.minivoice.ime.service;

import android.view.WindowManager;
import android.app.Dialog;
import android.Manifest;
import android.content.Context;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
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
import com.minivoice.ime.ui.WaveformDrawable;

import android.view.View;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputConnection;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.minivoice.ime.R;
import com.minivoice.ime.asr.AsrConfig;
import com.minivoice.ime.asr.AsrEngine;
import com.minivoice.ime.asr.BailianAsrEngine;
import com.minivoice.ime.asr.HttpAsrEngine;
import com.minivoice.ime.asr.LocalAsrEngine;
import com.minivoice.ime.asr.LocalModelManager;
import com.minivoice.ime.asr.RealtimeAsrEngine;
import com.minivoice.ime.asr.TermFix;
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
    /** 点设置键展开的快捷面板，里面是换输入法和进设置两条。 */
    private LinearLayout llQuick;
    private ImageView ivQuickIme;
    private ImageView ivQuickSettings;
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
    /** 微博里是否已经点开了表情区。切到别的 App 会自动收回。 */
    private boolean weiboExpanded = false;
    private String currentPkg = null;
    private boolean holdStarted = false;
    /** 录音时上滑取消，松手不再转写。 */
    private boolean voiceCanceled = false;
    private float downY = 0f;
    private boolean hasComposing = false;
    private Runnable holdStarter;

    private final AudioRecorder recorder = new AudioRecorder();
    private AsrEngine engine;
    private boolean recording = false;
    private boolean waiting = false;

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
        btnDelete = root.findViewById(R.id.btn_delete);
        btnSend = root.findViewById(R.id.btn_send);
        btnSettings = root.findViewById(R.id.btn_settings);
        btnMode = root.findViewById(R.id.btn_mode);
        tvModeLabel = root.findViewById(R.id.tv_mode_label);
        ivModeIcon = root.findViewById(R.id.iv_mode_icon);
        llNumbers = root.findViewById(R.id.ll_numbers);
        llQuick = root.findViewById(R.id.ll_quick);
        ivQuickIme = root.findViewById(R.id.iv_quick_ime);
        ivQuickSettings = root.findViewById(R.id.iv_quick_settings);
        flExpand = root.findViewById(R.id.fl_expand);
        ivExpand = root.findViewById(R.id.iv_expand);
        tvStatus = root.findViewById(R.id.tv_status);
        ivMic = btnVoice.findViewById(R.id.iv_mic);
        tvSwipeHint = root.findViewById(R.id.tv_swipe_hint);
        pbInline = btnVoice.findViewById(R.id.pb_inline);

        // 图标本身是白的，键盘不是用 AppCompat 的 inflater 建的，app:tint 不生效，
        // 所以按主题在代码里上色，浅色主题下才不会白图标配白底。
        int iconTint = uiCtx.getColor(R.color.text_primary);
        btnSettings.setColorFilter(iconTint);
        btnDelete.setColorFilter(iconTint);
        btnSend.setColorFilter(iconTint);
        ivMic.setColorFilter(iconTint);
        ivModeIcon.setColorFilter(iconTint);
        ivExpand.setColorFilter(iconTint);
        ivQuickIme.setColorFilter(iconTint);
        ivQuickSettings.setColorFilter(iconTint);
        root.findViewById(R.id.row_switch_ime).setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            switchIme();
        });
        root.findViewById(R.id.row_open_settings).setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            hideQuickPanel();
            openSettings();
        });
        // 点按键区任何地方都把面板收起来，不挡视线
        View keyArea = root.findViewById(R.id.fl_keys);
        keyArea.setOnTouchListener((v, e) -> {
            hideQuickPanel();
            return false;
        });
        ivExpand.setOnClickListener(v -> {
            weiboExpanded = !weiboExpanded;
            applyPanelMode();
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        });

        buildNumberPad();
        bindVoiceButton();
        bindDeleteButton();
        bindSendButton();
        bindSettingsButton();
        bindModeButton();
        tintNavigationBar();

        // 重进键盘时保持 123 展开状态
        applyModeState(numbersOpen);
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
        // 离开微博就把展开态收回，下次回来又是极简那一条
        if (!weibo) weiboExpanded = false;
        if (flExpand != null) flExpand.setVisibility(weibo ? View.VISIBLE : View.GONE);
        if (ivExpand != null) ivExpand.setRotation(weibo && weiboExpanded ? 180f : 0f);
        int target = 0;
        if (weibo && weiboExpanded) {
            target = Math.min(getResources().getDisplayMetrics().heightPixels * 44 / 100, dp(PANEL_MAX_DP));
        }
        inputRoot.setMinimumHeight(target);
        if (inputRoot instanceof LinearLayout) {
            ((LinearLayout) inputRoot).setGravity(weibo && weiboExpanded ? Gravity.BOTTOM : Gravity.TOP);
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
                    } else if (Prefs.getString(this, Prefs.K_SPACE_MODE, Prefs.SPACE_SEND)
                            .equals(Prefs.SPACE_SEND)
                            && SystemClock.uptimeMillis() - lastVoiceDoneAt < 20000) {
                        // 刚转完字，点一下就当回车发送
                        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        performSend();
                    } else {
                        // 平时点一下就是空格键
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                        insertSpace();
                    }
                    return true;
            }
            return false;
        });
    }

    private Handler deleteHandler;
    private Runnable deleteRunnable;

    /** 后台选的是“打一个空格”还是“说完后发送”。 */
    private boolean isSpaceMode() {
        return Prefs.getString(this, Prefs.K_SPACE_MODE, Prefs.SPACE_SEND)
                .equals(Prefs.SPACE_INSERT);
    }

    private void bindDeleteButton() {
        // 用触摸事件自己控制节奏：按下删一个，按住 400ms 后每 60ms 删一个
        btnDelete.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    deleteOnce();
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
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

    private void startRepeatDelete() {
        if (deleteHandler == null) deleteHandler = new Handler(Looper.getMainLooper());
        stopRepeatDelete();
        deleteRunnable = new Runnable() {
            @Override
            public void run() {
                deleteOnce();
                deleteHandler.postDelayed(this, 60);
            }
        };
        deleteHandler.postDelayed(deleteRunnable, 400);
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
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        ic.commitText(" ", 1);
        vibrate(8);
    }

    private void performSend() {
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

    /**
     * 展开快捷面板。输入法窗口高度跟着视图走，面板一显示窗口自己就长高，
     * 不用像微博那样手动垫高度，也不用 PopupWindow，那个会被小窗口裁掉。
     */
    private void showQuickPanel() {
        if (llQuick == null) return;
        numbersOpen = false;
        applyModeState(false);
        llQuick.setVisibility(View.VISIBLE);
    }

    private void hideQuickPanel() {
        if (llQuick != null && llQuick.getVisibility() != View.GONE) {
            llQuick.setVisibility(View.GONE);
        }
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

    /** 文字态显示 123，按下去进数字行；数字态显示麦克风，按下去回语音。 */
    private void bindModeButton() {
        btnMode.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            numbersOpen = !numbersOpen;
            applyModeState(numbersOpen);
            vibrate(10);
        });
    }

    private void applyModeState(boolean numbers) {
        if (llNumbers != null) {
            llNumbers.setVisibility(numbers ? View.VISIBLE : View.GONE);
        }
        if (tvModeLabel != null) {
            tvModeLabel.setVisibility(numbers ? View.GONE : View.VISIBLE);
        }
        if (ivModeIcon != null) {
            ivModeIcon.setVisibility(numbers ? View.VISIBLE : View.GONE);
        }
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


    private void onNumberKey(String key) {
        commitKey(key);
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

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            refreshStatus(getString(R.string.voice_need_setup));
            Intent it = new Intent(this, PermissionActivity.class);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(it);
            return;
        }

        AsrConfig config = AsrConfig.load(this);
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

        engine.start(new AsrEngine.Callback() {
            @Override
            public void onPartial(String text) {
                if (TextUtils.isEmpty(text)) return;
                InputConnection ic = getCurrentInputConnection();
                if (ic != null) {
                    ic.setComposingText(text, 1);
                    hasComposing = true;
                }
                refreshStatus(getString(R.string.voice_live));
            }

            @Override
            public void onFinal(String text) {
                if (TextUtils.isEmpty(text)) {
                    waiting = false;
                    showBusy(false);
                    refreshStatus("没听清");
                    flashAccent(false);
                    return;
                }
                final String raw = clean(text);

                // 流式识别时输入框里已经挂着一段 composing 文本，
                // 这里先把它替换成完整稿，整理完再替换一次，不会重复上屏。
                InputConnection ic = getCurrentInputConnection();
                if (ic != null) ic.setComposingText(raw, 1);

                // 本地识别也能过一遍 AI 整理，思路是把识别好的文字交给云端模型规整，
                // 联网失败就退回本地清理，不至于卡住不出字
                if (AsrConfig.PROTOCOL_LOCAL.equals(config.protocol)) {
                    if (config.polish && raw.length() > 20) {
                        refreshStatus("整理中…");
                        startPolishProgress();
                        TextPolisher.polish(MinimalVoiceIME.this, raw, result -> {
                            waiting = false;
                            showBusy(false);
                            String base = (result == null || result.isEmpty())
                                    ? TextPolisher.polishLocal(raw) : result;
                            String text2 = TermFix.apply(MinimalVoiceIME.this, base);
                            InputConnection c2 = getCurrentInputConnection();
                            if (c2 != null) {
                                c2.setComposingText(text2, 1);
                                c2.finishComposingText();
                            }
                            hasComposing = false;
                            refreshStatus(isSpaceMode() ? getString(R.string.voice_tap_space)
                                    : getString(R.string.voice_tap_send));
                            flashAccent(false);
                            lastVoiceDoneAt = SystemClock.uptimeMillis();
                            flashDone();
                        });
                        return;
                    }
                    String done = config.polish ? TextPolisher.polishLocal(raw) : raw;
                    if (!done.equals(raw) && ic != null) ic.setComposingText(done, 1);
                    waiting = false;
                    showBusy(false);
                    if (ic != null) ic.finishComposingText();
                    hasComposing = false;
                    refreshStatus(isSpaceMode() ? getString(R.string.voice_tap_space)
                            : getString(R.string.voice_tap_send));
                    flashAccent(false);
                    lastVoiceDoneAt = SystemClock.uptimeMillis();
                    flashDone();
                    return;
                }

                if (config.polish && raw.length() > 20) {
                    // 整理期间保持 waiting，防止用户又按一次录进来
                    refreshStatus("整理中…");
                    startPolishProgress();
                    TextPolisher.polish(MinimalVoiceIME.this, raw, result -> {
                        waiting = false;
                        showBusy(false);
                        InputConnection c2 = getCurrentInputConnection();
                        if (c2 != null) {
                            String text2 = TermFix.apply(MinimalVoiceIME.this,
                                    (result == null || result.isEmpty()) ? raw : result);
                            c2.setComposingText(text2, 1);
                            c2.finishComposingText();
                            hasComposing = false;
                        }
                        refreshStatus(isSpaceMode() ? getString(R.string.voice_tap_space)
                            : getString(R.string.voice_tap_send));
                        flashAccent(false);
                        lastVoiceDoneAt = SystemClock.uptimeMillis();
                        flashDone();
                    });
                    return;
                }

                waiting = false;
                showBusy(false);
                if (ic != null) ic.finishComposingText();
                hasComposing = false;
                refreshStatus(isSpaceMode() ? getString(R.string.voice_tap_space)
                        : getString(R.string.voice_tap_send));
                flashAccent(false);
                lastVoiceDoneAt = SystemClock.uptimeMillis();
                flashDone();
            }

            @Override
            public void onError(String message) {
                waiting = false;
                showBusy(false);
                refreshStatus(message);
                flashAccent(false);
                Toast.makeText(MinimalVoiceIME.this, message, Toast.LENGTH_LONG).show();
            }
        });

        // 本地识别由系统自己开麦克风，不能再起外部录音器，否则两路抢麦
        if (engine.managesOwnAudio()) {
            recording = true;
            refreshStatus(getString(R.string.voice_release));
            flashAccent(true);
            vibrate(30);
            return;
        }

        boolean preferBt = Prefs.getBool(this, Prefs.K_PREFER_BT, true);
        boolean bt = preferBt && BluetoothAudioRouter.hasBluetoothMic(this);
        if (bt) {
            BluetoothAudioRouter.enterSco(this);
        }

        boolean ok = recorder.start(this, bt, new AudioRecorder.Listener() {
            @Override
            public void onPcm(byte[] data, int length) {
                if (engine != null) engine.feed(data, length);
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
            return;
        }

        recording = true;
        refreshStatus(getString(R.string.voice_release));
        flashAccent(true);
        vibrate(30);
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
        armWaitTimeout();
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
        recorder.stop();
        BluetoothAudioRouter.leaveSco(this);
        if (engine != null) {
            engine.cancel();
            engine = null;
        }
        showBusy(false);
        refreshStatus("");
        flashAccent(false);
    }

    private void stopEverything() {
        cancelVoice();
    }

    private AsrEngine createEngine(AsrConfig config) {
        if (AsrConfig.PROTOCOL_LOCAL.equals(config.protocol)) {
            return new LocalAsrEngine(this);
        }
        if (AsrConfig.PROTOCOL_BAILIAN.equals(config.protocol)) {
            // 优先走流式，边说边出字；连不上会自动退回整段上传
            return new RealtimeAsrEngine(config);
        }
        if (AsrConfig.PROTOCOL_WS.equals(config.protocol)) {
            return new WsAsrEngine(config);
        }
        return new HttpAsrEngine(config);
    }

    /** 过滤语气词、套用标点偏好。 */
    private String clean(String text) {
        String out = text.trim();
        for (String filler : Prefs.getFillerList(this)) {
            out = out.replace(filler, "");
        }
        String punctRule = Prefs.getString(this, Prefs.K_PUNCT, "");
        if ("全角".equals(punctRule)) {
            out = out.replace(',', '，').replace('.', '。').replace('?', '？')
                    .replace('!', '！').replace(':', '：');
        }
        return TermFix.apply(this, TextPolisher.tidy(out));
    }

    /** 发出去之后最多等 20 秒，超时就复位，别一直挂着“识别中”。 */
    private void armWaitTimeout() {
        main.postDelayed(() -> {
            if (!waiting) return;
            waiting = false;
            showBusy(false);
            refreshStatus("识别超时了，再按住说一次");
            flashAccent(false);
            if (engine != null) {
                engine.cancel();
                engine = null;
            }
        }, WAIT_TIMEOUT_MS);
    }

    private void refreshStatus(String text) {
        main.post(() -> {
            if (tvStatus != null) tvStatus.setText(text);
        });
    }

    /** 识别和整理期间，语音条里的麦克风换成转圈，完事再换回来。 */
    private void showBusy(boolean on) {
        main.post(() -> {
            if (pbInline != null) {
                pbInline.setVisibility(on ? View.VISIBLE : View.GONE);
            }
            if (ivMic != null) {
                ivMic.setVisibility(on ? View.GONE : View.VISIBLE);
            }
            if (!on) stopPolishProgress();
        });
    }

    /** 整理期间，整条语音按钮跑彩色跑马灯，一眼能看出还在处理。 */
    private void startPolishProgress() {
        main.post(() -> {
            if (btnVoice == null) return;
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

    public void openSettings() {
        Intent it = new Intent(this, SettingsActivity.class);
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(it);
    }
}

# 极简语音输入法 iOS 版移植说明

这份说明写给负责做 iOS 版的开发，安卓版全部功能细节都在这里。看完能直接动手，不用回头翻安卓代码。

## 一、产品是什么

一个极简的语音输入法。键盘上只有几个键：左边切换数字和符号、中间一个大的语音条（长按说话、松手出字）、右边删除和发送，最下面一条快捷栏放菜单、切输入法、设置。核心目标只有一条：说完话能准确地把字打进输入框。视觉好看是次要的。

## 二、iOS 必须先想清楚的三件事

1. iOS 第三方的键盘叫 Keyboard Extension（`UIInputViewController`），它是独立进程，内存上限很紧，实测几十 MB 级别，超了系统直接杀。安卓那套「手机里跑 whisper medium 903MB 和 Qwen 1.5B」的方案在扩展里根本放不下。所以 iOS 版建议：键盘扩展只负责录音、UI 和往输入框写字，识别和整理全走云端；本地模型这条路在 iOS 上基本走不通，不要照抄。
2. 键盘扩展拿麦克风要用户在「设置 → 键盘 → 完全访问」里打开允许完全访问，同时 App 要有麦克风权限，否则录音直接失败。这一条和鸿蒙的完全访问模式类似，必须做引导页。
3. 键盘扩展不能随便开后台长任务，录音要在用户按下的这段时间内完成。松手后请求要控制在几百毫秒以内出结果，否则体验崩掉。云端实时识别的 WebSocket 方案在这里更合适，一次连接、边说边出字。

## 三、功能清单

- 两种识别方式：云端模型 / 本地模型。安卓选一次就记住。
- 两种 AI 整理引擎：云端整理（百炼 qwen3.8-flash）/ 本地整理（手机里跑 GGUF 小模型）。本地模式纯本地，云端模式纯云端，不能混。
- 本地识别三档模型：预览流式 24MB、标准终稿 SenseVoice 228MB、高精度终稿 whisper medium 903MB。
- 本地整理两档模型：小 0.5B 409MB、大 1.5B 718MB，用户自己选自己下。
- 设置页可填自定义接口地址、Key、模型名，也能填自己的模型下载地址。
- 云端用量统计：累计 tokens 和次数，能清零，上万用「万」显示。
- 主题三档：跟随系统 / 浅色 / 深色。
- 空格键模式：打空格 / 说完发送。
- 蓝牙麦优先、局域网配置同步、耳机按键拦截（这三个是安卓专属，iOS 只保留可做的部分）。

## 四、键盘 UI 规格

结构从上到下：

- 数字行 `ll_numbers`，高 40dp，默认隐藏，点左边切换键展开。每格上下两层：标点 9sp 在上居中，数字 15sp 在下。
- 微博展开条 `fl_expand`，高 24dp，只有微博需要，其他 App 隐藏。
- 主键区 `fl_keys`，高 56dp：
  - 左边：设置键 `btn_settings`（44dp，现在改成三横杠图标）和切换键 `btn_mode`（44dp，文字 15sp + 图标 22dp）
  - 中间：语音条 `btn_voice`，高 48dp，里面是波形进度条（19dp）、麦克风图标（21dp）、提示文字（14sp，默认「按住说话」）
  - 右边：删除 `btn_delete` 44dp、发送 `btn_send` 44dp
- 上滑取消提示 `tv_swipe_hint`，高 22dp、11sp，录音时才出现，是个黑色小胶囊，不占常驻高度。
- 快捷栏 `ll_quick`，高 52dp，整条是一块底板（圆角 14dp + 1dp 描边，颜色 quick_bar_bg / quick_bar_stroke），里面三个 44dp 的键：菜单（三横杠）、切换输入法（地球）、设置（齿轮）。

配色（浅色主题）：panel_bg #F4F6FA、card_bg #FFFFFF、accent #2E7CF6、text_primary #1A1D21、text_secondary #5D6673、divider #E4E8EF、quick_bar_bg #DCE4F2、quick_bar_stroke #B9C6DA、recording #FF4D4F、success #16A34A。深色主题在 values-night 里另有整套对应色。圆角统一：卡片 16dp、按键 12dp、输入框 12dp、快捷底板 14dp。所有间距用 8/10/12 的倍数。

## 五、语音交互状态机

1. 按下语音条：启动 300ms 延时器，超过 300ms 才算长按，避免误触。
2. 长按成立 → 开始录音（16kHz、单声道、PCM 16bit）。同时界面进入录音态：外圈闪一下强调色、轻震动 30ms、中间换成音量驱动的波形（中间高两边低），提示文字变「松手完成」。
3. 录音中上滑超过 52dp（按 density 换算）进入取消区：只提示不取消，滑回来还能继续，进区时震动 40ms。松手在取消区就丢弃，弹「已取消这次输入」。
4. 松手正常结束 → 进入识别中：文字在水波/跑马灯状态，提示「识别中」。
5. 流式识别过程中要把中间结果用 `setComposingText` 写进输入框，别直接提交，避免重复上屏。
6. 最终稿回来：先替换成完整稿，再走整理（如果开了 AI 整理），最后提交并闪一次绿色表示完成。
7. 20 秒窗口规则：刚转完文字的 20 秒内，单击语音条等于「发送」（回车），文案提示「点击回车/发送」；过了这个窗口或者切到空格模式，单击就是打空格。
8. 发送逻辑：读 EditorInfo 的 imeOptions，是 IME_ACTION_SEND 就 `performEditorAction(SEND)`（微信这类必须这样才发得出去），否则发一个 KEYCODE_ENTER 的 down/up。
9. 删除键长按连续删，用 Handler 循环。
10. 录音期间静音 STREAM_MUSIC 和 STREAM_SYSTEM，避免自己的提示音被收进去。iOS 用 `AVAudioSession` 的 playback 静音或者干脆不放提示音。

## 六、云端识别接口（百炼）

域名必须用业务空间专属域名，`sk-ws-` 开头的 Key 配通用 dashscope 一定 401：

- 域名：`private-space.example.com`

两条通道：

1. 实时流式（主用，边说边出字）：`wss://<域名>/api-ws/v1/realtime?model=qwen3-asr-flash-realtime`
   - 连上后先发 `session.update`：`{"type":"session.update","session":{"input_audio_format":"pcm","input_audio_transcription":{"model":"qwen3-asr-flash-realtime"},"turn_detection":null,"modalities":["text"]}}`
   - `turn_detection` 一定要 null，否则服务端按静音自己切段，一句话说一半就被断开。
   - 音频用 `{"type":"input_audio_buffer.append","audio":"<base64 PCM16 16k>"}` 每帧推。
   - 说完发 `input_audio_buffer.commit` 收尾，等最终结果。安卓侧等待超时 20 秒。
   - 事件：`conversation.item.input_audio_transcription.text` 是中间结果，`.completed` 是最终结果，`error` 是报错。
   - 连不上或中途断了要自动退回一次性整段上传，保证还能出字。
2. 一次性整段（备用）：`https://<域名>/api/v1/services/aigc/multimodal-generation/generation`
   - 请求体：`model = qwen-audio-3.1-asr-flash`，`input.messages[0].content[0] = {"type":"input_audio","input_audio":{"data":"data:audio/wav;base64,..."}}`，`parameters = {"format":"wav","sample_rate":"16000","language_hints":["zh"]}`
   - 返回取 `output.text`。兼容接口则是 `choices[0].message.content`。

内置 Key 安卓版是编译时从 local.properties 注入的，不写进仓库。iOS 版建议同样用构建配置注入，公开仓库不要带 Key。

## 七、AI 整理

### 云端整理

- 地址：`https://<域名>/compatible-mode/v1/chat/completions`
- 模型：`qwen3.8-flash`
- 参数：`enable_thinking: false`（这个账号开着思考会多等一两秒）、`temperature: 0.2`、`max_tokens = min(800, raw.length()*3+100)`
- 超时：连接 8 秒、读取 12 秒，只用 HTTP/1.1，不走代理。
- 失败就返回原文，绝不吃掉用户说的话。
- 响应里的 `usage.total_tokens` 累加到本地统计（总量 + 次数），设置页显示，可清零。

system 提示词（云端，原文照抄）：

```
你是语音输入法的整理助手。用户说完话，你负责把转写出来的文字整理成他真正想发出去的样子。规则：
1. 只改表达，不改意思。用户没说的内容一个字都不要加，用户说过的信息不要删。
2. 去掉嗯、啊、呃、那个、就是说、然后、嗯哼这类口头语，以及重复啰嗦的词。
3. 同音错字按上下文改成正确的字，人名、产品名、型号、数字、英文按原样保留，不要自行改写或翻译。
4. 补上合适的标点，长句按意思断成短句，一句话说不完可以两三句。
5. 保留用户原本的语气，别改成公文体，也别加书面化的连接词。
6. 只有用户明确在说几件事、明显是并列的时候才分条，最多用 1、2、3 分行，其他情况就写成一整段，不要主动分条。
7. 不要加任何前缀、标题、解释、总结，也不要用引号把整段话包起来，直接输出整理后的正文。
```

如果用户在设置里填了「自定义名词」，在提示词末尾追加第 8 条，把词表用顿号连起来附上，要求出现时按这个写法输出。

### 本地整理

手机上用 llama.cpp 跑 GGUF。安卓是自己交叉编译 llama.cpp 静态库 + 手写 JNI，iOS 可以直接把 llama.cpp 编成 framework/静态库，或者用 swift 包一层。

模型两档（hf-mirror 可直连，国内网络可用）：

- 小：Qwen2.5-0.5B-Instruct q4_0，428730208 字节（409MB）
  `https://hf-mirror.com/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_0.gguf`
- 大：Qwen2.5-1.5B-Instruct q2_k，752880160 字节（718MB）
  `https://hf-mirror.com/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q2_k.gguf`

两档放不同目录（llm-qwen05 / llm-qwen15），可以共存。切档要先把旧模型 release 再加载新的（用加载路径比对，路径变了就重载）。

推理参数（这些是实测调出来的，别改）：

- prompt 模板：`<|im_start|>system\n{sys}\n<|im_end|>\n<|im_start|>user\n{user}\n<|im_end|>\n<|im_start|>assistant\n`
- 采样链：penalties(n_vocab, 96, 1.25, 0.0, 0.0) → top_k(40) → top_p(0.9) → temp(0.45) → dist(seed 12345)
  - 最早用纯贪心，0.5B 必然原样复读，一次都不整理，这条链是修这个的。
- ctx：`n_ctx = n_prompt + maxTokens + 16`，`n_batch = 512`，线程数 clamp(2, 6, CPU 核数)
- maxTokens = max(48, min(256, raw.length()*3+24))
- 生成时检测到 `<|im_end|>` 就截断，`llama_vocab_is_eog` 判停。

本地整理 system 提示词（原文照抄，注意第 7/8/9 条是防过度压缩的）：

```
你是输入法的文字整理助手。用户给的是语音识别原文，口语化，可能有识别错误。你的任务是把它改写成通顺的书面句子。

示例：
原文：嗯那个我今天下午大概三点左右吧去那个福田的 apple 店看一下 iphone 的新机子啊顺便问问 wifi 那个问题
整理：我今天下午三点左右去福田的 Apple 店看 iPhone 新机，顺便问一下 WiFi 的问题。

要求：
1. 只输出整理后的句子，一行，不要前缀，不要引号，不要解释。
2. 删掉「嗯」「啊」「那个」「就是说」「大概」「吧」这类口语填充词，重复的词合并。
3. 把口语改成书面表达，语序理顺，该断句就断句，绝对不要照抄原文。
4. 中英混杂的名词按常见写法输出，比如 iPhone、WiFi、Type-C、Pro，不要翻译成中文，也不要改成别的词。
5. 明显的同音错字按上下文改对，人名、地名、专业词不乱改。
6. 意思和语气不能变，不要扩写，不要加评论和总结。
7. 原文里说到的每件事、每个信息点都要留下，不能因为嫌啰嗦就把内容砍掉。
8. 整理后的长度应该跟原文差不多，最多只能短一成左右，明显变短就说明你删多了，这是不允许的。
9. 宁可保留一点口语痕迹，也不许把意思压没。
```

输出清理（三层兜底，顺序不能变）：

1. 去前缀：「整理后：」「整理后:」「整理结果：」「输出：」「以下是整理」
2. 去掉首尾成对引号
3. 长度超过 `raw.length()*1.8+20` 就判定模型乱扩写，直接丢弃，退回规则清理
4. 最后过一遍标点清理（见第九节）

本地模型没下好、加载失败或者返回空，一律退回规则清理 `polishLocal`，不能把用户的字吃掉。

### 规则清理（无模型时兜底）

- 连续同一个字说 3 次以上压成 1 个；2 到 4 字的词组连说 3 次以上压成 1 个。「常常」「看看」「谢谢」这种两次的正常叠词不动。
- 标点前不留空格，连续逗号顿号压成一个，句末没标点补句号。

## 八、本地语音识别

用的是 sherpa-onnx（onnxruntime + 现成的 Java/Swift API，iOS 有官方 Swift 包 `sherpa-onnx`，可以直接 SPM 引入）。两段式：

1. 说话时用 14M 流式 zipformer 出预览，边说边写进输入框。
2. 松手后用终稿模型重算整段，替换预览文字。

模型清单（全部 hf-mirror、国内直连）：

- 预览（两档共用，目录 sherpa-zh14m，共 24MB）：
  base `https://hf-mirror.com/csukuangfj/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23/resolve/main/`
  文件 `encoder-epoch-99-avg-1.int8.onnx` 21621684 / `decoder-epoch-99-avg-1.int8.onnx` 1888682 / `joiner-epoch-99-avg-1.int8.onnx` 1795562 / `tokens.txt` 48697
- 标准档终稿（SenseVoice int8，中英日韩粤，自带标点，目录 sherpa-sensevoice，228MB）：
  base `https://hf-mirror.com/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/`
  文件 `model.int8.onnx` 239233841 / `tokens.txt` 315894
  配置：language="auto"（写死 zh 会把英文当中文谐音，get up 会被拼成怪东西）、inverseTextNormalization=true
- 高精度档终稿（whisper medium int8，903MB，目录 sherpa-whisper-medium）：
  base `https://hf-mirror.com/csukuangfj/sherpa-onnx-whisper-medium/resolve/main/`
  文件 `medium-encoder.int8.onnx` 374196283 / `medium-decoder.int8.onnx` 571059257 / `medium-tokens.txt` 816730
  配置：language="zh"（写死中文，留空让它自己猜语种会吐繁体）、task="transcribe"、tailPaddings=1000ms

判就绪：文件存在且大小 ≥ 期望值的 98%。下载走 Range 断点续传，临时文件加 `.part`，下完改名，每 1MB 报一次进度。用户可在设置页填自己的下载地址覆盖默认，文件名必须和该档约定一致，下完自动切到这一档。

再强调一次：这一整套在 iOS 键盘扩展里放不下，只在主 App 里做才有意义。键盘扩展要做本地识别就只剩很小的模型可选，体验不如云端。

## 九、文字后处理（三个必做的本地步骤）

### 1. 繁转简

本地 whisper 偶尔吐繁体。识别结果上屏前统一转简体：优先用系统 ICU 的 `TraditionalChinese/SimplifiedChinese` 转换器（Android 是 API 29 以上才有，iOS 可以用 `CFStringTransform(kCFStringTransformSimplifiedChinese)` 或自己维护字表），失败回落内置常用字对照表（這这、們们、個个、來来、時时、說说、話话、點点、裡里、爲为、後后、發发、現现、實实、學学、體体、習习、聽听、問问、題题、嗎吗、兒儿、麼么、樣样、誰谁、國国、還还、沒没、準准、備备、東东、車车、電电、腦脑、網网、絡络、錢钱、開开、關关、機机、碼码、聯联、係系、統统、軟软、設设、頁页、記记、錄录、視视、頻频、圖图、書书、館馆、難难、謝谢、錯错、誤误、轉转、換换、總总、數数、據据、語语、聲声、響响、應应、歡欢、迎迎、顔颜、臉脸、潔洁、夠够、簡简、極极、終终、於于）。

### 2. 名词与大小写修正（TermFix）

纯本地正则近似替换，两种识别方式都生效。两层：

- 用户自定义词表：识别结果里出现跟词表相差一个字的近似词，就地改回用户写的写法。
- 内置表：常见缩写统一大写（cpu/gpu/npu/ram/rom/ssd/hdd/usb/hdmi/api/sdk/ui/ux/ai/asr/tts/ime/ota/nfc/wifi/gps/lte/hdr/oled/lcd/fps/dpi/ppi/pc/tv/vr/ar/soc/bios/dns/cdn/http/https/json/pdf），品牌统一官方写法（iphone→iPhone、ipad→iPad、ios→iOS、macos→macOS、macbook→MacBook、airpods→AirPods、app store→App Store、wechat→WeChat、douyin→抖音、bilibili→B站、huawei→华为、xiaomi→小米、honor→荣耀、oneplus→一加、android→Android、windows→Windows、google→Google、openai→OpenAI、chatgpt→ChatGPT、gpt→GPT、claude→Claude、deepseek→DeepSeek、doubao→豆包、tesla→Tesla、nvidia→NVIDIA、qualcomm→Qualcomm）。

### 3. 标点清理

- 每行开头不留逗号句号顿号
- 标点前不留空格
- 连续相同标点压成一个
- 逗号顿号后面紧跟句号感叹号，只留后面的
- 结尾孤零零的逗号去掉
- 多余空格压成一个

## 十、设置页

两个页签：语音 / 更多。语音页从上到下：

1. 「识别方式」卡片：两个胶囊按钮「云端模型」「本地模型」。云端选中时下面显示「云端用量 xx tokens，共 n 次」和清零小按钮（上万用「万」，两万显示 2万，二十万显示 20万，几千显示完整数字；识别和整理都设成本地时这行自动收起）。
2. 同一个卡片里，本地模式展开本地那一节：档位单选「标准 252MB」「高精度 903MB」、模型状态一行、下载地址输入框（一行，可改成自己的）、「下载模型」和「测试本地模型」两个按钮、结果文字。测试会把内置的中文和英文两段语音各识别一遍，两句都出字才算通过，结果按「中文：…」「英文：…」列出，附耗时。
3. 云端模式则显示另一张「接口地址」卡：接口地址、Key（带小眼睛切换明文）、模型名、测试按钮、结果。
4. 「AI 整理」卡片：总开关、云端整理 / 本地整理两个单选、本地档位「小模型 409MB」「大模型 718MB」、状态文字、下载（下完按钮变「模型测试」）、模型测试结果。

更多页：接口细节、自定义名词、标点规则、口头语过滤、蓝牙麦开关、空格模式、主题三档、局域网同步、已下载的模型管理（列出预览模型、终稿模型、整理小模型、整理大模型的名字和体积，能删，删前二次确认，删整理模型前先释放已加载的模型）。

精简原则：设置页文字能短就短，不要一堆解释；云端模式不显示下载按钮，只显示状态。

## 十一、偏好键对照表（照这个建 iOS 的 UserDefaults）

| 键 | 含义 |
| --- | --- |
| asr_endpoint / asr_api_key / asr_model | 云端接口地址、Key、模型名 |
| asr_protocol | bailian / local / http / ws |
| asr_language | 默认 zh |
| prefer_bluetooth_mic | 优先蓝牙麦，默认 true |
| device_name | 设备名 |
| custom_vocab | 自定义名词，按行存 |
| punctuation_rule / filler_filter | 标点规则、口头语过滤词，默认「嗯,呃,啊,那个,就是」 |
| builtin_ver | 内置 Key 和域名的版本号，换一次 +1，用来刷掉老配置 |
| ai_polish | 是否开 AI 整理 |
| polish_engine | cloud / local |
| llm_url / llm_tier | 本地整理模型地址、档位 small / big |
| polish_tokens / polish_requests | 云端累计 token 和次数 |
| space_mode | send / space |
| local_model_url / local_model_url_sv | 本地识别模型自定义地址 |
| local_model_tier | light / hq |
| theme_mode | auto / light / dark |

## 十二、其余安卓模块，iOS 对应做法

- MainActivity：引导开启输入法、检查权限，iOS 换成引导「设置 → 通用 → 键盘 → 添加键盘 + 允许完全访问」。
- PermissionActivity：申请麦克风权限，iOS 用 AVAudioSession 请求权限。
- BluetoothAudioRouter：录音期间切 SCO，录完放开。iOS 用 `AVAudioSession.setCategory(.playAndRecord, options: .allowBluetooth)`。
- KeyInterceptorService（无障碍）：抓耳机按键转成录音，iOS 做不了，砍掉。
- LanSyncManager：局域网 NSD + TCP 8988 端口互推配置 JSON，iOS 可用 `Network.framework` 的 Bonjour 实现，优先级低。
- ThemeUtil：三档主题，键盘里所有颜色从主题 Context 取。
- WaveformDrawable：录音时音量驱动的波形，中间高两边低。
- MarqueeDrawable：整理时的亮彩跑马灯。

## 十三、开发约定和踩过的坑

- 每版递增 versionCode 和 versionName，APK/包名跟着改。
- 输入视图的尺寸只能改 minimumHeight 或自己包一层容器，禁止动根视图 LayoutParams。安卓 0.2.11 就是给无父容器的根视图挂了 LinearLayout.LayoutParams，导致所有 App 都弹不出键盘（FrameLayout 强转崩溃）。iOS 对应就是别去改 inputView 的 frame 约束链，只调整内部容器高度。
- 豆包、微博这类 App 点发送后自己收键盘，第三方输入法改不了，不要写「补弹键盘」的逻辑，那是割裂感的根源。
- 微博表情面板：安卓的做法是命中 com.sina.weibo 时把输入视图 minimumHeight 抬到「屏高 44% 和 320dp 取小」，内容 gravity 贴底，键盘上方留白给微博表情。注意这个做法只对微博生效。iOS 上微博同理，得实测。
- 荣耀指关节截屏在键盘弹起时用不了，加 FLAG_NOT_TOUCH_MODAL 也无效，属于系统限制。
- 云端 Key 用 sk-ws- 前缀必须配业务空间专属域名，通用域名一定 401。
- 这个账号的文本模型只有 qwen3.8-flash 可用，其他要么 403 要么 404。
- huggingface.co 国内直连不通，用 hf-mirror.com 镜像。
- 语音识别的「模型测试」一定要真加载一遍模型再喂语音，只检查文件大小会漏掉「下错模型」的情况。下错模型（比如下了 TTS 或者 LLM）要弹窗提示「这不是语音识别模型」。

## 十四、几个关键阈值汇总

- 长按判定：300ms
- 上滑取消：52dp（按 density 换算）
- 发送窗口：语音转完 20 秒内单击语音条等于发送
- 云端识别等待：20 秒超时
- 云端整理超时：连接 8 秒 / 读取 12 秒
- 本地整理 maxTokens：48 到 256，`raw.length()*3+24`
- 本地整理扩写阈值：超过原文 1.8 倍加 20 字就丢弃
- 模型就绪判定：文件大小 ≥ 期望 98%
- 微博表情面板高度：min(屏高 44%, 320dp)
- 采样率：16000Hz 单声道 PCM16

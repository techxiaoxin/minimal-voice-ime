# 极简语音输入法

一个安卓输入法，主打按住说话、松手出字。没有广告，没有推送，没有多余按键。需要手打的时候也能切到全键盘。

识别可以走云端，也可以纯本地跑模型，不联网。

## 长什么样

默认就一条，五个键：

- 最左：三横杠，点开在上面叠两个小键，切换输入法和设置
- 左二：模式键，中文全键盘和语音之间来回切
- 中间：语音条，按住说话。有拼音串时点一下是空格，输入框里有字时是发送，空的时候提示按住说话
- 右二：删除，长按连删。打拼音时先退拼音串，拼音退完了才退已上屏的字
- 最右：发送，长按换行

![深色](preview/keyboard_dark.png)
![浅色](preview/keyboard_light.png)

全键盘是四排，数字一排、字母三排。字母键右上角小字是上滑能打的标点，长按三百多毫秒出大写。底下一整条语音键一直在，随时按住说话。

## 已经做到的事

语音

- 按住说话、松手定稿，说的时候字就往上走
- 上滑取消这次输入，已上屏的草稿一起清掉
- 录音时红色胶囊上是跟音量跳的波形，整理时是彩色跑马灯
- 转完字闪两下提示完成
- 三种主题：跟随系统、浅色、深色
- 录音期间自动静音外放，免得喇叭声被麦克风收进去

文字

- 离线拼音输入，自带 2.5 万词的词表，支持词组、自动分词组词
- 标点上滑、长按大写、中英切换
- 按键按下有放大回弹和振动反馈

模型

- 云端识别走阿里云百炼，边说边出字
- 本地识别走 sherpa-onnx，模型自己下，放 `filesDir`，不进 APK
- AI 整理也是本地和云端各一套，选本地模式就只用本地模型，选云端就只走云端
- 设置页能测模型、能删模型、能看云端 Token 用量和每次的明细

词库

- 个人词库：发票抬头、税号、手机号这类固定信息，打缩写整段上屏
- 快捷输入：常用回复、地址这类整段文字
- 缩写要打完整才出，不打一半就提前蹦
- 一堆内容粘进去，AI 拆成缩写加正文，不用一条条录

## 第一次用要配什么

仓库里没有内置 Key，源码里的默认值是空串，clone 下来装完是没法直接用的。两种办法：

一是在设置页填自己的阿里云百炼 Key。`sk-ws-` 开头的业务空间 Key 必须配业务空间专属域名：

- 识别地址：`https://<你的业务空间域名>/api/v1/services/aigc/multimodal-generation/generation`
- 识别模型：`qwen-audio-3.1-asr-flash`
- 流式实时：`wss://<域名>/api-ws/v1/realtime?model=qwen3-asr-flash-realtime`
- AI 整理：`https://<域名>/compatible-mode/v1/chat/completions`，模型 `qwen3.8-flash`

二是干脆不填，切到本地模式，模型在设置页里直接下，整条链路不联网。云端那部分不填 Key 就不会被调用。

如果自己编译、想让它装完就能用，把 Key 写进 `local.properties`，这个文件不会进仓库：

```properties
bailian.key=sk-ws-你自己的key
```

## 怎么编译

需要 JDK 17、Android SDK 34、Gradle 8.7。用 Android Studio 打开 `安卓版` 这一层目录直接跑也行。

命令行：

```bash
export JAVA_HOME=/path/to/jdk17
./gradlew assembleDebug
```

产物在 `app/build/outputs/apk/debug/app-debug.apk`，只打 arm64-v8a。首次编译会从 Maven 拉依赖，国内建议配好镜像。

仓库里没有放工具链，`devtools/`（JDK、Gradle、Android SDK）是自己装在本地的，已经在 `.gitignore` 里。APK 也不进仓库，去 Releases 拿。

## 本地模型

设置页里自己下，默认走 hf-mirror 镜像，地址框留空就用预设，也可以填自己的地址。下载完会自动切到刚下的那个模型。

- 识别标准档，约 199MB：中英双语流式 zipformer，边说边出字，松手就是这个结果
- 识别高精度档，约 563MB：流式预览加 whisper small int8 终稿，中英混说更稳
- AI 整理小模型，约 409MB；大模型，约 718MB

模型文件夹在 `filesDir/models`，设置页「其他设置」下面能看已下载的，也能删。

## 代码在哪

```
app/src/main/java/com/minivoice/ime/
├── service/MinimalVoiceIME.java   输入法本体，按键、全键盘、候选、录音、状态刷新
├── input/PinyinDict.java          离线拼音词表、分词、组词
├── data/PhraseStore.java          个人词库和快捷输入的存储与缩写匹配
├── data/PhraseAi.java             粘一段内容让 AI 拆成词条
├── data/PhraseParser.java         纯 Java 解析，能脱离安卓单独跑
├── asr/
│   ├── RealtimeAsrEngine.java     云端流式识别
│   ├── BailianAsrEngine.java      整段上传兜底
│   ├── LocalAsrEngine.java        sherpa-onnx 本地识别
│   ├── AsrConfig.java             Key、域名、模型
│   ├── TextPolisher.java          AI 整理
│   ├── LlmPolisher.java           本地模型整理
│   └── TermFix.java               固定名词和英文大写
├── audio/AudioRecorder.java       16k 单声道录音
├── sync/LanSyncManager.java       局域网同步
├── settings/Prefs.java            全部本地配置，含 Token 统计
└── ui/                            设置页、主题、波形和跑马灯
```

拼音词表在 `app/src/main/assets/`，`pinyin_words.txt` 和 `pinyin_chars.txt`，从 jieba 词频筛出来的。

## 已知的坑

- 荣耀的「敲一敲」截屏，键盘弹着的时候用不了，试过 `FLAG_NOT_TOUCH_MODAL` 无效。
- 豆包、微博这类 App 点发送后会自己收键盘，任何第三方输入法都一样，属于 App 行为，硬弹回来反而更乱。
- 拼音表只收了词频 100 以上的词，很冷僻的词和个别长串会切得不够漂亮。
- ARM 的 Mac 上开安卓模拟器需要虚拟化权限，某些环境跑不起来，这部分只能真机验。

## 许可

MIT，见 LICENSE。

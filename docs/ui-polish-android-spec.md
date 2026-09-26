# JevChat Android UI 打磨 — 规格与实施计划（SPEC）

> 来源：`D:\dev\HANDOFF.md`（2026-09-26）。本文件是 Android 侧施工的唯一事实源。
> 硬规则（HANDOFF §2）全程有效：只填不发；只填外语；面板不自动弹出、悬浮窗保持
> `FLAG_NOT_FOCUSABLE`；日志只记条数和长度；无障碍服务类名 `SelectToSpeakService` 不改名。

## 0. 用户拍板项的代决（HANDOFF §9，本轮代决，可在 review 时推翻）

| # | 问题 | 决策 | 理由 |
|---|---|---|---|
| 1 | 统一主色 | **靛蓝 `#4F5BD5`**（HANDOFF 建议值） | 与微信/WhatsApp 绿、QQ 蓝都拉开层次 |
| 2 | 深色模式本轮做不做 | **做**（跟随 `Configuration.uiMode`） | token 化后成本低；P2-4 明确要求 |
| 3 | Windows 窗口高度 | 不在本轮范围（只做 Android） | 任务限定 Android 仓库 |

## 1. 设计 Token（两个平台共用语义，本文件定义 Android 实现）

### 1.1 颜色

| token | 浅色 | 深色 | 用途 |
|---|---|---|---|
| `ink` | `#111827` | `#E5E7EB` | 回复正文、译文 |
| `sub` | `#6B7280` | `#9CA3AF` | 中文意思、分析 |
| `faint` | `#9CA3AF` | `#6B7280` | 头部小字、来源 |
| `surface` | `#FFFFFF` | `#1F2937` | 面板、卡片 |
| `canvas` | `#F5F6F8` | `#111827` | 设置页底色 |
| `card` | `#F3F4F6` | `#374151` | 非推荐回复卡底 |
| `accent` | `#4F5BD5` | `#6B76E8` | 气泡「好了」、主按钮、选中 |
| `accentSoft` | `#E4E6FA` | `#33374F` | 推荐卡浅底（≈accent 10%） |
| `danger` | `#DC2626` | `#EF4444` | 出错 |
| `warn` | `#D97706` | `#FBBF24` | 危险等级中档（保留既有语义） |
| `ok` | `#16A34A` | `#34D399` | 危险等级低档（保留既有语义） |

### 1.2 字号（sp，4 级梯度，HANDOFF §5）

| token | 值 | 用途 |
|---|---|---|
| `TEXT_BODY` | 13.5 | 回复正文 |
| `TEXT_TRANS` | 13.5（粗体） | 译文 |
| `TEXT_AUX` | 11.5 | 中文意思、分析 |
| `TEXT_META` | 10.5 | 头部小字、来源说明 |

设置页保留其既有层级（标题 16/正文 14/说明 11–12.5），但颜色一律走 token。

### 1.3 形状与间距（dp）

| token | 值 | 用途 |
|---|---|---|
| `RADIUS_CARD` | 10 | 回复卡 |
| `RADIUS_PANEL` | 14 | 面板、菜单 |
| `RADIUS_PILL` | 18+ | 胶囊/大圆角 |
| 回复卡内边距 | 9(横) × 8(纵) | HANDOFF §5 |
| 回复卡间距 | 5（topMargin） | HANDOFF §5 |
| 面板内边距 | 10 × 6–8 | 现状保持 |

### 1.4 气泡状态（HANDOFF §5 状态矩阵 + P1-5）

| 状态 | 文案 | 颜色 | alpha |
|---|---|---|---|
| IDLE | `Jev` | accent | 0.5 |
| LOADING | `···` | accent | 0.9 |
| READY | `✓`（取代旧版数字「3」） | accent | 1.0 |
| ERROR | `!` | danger | 1.0 |

## 2. 纯逻辑层（可 JVM 单测，零 Android 依赖）

新包 `com.jev.probe.core.ui`，所有值用 `String`（hex）或 `Long`（ARGB）表达，不 import
`android.*`，保证 `test` 源集用 JUnit4 直接跑。

### 2.1 `UiTokens.kt`

```kotlin
object UiTokens {
    data class Palette(
        val ink: String, val sub: String, val faint: String,
        val surface: String, val canvas: String, val card: String,
        val accent: String, val accentSoft: String,
        val danger: String, val warn: String, val ok: String,
    )
    val LIGHT: Palette
    val DARK: Palette
    fun palette(dark: Boolean): Palette = if (dark) DARK else LIGHT

    const val TEXT_BODY = 13.5f
    const val TEXT_TRANS = 13.5f
    const val TEXT_AUX = 11.5f
    const val TEXT_META = 10.5f

    const val RADIUS_CARD = 10
    const val RADIUS_PANEL = 14
}
```

### 2.2 `OverlayRules.kt`

```kotlin
object OverlayRules {
    enum class BubbleState { IDLE, LOADING, READY, ERROR }
    data class BubbleVisual(val label: String, val alpha: Float, val danger: Boolean)
    fun bubbleVisual(s: BubbleState): BubbleVisual
    // IDLE("Jev",0.5,false) LOADING("···",0.9,false) READY("✓",1f,false) ERROR("!",1f,true)

    /** P0-1 同源规则：中文对话或 gloss 与正文相同 → 不显示灰字。 */
    fun shouldShowGloss(lang: String, gloss: String, body: String): Boolean
    // lang 空白或 "中文" → false；gloss 空白 → false；gloss == body → false；否则 true

    /** P1-7：把原始错误映射成「一句人话 + 一个动作」。 */
    enum class ErrorAction { NONE, OPEN_SETTINGS, RETRY }
    data class ErrorView(val message: String, val action: ErrorAction, val actionLabel: String?)
    fun errorView(raw: String, hasReplyKey: Boolean): ErrorView
    // !hasReplyKey → ("还没填回复接口的 key", OPEN_SETTINGS, "去设置")
    // raw 含 401/403/key/密钥（忽略大小写）→ ("回复接口的 key 不对或已过期", OPEN_SETTINGS, "去设置")
    // raw 含 timeout/超时/Unable to resolve/网络（忽略大小写）→ ("网络连不上，稍后再试", RETRY, "重试")
    // 其他 → ("出错了，稍后再试", RETRY, "重试")
    // SPEC-ui-polish A3：原始错误信息不进 UI（logcat 只打长度/条数），fallback 不含 raw。

    /** 设置页「高级」折叠：智能回复开 → 默认折叠（P1-6）。 */
    fun foldAdvancedByDefault(bilingualMode: Boolean): Boolean = bilingualMode
}
```

### 2.3 单元测试（TDD：先写测试）

`app/src/test/java/com/jev/probe/core/ui/`：
- `UiTokensTest`：light/dark palette 每个字段都是合法 `#RRGGBB`；`palette(true)==DARK`。
- `OverlayRulesTest`：bubbleVisual 四态；shouldShowGloss 全分支；errorView 全分支；
  foldAdvancedByDefault。

`app/build.gradle.kts` 加 `testImplementation("junit:junit:4.13.2")`。
验证命令：`./gradlew.bat testDebugUnitTest --console=plain`

## 3. OverlayController.kt 改造（HANDOFF §6 Android 1/2/3 + P1-5/P1-7/P2-6）

1. **token 化**：文件内所有 `Color.parseColor("#…")` 与 hex 字面量消灭，改读
   `UiTokens.palette(isDark())`；`isDark()` 读 `resources.configuration.uiMode`。
   气泡 58/122/254 蓝、`#16A34A` 绿等全部废弃。面板底色 `panelBg()` 用 palette.surface
   叠用户透明度（alpha 下限 150 不变）。
2. **气泡四态**按 §1.4 表实现（READY 用 `✓`）。
3. **生成中骨架屏**（P1-7）：`showLoading` 的面板内容从「生成中…」一行字改为
   3 张骨架卡：圆角 10、高 44dp、底色 palette.card，透明度 0.6 的静态灰条即可
   （不做闪烁动画，保持安静）。双语路径与旧 Jev 路径都走骨架。
4. **出错态**（P1-7）：`showError(msg)` 改走 `OverlayRules.errorView(msg, hasReplyKey)`，
   面板 = 一行 danger 色人话 + hint 原文（可选）+ 一个动作按钮：
   - OPEN_SETTINGS → 按钮文案「去设置」，点击 `openSettings()`；
   - RETRY → 「重试」，点击 `onManualAnalyze?.invoke()`；
   按钮用 `bigButton` 样式（accent 底白字）。
5. **展开/收起动效**（P2-6）：140ms，alpha 0→1 + translationY dp(12)→0（收起反向），
   `AccelerateDecelerateInterpolator` 或不加 interpolator 的线性均可；不要弹簧。
   收起时动画结束再 `visibility = GONE` 并复位窗口位置；动画期间重复点气泡要幂等
   （再次 toggle 直接完成最终态）。窗口 flags 不动（`FLAG_NOT_FOCUSABLE` 保持）。
6. **填入反馈**：`replyCard` 点击填入后 toast「已填入，确认后自己发送」（现状收起不变）。
7. **头部小字**：保持「对方 · 语言　点一条填入」，颜色用 palette.faint。
8. `showBilingual` 中 gloss 行加 `OverlayRules.shouldShowGloss` 守卫（与 Windows P0-1 同规则）。
9. 不改：`attachBubbleTouch` 的边距逻辑、菜单项、`resetForNewConversation` 语义、
   `setHiddenForShot`、danger badge 语义（颜色走 token 的 warn/ok/danger）。

## 4. SettingsActivity.kt 改造（HANDOFF §6 Android 4 + P1-6）

1. **token 化 + 深色**：`accent/ink/sub/pillOff` 等私有字段改读 `UiTokens.palette(...)`；
   `window.decorView` 底色用 palette.canvas；卡片底 palette.surface；EditText 底 palette.card。
   （Activity 不重建时以打开瞬间的 uiMode 为准，可接受。）
2. **顺序调整**：「接口」区 = 回复接口卡 → 「高级」折叠容器（判断接口卡 + 视觉接口卡）。
   即回复接口升到第一张。
3. **「高级」折叠**：容器默认状态 = `OverlayRules.foldAdvancedByDefault(prefs.bilingualMode)`
   （开=折叠）；折叠条一行：「高级：判断接口 · 识图接口」+ 右侧「展开/收起」；
   「智能回复」开关被切换时同步折叠/展开（开→折叠，关→展开），仅影响显示，不改 prefs。
4. **「关于我」上第一屏**：独立成卡，置于标题「设置」之后、「接口」区之前，是设置页
   第一张业务卡（SPEC-ui-polish A5）；「分析」卡从关系描述开始，保存逻辑不变。
5. 不改：所有保存逻辑、provider 解析/URL 展开逻辑、测试按钮逻辑、pasteBtn、
   自检入口、隐私/开源链接。只是视觉与排布。

## 5. 验收（HANDOFF §7，Android 部分）

- `./gradlew.bat assembleDebug` 通过；`testDebugUnitTest` 通过。
- `grep -rnE '#[0-9A-Fa-f]{6}' app/src/main/java` 只允许命中 `UiTokens.kt`。
- 行为不变：只填不发、只填外语；面板从不自己弹出；`FLAG_NOT_FOCUSABLE` 不变。
- 面板构成：译文 + 3 卡 + 一行分析 + 一行来源（与现状一致）。
- 装机截图（WhatsApp 闲置/生成中/好了 + 长按菜单）：**当前 adb 无设备，阻塞待手机接入**。

## 6. 分工（子 agent）

| 子任务 | 文件 | 依赖 |
|---|---|---|
| A：token + 纯逻辑 + 单测（TDD） | `core/ui/UiTokens.kt`、`core/ui/OverlayRules.kt`、`app/src/test/**`、`app/build.gradle.kts`（加 junit） | 无 |
| B：OverlayController 改造 | `overlay/OverlayController.kt` | A 的 API 签名（§2 为准） |
| C：SettingsActivity 改造 | `SettingsActivity.kt` | A 的 API 签名（§2 为准） |

B、C 文件不相交，A 完成后并行。任何子任务都不得改动 `ChatCaptureService.kt`、
`ReplyClient.kt`、`Prefs.kt`、`ChatMemory.kt`（工作区未装机改动，非本轮范围）。

---

# 第二轮：设计系统 v2（企业级 · 艺术配色 · juiciness）

> 用户指示：在第一轮达标的基础上，以大师级 UI/UX、专业平面设计思路继续打磨。
> 硬规则不变：只填不发、面板不自动弹出、FLAG_NOT_FOCUSABLE、日志不记内容。

## v2.1 色彩（新增 token）

| token | 浅色 | 深色 | 用途 |
|---|---|---|---|
| `accentDeep` | `#3F4BC0` | `#5A64DC` | 渐变末端、按压态 |
| `accentLight` | `#8B94EC` | `#9AA3F2` | 高光、次要强调 |
| `accentSoft` | `#E8EAFC`（调整） | `#2B2F4A`（调整） | 推荐卡底 |
| `surfaceElev` | `#FFFFFF` | `#232840` | 菜单、气泡菜单等抬升面 |
| `canvas` | `#F4F5F9`（调整） | `#0F1220`（调整） | 页面底色 |
| `hairline` | 黑 8% | 白 10% | 发丝分割线（运行时 alpha 值） |

气泡/主按钮渐变：`accentLight → accent` 135°（GradientDrawable.Orientation.TL_BR）。
骨架屏微光：底色 `card` 上叠加 `accentSoft` 的 alpha 呼吸（0.35↔0.65，1200ms，三张卡相位错开 150ms）。

## v2.2 动效（juiciness，克制）

| 交互 | 参数 |
|---|---|
| 气泡按下 | scale → 0.88，松开 OvershootInterpolator(tension=2.0) 回 1 |
| 回复卡按下 | scale → 0.97 + 背景加深，100ms；松开回弹 |
| 面板展开/收起 | 160ms（原 140ms 放宽），展开带 tension≈0.9 的极轻 overshoot |
| 气泡状态切换 | 背景色 200ms crossfade（ArgbEvaluator），文案同步切换 |
| toast | 改面板内胶囊 snackbar：黑 78% 底白字 12sp，底部居中，1.6s 淡出 |

新增 `UiTokens` 常量：`DUR_MICRO=100L`、`DUR_PANEL=160L`、`DUR_STATE=200L`、
`PRESS_SCALE=0.97f`、`BUBBLE_PRESS_SCALE=0.88f`。

## v2.3 平面设计规范

- 全部间距落在 4 的倍数（8pt 网格：4/8/12/16/20/24）。
- 面板圆角升 18（`RADIUS_PANEL` 调整），`clipToOutline` + elevation 8 柔和投影。
- 卡片描边统一为 hairline（替代原 `#22000000` 半透明黑，深色下换白 10%）。
- 回复正文行距 1.3 倍；头部小字 letterSpacing 0.02。
- 设置页：section 标题 12sp + letterSpacing 0.08 大写感（中文等效加宽字距）；卡片 elevation 2。

## v2.4 分工

- 我：UiTokens/OverlayRules 扩展 + 单测（TDD）
- 子任务 B2：`OverlayController.kt`（气泡渐变+投影+弹性、状态 crossfade、卡片按压反馈、骨架 shimmer、snackbar、面板 18 圆角投影）
- 子任务 C2：`SettingsActivity.kt` + `MainActivity.kt` + `KnowledgeActivity.kt`（section 排版、卡片投影、divider 发丝化、间距 8pt 网格校正、深色核验）

验收：构建 + 单测全绿；hex 仍只在 UiTokens.kt；真机截图四态 + 深色模式一组。

## v2.5 补充决策（review 采纳）

- **错误兜底文案不带原始错误信息**：原始错误只进 logcat（且只打长度，不打内容）。
  理由：接口返回的错误串可能夹带请求细节，UI 只显示人话 + 动作。§2.2 的兜底分支
  以本条目为准（`errorView` 与 `showError` 已实现一致）。

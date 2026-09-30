# Jev 聊天助手（安卓）— 全平台非侵入对话副驾

挂在任意聊天 App 旁边（微信、QQ、飞书、X、WhatsApp 已适配）的非侵入式助手：读到对方最新消息 → 悬浮窗给出分析和 3 条候选回复（bilingual 分支支持中德双语）→ 人一键填入聊天输入框。**发送永远由人手动点，程序不自动发。**

> Sprint 5 起 Jev 判断模式已删除，回复只走 `ReplyClient`。下文 Jev / OpenRouter 相关条目是历史实测记录，动手前先对照现有代码。

## 硬约束（所有人必须遵守）

1. **不 hook、不 Xposed、不改目标 App、不读其数据库**。只用系统无障碍服务与截屏。
2. **绝不自动发送消息**，绝不点任何 App 的发送按钮。填入输入框后停手。
3. **不碰钱**：不触碰转账、红包、收款码相关任何界面元素。
4. **路径全 ASCII**：Android 构建工具在 Windows 上不接受中文路径。项目只能在 `D:\dev\jev-chat-jarvis`。
5. **密钥不落盘、不进日志、不进 git**：各路模型（DeepSeek、OpenRouter 等）的 key 只存 App 内 Keystore 加密设置项（`core/KeyVault`），或从环境变量读。任何文件里都不许出现真实 key（如 `sk-or-`、`sk-` 开头的字符串）。
6. 编码一律 UTF-8。Windows 中文环境下 PowerShell 用 pwsh 7+，Python 读写文件必须显式 `encoding='utf-8'`。
7. 允许 `git commit` / `git push`，但**必须先 review 通过**（如 `/dev-review` 返回 PASS）再提交；push 只推 `origin`（fork），不推 `upstream`。

## 技术栈

- Kotlin，传统 View + XML，**不用 Compose**
- minSdk 30，compileSdk / targetSdk 35
- JDK 17 通过 `JAVA_HOME` 指定（`gradle.properties` 不写绝对路径），Android SDK 在 `C:\Users\Jack\AppData\Local\Android\sdk`（见 `local.properties`，不进 git）
- 构建：`./gradlew assembleDebug`
- 目标机：小米 14（houji / 23127PN0CC），HyperOS 3.0 / Android 16 (SDK 36)，微信 8.0.78
- 模型客户端分两路：`jev/ReplyClient`（回复）、`jev/VisionClient`（视觉，OCR 用），共用 `jev/HttpJson`；配置在 `core/Prefs`（`reply*` / `vision*` 字段）。`JudgeClient` 与判断模式已在 Sprint 5 删除，`Prefs` 里残留的 `judge*` 字段仅为老版本升级数据不崩溃。
- ML Kit `com.google.mlkit:text-recognition-chinese:16.0.1`（bundled，不是 play-services 版），`ndk.abiFilters` 只留 `arm64-v8a`。

## 关键背景（2026-09-21 实测结论，别重复踩）

- 微信 8.0.52 起对普通无障碍服务**混淆/隐藏节点**。本机实测 `uiautomator dump` 对微信任何界面只返回一个空根节点。
- 社区绕法：把无障碍服务的类名注册成系统内置的 `com.google.android.accessibility.selecttospeak.SelectToSpeakService`。**对 8.0.78 是否仍有效未验证，这就是探针 App 要回答的问题。**
- 兜底路线：无障碍服务的 `takeScreenshot()` + 本地 OCR（ML Kit），同样零 token。
- 飞书 Android（2026-09-21 实测）：消息正文自绘，无障碍树里**没有文字**（伪装服务与 `uiautomator dump` 一致），只有 `bubble_content_container` 气泡位置、`group_name` 标题、`kb_rich_text_content` 输入框；正文要走 takeScreenshot + OCR。飞书默认左对齐布局，我/对方不能按左右判。
- 手机 QQ 9.3.50（2026-09-21 实测，小米 14 / 1200×2670）：节点**不混淆**，普通 `uiautomator dump` 即可读。消息正文 `com.tencent.mobileqq:id/mjn`（TextView，text 即正文），群昵称 `id/mjq`，标题 `id/371`，输入框 `id/input`，发送按钮 `id/send_btn`（**绝不 performAction**）。时间戳与系统提示条无 id，只采 `id/mjn` 就自然排除。
- QQ 全程是 `SplashActivity`（fragment 架构），**不能按 activity 判断是否在聊天窗**，只能看树里有没有 `id/mjn` / `id/input`。头像贴各自外侧（别人 left≈width×0.13，自己 right≈width−width×0.13），判「我/对方」要比左右两边离头像列的距离，不能用中心点（长消息中心会过半屏）。
- X / Twitter 12.25.2（2026-09-21 实测，小米 14 / 1200×2670 / 中文界面）：私信页是 **Compose UI，消息节点没有 resource-id**，`android.view.View`、全宽 `[0,y][1200,y+h]`、text 为空，**全部信息在 content-desc**，格式 `发件人：正文。8:11 上午。Read。`（全角冒号分隔、`。` 粘字段、末尾可能有时间和 `Read`）。附件行 `All-In：附加的帖子。。` 内部嵌套引用帖子的 TextView，只采 View 自身的 desc、不采子节点。
- X **所有页面都是 `com.x.android.main.MainActivity`，不能按 activity 判窗**：对话页有 EditText（唯一那个，[204,2424][1152,2568]），私信列表页没有 → 靠「树里有没有可编辑节点」判断。列表页的行长得也像（全宽 View + desc），但格式是 `All-In, @all_in_2026, 正文…`，用含 `, @` 再排除一次。发送按钮输入后才出现，**绝不点**。
- 采集层按 App 分发：`capture/ChatAppAdapter.kt` 一个 App 一个适配器，`ChatCaptureService` 按前台包名查表；下游通用。
- Jev = TypeSafe 的判断模型，只回答选择题/打分/是非，不生成文字。走 OpenRouter：
  `POST https://openrouter.ai/api/alpha/decisions`，model `typesafe/jev-1.13`，
  body `{model, state, questions}`，答案在 `answers`。实测 7 题一次约 900 ms、约 1000 输入 token、0.00004 美元。
- Jev 主训练语言是英文：**题目的 instructions 和 criteria 用英文写，state 里的聊天内容保留中文原文。**
- 知识库 / 上下文数据在 `filesDir/kb` 下的 JSON 文件（`notes.json` / `contacts.json` / `logs/<contactId>.json`）；`KbStore` 单锁 + 原子写（先写 `.tmp` 再 rename）。`ContextBuilder` 只做 alwaysOn 笔记全带 + 标签/标题包含匹配（不做语义检索、不打分），**不自动建档、历史默认关闭（`contextEnabled=false`）**。
- Kotlin 字符串模板 `$x` 后面紧跟中文标点（如 `」`、`）`）会被解析成标识符的一部分，导致 `Unresolved reference` 编译错误；**一律写成 `${x}`**。D 阶段在 `KbStore.kt` / `KbSelfCheck.kt` 踩过。
- OCR 层在 `capture/ocr`：`ScreenCapture` 限频 ≥1s + 失败退避（1s→2s→4s→8s→16s→30s 封顶），错误码 1/2/3/4/6 各给一句人话；`MlKitOcr` 用 bundled 中文模型。适配器契约：`extract` 返回 `null` = 不在聊天窗，返回空消息列表 = 在聊天窗但树里没正文——只有后者才触发 OCR 兜底。
- 无障碍 XML 加了 `android:canTakeScreenshot="true"`，**改完必须把无障碍关掉再重新开启才生效**，否则 `takeScreenshot` 直接回 errorCode 2。伪装服务（`SelectToSpeakService`）在 HyperOS 上能不能截屏未验；被拒（码 1/2，重开无障碍后仍是）就另起一个不伪装的截屏专用服务，`ScreenCapture` 已按可换宿主的方式封装。

## 目录与文件锁

| 目录 | 归属 | 说明 |
|---|---|---|
| `app/`、`gradle/`、根 gradle 文件 | Android 构建方 | 安卓工程 |
| `tools/` | 主控 | 真机回归 shell 脚本与「Jev 已删除」门禁（`check_no_jev.sh`） |
| `docs/` | 主控 | 设计文档、PO 裁决（`po-handoff/`）、ADR |
| `docs/v1.3-plan.md` | 主控 | v1.3 总方案与修订，**所有 worker 必读** |
| `_reports/` | 所有人 | 每个任务的交付报告写这里 |

跨边界的问题**只报告，不改**，由主控收口。

## 报告格式

任务完成后写 `_reports/<任务名>_report.md`：根因或做法（带证据）→ 改了什么（逐条列文件）→ 验收命令的**真实输出**（贴原文，不许编）→ **自验缺口**（没验到的明说）。

## Agent skills

### Issue tracker

Issues 记在 GitHub fork（`wenhuorongbing-netizen/jev-chat-jarvis`），用 `gh` CLI，不要发到 upstream。See `docs/agents/issue-tracker.md`.

### Triage labels

使用默认五个标签：`needs-triage`、`needs-info`、`ready-for-agent`、`ready-for-human`、`wontfix`。See `docs/agents/triage-labels.md`.

### Domain docs

single-context：根目录一个 `CONTEXT.md` + `docs/adr/`。See `docs/agents/domain.md`.

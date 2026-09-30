# Project State Packet — 交接给 PO / CEO / Chief Architect

> 编写：Coordinator，2026-09-30。事实来源是两个仓库的真实 git 状态、真实测试输出和仓库内文档；无法验证的地方写明 UNVERIFIED。
> 本文不是路线图，路线由 PO 重新裁决。

## 1. 项目

**Jev 聊天助手**：挂在聊天 App 旁边的非侵入式对话副驾。读到对方最新消息 → 给出分析和 3 条候选回复（支持中德双语，外语回复附中文意思）→ 人一键**填入**输入框。**发送永远由人手动点。**

- 用户：单个个人用户（项目 Owner 自己），跨平台聊天，包含德语对话；也对外发布 APK / exe，但目前无多用户运营。
- **Windows 端**（`jev-chat-windows`，Python 3.12 + PySide6 + qfluentwidgets）：窄面板贴在微信 / QQ / WhatsApp 桌面窗口旁。采集 = 窗口级截图 + 本地 OCR（UIA 对微信无效，已实测）。
- **Android 端**（`jev-chat-jarvis`，Kotlin，传统 View，无 Compose）：悬浮气泡 + 面板。采集 = 无障碍服务读节点，读不到才用 `takeScreenshot()` + ML Kit 本地 OCR。目标机小米 14（HyperOS 3.0 / Android 16）。
- 两端**没有运行时通信**，各自独立读、独立调用大模型；共享的只是产品规则和提示词设计，**目前靠人手工在两边各写一遍**（见 §8 技术债）。
- 硬约束（两端一致，不可破坏）：不 hook / 不 Xposed / 不读别人 App 数据库；绝不自动发送、不点任何发送按钮；不碰钱（转账 / 红包 / 收款码）；key 不落盘不进日志不进 git（Android 用 Keystore 加密 `core/KeyVault`，Windows 用环境变量 / 注册表）；日志里不许出现聊天内容；截图不落盘（Windows）；路径全 ASCII（Android 构建限制）。

## 2. 仓库状态（截至本文提交前的实测）

| | Android | Windows |
|---|---|---|
| 本地路径 | `D:\dev\jev-chat-jarvis` | `D:\dev\jev-chat-windows` |
| remote（push 目标） | `origin` = `github.com/wenhuorongbing-netizen/jev-chat-jarvis`（fork，公开，Issues 已开） | `origin` = `github.com/wenhuorongbing-netizen/jev-chat-windows`（fork，公开，**Issues 已关**） |
| upstream（**永不 push**） | `github.com/jev-chat/jev-chat-jarvis`（别的组织的仓库） | `github.com/jev-chat/jev-chat-windows` |
| 分支 | `feat/whatsapp-bilingual`（main 另有） | `feat/qq-whatsapp-bilingual`（main 另有） |
| 本地 HEAD（本次 push 前） | `992a4e3` | `9ee266d` |
| origin 同名分支（本次 push 前） | `765bb70`（本地领先 1 个 commit） | `9ee266d`（已同步，0 ahead / 0 behind） |
| 工作区 | 仅 `docs/windows-port-issues-draft.md` 未跟踪（本次一并提交） | 干净；忽略项：`config.json`（含个人设置，已 gitignore，未跟踪）、`.venv/`、`__pycache__/` |
| 未完成的 merge / rebase / cherry-pick | 无 | 无 |
| 测试 | 131 个 JVM 单测通过（`ModelCapabilitiesTest` 12 + `VisionRouteTest` 8 是最新增加的）；`assembleDebug` 通过 | `pytest`：**145 passed**（offscreen Qt，耗时 312 s，其中大量时间在 `test_session_order.py` 的 setup） |
| CI | **没有**（无 `.github`） | 只有 `release.yml`：推 `v*` tag 才在 windows-latest 上 PyInstaller 打包；**没有 PR / 测试 CI** |
| 发布 | tag v1.1–v1.4；`apk/jev-assistant-v1.4-release.apk`（25 MB）**直接提交在仓库里** | tag v0.1.5…v0.1.11，main 是 v0.1.11 |
| 代码量 | main 7551 行 Kotlin，测试 13 个文件 | 7387 行 Python（含 probe/tools），`app/overlay.py` 单文件 2140 行 |

密钥 / 凭据扫描：两边待 push 的 diff 未发现 `sk-`、`sk-or-`、私钥、keystore；`local.properties` 与 Windows `config.json` 均被 gitignore。**注意**：Android 仓库跟踪了一个 25 MB 的 release APK（历史遗留，已在 git 历史中）。

## 3. Android 最近关键 commits（新→旧）

- `992a4e3` 视觉路线与回复同源，并向服务商查询模型是否支持图片（#8）
- `765bb70` / `0d1f4fb` QQ 9.3.65 与 WhatsApp、微信截屏可行性的实测样本和结论（#7 部分）
- `276e2ca` `6c4313f` `66f8f3d` `1a44e74` 关系提议：三选一建档、自定义输入与跳过、合并推荐、群聊（#3–#6，全部完成）
- `04b80c3` `c1d3122` 领域词汇表 `CONTEXT.md`、ADR 0001（图片走截屏而不是读本地文件）、图片获取调研
- `485a8ca` sprint7：首次向导、重新生成反馈、可选吸边；`801a8ea` sprint6：release 混淆 28.2→16.1 MB、崩溃兜底；`6483c12` sprint5：删除 Jev 判断模式

## 4. Windows 最近关键 commits

`9ee266d` 双语复制入口、原文展开、注释开关 → `839c5a5` 生成可取消、重试一次、单卡重抽 → `2b46ae1` 会话按最近排序、单会话静音、未读徽标 → `bf660c8` 托盘、面板热键、定时暂停采集 → `c1b9235` 连续消息不吞、接通微信图片采集 → `ec8069f` **让起草模型看到对方最新图片**

## 5. 两端功能差异（UNSYNCED，来自 git 历史和 issue 草稿，非逐文件比对）

| 能力 | Android | Windows |
|---|---|---|
| 联系人档案 / 知识库 / 上下文 | 有（`core/kb`，JSON 原子写） | **没有** |
| 关系提议（三选一建档、合并推荐、群聊） | 有（#3–#6） | **没有**（只有每会话手选关系下拉） |
| 视觉路线与回复同源 + 查询模型是否支持图片 | 有（#8） | **没有** |
| 手动识别图片、OCR 结果作回复上下文 | 未做（#9–#10 待办） | 已把最新图片喂给起草模型（`ec8069f`，图片采样细节 UNVERIFIED） |
| 会话列表按最近排序、静音、未读、托盘、热键 | 无 | 有 |
| 生成可取消、单卡重抽 | 部分（重新生成） | 有 |
| 设计 token / 主色 | `accentSoft` 已对齐 | 已统一（见 sprint5 记录，两边取值一致性 UNVERIFIED） |

Windows 端的对应 issue 草稿 W1–W6 在 `docs/windows-port-issues-draft.md`，**未发布**（Windows fork Issues 已关，需 Owner 决定开启或另选仓库）。

## 6. 领域文档（真实存在）

Android 仓库：`CLAUDE.md`（硬约束 + 2026-09-21 实测结论 + 目录锁）、`CONTEXT.md`（领域词汇）、`docs/adr/0001-images-via-screenshot-not-local-files.md`、`docs/v1.3-plan.md`（**部分过时**，视觉路线两处已标注）、`docs/sprint-plan.md`（sprint5–7 施工记录）、`docs/acceptance.md`、`docs/round3-plan.md`、`docs/ui-polish-android-spec.md`、`docs/probe_spec.md`、`docs/v1.3-morning-checklist.md`、`docs/research/*`（图片气泡采样、图片获取备选方案、本地文件可靠性）、`docs/agents/*`（issue tracker / triage 标签 / 领域文档约定）、`README.md`、`CHANGELOG.md`、`PRIVACY.md`。

Windows 仓库：`README.md`（485 行，含路线图 / 已知限制 / 更新记录）、`docs/KICKOFF.md`（早期 OCR 版接续说明，**已过时**）。没有 `AGENTS.md`、没有 CLAUDE.md（Windows 侧）、没有 ADR、没有架构文档。

仓库之外：`D:\dev\HANDOFF.md`（2026-09-26 的**UI/UX 打磨** handoff）与 `D:\dev\SPEC-*.md`（round2/4/5/6/7 与 ui-polish 的规格）。这些 **不在任何 git 仓库里**、也不反映 09-27 之后的工作（联系人、关系提议、图片识别），**不能当作当前权威 Handoff**；本文取代它，但它们仍是历史证据。

**Handoff 是否反映真实代码状态**：否。没有一份仓库内的、覆盖两端的最新 handoff；本文是首份。

## 7. Issue 跟踪（fork：`wenhuorongbing-netizen/jev-chat-jarvis`）

- #1 spec 关系提议 — 子任务 #3–#6 **代码已完成并提交**，issue 尚未关闭
- #2 spec 图片识别 — 子任务：#7 真机采样（部分完成，`ready-for-human`，仍开）、#8 视觉路线（**已实现并提交 `992a4e3`，真机验收未做**）、#9 手动识别（QQ）、#10 识别结果作上下文、#11 回复模型支持看图时直接附图、#12 WhatsApp 图片定位、#13 自动识别、#14 找不到图片的提示与手动框选、#15 微信支持
- 所有 issue 至今都没有评论 / 关闭动作，状态标签仍是 `ready-for-agent`

## 8. 已知问题与技术债（Coordinator 视角，供 PO 独立判断）

1. **两端各写一遍核心逻辑**：提示词、provider 预设、回复解析、关系语义在 Kotlin 与 Python 各有一份，已经开始漂移（§5）。我此前给过一版「共享契约 + 黄金测试向量」的建议，**尚未落地，也未经 PO 裁决**。
2. **Android 巨型文件**：`OverlayController.kt` 1328 行、`ChatCaptureService.kt` 854 行、`SettingsActivity.kt` 636 行、`KbStore.kt` 574 行、`ChatAppAdapter.kt` 566 行。Windows `app/overlay.py` 2140 行。UI 与状态、采集与分析混在一起。
3. **`Prefs`（420 行）是全局 God object**，`ModelCapabilities.shared`、`HttpJson` 都是静态单例；`ModelCapabilities.shared` 写死 `Route.REPLY`（仅影响错误文案）。
4. **设置页预设列表重复三处**；`HttpJson.get` 与 `post` 连接配置重复。
5. **文档漂移**：Android `CLAUDE.md` 仍写「微信 8.0.78 是目标机」并要探测微信；`CHANGELOG` v1.4 却写「微信停止支持」；`docs/v1.3-plan.md` 部分过时；Windows `docs/KICKOFF.md` 过时。#7 里关于微信「防截屏 / 树隐藏」的结论在小米 14 上**尚未复测**，所以 CLAUDE.md 未改。
6. **发布形态**：release APK 提交进 git（25 MB，且历史里可能有多个版本）；无 Android CI、无 Windows 测试 CI；发布靠手工。
7. **升级迁移缺口**：已经保存过 OpenRouter / qwen 视觉预设的老用户不会被迁移；非 DeepSeek 回复路线仍需第二把视觉 key。
8. Windows `pytest` 全量 312 s，`test_session_order` 的 setup 每个用例约 10 s，测试很慢。
9. 后台 shell 类、adb、HyperOS 后台冻结等平台问题（README 已知限制）依旧存在。

## 9. 已验证 / 未验证

**已验证（有命令输出或真机证据）**
- Android 131 单测全绿、`assembleDebug` 通过（本次 push 前）；Windows 145 测试全绿（本次实测）。
- 关系提议 #3–#6 在 Android 上的 JVM 测试通过；**真机验收没有集中留档，按 UNVERIFIED 处理**。
- QQ 9.3.65 自己一侧图片节点、WhatsApp 自己一侧图片节点的采样与截屏可行性：已入库为样本与文档（`docs/research/image-bubble-sampling.md`、`app/src/test/resources/samples/`）。
- DeepSeek `GET /v1/models` 无 key 时返回 401（路径存在）。

**未验证**
- #8 的真机验收：用真实 DeepSeek key 在设置页点「测试视觉」看 `deepseek-flash` 是否真的能读图。**Coordinator 不会往手机里输入 key，需 Owner 自己做。**
- `deepseek-flash` 的 `/v1/models` 是否真的返回 `input_modalities` 且包含 `image`（从未拿到过带 key 的 200 响应）。
- 小米 14 + QQ 9.3.50 图片采样；WhatsApp 对方发来的图片；QQ 9.3.65 对方图片；GIF、相册、群聊；微信 OCR 质量与其他页面。测试用的是「另一台手机」（序列号 `8HSGRKGQBQ6TUGZT`，**不是目标机小米 14**）。
- 两端 UI token 是否逐值一致。

## 10. Owner 已做的明确决策（历史）

- 关系提议：用户三选一，**永不自动建档**（见 `CONTEXT.md`「建档」「关系提议」）。
- 图片识别：**手动为主**，只有「对方最新一条是图片」且用户开启时才自动看图；识别结果只作本次回复上下文，**不保存**（ADR 0001 + `CONTEXT.md`）。图片走「截屏裁剪」，不读本地文件。
- 视觉路线与回复同源；判断「是否支持看图」向服务商查询，不写死模型名；非 DeepSeek 端点视为不支持。
- 只发到自己 / 一部手机足够测试、测试期间禁止休眠手机；push 只推 `origin`，不推 `upstream`。
- Jev 判断模式已在 sprint5 删除，回复只走 `ReplyClient`。

## 11. 尚未裁决

1. Windows 端 issue 发到哪里（开启 `jev-chat-windows` fork 的 Issues？还是发到 upstream 组织仓库——后者需 Owner 明确同意）。
2. 跨端逻辑是否抽成共享契约 / 黄金向量，还是接受各端独立演进并明确分工。
3. 微信在 Android 上到底继续做还是彻底放弃（CLAUDE.md、CHANGELOG、#15 三者互相矛盾）。
4. 是否继续做 #9–#15 图片识别这条线，还是先整理架构 / 补 CI。
5. 是否清理仓库里的 release APK，改用 GitHub Release。
6. #1、#3–#6、#8 是否可以关闭。

## 12. 曾失败或已被证伪的方法

- Windows：UIA 读微信——树只有 2 个节点，无控件（已实测证伪），所以走截图 + OCR。
- Android 微信：8.0.52+ 对普通无障碍服务隐藏节点；`uiautomator dump` 只有空根节点；伪装成 `SelectToSpeakService` 的社区绕法对 8.0.78 **未验证**（探针要回答的问题）。
- 飞书 / X：飞书正文自绘、树里无文字，必须走截图 + OCR；X 是 Compose，信息在 content-desc。
- 旧「DeepSeek 没有视觉」的假设：已被 #8 推翻，并在 v1.3-plan 标注。
- Kotlin 字符串模板 `$x` 后接中文标点会被当成标识符，一律写 `${x}`。
- Git Bash 调 adb 需要 `MSYS_NO_PATHCONV=1`。

## 13. AVAILABLE SKILLS（Coordinator 环境，`~/.claude/skills/`，用户级；全部不是本项目自带）

诚实标注：「已加载」= 本会话里已经实际调用并读过全文。其余只读过名称和一行描述，**没有学过内容**。

| Skill | 类别 | 用途 | 已加载 |
|---|---|---|---|
| `implement` | 通用工程 | 按 spec / ticket 用 TDD 实现，末尾跑一次全量测试 + `/code-review` + 提交到当前分支 | **是** |
| `code-review` | 通用工程 / 评审 | 双轴评审（规范 + 对照 issue 的 spec），并行子 agent | **是** |
| `to-spec` | planning | 把对话综合成 spec 发到 issue tracker，标 `ready-for-agent` | **是** |
| `ask-matt` | planning / 导航 | 说明各 skill 的主流程（grill → spec → tickets → implement） | **是** |
| `tdd` | testing | 红-绿-重构 | 否（`implement` 内部引用） |
| `diagnosing-bugs` | debugging | 先建立紧反馈环再诊断 | 否 |
| `improve-codebase-architecture` / `codebase-design` / `domain-modeling` | architecture / refactor | 找「加深模块」的机会、深模块词汇、维护领域词汇表和 ADR | 否 |
| `grill-with-docs` / `grill-me` / `grilling` / `wayfinder` / `to-tickets` / `triage` | planning | 访谈、决策地图、拆 ticket、issue 分诊 | 否 |
| `research` | research | 后台读一手资料并产出带引用的 Markdown | 否 |
| `handoff` / `claude-handoff` | handoff | 压缩成 handoff 文档 / 交给后台 agent | 否 |
| `pr`、`git-guardrails-claude-code`、`resolving-merge-conflicts`、`setup-pre-commit` | Git / GitHub | PR 描述、危险 git 命令拦截、冲突解决、pre-commit | 否 |
| `lean-ctx` / `graphify` | 上下文工具 | 压缩读取 / 检索；graphify 只在显式 `/graphify` 时构建知识图 | 只按 CLAUDE.md 说明使用 |

没有任何 Windows 专用或 Android 专用 skill，没有本项目自定义 skill。仓库内没有 `.claude/`。

## 14. AVAILABLE TOOLS（Coordinator）

本地 shell（Git Bash；`python`/`pwsh` 被白名单挡，Windows 测试用 `.venv/Scripts/python.exe -m pytest`）、Gradle 构建与 JVM 测试、adb（另一台手机在线；目标机小米 14 当前不在线）、`gh` CLI（fork 仓库读写；**不 push upstream**）、子 agent、内置浏览器与 Chrome 扩展、文件读写。**没有**：往手机输入 key / 密码的权限、对 upstream 的写权限、目标机小米 14。

## 15. 给 PO 的边界

- Coordinator 不会自行改路线；PO 裁决后才拆执行包。
- 需要 Owner 本人做的事：输入 API key、真机（小米 14）验收、决定 Windows issue 的去向、对外发布。

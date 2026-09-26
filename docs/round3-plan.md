# JevChat Android 第三轮开发计划（review 汇总 → 施工清单）

> 来源：五个角色（PM/用户研究、UX、UI、动效、工程/QA/安全）对第二轮成果的只读评审。
> 原始发现逐条见本文件附录引用；本文件是第三轮施工的唯一事实源。
> 主题：**删与修**——不加新功能。删双模式噪音、删 AI 味文案、修说谎的提示、修静默失效。
> 硬规则不变：只填不发、面板不自动弹出、FLAG_NOT_FOCUSABLE、日志不记聊天内容。

## 0. 五部门共识（每条都有 ≥2 个部门独立命中）

1. **「AI 味/信息多」的最大单一来源是遗留 Jev 判断模式**：危险徽章、意图+置信度、「Jev 排序」、知识库计数，而且它的 `render()` 会**自动弹面板**（违反硬规则）。默认模式是 bilingual（`Prefs.bilingualMode` 默认 true），Jev 模式整体沉入「高级」、永不自动弹。
2. **常驻操作提示该删**：「点一条填入」「长按复制」「知识库 N · 历史 N」是学一次就会的东西，不该常驻面板。
3. **有提示在说谎**：点卡先弹「已填入」再异步真填（失败时先说成功）；微信提示是死代码（先 hide 再 toast，永远不显示）。
4. **设置页保存按钮在页底 = 首次填 key 最大流失点**；且同一「自动生成」开关在菜单里即时生效、在设置页要保存才生效，语义分裂。
5. **权限静默失效**：悬浮窗被 MIUI 瞬态 false 时无重试无引导；无障碍掉线无检测。我们装机时亲踩。

## 1. P0（必修，本轮全部做完）

| # | 内容 | 证据 | 做法 |
|---|---|---|---|
| A | **ChatMemory 隐私门控** | ChatCaptureService.kt:250,405；ChatMemory.kt | `contextEnabled` 关闭时不 merge/不写风格样本；「清空知识库与历史」连带清 `filesDir/memory/`；PRIVACY.md 补披露 memory/ 目录 |
| B | **填入反馈说真话** | OverlayController.kt:781；ChatCaptureService.kt:654 | 点卡后不弹提示；由 fillInput 按真实结果弹一次（成功「已填入，确认后自己发送」/失败「已复制，长按粘贴」）；面板延迟 150ms 收起让按压回弹播完 |
| C | **微信提示死代码** | ChatCaptureService.kt:317 | 调整顺序或改面板内提示（先提示后 hide）；同时修 showNotice 自动弹面板——改为气泡红点 + 下次点开面板时展示 |
| D | **Jev 模式沉底 + 杀自动弹** | OverlayController.kt:743 render() 末尾 toggle() | render() 永不自动 toggle；Jev 模式设置入口沉入「高级」，面板结构与 bilingual 同构（译文+3 卡+一行分析，删危险徽章/意图/置信度/「Jev 排序」/#n·87%） |
| E | **设置页即改即存** | SettingsActivity.kt:477 | 控件 onChange 直写 Prefs，删「保存全部设置」按钮；provider 切换时的 URL 回填逻辑保留并即时生效 |

## 2. P1（本轮做）

**文案大扫除**（全部给出改写稿，见附件 A）：
- 面板头部：「对方 · 德语　点一条填入」→「德语」（只留语言标签，中文会话整行去掉）；maxLines=1。
- 长按菜单：196dp→236dp；「新消息自动生成：开（点关）」→「自动生成：开」；「把当前会话存为联系人」→「存为联系人」；「隐藏助手（本次）」→「隐藏」；菜单砍到 4 项（自动生成/截屏识别/设置/隐藏），「存为联系人」移到设置页。
- 设置页黑话：「智能回复（跟随对方语言，不用 Jev）」→「翻译模式：外语翻成中文，用对方语言回」；删 provider 路径说明长文；「B 阶段」「注入」等开发黑话全删。
- 主页：副标题补 WhatsApp、删「判断」措辞；删「关联上下文未开启」常驻 nag；权限卡「已开启」去重；「自启动」卡注明检测不到不用重复点。
- 删除面板 meta 行（知识库 N · 历史 N）；分析压 1 行省略。

**视觉**：
- 字阶上移取整：TEXT_BODY 14 / TEXT_TRANS 14 粗 / TEXT_AUX 12 / TEXT_META 11（UiTokens 四行常量，测试同步）。
- 头部/meta 灰字从 faint 升 sub（对比度达标）；译文限 2 行 + 「展开」；译文下加 accentSoft 左边框与回复卡分区。
- 骨架屏可见性：card 底 + accentSoft 呼吸层两段式（0.55↔0.9）。
- 深色 accentSoft `#2B2F4A`→`#353B60`（推荐卡可区分）。
- SeekBar 着色 accent；长按菜单圆角 12→18 对齐面板。

**动效**：
- 新消息到达提醒（只播一次）：alpha 0.5→1 渐变 250ms + scale 1→1.12→1 脉冲 320ms + 文案淡换。
- setContent 两段式交叉淡化：旧 80ms 淡出 → 新 180ms 淡入+上移 6dp，三卡错 40ms。
- 长按菜单长出动画 130ms（pivotY=0）；消失 100ms 淡出后 removeView。
- 气泡出现 scale 0.6→1 180ms；hide() 140ms 淡出后 removeView。
- 参数清理：面板展开去掉形同虚设的 Overshoot(0.9) 换 Decelerate；气泡按压 Overshoot 2.0→1.5。

**交互可靠性**：
- 拖动阈值 6dp → ViewConfiguration.getScaledTouchSlop()；长按触发后锁定本次拖动。
- toggle() 开头先移除已开菜单；菜单加屏内位置钳制。
- 生成中 ↻ 禁用防重；resetForNewConversation() 清 dangerDot。
- 填入 snackbar 加动作「换一条」（重新展开面板）。

**工程**：
- 悬浮窗权限：AppOps 监听 + ensureRoot 退避重试（2s/5s/15s）+ 掉权限一次性通知（深链权限页）。
- 无障碍掉线检测（KeepAliveService 心跳比对 ENABLED_ACCESSIBILITY_SERVICES）+ 通知引导；补 POST_NOTIFICATIONS 运行时请求。
- logcat 去掉会话标题（ChatCaptureService.kt:243 打 len）；JudgeClient 错误只打 status+len。
- ChatMemory load 校验 `k` 字段防 hash 碰撞串会话。
- bubbleY 恢复时夹紧屏内（OverlayController.kt:128）。
- 剪贴板标 IS_SENSITIVE（两处 copy）。
- 无障碍事件 300-500ms 去抖（maybeCapture 层 trailing debounce）。

**测试**（TDD，先写）：
- ChatMemory.merge/overlap 纯逻辑；ReplyClient.parseThree/parseBilingual 契约（有几条返回几条，不凑数）；isTransientTitle/cleanBubbleText；新增 errorView/文案回归。org.json 进 testImplementation。

## 3. P2（排进 backlog，本轮不做）

EncryptedSharedPreferences→Keystore 自封装加密（P1-6）；parseXDesc 抽测；provider 胶囊渐隐边缘；dangerDot pop；onboarding 就绪卡一键直达设置；品牌名统一（JevChat/Jev助手/Jev 聊天助手）；release shrinkResources。

## 4. 验收（第三轮）

- 构建 + 全部单测绿；hex 仍只在 UiTokens.kt。
- 真机：面板全文无一处折行；面板里没有任何常驻操作提示；点卡填入的提示与真实结果一致；关闭「记录聊天历史」后 `filesDir/memory/` 无新增；微信会话气泡变红点提示而不弹面板。
- 深色一组 + 浅色一组截图（聊天内容打码后入 handoff-assets）。
- 日志 grep 无会话标题、无聊天内容、无 key。

## 附件 A：文案改写对照表

（评审报告逐条改写稿的汇总，施工时逐字采用）

| 位置 | 现状 | 改为 |
|---|---|---|
| 面板头部（外语） | 对方 · 德语　点一条填入 | 德语 |
| 面板头部（中文） | 点一条填入 · 长按复制 | （整行隐藏） |
| 菜单 1 | 新消息自动生成：开（点关） | 自动生成：开 |
| 菜单 3 | 把当前会话存为联系人 | （移到设置页） |
| 菜单 5 | 隐藏助手（本次） | 隐藏 |
| 设置主开关 | 智能回复（跟随对方语言，不用 Jev） | 翻译模式：外语翻成中文，用对方语言回 |
| 关系描述 | 关系描述（给 Jev 判断用） | 关系（回复会参考） |
| 视觉接口卡 | 视觉接口（OCR 用，可先不填）…B 阶段 | 识图接口（截图识别用，一般不用填） |
| OCR 兜底 | 树读不到正文时用 OCR 兜底 | 读不到文字时用截图识别 |
| 主页副标题 | …已支持 QQ、X、飞书…给出判断和候选回复 | 贴在 WhatsApp / QQ / X / 飞书旁：读出对方新消息，外语翻成中文，给 3 条候选回复。只填入输入框，发送由你点 |
| 主页 nag | 关联上下文未开启，可在设置里开启 | （删除） |
| 判断测试结果 | 成功 812ms · 意图=confirm_you_care（置信 76%） | 成功 · 812ms |
| OCR 提示 | OCR 未分边，把全部消息当作对方所说 | 截图识别：分不清谁说的，都按对方处理 |
| 自启动卡 | 小米/HyperOS 必做，否则服务被冻结、读不到消息 | 小米/HyperOS 必做，否则服务被冻结。设过一次即可，这里检测不到，不用重复点 |

# JevChat Sprint 路线图（2026-09-26，第四轮完成后）

> 前置状态：Android 四轮打磨完成（687c470 → 6bbff41），62 个 JVM 单测，评审发现清零。
> 本文档规划后续 3 个 sprint 的主题、范围、验收与决策点。每个 sprint 结束必须真机验证。

## 决策点（需要拍板，影响排期）

| # | 问题 | 选项 | 建议 |
|---|---|---|---|
| D1 | Jev 判断模式去留 | A. 彻底删除（代码+设置页高级区+判读接口）/ B. 保留沉底 | **A**。五部门评审一致：它是「AI 味」最大来源，默认路径用不到；删 ≈ -1500 行 |
| D2 | Windows 侧是否同步 | A. 同 token 重做 / B. 仅修 P0-1/2/3 / C. 冻结 | B 起步，A 视 Android 稳定度 |
| D3 | 微信支持路线 | A. 继续 OCR 兜底实验 / B. 放弃（读屏被墙是平台意志） | B 建议：维护成本 vs 收益不成比例，保留开关但标注「不可用」 |
| D4 | 发布形态 | A. 继续侧载 debug / B. release 签名 + minify + keep 规则 / C. 上 Play（伪装服务类必被拒） | B；C 不可行（伪装类名），侧载为主 |
| D5 | 模型预设范围 | 砍到「DeepSeek 官方 + 自定义」两个 / 保留现状 | 砍。6 个 provider 的维护成本远高于价值 |

## Sprint 5：删（产品瘦身 + Windows 同步）

**主题：把「不需要的」从产品里拿掉，双端对齐。**

Android：
- D1 落地：删 Jev 模式（JudgeClient 调用链、render() 旧路径、设置页判断接口卡、judge provider 六选一→直接删卡、Prefs judge 字段标记 deprecated 保留读取防崩）
- D5：回复接口 provider 胶囊砍到「DeepSeek 官方 / OpenRouter / 自定义」
- 微信（实验）开关按 D3 处理
- 空态补全：不在聊天界面时长按气泡的菜单仍可用（截屏识别/设置/隐藏）
- 回归：测试全绿（删 foldAdvancedByDefault 用例后 61 个）+ 新增「Jev 模式引用为零」的 grep 门禁脚本（tools/check_no_jev.sh）

**Sprint 5 Android 施工记录（已完成）**：
- 删 `jev/JudgeClient.kt`、`jev/JevClient.kt`、`jev/JevQuestions.kt`；`core/ChatModels.kt` 删 `Analysis/Choice/Score`（`BilingualResult/RankedReply` 保留）；`HttpJson.Route.JUDGE` 删
- `ChatCaptureService.runAnalysis` 删 judge 分支，bilingual 为唯一路径；`OverlayController` 删 `showJudgment/showReplies/render/analysisLine` 与危险红点（`tintBubbleDanger/dangerColor`，`dangerDot→noticeDot` 语义单一化给 showNotice 用），`showLoading` 的 open 参数删除；`INTENT/ACTION` 映射表删除
- 设置页删「判断接口（旧模式）」整张卡与「翻译模式」开关；「高级」折叠条文案改「高级：识图 · 上下文」且默认折叠写死；回复接口 pills 砍到「DeepSeek 官方 / OpenRouter / 自定义」（D5）；微信开关改「微信（暂不可用）」（D3，功能保留）
- `Prefs`：judge 四字段 + openRouterKey 标 @Deprecated 保留读写；`bilingualMode` 恒 true（setter 忽略写入）；`hasKey()` 等价 `hasReplyKey()`；`judgeEndpoint()` 与失效 provider 预设常量删除
- `OverlayRules.foldAdvancedByDefault` 及其测试用例删除

Windows（若 D2=B）：
- `app/overlay.py` 修 P0-1（中文 gloss 重复）、P0-2（纸飞机图标换 EDIT）、P0-3（中文对话不显示译文行）
- 主色 `#18794e` → `#4F5BD5`（setThemeColor），与 Android 同 token 值

验收：Android 装机走查 bilingual 全路径；Windows 截图对照 handoff §7；grep 门禁过（`bash tools/check_no_jev.sh` 输出 PASS）。

## Sprint 6：稳（工程质量 + 发布形态）

**主题：能发出去的 release。**

- D4 落地：release minify + shrinkResources，R8 keep 规则保住 `SelectToSpeakService` 类名与 ML Kit；真机装 release 包回归
- 权限自愈补 instrumentation 验证（adb 反复 revoke/grant 的脚本化回归：tools/perm_churn.sh）
- Robolectric 引入（如需）测 Prefs.isAllowed / 设置页即改即存的存储语义
- 弱网路径：ReplyClient 超时/429/500 的 UI 状态回归（截屏 evidence）
- 耗电观测：无障碍事件去抖后的典型会话 CPU 占比记录（adb dumpsys batterystats 前后对照）
- Crash 防线：全局未捕获异常 → 写文件 + 下次启动提示（不上传）

验收：release 包装机跑通；权限搅浑脚本跑 10 轮气泡都能恢复；耗电对照有数字。

## Sprint 7：长（增长与体验上限）

**主题：让用户愿意推荐给别人。优先级按 D1-D5 拍板后重排。**

候选池（评审与 backlog 汇总，按价值排序）：
1. **首次引导流**：三步 onboarding（权限 → 填 key → 看一次演示），替代主页自助摸索——PM 评审指出的最大流失点
2. **回复质量闭环**：填入后可选「这句不准」轻反馈（本地计数调 prompt，不上传）
3. **气泡吸边可选**（设置项；默认保持现状躲 MIUI 手势）
4. **多语言 UI**（系统语言跟随；用户是中文，对方外语——UI 中文即可，低优先）
5. **知识库 UX**：从聊天里一键存「对方偏好」笔记（现在只能手动进设置）
6. **面板内历史**：同一会话最近 3 轮的回复记录（本机，关开关不写）
7. **Windows 全量 token 化**（若 D2 升级为 A）

## 度量（每个 sprint 结束看）

- 首屏无滚动可见 3 卡的比例（真机截图抽测）
- 从「来消息」到「填入」的步数（目标恒定 = 2）
- JVM 单测数与覆盖域（当前 62）
- 权限静默失效次数（目标 0，logcat 周期计数）
- APK 体积（当前 28MB，Sprint 6 目标 < 20MB）

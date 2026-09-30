# PO 第一轮裁决 JEV-PO-20260930-R1（浓缩存档）

来源：ChatGPT "6 Pro" 长期 PO 对话（"项目交接审查规划"，Project "ez的日常"），思考 26 分 22 秒。审查的远端版本：Android `4faf621`、Windows `9ee266d`。
PO 未改仓库、未重跑测试；它的源码判断由 Coordinator 抽查（见文末）。原文在 ChatGPT 对话里，本文是决策的浓缩，不是逐字稿。

## 总裁决
- 保留两端原生技术栈，不做跨端大重写；暂停按编号直接推进 #9–#15；不按文件行数拆分。
- 先建三条可验证边界：**回复只能填进它所属的会话；图片只能来自本次获准识别的截图；密钥只能发往被确认绑定的接口。**
- 之后靠共享契约 + 确定性向量 + 状态所有权整理两端。联系人持久化和发布链要修，但不打包成一个大整顿 Sprint。
- **只执行 S0；S1–S6 不是批量开工授权，S0 完成后由 PO 复审（Accept/Reject/Correct）再开放下一个。**

## 关键发现（PO 视角）
- F1 标题被当成身份：Windows OCR 标题失败沿用上一会话，Android `stabilizeTitle()` 同理；联系人按同名/别名自动归并。`CONTEXT.md` 里「会话由 App＋标题确定」需修订。
- F2 填入丢失来源：Android 只传文本，执行时找当前窗口第一个可编辑节点；Windows 同样没有校验候选所属会话/消息版本。
- F3 Windows 图片三种语义并存：QQ 本地文件按修改时间取图、`read_images` 默认开、连续几条对方消息里的图都带入；与 ADR 0001 和「必要看图」定义冲突。
- F4 `effectiveReplyKey()` 无条件回落 `judgeKey`（视觉那条有同源检查，这条没有）；Windows 用跨 provider 共用的环境变量 key。`ModelCapabilities` 把「不知道」和「明确不支持」都当 false。
- F5 `KbStore` 原子写失败会退回原地覆盖；部分业务方法忽略 `saveContact()` 失败仍显示成功。
- F6 巨型文件是症状，状态与职责混杂才是原因。任务边界不能再按「这个 Agent 只能改这个文件」划。
- Windows 旧 Jev 判断模式**没有删**：关闭双语后 `main.py` 仍走判断→起草→排序，须先迁移调用点再删。Windows 并非全部走 OCR，QQ/WhatsApp 有 UIA 进程。
- Android 微信：默认关闭但适配器路径仍在，「彻底停止支持」不准确。
- 密钥表述改为「不持久化明文、不进日志和 Git；允许平台保护的密文」。Windows 改用户范围 DPAPI/系统凭据，环境变量只作兼容导入。
- 发布：Windows 发布用 Python 3.11 而开发 3.12、发布不跑测试、依赖未锁；Android release 缺签名时回落 debug 签名。

## 目标架构（最小充分）
- 两个原生应用（Kotlin / Python），**共享版本化契约 + 提示词资源 + 确定性向量**，不共享运行时。契约拟放 Android 仓库 `contracts/jev/v1/`，Windows 固定引用版本与摘要，运行时不下载。
- 共享范围：回复结构、语言与填入规则、关系确认语义、图片使用政策、错误分类、provider 元数据格式、合成测试样例。UI 与平台采集不共享。
- 模块：会话与流程协调（单写入者）/ 回复与上下文规则 / 联系人与本地数据 / 模型与凭据适配（不可变路由快照）/ 平台采集与填入 / 展示层 / 组装入口。
- 契约：ConversationRef、CapturedSnapshot、GenerationRequest、ReplyOutcome、**FillIntent**（携带候选所属请求、预期会话与消息版本，只放正文）、**CommitResult**、CapabilityEvidence（Supported / Unsupported / Unknown / DisabledByPolicy）。
- 未知身份、未知发言方、能力查询失败、保存失败都保留为真实状态，不伪装成「上一会话」「对方」「不支持」「保存成功」。图片与描述只属于本次稳定消息版本对应的回复过程，不进历史。

## 六项未决事项的裁决
1. Windows issue：放 Owner 自己的 Windows fork，开启该 fork 的 Issues；不发 upstream。（开启 Issues 是 Owner 的账号设置，Coordinator 未动。）
2. 共享契约与黄金向量：**做**。
3. Android 微信：产品状态「禁用/未验收」，不宣布永久放弃；从已支持清单和默认主线移出。
4. #9–#15：不按旧编号继续，先 S0–S4，再映射到 S5（手动图片纵切）、S6（必要看图）。
5. APK 移出 Git：**要做，但先验证替代 Release 工件**；不重写历史。
6. #1、#3–#6、#8：本轮都**不关闭**。代码完成度、验收完成度、需求是否仍有效要分开记录。

## Sprint 序列（顺序有依赖）
- **S0 可信动作边界与基线**（现在执行）
- S1 可复现测试、共享契约、发布基线（两端测试 CI、`contracts/jev/v1/`、Windows 环境隔离与慢测诊断、依赖锁定、签名门禁）
- S2 会话与异步流程单一所有权（代次/版本/不可变请求/取消有界）
- S3 联系人、配置与秘密的可靠持久化（提交结果说真话、DPAPI、合并需确认）
- S4 模型路由、能力三态、回复契约收口（含退役 Windows 旧判断模式）
- S5 手动图片识别最小纵向闭环（WhatsApp 单聊 → QQ；Windows 对齐当次上下文语义）
- S6 必要看图、平台资格、稳定交付（Android 微信仅做有界公开 API 探测，受保护即记为阻塞，不做规避）

## S0 = JEV-S0-TRUST-BOUNDARY

前置：先核对分支/HEAD/工作区/tracking，基于两个 feature 分支；不 `reset --hard`、不清理覆盖。测试必须用隔离配置和假凭据（部分 Windows 测试创建 Overlay 后才替换配置路径，不足以证明隔离）。不需要真实 key、不需要小米 14。

- **S0-A 基线**：核对 Git；隔离测试配置/凭据/网络副作用；记录构建与测试结果（若不是 131/145 要解释，不改测试凑数）；写下三条边界。
- **S0-B 目标绑定填入**：候选携带原会话与版本；执行前及重试前核验；不再用「当前第一个可编辑节点」作唯一依据；无法证明安全则退回复制。
- **S0-C 图片来源与触发止血**：移除 QQ 本地文件读取运行路径；停止未经明确授权的自动图片行为；不满足「对方最新一条」的图片不进请求；文本流程保留。
- **S0-D 凭据目标约束**：Android 旧 judge key 无条件回落要修；Windows provider/base 变化后不盲目复用来源不明的 key；假传输验证。
- 不做：联系人存储重构、改所有 provider、DPAPI 迁移、全面拆 OverlayController/overlay.py。若基线暴露正在发生的数据损坏，单独升级回报，不默默扩大。

必须具名的失败用例：切会话后点旧候选；点击后填入前切窗口；同 App 非聊天/支付页存在可编辑节点；中文解释与外语正文同时存在只填正文；QQ 图片目录里有时间刚好匹配的文件不被打开；未开启必要看图或最新一条不是对方图片时不自动附图；旧判断 key 与回复接口不同源不借用且错误不含 key；provider/base 改变但无凭据绑定时不发旧凭据；假 HTTP 错误正文里的合成聊天/密钥标记不进日志或工件。

S0 退出：三类边界有测试；文本路径回归通过；无新增自动发送/自动建档/本地图片读取；降级被记录；Git 与远端可核对。旧测试若把已被禁止的行为当正确，必须在 DEVIATIONS 列出旧期望、新裁决、替代用例，不能静默改。

回报格式：REQUEST/SPRINT → DECISION IMPLEMENTED → CHANGED → NOT CHANGED → VALIDATION → RUNTIME EVIDENCE → GIT → DEVIATIONS → KNOWN REMAINING RISKS → QUESTION FOR PO。

## Coordinator 对 PO 源码判断的抽查（2026-09-30）
- 确认：Android `Prefs.effectiveReplyKey() = replyKey.ifBlank { judgeKey }`，无同源检查。
- 确认：Windows `app/images.py::qq_file_since` 用 `glob` + `getmtime` 从 QQ 本地目录取图；`settings.read_images()` 默认 `True`。
- 确认：Android `ChatCaptureService.fillInput(text: String)` 只收字符串；`adapterFor()` 里微信在 `wechatEnabled` 为真时仍返回适配器。
- 未逐条验证：Windows 标题沿用相似度归并、旧判断链、`KbStore` 回落覆盖等，开工时按执行包核对。

# Windows 端 issue 草稿（未发布）

对照 `D:\dev\jev-chat-windows`（Python，分支 `feat/qq-whatsapp-bilingual`）的现状写的，不是照抄安卓 issue。
Windows 端已有：`app/ocr.py`、`app/images.py`（窗口截图 + QQ 原图）、`app/image_bubbles.py`（numpy 颜色找图片泡泡）、
`core/draft.py` 里带图请求与 `_with_image_fallback`、`app/settings.py` 里按会话记关系（`chat_rel`）。
没找到：联系人/笔记档案（KB）。目标仓库未定（fork 关了 issues，upstream 是 `jev-chat` 组织的）。

## W1 联系人档案（关系提议的前置）

Windows 没有持久的联系人/笔记存储，只有 `settings` 里每个会话一个关系字符串。关系提议需要「联系人 + 别名 + 会话标题归一化」。
- 范围：联系人表、别名、标题归一化（去成员数、全半角括号、零宽字符）、群/个人判断。
- 验收：跑通共享测试向量 `spec/vectors/title.json`（见 W6）。

## W2 关系提议（对应安卓 #1、#3–#6）

依赖 W1。
- 模型提议关系 → 用户确认才写入；提议前先推荐「合并到已有联系人」；支持自定义输入；可按会话跳过；群聊用群措辞。
- Windows 落点：`core/engine.py` 出提议、`app/overlay.py` 出确认卡、`app/settings.py` 存。
- 验收：跑通 `spec/vectors/relation_proposal.json` 与 `contact_match.json`。

## W3 视觉路线与回复同源，并查询模型是否支持图片（对应 #8）

Windows 已有 `image=` 参数和 `_with_image_fallback`（发图失败回退纯文本），缺的是「先问模型支不支持图片」而不是失败后才退。
- 范围：`core/providers.py` 加 supports_image 查询与缓存；视觉调用走回复同一家。
- 验收：不支持图片的模型不发图、不报错，界面有一句人话说明。

## W4 手动识别图片，并把识别结果作为上下文（对应 #9、#10、#11）

Windows 已有 `ocr.py` 和 `images.py`。
- 范围：手动触发识别；识别文本以固定格式进入回复请求；支持看图的模型直接附图（W3 的结果决定走哪条）。
- 验收：识别文本作为「聊天记录」而非指令进入提示词（沿用 `draft.py` 现有的防注入措辞）。

## W5 图片定位交叉核对（对应 #12–#15，Windows 侧只做核对）

Windows 用颜色找图，不依赖无障碍节点，安卓侧 QQ/WhatsApp 的 id 结论不能直接搬。可搬的是「哪些不算图片」：
- 表情包（QQ `表情[动画表情]`）不当图片；图片下面带文字说明的气泡；自己发的图（右侧头像）要排除。
- 范围：用 `tests/test_image_bubbles.py` 补这几类样本；找不到图时给提示与手动框选。
- 微信：安卓侧在另一台手机上发现树可读且截屏可用，与 CLAUDE.md 旧结论冲突，**尚未在目标机确认**，Windows 侧不引用这条。

## W6 共享测试向量（跨端）

把安卓已有的纯逻辑测试的输入/期望导出成 JSON，放独立目录，各端各写一个读取器：
`title.json`（标题归一化、群判断）、`contact_match.json`、`relation_proposal.json`。
先在安卓端导出，Windows 端读同一份。

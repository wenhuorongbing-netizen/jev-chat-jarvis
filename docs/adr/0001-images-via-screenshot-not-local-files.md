# 图片一律走截图，不读别的 App 的本地文件

对方发来的图片，统一用无障碍截图在内存里裁出图片气泡，交给视觉模型，用完即丢；不去读微信、QQ、WhatsApp 的本地图片目录。原因：在小米 14（Android 16 / HyperOS 3）上实测，微信的收图缓存在私有目录 `/data/data` 里，QQ 的 `Android/data/.../chatpic` 8 月以后没有新文件，都无法不 root 读取；只有 WhatsApp 的 `Android/media` 能读，且要求用户先开启自动下载。为一个 App 单独放宽"不碰其他 App 数据"的约束，得不偿失。

调研依据：`docs/research/chat-image-local-files-reliability.md`、`docs/research/chat-image-acquisition-alternatives.md`。

## Considered Options

- 读本地图片目录（WhatsApp 可行，微信、QQ 不可行）：覆盖不全，还需放宽 CLAUDE.md 硬约束 #1。
- "分享到 Jev"接收器：能拿到原图，但各 App 是否支持未验证，需要用户逐张操作。暂缓，需要原图时再做。
- 读取电脑版微信的加密图片：密钥要从进程内存读取，违反"不读内存"的约束。

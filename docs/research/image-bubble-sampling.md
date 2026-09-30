# Image bubble sampling: QQ, WhatsApp, WeChat (issue #7, partial)

Date: 2026-09-30. Sampled over adb (`uiautomator dump`). The only messages sent were one neutral image (a screenshot of the Android settings page) to the owner's own accounts: WhatsApp "message yourself" and QQ "我的电脑". Nothing was sent to any other person or group.

## Devices

| | Phone A (first session) | Phone B (second session) | Target in CLAUDE.md |
|---|---|---|---|
| Model | 2201122G, SDK 35 | 25060RK16C, SDK 35, 1280x2772 | 小米 14, SDK 36 |
| QQ | 9.3.10 | 9.3.65 | 9.3.50 |
| WeChat | 8.0.72, logged out | 8.0.78, logged in | 8.0.78 |
| WhatsApp | installed | installed | — |

QQ resource ids are obfuscated and change between versions. Ids below are labelled with the version they were seen on. Neither phone is the 小米 14, and none has QQ 9.3.50.

Samples (sanitised: every `text` blanked; every `content-desc` blanked except fixed UI labels such as `图片`, `我的资料卡`, `放大照片`, `已读`/`已看`, `聊天设置`, `相册`; file names, timestamps and counts were blanked too; ids, classes, bounds kept) are in `app/src/test/resources/samples/`. Because the file name of the shared image and the timestamps are blanked, a test cannot rely on them.

## QQ

### Received picture, group chat — QQ 9.3.10, `qq_group_images.xml`

- **Image bubble:** `ImageView`, `id/mkb`, `content-desc="图片"`, `clickable=true`, inside the body container `LinearLayout id/oy3`, inside a message row `ViewGroup id/root` (full width).
- **Bounds:** the ImageView is the picture itself. Seen: `[162,550][456,1176]` and `[162,1289][621,1915]` on a 1080-wide screen.
- **Other side:** avatar `id/vyw` > `id/mh2`, 108 px square (full rows), x=32..140. The body container `oy3` starts at x=140 (the avatar's right edge) and the picture sits 22 px inside it, so it starts at x=162. Use the avatar column or `oy3` for the left edge, not the picture. The first row's avatar is clipped by the top of the list (`[32,237][140,253]`, 16 px tall). Group rows also have a nickname row `id/w_r` > `id/mji`.
- Timestamps are `id/f24` (about 80 px wide, centred at x≈540). The one at `[500,1937][580,1983]` is in its own `root` row. **The one at `[500,361][580,459]` is not:** it lies inside the picture row `root [0,361][1080,1198]`, overlapping its top edge. So "the row contains no `mkb`" does not separate timestamp rows from picture rows; check for the `mkb` node itself (or the class `ImageView` with desc `图片`) and ignore `f24`.

### Own side — QQ 9.3.65, `qq_self_file_image.xml` (chat "我的电脑")

- **Own avatar:** `id/wdj` > `id/mh_` > `ImageView id/mhe`, `content-desc="我的资料卡"`, 128 px square at x=1114..1242 (right edge of a 1280 screen). The other side's avatar is on the left, so a row belongs to me when its avatar node is on the right or is `我的资料卡`.
- **Text:** `TextView id/mjn` (same id as the 9.3.50 note), body container `LinearLayout id/p2r` ends at x=1114, the avatar's left edge.
- **Image sent via the system share sheet arrives as a file-with-preview**, not as a picture message: `ImageView id/mi2` (`NAF=true`, `content-desc=""`), and a sibling `LinearLayout id/mi5` whose `content-desc` is `<file name><size>`. It is right-aligned: `mi2` `[750,1389][1092,2131]`, body container `p2r` `[728,1367][1114,2153]`. This is **not** the same node as the plain picture bubble on 9.3.10 (`mkb`, `content-desc="图片"`).
- Regular files use `id/wdt` with `id/kbs` (name), `id/k73` (size), `ImageView id/ufs` (`content-desc="file icon"`).

### Not verified for QQ

- A plain picture message sent by me (`mkb` or `mi2`-style?) and on the right side. The sample above is a file bubble.
- Whether `mkb` (9.3.10) still exists on 9.3.65 and 9.3.50.
- Stickers, GIFs, forwarded pictures.
- Anything on the 小米 14 with QQ 9.3.50.

## WhatsApp — sent picture in a self chat, `whatsapp_self_image.xml`

WhatsApp ids are not obfuscated (`com.whatsapp:id/...`).

- **Row:** `conversation_row_image` `ViewGroup`, full width `[0,1049][1280,2382]`.
- **Bubble:** `main_layout` `[393,1212][1225,2366]` > `media_container_wrapper` > `media_container` (`clickable=true`) > `ImageView id/image`, `content-desc="放大照片"`, bounds `[406,1225][1212,2353]` (about 13 px inside the bubble on each side).
- **Sent by me:** the bubble's right edge is at x=1225 on a 1280 screen (about 55 px from the right edge), and there is `ImageView id/status` (`content-desc` `已读` or `已看`, i.e. delivery ticks) inside `text_and_date`. A sent text row looks the same on the right: `main_layout` right edge 1225, plus `status`.
- Date divider `conversation_row_date_divider` (on this phone the text was `今天`, blanked in the sample) is a `TextView` whose bounds lie inside the image row's range, so it does not appear as a separate row.
- Text rows use `conversation_row_text` > `main_layout` > `conversation_text_row` > `message_text`.
- Input `entry`, send `send_container` (never act on it).

### Not verified for WhatsApp

- **A received picture (other side).** A self chat cannot produce one. Expected: bubble hugging the left edge and no `status` node, but not seen.
- Group chats (sender name row), captions on images, albums of several pictures, stickers, video thumbnails.
- Language dependence: the `content-desc` strings are the Chinese UI strings on this phone (`放大照片`, `已读`, `已看`). Match on ids, not on these strings.

## WeChat 8.0.78 on phone B — accessibility screenshot works, and the tree is readable

Setup: phone B (25060RK16C, SDK 35), WeChat 8.0.78 logged in, Jev accessibility service on (the disguised `SelectToSpeakService`, `capabilities=129`, which includes takeScreenshot). The Jev "微信" switch (`wechatEnabled`) is off by default and the service skips WeChat completely while it is off. It was switched on only for the tests and switched off again afterwards. Auto-analyse stayed off; nothing was OCR'd or sent to a model, and no chat text was read from the log. The tests used a normal one-to-one chat that was already open.

### Tree read (unmodified repo build)

`JEVASSIST`: `snapshot[com.tencent.mm] title.len=2 n=7 me:15 | me:5 | other:2 | me:1 | me:1 | me:62`. The tree gave 7 message bubbles with text length and a me/other split, and in a later run the title too (`title.len=0` in an earlier run, `2` in this one). This contradicts the CLAUDE.md note that WeChat returns only an empty root node. On this phone, with the disguised service, it does not.

### Screenshot (temporary probe build)

The app blocks a manual screenshot in WeChat (`ocrCaptureManual` returns early), and the automatic OCR fallback only fires when the tree has no text, which was not the case. So a throw-away probe (not committed, reverted with `git checkout`) took **one** `ScreenCapture.capture` from the bubble menu's "截屏识别" in the same chat, logged the outcome, and did no OCR and no upload:

```
wechat-probe: ok 1280x2772 distinctColors=61 origin=0,0
```

- **`takeScreenshot` succeeded** (no error code) and returned the full 1280x2772 display.
- **The picture is not blank:** 61 distinct colours in a 40x40 sample grid, so the window is not a FLAG_SECURE black frame. The path taken is `takeScreenshotOfWindow`/`takeScreenshot` exactly as `ScreenCapture` does it (`origin=0,0`).
- Consistent with the window dump: `dumpsys window` shows `LauncherUI` without a `SECURE` flag, and a plain `adb screencap` also returned the real chat.

So the "微信禁止截屏" statement in the settings screen text does not hold for this phone and this WeChat version.

### Not verified for WeChat

- **Evidence is not reproducible from the repo.** The `wechat-probe` log line, the `dumpsys window` output and the `JEVASSIST` snapshot line above are quoted from the test session; the probe was reverted and no WeChat sample is checked in. "Not a secure window" rests on a colour count (61 distinct colours) plus an unflagged `dumpsys` entry, not on an error code from the target device.
- **Scope of the claim:** "the 微信禁止截屏 statement does not hold" is for phone B / SDK 35 only. It contradicts a CLAUDE.md finding recorded on the target device, so CLAUDE.md should not be changed until the 小米 14 is retested.

- The picture content was not read (no OCR run); only "not blank" was checked. Whether OCR quality on WeChat bubbles is usable is untested.
- Only one chat page, one shot. Other WeChat pages (moments, mini-programs, payment pages) may still be secure. Nothing was tested near money screens (hard rule 3).
- The 小米 14 / HyperOS 3.0 (SDK 36). This is phone B on SDK 35, so CLAUDE.md's original finding may still be right on the target device, or was made with a non-disguised service.
- Whether the tree stays readable over time or after a WeChat update; and image bubbles in WeChat (no picture message was sampled).
- The previously installed Jev build on phone B differed from the repo; the tests used the repo build (plus the probe for the screenshot).

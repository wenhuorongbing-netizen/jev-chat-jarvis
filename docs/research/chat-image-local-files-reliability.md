# Can a no-root app reliably read received chat images from local files? (WeChat 8.0.x, QQ 9.3.x, Android 16 / HyperOS 3)

Date: 2026-09-29. Research only. Nothing on the phone was touched; the phone facts come from the task brief. PC facts were checked by listing directories on this Windows machine. `[Sn]` points to the source list. "Community" means a blog or forum post, not Tencent. "Unverified" means I could not confirm it.

## Verdict

**The claim "local image files can always be obtained reliably" does not hold on this phone.** Two of the five apps have no confirmed no-root route. WeChat has none. QQ has a possible route whose freshness is in doubt. The dependable local-file routes are on the PC, and there only QQ NT works without extra secrets.

## 1. WeChat 8.0.x on Android

- Received chat images live in app-private storage, `/data/data/com.tencent.mm/MicroMsg/<32-hex>/image2/`. Only voice2, video and Download sit on shared storage. Community write-up on 8.0.18 [S1], and the wechat-dump script pulls `image2` from `/data/data/...` with `su` [S2].
- wechat-dump lists the resource dirs `avatar, emoji, image2, sfs, video, voice2` under `/data/data/.../MicroMsg/<userid>` and says it needs a rooted phone [S2].
- Zhihu answers say the old `Tencent/MicroMsg/.../image2` is now empty and the data moved to `/data` (community, search summary only; the page returned 403 to curl) [S3].
- This matches your listing: no image2 under `Android/data` or `/sdcard/Tencent`, and `run-as` refused.
- File format: the `image2` files can be WXGF, an HEVC-based format. wechat-dump decodes WXGF with ffmpeg or an on-device decoder server [S2].
- No official Tencent doc describes the internal cache. The picture above is from open-source tools plus your measurements.
- A "viewed original but not saved" image is said to sit under `Android/data/com.tencent.mm/MicroMsg/<hash>/` (community, search summary) [S3]. Your phone shows only `image/shakeTranImg`, so treat this as **not working on 8.0.78**.
- Moments images are reported under `Android/data/com.tencent.mm/cache/<hash>/sns` (community, search summary) [S4]. That is not chat images.

## 2. Built-in "write to shared storage" options

- **WeChat.** The settings page is `我 > 设置 > 通用 > 照片、视频、文件和通话`. Honor's support page describes toggles for auto-save of photos and videos you shoot or edit in WeChat, and for auto-download [S5]. The toggle covers media you capture or edit, not chat images you receive (Honor's wording).
- Manually saved images go to `Pictures/WeiXin` (community) [S6]. That is user action per image. There is no official WeChat help page for the destination path.
- `文件传输助手` received files go to `Android/data/com.tencent.mm/MicroMsg/Download`, and images are said not to be there (community) [S6].
- `聊天记录迁移` and PC backup produce packages for another device, not plain image files (unverified in detail).
- **QQ.** Tencent's own FAQ says images received directly are saved automatically in the chat record, and the save path cannot be viewed. Deleting the chat deletes them [S7]. Long-press "保存至相册" writes to the system gallery (search summary of the FAQ and community pages). I found no setting that auto-saves received images to the gallery.
- WhatsApp: see section 6.

## 3. QQ 9.3.x on Android

- The NT database is private: `/data/user/0/com.tencent.mobileqq/databases/nt_db/nt_qq_<hash>/nt_msg.db` [S8][S9].
- Image cache: a December 2025 write-up on Android NTQQ says images sit in `Android/data/com.tencent.mobileqq/Tencent/MobileQQ/chatpic/{chatthumb,chatimg,chatraw}`. The file name is `Cache_<crc64 hex>` with no extension, and the subdirectory is the last 3 hex characters. The crc64 input is `chatimg:<MD5>` and the MD5 comes from the private database [S10]. So `chatpic` was **not legacy as of QQ around 9.1 (December 2025)**. It is shared storage and has no extension.
- Your finding that the newest `chatpic` subdirs date from June-July 2026 while QQ is used daily is consistent with the cache location having moved or gone stale in 9.3.x. Subdirectories are named by hash, so a real write should refresh some directory's mtime within days. **Unverified.**
- Weak evidence of change: a 9.3.55 fix in a root-only hook project says `PicElement` now holds local cache paths under `/data` or `/storage` (AI-authored PR, community) [S11]. It does not say which directory.
- Diagnostic to run: a file-level `find -newermt` on `Android/data/com.tencent.mobileqq`, not directory mtimes.
- Even if fresh, file names are hashes. An app can only guess "the newest file", which is ambiguous when several images arrive close together.

## 4. Routes into private storage without root

| Route | Result |
|---|---|
| `adb backup` | Dead. For apps targeting API 31+, adb backup excludes app data unless the app is debuggable [S12]. WeChat is described as not backup-capable in community posts [S13]. Unverified: the actual manifests. |
| QQ system backup | QQDecrypt says QQ data can be exported with the system backup, tested on 9.0.65 to 9.1.60, and some systems fail [S9]. That is for a PC-side database, and it is a one-off export, not live. Unverified on HyperOS 3 and 9.3.x. |
| Device-to-device transfer (`聊天记录迁移`) | Moves data between phones only. No plain files. |
| Shizuku or wireless adb | Runs with shell-user permissions [S14]. Your `adb shell` already shows shell reads `Android/data` but not `/data/data`, so it reaches `chatpic` and never `image2`. HyperOS may disable USB debugging when Shizuku starts [S15]. |
| Dual Apps / Second Space | Clone data goes to another user (`/data/user/999`, `/storage/emulated/999`), still private, and only the clone's external data is browsable (community) [S16]. Does not expose `image2`. |
| `run-as` | Needs a debuggable app. Denied on your phone. |

**No confirmed no-root route reaches WeChat's `image2`.**

## 5. PC sources (checked on this machine)

- **QQ NT PC.** Path confirmed: `C:\Users\<user>\Documents\Tencent Files\<qq>\nt_qq\nt_data\Pic\YYYY-MM\{Ori,Thumb,OriTemp,ThumbTemp}\<md5>.jpg`. Latest month is `2026-09`, and files were written on 2026-09-28. Files are plain `.jpg`. This matches `D:\dev\jev-chat-windows\app\images.py`. Reliable.
- **WeChat 4.x PC.** Path confirmed: `C:\Users\<user>\xwechat_files\<wxid_..._suffix>\msg\attach\<md5 of chat>\YYYY-MM\Img\`. Files are `.dat`, in three variants: `*_t.dat` thumbnail, `*_h.dat` HD and plain `.dat` (the September 2026 counts on this PC were 1826, 480 and 1160).
- Format: 4.x images use V2, magic `07 08 56 32`. It is AES-128-ECB plus a tail XOR [S17]. The AES key is tied to the wxid and lives in the running process's memory. It is not in the files [S17]. Older formats are XOR-only or V1 with a fixed key [S18].
- The best-known 4.x decryptor repo was taken down by DMCA on 2026-07-15 [S19]. Forks exist [S18].
- The V2 key needs a process-memory read. Check that against the project's hard constraints before choosing it. The sibling project already routes WeChat around this with window screenshots (`images.py`).

## 6. WhatsApp

- Per WhatsApp Help Center summaries, received media is saved to the gallery by default, and **Media visibility** is a per-chat setting (contact or group info > Media visibility > Default / Yes / No). The global setting is `Settings > Chats > Show media in gallery` [S20]. I read these through search summaries, because the pages are JavaScript-rendered.
- Auto-download is set in `Settings > Storage and data > Media auto-download` [S21]. Images that never download are never written.
- The Android 11+ folder is `Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images`. A `.nomedia` file stops gallery indexing [S22].
- Your empty folder (only Sent/Private, dated 2023) suggests auto-download is off or the app is not the one in use (Business `com.whatsapp.w4b`, or a clone in user 999). **Cause unverified, no facts about it on the phone.**
- Reliable route: turn on auto-download for photos on Wi-Fi and mobile, and set Media visibility to Yes. Then read `Android/media/com.whatsapp/...` through MediaStore (needs `READ_MEDIA_IMAGES`) [S22].

## 7. Bottom line

| App | Local-file route | Reliable? |
|---|---|---|
| WeChat Android | `image2` in `/data/data` | **No** (root only) |
| WeChat Android | `Pictures/WeiXin` via manual "save" | Needs user action per image |
| WeChat Android | `Android/data/.../MicroMsg` cache | **No** (empty on 8.0.78) |
| WeChat PC | `xwechat_files\...\Img\*.dat` | Files exist, but V2 needs a memory-read key: **No** without it |
| QQ Android | `Android/data/.../chatpic/chatimg` | **Unverified** (looks stale since July 2026); needs Shizuku or the user |
| QQ Android | gallery via long-press "保存至相册" | Needs user action per image |
| QQ PC | `nt_data\Pic\YYYY-MM\Ori\*.jpg` | **Yes** (plain JPEG, current) |
| WhatsApp Android | `Android/media/.../WhatsApp Images` | **Yes after the user turns on auto-download and Media visibility** |
| X | none found (`cache` empty) | **No** route found |
| Feishu | not installed | **N/A** |

Three practical points:

- For WeChat, QQ (except possibly `chatpic`) and X on this phone, screenshot or share-in stay the only paths that work without user action per image.
- For QQ, run the file-level check in section 3 before building on `chatpic`.
- For WhatsApp, fix the two settings first.

## Sources

- [S1] https://blog.greycode.top/posts/android-wechat-bak/ (community)
- [S2] https://github.com/ppwwyyxx/wechat-dump (README, `android-interact.sh`)
- [S3] https://www.zhihu.com/question/463796411 (community, via search summary; 403 on curl)
- [S4] https://blog.csdn.net/fjh1997/article/details/140376154 (community, via search summary)
- [S5] https://www.honor.com/cn/support/content/zh-cn15833006/ (vendor support page)
- [S6] https://blog.csdn.net/qiuchangyong/article/details/129204150 (community, via search summary)
- [S7] https://kf.qq.com/touch/faq/120307IVnEni140228y226VN.html?platform=14 (Tencent FAQ, first sentence read directly)
- [S8] https://cyp0633.com/post/android-qqnt-export/ (community)
- [S9] https://qqbackup.github.io/QQDecrypt/decrypt/extract/NTQQ%20(Android).html (community docs)
- [S10] https://h4ckm310n.com/?p=1144 (community, 2025-12-26)
- [S11] https://github.com/Hakunm/qqzygisk/pull/4 (community, AI-authored)
- [S12] https://developer.android.com/about/versions/12/behavior-changes-12 (ADB backup restriction)
- [S13] https://blog.csdn.net/mp624183768/article/details/80518414 (community; manifest not confirmed)
- [S14] https://github.com/RikkaApps/Shizuku (README)
- [S15] https://github.com/RikkaApps/Shizuku/issues/515 (2024 issue)
- [S16] https://xdaforums.com/t/workaround-to-access-dual-app-storage-in-default-file-manager-in-miui-eu-rom.4645908/ ; https://blog.csdn.net/SYK000/article/details/131534447 (community)
- [S17] https://github.com/kyan-du/agent-wechat/issues/119 (community, fetched directly)
- [S18] https://pkg.go.dev/github.com/xinyao27/wechat-log/pkg/util/dat2img ; https://github.com/L1en2407/wechat-decrypt ; https://github.com/CkBcDD/WeChat-Dat-Decoder
- [S19] https://github.com/github/dmca/blob/master/2026/07/2026-07-13-wechat-3.md (via the GitHub API block notice for `ylytdeng/wechat-decrypt`)
- [S20] https://faq.whatsapp.com/453914586839706/?cms_platform=android ; https://faq.whatsapp.com/581349212694973/?locale=en_US (via search summary)
- [S21] https://faq.whatsapp.com/366146522333492/?cms_platform=web (via search summary)
- [S22] https://www.stellarinfo.com/article/fixed-whatsapp-photos-not-showing-android-gallery.php (community)
- Local: `D:\dev\jev-chat-windows\app\images.py`; `C:\Users\<user>\xwechat_files\...\msg\attach`; `C:\Users\<user>\Documents\Tencent Files\<qq>\nt_qq\nt_data\Pic`.

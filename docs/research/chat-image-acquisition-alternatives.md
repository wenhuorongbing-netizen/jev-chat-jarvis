# Getting chat images on Android 16 / HyperOS 3 without root (research)

Date: 2026-09-29. Device: Xiaomi 14, HyperOS 3.0. Research only; nothing was tested on-device. `[Sn]` = source list at the end. "Unverified" = could not confirm from a primary source.

## 1. TL;DR - ranked recommendation

1. **Keep `takeScreenshotOfWindow` as the primary path and send the pixels to the vision LLM.** It is the only technique that works identically for all five apps, needs no consent dialog and does not touch the target app. Real apps use it: ScreenshotTile [S6], vamsi3/screen-translator [S8]. Crop only where the rect is trustworthy; otherwise send the whole (downscaled) window and let the LLM find the image.
2. **Add a user-initiated "Share to Jev" receiver (ACTION_SEND / SEND_MULTIPLE, `image/*`).** It gives full-resolution originals with zero permissions and matches "user chose it". overlay-translator accepts shared images the same way [S7]. Coverage per app needs an on-device check.
3. **Opportunistic extras, never load-bearing.** NotificationListener MessagingStyle thumbnails (WhatsApp only, low-res) [S3][S4]. MediaStore only for images the user saved to the gallery (WeChat: `Pictures/WeiXin`, community claim [S12]).
4. **Fallback captures** if takeScreenshot fails: `GLOBAL_ACTION_TAKE_SCREENSHOT` plus MediaStore [S5], Assist API `onHandleScreenshot` [S9], MediaProjection [S2].
5. **Do not build:** clipboard, accessibility long-press "save", MANAGE_EXTERNAL_STORAGE, SAF for `Android/data`. Shizuku is an optional power-user mode only.

Grounding in the repo: `ScreenCapture.kt` already does window and display shots, throttling and FLAG_SECURE messages. `BubbleRect(rect, side)` exists in `core/ChatModels.kt` but only the Feishu adapter fills it (`collectFeishuBubbleRects`). Nothing yet classifies a bubble as an image.

## 2. Feasibility table

Legend: Y = works as designed, P = partial or fragile, N = no, ? = unverified/needs device test.

| Technique | WeChat 8.0.x | QQ 9.x | WhatsApp | Feishu | X | Notes |
|---|---|---|---|---|---|---|
| 1 A11y takeScreenshot + crop | Y (no node rects; heuristic crop) | Y (nodes readable) | Y (not view-once) | Y (rects exist; admin screenshot block ?) | Y (content-desc, no bounds needed) | on-screen pixels only; 333 ms system limit [S1] |
| 2 MediaProjection | Y | Y | Y | Y | Y | consent each session on Android 14+ [S2] |
| 3 performAction save/copy | N (empty tree) | P ? | P ? | N (self-drawn) | P ? | menu sits next to "forward/send"; policy risk [S10] |
| 4 Share-sheet receiver | ? | ? | Y (unverified) | ? | P (tweet share is a link) | user-initiated |
| 5 Clipboard image URI | N | N | N | N | N | read needs focus/IME [S11] |
| 6 Notification listener | N ? | N ? | P | N ? | ? | low-res, only if notification visible |
| 7a MediaStore | P: `Pictures/WeiXin` after user saves [S12] | ? | Y via Android/media (known) | ? | ? | READ_MEDIA_IMAGES |
| 7b SAF / MANAGE_EXTERNAL_STORAGE | N | N | N | N | N | `Android/data` excluded [S13][S14] |
| 8 Shizuku/wireless adb | Y ? | Y ? | Y | Y ? | Y ? | re-arm each reboot; HyperOS quirks [S15] |
| 9 Global screenshot / Assist API | Y | Y | Y | Y | Y | see below |

HyperOS/Android 16 notes: sideloaded apps hit Restricted Settings for accessibility and notification access (Android 13+) [S6][S16]. Android 16 Advanced Protection Mode is opt-in; reports say a later build revokes accessibility from apps that are not declared accessibility tools [S17] (community/press, not primary). HyperOS kills background apps aggressively: set battery "No restrictions", autostart and background autostart, lock in Recents [S18].

## 3. Per-technique detail

**1. takeScreenshot / takeScreenshotOfWindow.** API 30 / 34; needs `canTakeScreenshot`. Returns HardwareBuffer -> copy to ARGB_8888 -> close. System rejects calls within 333 ms (`ACCESSIBILITY_TAKE_SCREENSHOT_REQUEST_INTERVAL_TIMES_MS`), tracked per service and per window [S1a]. FLAG_SECURE gives `ERROR_TAKE_SCREENSHOT_SECURE_WINDOW` (6, API 34) [S1]; WhatsApp view-once blocks screenshots per its help centre [S19]; QQ "flash photos" also reportedly block them (community, [S20]). No foreground-service requirement, no per-shot dialog. Limit: you capture rendered thumbnails, not originals; open the full-screen viewer for resolution. Crop: WeChat nodes are empty, so use a heuristic (large non-text region, or column/side from OCR line positions) or skip cropping. Users: ScreenshotTile [S6], vamsi3/screen-translator [S8], taotao-screen-translator (accessibility nodes plus screenshot/OCR fallback) [S21]. Play: accessibility declaration required; automation must be deterministic, not autonomous [S10].

**2. MediaProjection.** `createScreenCaptureIntent` + foreground service type `mediaProjection`. Android 14+: one `createVirtualDisplay` per token, consent per session, must register `onStop`, user can pick single-app sharing [S2][S22]. Community reports the projection dies at screen-off and needs re-grant [S23]. Users: AutoX.js `ScreenCapturer` [S24], overlay-translator (MediaProjection or Shizuku) [S7], Screen-trans [S25]. Strictly worse than #1 here except for continuous frames. FLAG_SECURE still blanks it.

**3. performAction on target UI.** Nothing to verify from a primary source per app; WeChat and Feishu expose no usable nodes (project CLAUDE.md), so this is N there. Even on QQ/X, the long-press menu places "forward/send" next to "save", so a mis-hit could send. Play prohibits autonomous accessibility actions except deterministic scripts [S10]. Not recommended; at most let the user tap Save themselves.

**4. Share receiver.** Manifest intent-filter `ACTION_SEND` / `ACTION_SEND_MULTIPLE`, `image/*`; you get a content URI with a temporary read grant, no storage permission. No Play concerns. Which of the five apps expose an image share-out is unverified; WeChat's viewer was not confirmed to have one [S26]. Also enables screenshots or gallery images shared by the user.

**5. Clipboard.** AOSP `ClipboardService.clipboardAccessAllowed` allows reads only for the default IME, the focused app, content-capture or autofill services; accessibility services are not on the list [S11]. Android 12+ shows a toast. Chat apps' image copy support is unverified. Not viable as a background path.

**6. NotificationListenerService.** Permission `BIND_NOTIFICATION_LISTENER_SERVICE`. AOSP grants listeners read access to `content://` URIs inside the notification while it lives [S3]. WhatsApp uses MessagingStyle image previews (Android Pie, beta report) [S4]; read `EXTRA_MESSAGES` `uri`/`type` (or `EXTRA_PICTURE` if BigPicture). Auto-reply tools such as AutoResponder for WA read only text [S27]. Limits: only when notified and unmuted, thumbnail size, HyperOS may hide content; WeChat/QQ/Feishu likely text-only placeholders (unverified). Play: Notification access is sensitive but permitted with disclosure.

**7. Storage routes.** MediaStore: `READ_MEDIA_IMAGES` (Android 14 adds partial access `READ_MEDIA_VISUAL_USER_SELECTED`) [S28]; Play limits READ_MEDIA_* to core-use cases and pushes the photo picker [S29]. SAF tree: cannot select `Android/data` or `Android/obb` on Android 11+ [S13]. MANAGE_EXTERNAL_STORAGE: excludes `Android/data`, so no help; Play allows it only for file managers/backup-class apps [S14]. Only images the user saved to the gallery are reachable.

**8. Shizuku / wireless debugging.** Shizuku starts a shell-uid server via on-device wireless debugging (Android 11+), must be redone after each reboot, and Xiaomi needs "USB debugging (Security settings)" enabled plus the notification-style workaround [S15]. The shell uid can reach the caches, which is why overlay-translator offers a Shizuku capture path [S7]. Not Play-friendly, adds friction, and Android 16 Advanced Protection may disable it (unverified). Keep as opt-in "advanced" only.

**9. Other.**
- `GLOBAL_ACTION_TAKE_SCREENSHOT` (API 28) writes to the gallery with system UI flash [S5]; ScreenshotTile uses it [S6].
- Assist API: become the default digital assistant; `onHandleScreenshot` returns null if the app or policy disables it [S9]. HyperOS assistant slot behaviour is unverified.
- MediaStore ContentObserver for user-taken screenshots: privacy-clean and user-initiated.
- Screen translators offer "share image into app" [S7].

## 4. Open questions for the Xiaomi 14

1. Does `takeScreenshotOfWindow` succeed on WeChat chat, image viewer and Moments? Any FLAG_SECURE surprises (Feishu enterprise tenants, QQ flash photos)?
2. Which apps show a system share sheet for a received image in the full-screen viewer, and does your app appear (and with `image/*` original resolution)?
3. WhatsApp notification with a photo: does `EXTRA_MESSAGES` contain `uri`/`type`, and can the listener open it?
4. Where do "Save image" results land for WeChat, QQ, Feishu, X, and is `owner_package_name` set? (`adb shell content query --uri content://media/external/images/media`)
5. Does HyperOS 3 drop the accessibility service after hours idle or after "clean memory"? Does 333 ms hold?
6. Can a third-party app be chosen as default assistant on HyperOS 3 and receive a screenshot?
7. Is thumbnail resolution sufficient for the vision LLM, or must the app trigger the full-screen viewer (a tap on the image is not a send)?

## 5. Fit with the hard constraints

| Technique | No hook / no modify | No DB reads | Never auto-send/click send | Local, visible/received only | Verdict |
|---|---|---|---|---|---|
| 1 takeScreenshot | fits | fits | fits | fits | use |
| 2 MediaProjection | fits | fits | fits | fits, but consent each session | fallback |
| 3 performAction | fits | fits | risk: menu contains send/forward | fits | avoid |
| 4 Share receiver | fits | fits | fits | user-chosen | use |
| 5 Clipboard | fits | fits | fits | not feasible | skip |
| 6 Notifications | fits | fits | fits (reading only; reply actions must stay unused) | fits, received only | optional |
| 7 MediaStore/SAF/MES | fits | fits | fits | user-saved images only | optional |
| 8 Shizuku | conflicts in spirit (reaches app-private caches) | not DBs, but same dirs | fits | fits | opt-in only |
| 9 Global shot / Assist | fits | fits | fits | fits | fallback |

## Sources

- [S1] AccessibilityService reference (errors 1-6, takeScreenshot/OfWindow): https://developer.android.com/reference/android/accessibilityservice/AccessibilityService
- [S1a] AOSP `AbstractAccessibilityServiceConnection.java`, `AccessibilityService.java` (333 ms constant): https://github.com/aosp-mirror/platform_frameworks_base/blob/main/services/accessibility/java/com/android/server/accessibility/AbstractAccessibilityServiceConnection.java , https://github.com/aosp-mirror/platform_frameworks_base/blob/main/core/java/android/accessibilityservice/AccessibilityService.java
- [S2] MediaProjection guide (Android 14 single-use, onStop): https://developer.android.com/media/grow/media-projection
- [S3] AOSP `NotificationManagerService.java` (`updateUriPermissions` for listeners): https://github.com/aosp-mirror/platform_frameworks_base/blob/main/services/core/java/com/android/server/notification/NotificationManagerService.java
- [S4] WhatsApp MessagingStyle image previews (news report, secondary): https://www.newsbytesapp.com/news/science/whatsapp-bringing-new-feature-for-android-pie-devices/story
- [S5] GLOBAL_ACTION_TAKE_SCREENSHOT: same page as S1
- [S6] ScreenshotTile README (a11y, MediaProjection, restricted settings): https://github.com/cvzi/ScreenshotTile
- [S7] overlay-translator README (MediaProjection/Shizuku, share-in): https://github.com/ciddwd/overlay-translator
- [S8] vamsi3/screen-translator README: https://github.com/vamsi3/screen-translator
- [S9] VoiceInteractionSession.onHandleScreenshot: https://developer.android.com/reference/android/service/voice/VoiceInteractionSession
- [S10] Google Play AccessibilityService API policy: https://support.google.com/googleplay/android-developer/answer/10964491
- [S11] AOSP `ClipboardService.java`: https://github.com/aosp-mirror/platform_frameworks_base/blob/main/services/core/java/com/android/server/clipboard/ClipboardService.java
- [S12] WeChat `Pictures/WeiXin` (community/vendor posts, not Tencent): https://wap.zol.com.cn/ask/x_34538732.html , https://blog.csdn.net/qiuchangyong/article/details/129204150
- [S13] SAF restrictions: https://developer.android.com/training/data-storage/shared/documents-files
- [S14] Manage all files: https://developer.android.com/training/data-storage/manage-all-files
- [S15] Shizuku setup/FAQ (Xiaomi notes): https://shizuku.rikka.app/guide/setup/ ; https://github.com/RikkaApps/Shizuku
- [S16] Restricted settings for sideloaded apps (secondary): https://droidwin.com/android-13-restricted-settings-for-sideloaded-apps-how-to-bypass/
- [S17] Advanced Protection vs AccessibilityService (press): https://www.androidauthority.com/android-advanced-protection-mode-accessibility-apk-teardown-3640742/
- [S18] HyperOS background settings (community): https://docs.sportstracklive.com/android-battery-saving/xiaomi
- [S19] WhatsApp view-once screenshot block (Help Center; page is JS-rendered, confirmed via search summary only): https://faq.whatsapp.com/1077018839582332
- [S20] QQ flash photo screenshot (community, old): https://www.zhihu.com/question/53020746/answers/updated
- [S21] taotao-screen-translator: https://github.com/LinYiXin123/taotao-screen-translator--APP
- [S22] Android 14 behavior changes: https://developer.android.com/about/versions/14/behavior-changes-14
- [S23] ScreenTrans README (screen-off revokes recording permission, community): https://github.com/longipinnatus/ScreenTrans
- [S24] AutoX.js `ScreenCapturer.kt`: https://github.com/autox-community/AutoX/blob/master/autojs/src/main/java/com/stardust/autojs/core/image/capture/ScreenCapturer.kt
- [S25] Screen-trans (MediaProjection + ImageReader): https://github.com/Yellow4Submarine7/screen-trans
- [S26] WeChat share-out not confirmed (search only): https://developers.weixin.qq.com/doc/oplatform/Mobile_App/Share_and_Favorites/Android.html
- [S27] AutoResponder for WA (notification-based): https://play.google.com/store/apps/details?id=tkstudio.autoresponderforwa
- [S28] Partial photo access (Android 14): https://developer.android.com/about/versions/14/changes/partial-photo-video-access
- [S29] Play Photo and Video Permissions policy: https://support.google.com/googleplay/android-developer/answer/14115180

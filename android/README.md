# ConnectDesk Android Client

ConnectDesk ka Android client — parental-monitoring setup ke liye: **bacche ke phone par
visible app**, aapke dashboard se device status, battery/storage aur (consent ke saath)
notification sync. App icon hamesha launcher mein dikhta hai, connected rehte hue
persistent notification chalti hai, aur baccha **Disconnect** button se kabhi bhi
connection tod sakta hai.

## Safety design (non-negotiable)

- Visible app icon — launcher se hata nahi sakte, hide nahi hota
- Persistent "ConnectDesk connected" notification while connected
- Notification content **phone par hi mask** hota hai: OTPs, passwords, banking keywords
  wali notifications sync hi nahi hoti
- Camera, mic, contacts, location, SMS — **koi access nahi**
- Baccha app uninstall kar sakta hai (Google Play Protect ise normal app ki tarah treat karta hai)
- Dashboard se revoke karne par app turant dead ho jata hai

## Build

**GitHub Actions (recommended — computer par Android Studio nahi chahiye):**
1. Ye repo GitHub par push karo
2. Actions tab → "Build Android APK" → run
3. Artifact `connectdesk-debug-apk` download karo → `app-debug.apk`

**Local:**
```bash
cd android
gradle assembleDebug
# APK: android/app/build/outputs/apk/debug/app-debug.apk
```

> Pehle `android/app/src/main/java/com/connectdesk/app/ApiClient.kt` mein
> `BASE_URL` ko apne deployed dashboard ke URL par set karo (e.g.
> `https://your-app.freebuff.app`). Release build ke liye signed keystore banao.

## Install (bacche ke phone par)

1. APK copy karo (Drive/USB) → tap karo → "Install unknown apps" allow
2. App kholo → consent text padho → dashboard se **pairing code** daalo
3. Dashboard par **Devices** → pending device **Approve** karo
4. App mein "Connected" dikh jayega + status bar notification aa jayegi

## Notification sync setup (dono taraf consent)

1. Dashboard: device card par **Notifications** toggle ON
2. Phone: "Grant notification access" → Android Settings mein ConnectDesk enable
3. Phone: "Sync notifications" switch ON

Ab dashboard ki Notifications page par us phone ki notifications (masked) dikhengi —
sirf jab aapne history storage ON ki ho (privacy default OFF hai).

## What's working / what's next

- ✅ Pairing (code), heartbeat + battery/storage status, revoke handling, notification
  sync with on-device masking, persistent indicator
- ⏳ Phase 2: file browser integration (MediaStore), screen share (MediaProjection),
  QR scanner in-app (abhi code type karna hota hai)

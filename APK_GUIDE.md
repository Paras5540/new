# APK banane ke liye — 5 minute ka kaam

> Naya APK **screen share support** ke saath aata hai: dashboard se request karne par
> phone par Android ka official casting dialog aayega, Allow karne par dashboard par
> live screen dikhegi (device par persistent notification + Stop button ke saath).

## Step 1: Zip download karo
- Dashboard ke `/download` page se `connectdesk-android.zip` download karo
- (Isme `android/` folder + `.github/workflows/` + ye guide sab included hai)

## Step 2: GitHub repo banao
- github.com → New repository → Public ya Private → Create

## Step 3: Files upload karo
- Repo page par "uploading an existing file" link par click karo
- Zip extract karke iski **saari files** drag-drop karo:
  ```
  android/
    app/
      build.gradle.kts
      src/main/...
    build.gradle.kts
    settings.gradle.kts
    gradle.properties
  .github/
    workflows/
      android-build.yml
  APK_GUIDE.md
  ```

## Step 4: BASE_URL (already set hai!)
- `ApiClient.kt` me `BASE_URL = "https://valuable-goldfish-43.convex.site"` already
  configured hai — kuch badalne ki zaroorat nahi.

## Step 5: APK build hoga apne aap
- Repo mein **Actions** tab kholo → "Build Android APK" workflow chal raha hoga
- 3-5 minute → green check ✔
- Run kholo → **Artifacts** section → `connectdesk-debug-apk` download
- ZIP extract → `app-debug.apk` mil jayega

## Step 6: Phone par install
- APK phone mein copy (Drive/WhatsApp/USB) → tap → "Install unknown apps" allow
- App kholo → dashboard se pairing code daalo → dashboard par Approve karo
- Phone par permissions allow karo (SMS, contacts, location, media, notification access)
- Done — dashboard se status/notifications/SMS/location/media sab access

## Screen share test (naya feature)
1. Dashboard → Devices → phone ke liye **Screen share** capability ON karo
2. Screen Share page → "Start screen session"
3. "Phone par consent maango" dabao → phone par Android ka
   "Start recording or casting?" dialog khulega → **Allow** tap karo
4. Dashboard par live screen dikhne lagegi (~1 fps, JPEG streaming)
5. Stop: dashboard se "Stop session" YA phone ki notification par "Stop" button

## Agar Actions fail ho
- File check: `.github/workflows/android-build.yml` repo ke root mein hai (`.github/` folder ke andar)
- Workflow `gradle assembleDebug` use karta hai (gradlew wrapper nahi mangta)

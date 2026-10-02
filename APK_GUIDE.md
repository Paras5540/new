# APK banane ke liye (GitHub ke bina)

> Naya APK **screen share support** ke saath aata hai: dashboard se request karne par
> phone par Android ka official casting dialog aayega, Allow karne par dashboard par
> live screen dikhegi (device par persistent notification + Stop button ke saath).

GitHub account suspend hone ki wajah se Actions wala flow abhi kaam nahi karega.
Neeche ka tareeka **apne laptop par Android Studio se** APK banata hai — koi online
build service, GitHub ya account ki zaroorat nahi.

---

## Step 1: Zip download + extract karo
- Dashboard ke `/download` page se `connectdesk-android.zip` download karo
- Extract karo (Windows: Extract All | Mac: double-click | Linux: `unzip`)
- Andar `android/` folder milega — usko kholo, wahi project hai

## Step 2: Android Studio install karo (ek hi baar)
- https://developer.android.com/studio → "Download Android Studio" (Windows/Mac/Linux)
- Install karne ke baad **SDK Manager** khulega:
  - **SDK Platforms** tab → **Android 14 (API 34)** check karo → Apply
  - **SDK Tools** tab → **Android SDK Build-Tools 34** + **Android SDK Platform-Tools** → Apply
- Build ko **JDK 17** chahiye; Android Studio ka apna bundled JDK by default use hota hai
  (Settings → Build, Execution, Deployment → Build Tools → Gradle → **Gradle JDK = embedded JDK**)

## Step 3: Project kholo
- Android Studio → **Open** → extract kiye hue folder me **`android`** folder select karo
  (yahi `settings.gradle.kts` wala root hai)
- "Trust project" confirm karo
- Pehli build me Gradle dependencies download hoti hain → **3-8 minute** lag sakte hain
- (Optional but recommended) `Settings → Editor → File Encodings` me UTF-8 select karo

## Step 4: BASE_URL already set hai
`ApiClient.kt` me `BASE_URL = "https://valuable-goldfish-43.convex.site"` already
configured hai — kuch badalne ki zaroorat nahi.

## Step 5: APK build karo
- Android Studio → right side **Gradle** tab → `app` → **assembleDebug**
  (ya menu: **Build → Build Bundle(s) / APK(s) → Build APK(s)**)
- Bottom **Build** tab me green success ke baad link click karo, ya file yahan milegi:
  ```
  android/app/build/outputs/apk/debug/app-debug.apk
  ```

### Command line se (Android Studio ke bina IDE)
Terminal me `android/` folder kholo aur:
```bash
# macOS / Linux
./gradlew assembleDebug
# Windows
gradlew.bat assembleDebug
```
SDK path set na ho to pehle `android/local.properties` banao (ek line):
```
sdk.dir=C:/Users/<Tumhara-Username>/AppData/Local/Android/Sdk
```

## Step 6: Phone par install
- `app-debug.apk` phone mein copy karo (Drive / WhatsApp / USB / cable)
- File tap karo → "Install unknown apps" allow karo → Install
- App kholo → dashboard se pairing code daalo → dashboard par **Approve**
- Phone par permissions allow karo (SMS, contacts, location, media, notification access)
- Done — dashboard se status / notifications / SMS reply / location / photos + live screen

## Screen share test (naya feature)
1. Dashboard → Devices → phone ke liye **Screen share** capability ON karo
2. Screen Share page → **Start screen session**
3. **"Phone par consent maango"** dabao → phone par Android ka
   "Start recording or casting?" dialog khulega → **Allow**
4. Dashboard par live screen dikhne lagegi (~1 fps, JPEG streaming)
5. Stop: dashboard se **Stop session** YA phone ki notification par **Stop** button
6. (Phone par "Share my screen" button bhi hai — manual start/stop, same consent flow)

## Common build errors
| Error | Fix |
|---|---|
| `SDK location not found` | `android/local.properties` me `sdk.dir=...` likho |
| `Failed to install the following SDK components` | SDK Manager me API 34 + Build-Tools 34 install karo, "Show Details" checkbox on |
| `Unsupported Java version` | Gradle JDK = **17** (embedded) set karo |
| `Plugin [id: 'com.android.application'] not found` | Internet check — pehli build dependencies download karti hai |
| Build fail par details chahiye | **Build → Build Output** panel dekho |

## GitHub wapas aa jaye to
`.github/workflows/android-build.yml` repo me already hai — Actions tab se wahi
`connectdesk-debug-apk` artifact flow chal jayega. Tabhi guide ka GitHub wala section
wapas use ho sakta hai.

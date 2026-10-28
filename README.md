# ApexConnect

**ApexConnect** is a native Android application built with Kotlin, Jetpack Compose (Material 3), and Room that unifies the **KDE Connect Protocol v8** ecosystem with **Remote File Browsing** and a **Persistent Device Communication Space (Chat, Images & Notes)** inspired by KDE Connect Android and FileApex.

---

## Key Features

### 1. Core KDE Connect Ecosystem (Protocol v8)
- **LAN Discovery & Direct IP Probing:** Broadcasts and listens for `kdeconnect.identity` packets over `UDP/TCP 1716` with direct unicast IP probing for VPN/Tailscale/subnet nodes.
- **RSA-2048 Identity & SHA-256 TLS Pairing:** Deterministic symmetric verification code generation and explicit trust gating (`kdeconnect.pair`).
- **Core Plugins Integrated on the Home Hub & Tools:**
  - **Share File (`kdeconnect.share.request`)** with separate TCP payload socket streaming.
  - **Clipboard Bridge (`kdeconnect.clipboard`)** to push system or custom clipboard snippets.
  - **Find My Device (`kdeconnect.findmyphone.request`)** & custom **Ping (`kdeconnect.ping`)**.
  - **Remote Input Touchpad & Keyboard (`kdeconnect.mousepad.request`)**.
  - **MPRIS Media Controller (`kdeconnect.mpris.request`)** with playback and volume controls.
  - **Remote Commands (`kdeconnect.runcommand.request`)**.

### 2. Remote File Browser (`kdeconnect.vfs.*`)
- Browse authorized directories of paired devices with interactive breadcrumbs, file sizes, MIME types, and timestamps.
- Download files directly to Android user storage via **Storage Access Framework (`CreateDocument`)** or open locally via `FileProvider` with **SHA-256** integrity verification.
- Upload files from Android into the active remote directory with live progress tracking and transfer cancellation.
- Strict canonical root confinement and directory traversal protection (`PathValidator`).
- Explicit capability reporting when a target platform does not support remote filesystem browsing.

### 3. Persistent Device Space (`kdeconnect.space.*`)
- Dedicated per-device conversation history and bulletin notes stored locally in **Room (`ApexDatabase`)**.
- Supports plain-text messages, zero-permission Photo Picker image attachments stored durably in private app storage, and pinned persistent notes.
- Offline message/note queuing with automatic delta synchronization on reconnect, UUID deduplication, and Last-Write-Wins (`updatedAt` + `revision` + `tombstone`) conflict resolution.

---

## Downloading the APK from GitHub Releases

Every push to `main` (or version tag `v*`) automatically triggers the [Build & Publish ApexConnect APK Release](.github/workflows/android-release.yml) GitHub Actions workflow, which:
1. Runs the JUnit & Robolectric test suite (`gradle :app:testDebugUnitTest`).
2. Builds signed `ApexConnect-release.apk` and `ApexConnect-debug.apk`.
3. Publishes a **GitHub Release** with both APKs and `SHA256SUMS.txt` attached under the repository's **Releases** tab.

### Custom Production Signing (Optional)
To sign GitHub Release APKs with your own persistent keystore instead of an auto-generated CI keystore, add these Repository Secrets in **GitHub → Settings → Secrets and variables → Actions**:
- `SIGNING_KEYSTORE_BASE64`: Base64-encoded `.jks` keystore (`base64 -w 0 my-upload-key.jks`, key alias `upload`)
- `STORE_PASSWORD`: Keystore password
- `KEY_PASSWORD`: Key password

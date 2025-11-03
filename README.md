# WhatsLiberation

**Status:** Experimental / Proof of Concept

**Description:**

`WhatsLiberation` is an ambitious open-source project designed to automate the bulk export of WhatsApp conversations—including text messages and media (photos, videos, audio, documents)—from an Android device to a local filesystem. Leveraging the Android Debug Bridge (ADB) for UI automation, this Kotlin-based tool interacts with WhatsApp’s native "Export chat" functionality to extract and organize chat data into a structured, user-friendly format. The project explores innovative solutions to a complex problem, balancing technical creativity with practical utility.

## Highlights
- Automates WhatsApp chat exports using ADB-driven UI interactions.
- Supports both text-only and media-inclusive exports (in development).
- Organizes data into a logical folder structure for easy access.
- Built with Kotlin for robust scripting and extensibility.
- Open-source, inviting collaboration and real-world testing.

## Motivation

People deserve **data sovereignty**—the right to control, access, and preserve their own digital information. WhatsApp, while a powerful communication tool, locks user data within its ecosystem, offering limited native options for bulk export or long-term archival. `WhatsLiberation` empowers users to reclaim ownership of their conversations and media, providing a pathway to liberate personal data from proprietary constraints. This project is a step toward a future where individuals, not platforms, dictate the fate of their digital footprint.

## Quick Start
1. Clone the repository:  
   `git clone https://github.com/yourusername/WhatsLiberation`
2. Install prerequisites: Kotlin, JDK, Android SDK.
3. Connect an Android device via USB with USB debugging enabled (for live runs).
4. Create a `.env` file (see [Configuration](#configuration)) or rely on defaults.
5. Build & run tests:  
   `GRADLE_USER_HOME=.gradle ./gradlew test`
6. Run the CLI:
   - Dry run (no device required):  
     `GRADLE_USER_HOME=.gradle ./gradlew runDryRun`
   - Invoke the single chat export flow:  
     `GRADLE_USER_HOME=.gradle ./gradlew runSingleChatExport`

## Configuration
`WhatsLiberation` reads configuration from environment variables (optionally stored in a `.env` file). When the file is absent, sensible defaults are derived from the current user.

| Key | Description | Default |
| --- | --- | --- |
| `DEVICE_ID` | Optional ADB device identifier when multiple devices are connected. | `null` |
| `USERNAME` | Local macOS username used to infer default paths. | Current system user |
| `BASE_PATH` | Root path used for local fallbacks. | User home directory |
| `DEVICE_SNAPSHOT_DIR` | Directory on the device for temporary UI dumps. | `/sdcard/whats` |
| `LOCAL_SNAPSHOT_DIR` | Local directory for screenshots/UI dumps. | `<BASE_PATH>/Downloads/whatsliberation` |
| `ADB_PATH` | Absolute path to the `adb` executable. | `<BASE_PATH>/Library/Android/sdk/platform-tools/adb` |
| `GOOGLE_DRIVE_CREDENTIALS_PATH` | Path to a service-account JSON used for Drive API access. | _unset (Drive download disabled)_ |
| `GOOGLE_DRIVE_FOLDER_NAME` | Drive folder name to poll when credentials are provided. | `Conversations` |
| `GOOGLE_DRIVE_FOLDER_ID` | Optional explicit Drive folder ID (skips name lookup). | _unset_ |
| `GOOGLE_DRIVE_POLL_TIMEOUT_SECONDS` | Max seconds to wait for Drive export to appear. | `90` |
| `GOOGLE_DRIVE_POLL_INTERVAL_MILLIS` | Interval between Drive polling attempts. | `2000` |
| `GOOGLE_CONTACTS_CLIENT_SECRET_PATH` | OAuth client secret JSON (installed app) for Google Contacts access. | _unset (contact enrichment disabled)_ |
| `GOOGLE_CONTACTS_TOKEN_PATH` | Refresh-token JSON obtained from the helper task. | _unset_ |

Generate the Contacts token once via the built-in helper:

```
./gradlew runContactsAuth --args="path/to/client_secret_google.json path/to/contacts_token.json"
```

The script launches a browser for consent and writes the refresh token to the second path.

Example `.env`:
```
DEVICE_ID=FA6A1A040123
USERNAME=your-user
BASE_PATH=/Users/your-user
LOCAL_SNAPSHOT_DIR=~/Downloads/whatsliberation
DEVICE_SNAPSHOT_DIR=/sdcard/whats
ADB_PATH=~/Library/Android/sdk/platform-tools/adb
```

## CLI & Tasks
The Kotlin CLI is wired through Gradle tasks for repeatable executions:

- `GRADLE_USER_HOME=.gradle ./gradlew runDryRun` – validates configuration and exercises the orchestration without calling ADB.
- `GRADLE_USER_HOME=.gradle ./gradlew runSingleChatExport` – entry point for the Phase 1 single-chat workflow (now automating the WhatsApp export flow via ADB).
- `./gradlew run --args="single-chat --dry-run"` – invoke the CLI manually with custom arguments.
- `./gradlew run --args="all-chats --include-media --share-target Drive"` – iterate through the discovered chat list and export each one sequentially.

Verbose logging can be enabled with `--verbose true`.

### Single Chat Command Options

```
./gradlew run --args="single-chat \
  --target-chat 'Beatrice in Bali' \
  --include-media false \
  --share-target Drive \
  --verbose true"
```

- `--target-chat` – Name (or partial match) of the chat to export; defaults to the first visible chat when omitted.
- `--include-media` – Toggle between text-only exports (`false`) and the media-inclusive ZIP flow (`true`).
- `--share-target` – Share sheet label to tap. `Drive` is automated end-to-end, including tapping the Google Drive `Upload` button.
- `--drive-folder` – Target Google Drive folder name (defaults to `Conversations`). The automation opens the folder picker and selects this entry before uploading.
- When Drive credentials are configured, the tool polls the Drive API for the exported archive and downloads it into the run directory. Without credentials, it falls back to UI-only automation and logs where to locate the file manually.
- `--channel-prefix` – Optional string (e.g. `HK`) prepended to exported filenames for alternate channels like WhatsApp Business.

### All Chats Command Options

```
./gradlew run --args="all-chats \
  --include-media \
  --share-target Drive \
  --drive-folder Conversations \
  --max-chats 100"
```

- `--max-chats` – Optional cap to stop after _n_ chats (useful for testing).
- `--chat-list <path>` – Provide a newline-delimited file of chat names to back up instead of discovering in-app.
- Other flags mirror the single-chat command (`--include-media`, `--share-target`, `--drive-folder`, `--dry-run`, `-v`).
- Each `all-chats` run writes a summary JSON under `LOCAL_SNAPSHOT_DIR` (e.g., `backup-summary-YYYYMMDD-HHMMSS.json`) listing successful chats, downloaded artifacts, skipped entries, and failures.
- Filenames now include contact metadata when available: `NAME_FROM_CHAT_{CONTACT_ID}_{PHONENUMBER}_{DATE}.zip`. Provide Google Contacts credentials so the tool can resolve IDs and canonical numbers. Use `--channel-prefix HK` when exporting from WhatsApp Business to differentiate archives.
- `--dry-run` – Skip all ADB calls while logging the planned actions.

## Testing
Unit tests are mandatory for each phase:

- Run the full suite: `GRADLE_USER_HOME=.gradle ./gradlew test`
- Tests cover configuration loading/validation, the ADB client abstraction, XML parsing against recorded fixtures, and filesystem helpers.
- Additional integration tests will arrive as device-backed automation solidifies. See [`docs/testing.md`](docs/testing.md) for guidance on extending the suite.

## Prerequisites
- Kotlin 1.6+
- JDK 11+ (toolchain targets JDK 23 at build time)
- Android device (10.0+) with WhatsApp installed
- ADB installed and configured
- USB debugging enabled
- WhatsApp chats targeted for export

## Compatibility
- Tested on Android 10.0+ (earlier versions untested).
- WhatsApp v2.23.X.X (as of February 2025)—subject to change with updates.
- Fragility expected due to reliance on WhatsApp’s UI.

## Why This Matters
No widely-adopted, actively-maintained open-source tools currently exist for bulk WhatsApp exports via ADB automation. `WhatsLiberation` aims to fill this gap, offering a proof-of-concept that could evolve into a reliable solution with community input. The project tackles significant technical challenges—UI automation fragility, media handling complexity, and cross-version compatibility—making it a compelling exploration for developers and innovators.

**Disclaimer:**  
This is an experimental tool. ADB-based automation is inherently fragile and may break with WhatsApp updates. Use it as a foundation for experimentation, not a production-ready backup solution, and test thoroughly before relying on it.

## Project Milestones
- [x] Initial ADB connectivity and setup.
- [x] Documentation for environment configuration.
- [x] CLI scaffold with dry-run option and configuration validation.
- [ ] Single chat export automation.
- [ ] Bulk chat list iteration.
- [ ] Full text export with filesystem organization.
- [ ] Media export and extraction (in progress).

## Roadmap

### Phase 1: Foundation (1-2 Weeks)
- Establish ADB integration and Kotlin scripting environment.
- Analyze WhatsApp’s UI with `uiautomatorviewer` to map export workflows.
- Build a script for exporting a single text-only chat.

### Phase 2: Core Automation (2-4 Weeks)
- Implement chat list iteration and bulk export logic.
- Save text exports to a structured filesystem (e.g., `exports/chat_name.txt`).
- Add configurability (e.g., chat selection, timeouts).

### Phase 3: Media Integration (3-5 Weeks)
- Extend automation to handle "With media" exports.
- Develop methods to extract and organize media files (e.g., ZIP handling, `adb pull`).
- Link media to corresponding chats with timestamps or metadata.

### Phase 4: Polish & Scale (Ongoing)
- Enhance error handling and logging for robustness.
- Optimize performance with Kotlin coroutines or parallel processing.
- Test across devices, Android versions, and WhatsApp updates.
- Publish comprehensive docs and a user guide.

## Technical Stack
- **Kotlin:** Core scripting and logic.
- **ADB:** Device interaction and UI automation.
- **Android SDK:** UI inspection via `uiautomatorviewer`.
- **Development Tools:** IntelliJ IDEA or Android Studio.
- **Test Hardware:** Android device with WhatsApp.

## Challenges
- **UI Volatility:** WhatsApp updates may alter UI elements, requiring frequent script adjustments.
- **Media Complexity:** Automating media extraction via ADB is non-trivial and device-dependent.
- **Reliability:** ADB automation lacks native robustness, demanding creative workarounds.

## Aspirational Features
- **Dynamic UI Detection:** Use image recognition or ML to adapt to UI changes.
- **Fallback Mechanisms:** Handle failures gracefully with manual overrides or retry logic.
- **CLI/GUI Options:** Offer a simple interface for non-technical users.

## Security Notes
- Operates locally—no data is sent to external servers.
- Exports are unencrypted; secure your filesystem accordingly.
- Review privacy implications before sharing exported data.

## Contributing
This is an open-source initiative—your expertise can accelerate progress! Potential contributions:
- Refine UI automation logic.
- Solve media export challenges.
- Test on diverse devices and WhatsApp versions.
- Enhance documentation or add examples.

Submit pull requests or open issues on GitHub to get involved.

## Troubleshooting
- **ADB Connection Issues:** Verify USB debugging and device visibility (`adb devices`).
- **UI Automation Fails:** Recheck coordinates or element IDs after WhatsApp updates.
- **Timeouts:** Adjust script parameters for larger chats.

## License
[MIT License] (or your preferred open-source license)

## Vision
`WhatsLiberation` is more than a tool—it’s a challenge to rethink how we interact with closed ecosystems using open-source ingenuity. Join the journey to liberate your WhatsApp data!

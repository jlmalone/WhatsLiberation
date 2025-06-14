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
3. Connect an Android device via USB with USB debugging enabled.
4. Copy `src/main/resources/.env.example` to `.env` and update paths (ADB, snapshot directories).
5. Build the project:
   `gradle build`
6. Run with options:
   `gradle run --args="--help"`
   - To specify a device ID: `gradle run --args="--device-id <DEVICE_ID>"`

### Exporting a Single Chat
Running the program opens WhatsApp and walks through the UI to trigger the "Export chat" flow for the first conversation in your list. When the Android share sheet appears, choose a destination such as "Save to device" or "Save to Drive". If you save to local storage (e.g., `/sdcard/Download/`), you can retrieve the file on your computer via:

```bash
adb pull /sdcard/Download/<exported-file>.zip ./
```

## Prerequisites
- Kotlin 1.6+
- JDK 11+
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
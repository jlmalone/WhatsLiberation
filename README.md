# WhatsLiberation

**Status:** Experimental / Proof of Concept

**Description:**

`WhatsLiberation` is an experimental Kotlin/JVM project exploring WhatsApp conversation export via Android Debug Bridge (ADB) UI automation. **Export is currently disabled.** The CLI is inert: every `main` invocation prints usage or a disabled message and exits without loading configuration, invoking ADB, or touching the device.

## Highlights
- Inert CLI: `--help`, no arguments, `--dry-run`, and unknown flags exit without device effects.
- Export workflows are disabled until an explicit operation and artifact receipt path exist.
- Prior spike code remains in the repository for future state-machine work.
- Built with Kotlin for scripting and extensibility.
- Open-source exploration; not a production backup tool.

## Motivation

People deserve **data sovereignty**—the right to control, access, and preserve their own digital information. WhatsApp, while a powerful communication tool, locks user data within its ecosystem, offering limited native options for bulk export or long-term archival. `WhatsLiberation` explores a pathway to liberate personal data from proprietary constraints, but **no export is available in the current checkout**.

## Quick Start
1. Clone the repository:  
   `git clone https://github.com/yourusername/WhatsLiberation`
2. Install prerequisites: Kotlin, JDK.
3. Build the project when your host toolchain supports it.
4. Run safe commands only:  
   `./gradlew run --args="--help"` or `--args="--dry-run"`

**Do not expect export.** ADB is not invoked by the current entrypoint.

## Prerequisites
- Kotlin 1.6+
- JDK 11+
- For future export work only: Android device, ADB, USB debugging, WhatsApp

## Compatibility
- Export automation is not active in this checkout.
- Prior spike code targeted Android 10.0+ and WhatsApp UI structures that change frequently.

## Why This Matters
No widely-adopted, actively-maintained open-source tools currently exist for bulk WhatsApp exports via ADB automation. `WhatsLiberation` may evolve into a reliable solution with community input, but **the current CLI performs no export**.

**Disclaimer:**  
This is an experimental repository. The entrypoint is inert and does not run ADB. When export is re-enabled, ADB-based automation will remain fragile and may break with WhatsApp updates. Do not use this checkout as a backup solution.

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
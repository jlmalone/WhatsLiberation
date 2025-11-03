# Testing Guide

Unit tests are the backbone for each development phase. This document explains what currently exists and how to extend the suite.

## Running Tests
- Run everything: `GRADLE_USER_HOME=.gradle ./gradlew test`
- Focus on a single class: `GRADLE_USER_HOME=.gradle ./gradlew test --tests "vision.salient.*ClassName"`
- The build uses the Gradle wrapper with a local `.gradle` cache to avoid touching global directories.

## Current Coverage
- **Configuration:** `ConfigLoaderTest` verifies fallback defaults, validation errors, and success cases with on-disk fixtures.
- **ADB Client:** `RealAdbClientTest` validates command construction, shell helpers, and future fake backends via an injectable `ProcessRunner`.
- **UI Parsing:** `WhatsAppXmlParserTest` uses curated `uiautomator` dumps in `src/test/resources/fixtures/ui` to prove chat discovery and menu selection.
- **Filesystem:** `LocalExportRepositoryTest` ensures safe chat folder names and deterministic run folders.

## Adding Tests
1. Place new fixtures under `src/test/resources/` and load them via the class loader.
2. Prefer constructor injection or dedicated `EnvSource`/`ProcessRunner` test doubles rather than mocking global singletons.
3. Keep assertions explicit—tests should fail with clear context when UI IDs drift or environment changes.
4. When adding integration tests that touch the file system, create temporary directories with `Files.createTempDirectory` to avoid polluting the workspace.

## Future Work
- Introduce tagged integration tests that exercise the orchestration against the fake ADB backend.
- Capture real device dumps for regression coverage once the automation stabilises.
- Add coverage for retry/backoff behaviour as the automation layer matures.
- Drive API polling is guarded by `GOOGLE_DRIVE_CREDENTIALS_PATH`; tests use fake downloaders, so provide a stub or disable the feature when writing fresh cases. Multi-chat tests inject a `SingleChatExecutor` stub via the new abstraction.

## Live Capture Notes (2025-11-01)
- WhatsApp chat exports jump straight to the Android share sheet—there is no built-in "Save to device" target in the export dialog.
- Google Drive works end-to-end: tap `Without media`/`Include media`, pick Drive from the chooser, then use Drive's `Upload` button (`com.google.android.apps.docs:id/upload_action`) to finish.
- No extra permission prompts appeared during Drive upload; automation can assume the sheet is ready for the final tap.
- After upload, the chooser dismisses back to WhatsApp and no files are written under `/sdcard`; the exported payload resides in Drive until pulled via API or manual download.
- Share-sheet fixtures now live under `src/test/resources/fixtures/live/20251101-154303/` for both media/no-media paths, along with Drive upload XML/PNG captures.

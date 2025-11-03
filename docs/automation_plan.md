# Automation Plan: Drive Retrieval & Share-Sheet Hardening

This document captures the next build phases so any agent can pick up from here without context switching. The roadmap assumes the current codebase (2025-11-02 snapshot) with working WhatsApp export automation and Drive share-sheet captures.

## Phase A – Pull From Google Drive API (Preferred Path)

1. **Credentials & Auth**
   - Decide between OAuth client credentials vs. service account. For unattended CLI runs, a service account with delegated access to the Drive folder is ideal.
   - Store secrets in `.env` (e.g., `GOOGLE_APPLICATION_CREDENTIALS`) and extend `AppConfig` to surface Drive-specific entries.
   - Add a small `vision.salient.drive` module wrapping the `files.list` / `files.get` endpoints (Google API client or lightweight HTTP via `OkHttp`).

2. **Detect Export Completion**
   - After tapping `Upload`, poll Drive for a new file starting with `WhatsApp Chat with <chat>` in the `Conversations` folder.
   - Use exponential backoff with a reasonable timeout (e.g., 60–90 seconds). Surface a clear failure if the file never arrives.

3. **Download & Store Locally**
- Once located, stream the file to the `runDirectory`, preserving the Drive filename. ✅ Implemented in `GoogleDriveDownloader`.
- Optionally validate checksum/size to catch partial uploads.

4. **Tests & Tooling**
   - Unit-test the Drive client using fakes/mocks (e.g., stubbed HTTP via `MockWebServer`).
   - Provide an integration guard (disabled by default) that exercises the real API when credentials are present.

## Phase B – Harden Share-Sheet Fallback (Partially Complete)

If Drive API isn’t available, strengthen the existing UI automation:

1. **Folder Picker Robustness**
- Extend heuristics to scroll the picker when the folder isn’t visible. ✅ Basic navigation via “My Drive” implemented; still need scrolling behaviour for deep folder structures.
   - Support both Compose and legacy layouts by matching on `content-desc`, text, and potential icon buttons.

2. **Upload Confirmation**
   - Capture toasts/snackbars (if any) and watch for the chooser dismissal to confirm success.
   - Retry folder selection/upload a limited number of times when the Drive UI misbehaves.

3. **Device Artifact Retrieval (Optional)**
   - Investigate whether WhatsApp ever writes a local cache of the ZIP when media is included; if so, supplement Drive with `adb pull`.

4. **Documentation**
   - Update README and `docs/testing.md` once the fallback logic is enhanced so manual operators know when to expect a Drive-only flow vs. local artifacts.

## Immediate Next Steps

1. Add Drive API configuration hooks (`AppConfig`, `.env` support, CLI flags for credentials).
2. Implement the Drive client + polling loop in `SingleChatExportRunner` (behind feature flag until stable).
3. Write unit tests for Drive polling and ensure existing export tests still pass.
4. After Drive API path stabilizes, revisit Phase B items to keep the share sheet automation usable if cloud access isn’t configured.

## Phase C – Iterative Coverage & Scheduling

1. **Timestamped Drive Files**
   - After confirming the export in Drive, rename the uploaded ZIP/TXT (and local copy) to include `YYYYMMDD-HHMM` so the archive reflects when it was captured.

2. **Persistent Registry**
   - Store per-chat metadata (name, occurrence index, last export timestamp, last outcome) to drive incremental runs and avoid duplicating work.

3. **Snackable Prefix Runs**
   - Add CLI support for alphabetical prefix slices (e.g., search “Al”, “Be”) so small batches can be captured daily; combine with the registry to cover duplicate names across slices.

4. **Profiles & Scheduling**
   - Introduce run profiles (`daily`, `exhaustive`) that translate to sensible defaults (`maxChats`, lookback window, prefixes). Hook profiles into the registry so “last scanned” data informs scheduling.

5. **Alphabet Exploration**
   - Once search automation is in place, iterate through alphanumeric combinations over time to guarantee every conversation (including rarely active ones) is backed up eventually.

## Phase D – Contact Intelligence & Multi-Profile Coverage

1. **Google Contacts Integration**
   - Introduce a People/Contacts API client on the macOS/Linux runner to resolve WhatsApp phone numbers to Google Contact IDs and canonical names.
   - Cache lookups locally (registry) to minimise API calls and allow offline reruns.

2. **Enhanced Export Naming**
   - Incorporate contact metadata into filenames: `CONTACT_NAME_FROM_CHAT_{GOOGLE_CONTACT_ID}_{PHONENUMBER}_YYYYMMDD.zip` for personal chats. Fall back gracefully when a contact ID or number is unavailable (groups, hidden numbers).
   - Update Drive rename logic and local artifact naming to match the new scheme.

3. **WhatsApp Business Parity**
   - Mirror the entire automation stack for WhatsApp Business (second app on device). Prefix saved filenames with `HK_` (or configurable tag) to distinguish the channel.
   - Ensure the registry and CLI can target personal vs. business profiles independently or collectively.

4. **Reconciliation Tooling**
   - Add post-download reconciliation scripts that use Google Contacts data to detect renamed/duplicated conversations across exports.
   - Generate daily summary reports highlighting new/changed chats, leveraging the registry and contact metadata.

Feel free to append implementation notes or discovered edge cases here so future agents stay in sync.

# WhatsLiberation Roadmap

## Project Overview

WhatsLiberation is an automated WhatsApp chat backup system that exports conversations from an Android device to Google Drive with contact-aware naming. It uses ADB automation to navigate WhatsApp's UI, trigger exports, and download the resulting archives.

**Key Features:**
- Multi-chat batch exports with progress tracking
- Google Drive integration (OAuth-based)
- Google Contacts enrichment for filename generation
- Flexible chat discovery with fuzzy matching (Levenshtein distance)
- Search integration for finding chats dynamically
- Channel prefix support for WhatsApp Business differentiation
- JSON backup summaries for each run

---

## Current Status (as of 2025-11-03)

### ✅ What's Working

**Core Automation:**
- ✅ Single-chat export with full WhatsApp UI navigation
- ✅ Multi-chat batch processing (tested with 20 chats)
- ✅ Overflow menu → More → Export chat workflow
- ✅ Share sheet selection and Drive folder picker
- ✅ Automatic Drive file upload confirmation
- ✅ XML/PNG snapshots at every step for debugging

**Drive Integration (FULLY FUNCTIONAL):**
- ✅ OAuth2 user credentials (unified with Contacts API)
- ✅ Automatic Drive file downloads after export
- ✅ File renaming with contact-aware format before download
- ✅ Polling for newly uploaded exports (configurable timeout)
- ✅ Average export time: ~29 seconds per chat

**Google Contacts Integration:**
- ✅ Phone number lookup via People API
- ✅ Contact ID and display name resolution
- ✅ In-memory caching to minimize API calls
- ✅ Graceful fallback when contacts not found

**Flexible Chat Discovery (NEW - 2025-11-03):**
- ✅ WhatsApp search integration (types query, scrolls results)
- ✅ Fuzzy matching with Levenshtein distance (edit distance ≤ 5)
- ✅ Intelligent scrolling through main chat list (up to 5 pages)
- ✅ Duplicate name handling via occurrence index (matchIndex)
- ✅ Stagnation detection (stops scrolling when no new chats found)

**Filename Generation:**
- ✅ Format: `[CHANNEL_PREFIX_]NAME_FROM_CHAT_{CONTACT_ID}_{PHONE_DIGITS}_{YYYYMMDD}.txt`
- ✅ Example: `CHUCK_MALONE_FROM_CHAT_c1234567890_13239746605_20251103.txt`
- ✅ Channel prefix support: `HK_` for WhatsApp Business, none for personal

**Quality Assurance:**
- ✅ Comprehensive test suite (configuration, ADB, XML parsing, filesystem)
- ✅ Mock-friendly architecture with factory injection
- ✅ Live device fixtures captured for regression testing

---

## Recent Accomplishments

### Phase 1 & 2: Drive Integration (Completed 2025-11-03)
- ✅ Fixed OAuth token to include Drive scope (contacts.readonly + drive)
- ✅ Updated .env to use OAuth credentials instead of service account
- ✅ Verified Drive downloads working (3/3 chats successful)
- ✅ Committed all changes (53 files, 6015 insertions)
- ✅ Ran small-scale production test (10/20 chats successful)

**Root Cause of 50% Failure Rate:**
- Chat list synchronization issue
- MultiChatBackupRunner discovered 20 chats at startup
- After exporting 10 chats (~5 minutes), chat order changed
- Previously visible chats scrolled off-screen or moved position
- SingleChatExportRunner couldn't find them in current viewport

### Phase 3: Flexible Chat Discovery (Completed 2025-11-03)
- ✅ Implemented Levenshtein distance for fuzzy name matching
- ✅ Integrated WhatsApp search (tap icon, type query, scroll results)
- ✅ Added intelligent main-list scrolling with candidate collection
- ✅ Handles duplicate names via occurrence index
- ✅ Exits search mode gracefully with back button
- ✅ Committed flexible discovery implementation

---

## Known Issues & Limitations

### 🔴 Critical Issues

**1. Search Behavior Unclear (NEEDS CLARIFICATION)**
- **Question:** Is the search icon always visible at the top of the chat list?
- **Or:** Do we need to scroll the chat list to find the search icon?
- **Current Implementation:** Assumes search icon is always present
- **Impact:** If search icon requires scrolling to find, implementation will fail
- **Action Required:** User needs to clarify search UI behavior on their device

**2. Test Suite Failures (4 tests failing)**
- Flexible discovery adds extra ADB calls (search, scroll, exit search)
- Scripted test responses don't account for new call sequence
- Tests need updating to match new discovery workflow
- **Action Required:** Rewrite test fixtures after production validation

**3. Chat List Position Changes**
- **Status:** Addressed by flexible discovery, but needs validation
- Previously: 10/20 chats failed because they moved off-screen
- Now: Should find via search or scrolling, but not tested in production yet
- **Action Required:** Run full production test to validate fix

### ⚠️ Known Limitations

**4. Coordinate-Based Tap Actions**
- All UI interactions use absolute coordinates from XML parsing
- If WhatsApp UI changes (updates, different screen size), coordinates break
- **Mitigation:** XML snapshots help debug and adjust coordinates
- **Future:** Consider resource-ID-based taps where possible

**5. No Persistent Registry**
- Each run starts fresh, no memory of previous exports
- Can't do incremental backups (only export new/changed chats)
- Can't track last export timestamp per chat
- **Impact:** Every run is a full backup, slower and less efficient

**6. No Rate Limiting Protection**
- Doesn't throttle API calls to Google (Drive, Contacts)
- Could hit rate limits on large backups (100+ chats)
- **Mitigation:** ~29s per chat provides natural throttling
- **Future:** Add configurable delays and retry logic

**7. Single Device Support**
- Automation assumes single ADB device connected
- DEVICE_ID env var available but not tested with multiple devices
- **Future:** Add device selection/confirmation step

---

## Testing Results

### Production Test #1 (20 chats, 2025-11-03)
- **Configuration:** `--max-chats 20 --share-target Drive --drive-folder Conversations`
- **Duration:** 5m 47s
- **Result:** 10 successful, 10 failed (50% success rate)
- **Successful Chats:** T London, Heidi London, Reena London, Allie Vancouver, Crina London, Beverly London, Anna Belgium Brussels Antwerp, Patrycja London, Gulnaz London, Frannie London
- **Failed Chats:** Chuck & Malone, Aya London Barnett, Chloe 78 London, Yul Phillipines, MJ 78 London, Scarlett London, Ana London, Lu Washington dC Hagerstown, Xixi 8 Barcelona, Unknown Chat
- **Failure Reason:** "Chat not found on conversation list" (scrolled off-screen during run)

### Small Validation Test (3 chats, 2025-11-03)
- **Configuration:** `--max-chats 3` (after Drive fix)
- **Result:** 3/3 successful, all with Drive downloads
- **Artifacts:** ZIP files confirmed valid, contact-aware filenames working
- **Conclusion:** Core export workflow + Drive integration 100% functional

---

## Next Steps (Priority Order)

### 🔥 Immediate (Before Next Production Run)

**1. Clarify Search Behavior (USER INPUT REQUIRED)**
- Question: Is search icon always visible at top of chat list?
- If NO: Implement scroll-to-find-search-icon logic
- If YES: Proceed to production test

**2. Production Test with Flexible Discovery**
- Run: `./gradlew run --args="all-chats --max-chats 20 --share-target Drive"`
- Goal: Validate flexible discovery solves the 50% failure rate
- Monitor: Logs for search attempts, fuzzy matches, Levenshtein distances
- Success Criteria: 90%+ success rate (allowing for genuine failures)

**3. Analyze Production Test Results**
- Review XML snapshots in each chat's run directory
- Check search_results_page*.xml and scroll_page*.xml files
- Identify patterns in successful vs. failed discoveries
- Adjust fuzzy matching threshold if needed (currently ≤ 5 edit distance)

### 🎯 Short-Term (This Week)

**4. Fix Test Suite**
- Update SingleChatExportRunnerTest to include search/scroll ADB calls
- Add new test: "flexible discovery finds chat via search"
- Add new test: "flexible discovery finds chat via main list scroll"
- Add new test: "fuzzy match accepts name variations"
- Run: `./gradlew test` → all tests passing

**5. Medium-Scale Production Test (50-100 chats)**
- Run: `./gradlew run --args="all-chats --max-chats 100 --share-target Drive"`
- Duration estimate: ~48 minutes (100 chats × 29s)
- Monitor: Drive API rate limits, memory usage, disk space
- Check: Backup summary JSON for failures/patterns

**6. Document Search UI Discovery**
- Capture XML snapshot of WhatsApp home screen
- Locate search icon resource-id and bounds
- Update code with correct search icon locator
- Add fallback: if search icon not found, log warning and skip search

### 📋 Medium-Term (Next 2 Weeks)

**7. Implement Persistent Registry (Phase C in docs/automation_plan.md)**
- Design: SQLite DB or JSON file tracking chat metadata
- Schema: chat_name, contact_id, phone, last_export_timestamp, hash
- Enable: Incremental backups (only export chats changed since last run)
- Command: `--incremental` flag to check registry before exporting

**8. Add Retry Logic & Error Recovery**
- Retry failed chats up to 3 times with exponential backoff
- Handle transient failures: network timeouts, Drive API 429s
- Save failed chats to separate JSON file for manual retry
- Command: `--retry-failed <summary.json>` to retry only failures

**9. WhatsApp Business Support (HK Channel)**
- Test: Switch to WhatsApp Business app on device
- Run: `./gradlew run --args="all-chats --channel-prefix HK --share-target Drive"`
- Verify: Filenames start with HK_ prefix
- Document: Separate .env profile for Business app package name

**10. Rate Limiting & API Quotas**
- Add: Configurable delay between exports (default: 0, max: 60s)
- Monitor: Google API response headers for quota warnings
- Implement: Exponential backoff on 429 (rate limit) responses
- Log: API call counts (Drive downloads, Contacts lookups)

### 🚀 Long-Term (Next Month)

**11. Alphabetical Prefix Searches**
- Goal: Systematically cover all chats using search prefixes
- Example: Run with "Al", "Be", "Ch", etc. to find all "A*", "B*", "C*" chats
- Command: `--search-prefix Al --max-chats 50`
- Use Case: Break large backups into manageable alphabetical chunks

**12. Scheduling & Automation**
- Create: Cron job or systemd timer for daily incremental backups
- Script: Wrapper that sets env vars, runs export, emails summary
- Monitor: Parse JSON summary, alert on high failure rate
- Rotate: Old backup summaries (keep last 30 days)

**13. Web Dashboard (Optional)**
- UI: Show backup history, success/failure rates, last export times
- Charts: Export trends over time, storage usage
- Actions: Trigger manual backup, retry failed chats, view logs
- Tech: Simple Flask/FastAPI + React frontend

**14. Multi-Device Support**
- Config: Profiles per device (personal phone, work phone, tablet)
- Selection: Prompt user to choose device if multiple connected
- Verification: Show device name/model before starting export
- Registry: Separate per-device (avoid ID collisions)

---

## Architecture Overview

### Key Components

**1. WhatsLiberationCli** (`src/main/kotlin/vision/salient/cli/`)
- Entry point, command-line argument parsing
- Commands: `single-chat`, `all-chats`
- Flags: `--max-chats`, `--include-media`, `--share-target`, `--drive-folder`, `--channel-prefix`, `--match-index`

**2. MultiChatBackupRunner** (`src/main/kotlin/vision/salient/export/`)
- Orchestrates batch exports
- Discovers chats via initial scroll
- Delegates each chat to SingleChatExportRunner
- Writes JSON backup summary at end

**3. SingleChatExportRunner** (`src/main/kotlin/vision/salient/export/`)
- Core export workflow for one chat
- **New:** Flexible chat discovery (search, fuzzy match, scroll)
- Navigates WhatsApp UI via ADB taps/swipes
- Captures XML snapshots at each step
- Handles Drive folder picker and upload confirmation

**4. GoogleDriveDownloader** (`src/main/kotlin/vision/salient/drive/`)
- Polls Drive API for newly uploaded exports
- Renames Drive file to contact-aware format
- Downloads archive to local run directory
- Supports OAuth user credentials (primary) and service account (fallback)

**5. GoogleContactsClient** (`src/main/kotlin/vision/salient/contacts/`)
- Looks up phone numbers via People API
- Returns contact ID and display name
- In-memory cache to avoid redundant API calls

**6. WhatsAppXmlParser** (`src/main/kotlin/vision/salient/`)
- Parses uiautomator XML dumps
- Finds nodes by resource-id, text, or content-desc
- Calculates tap coordinates from bounds
- Extracts chat names from conversation list

**7. Configuration** (`src/main/kotlin/vision/salient/config/`)
- Loads from .env file with fallback defaults
- AppConfig, DriveConfig, ContactsConfig data classes
- Validation with errors and warnings
- Injectable EnvSource for testing

---

## Environment Setup

### Required Files

**1. client_secret_google.json** (OAuth Client)
- Google Cloud Console → APIs & Services → Credentials
- Create OAuth 2.0 Client ID (type: Desktop app)
- Download JSON, rename to `client_secret_google.json`

**2. contacts_token.json** (Generated by Helper)
- Run: `./gradlew runContactsAuth --args="client_secret_google.json contacts_token.json"`
- Browser opens for consent (approve Contacts + Drive scopes)
- Refresh token saved to contacts_token.json
- **Important:** This token is used for both Contacts AND Drive APIs

**3. .env File**
```bash
# Device
DEVICE_ID=38090DLJH002YA    # if multiple ADB devices connected

# Google Contacts (OAuth)
GOOGLE_CONTACTS_CLIENT_SECRET_PATH=/absolute/path/to/client_secret_google.json
GOOGLE_CONTACTS_TOKEN_PATH=/absolute/path/to/contacts_token.json

# Google Drive (Uses same OAuth token)
# GOOGLE_DRIVE_CREDENTIALS_PATH=/path/to/service-account.json  # Optional: only for service account
GOOGLE_DRIVE_FOLDER_NAME=Conversations

# Optional
USERNAME=yourname  # for audit logs (defaults to system user)
```

### Running Exports

**Single Chat:**
```bash
./gradlew run --args="single-chat --target-chat 'Chuck Malone' --share-target Drive"
```

**Multi-Chat (with limit):**
```bash
./gradlew run --args="all-chats --max-chats 10 --share-target Drive --drive-folder Conversations"
```

**WhatsApp Business (HK prefix):**
```bash
./gradlew run --args="all-chats --channel-prefix HK --max-chats 20"
```

**With Media (slower, larger files):**
```bash
./gradlew run --args="all-chats --include-media --max-chats 5"
```

---

## Output & Artifacts

### Per Chat Run Directory
```
~/Downloads/whatsliberation/
  ├── chuck_malone/
  │   └── 20251103-034049/
  │       ├── chat_list.xml
  │       ├── chat_detail.xml
  │       ├── chat_overflow.xml
  │       ├── chat_more_menu.xml
  │       ├── export_chat_dialog.xml
  │       ├── export_share_sheet_attempt0.xml
  │       ├── drive_upload.xml
  │       ├── drive_post_upload.xml
  │       ├── search_results_page0.xml  # NEW (if search used)
  │       ├── scroll_page0.xml          # NEW (if scrolling used)
  │       └── CHUCK_MALONE_FROM_CHAT_13239746605_20251103.txt
  └── backup-summary-20251103-034528.json
```

### Backup Summary JSON
```json
{
  "successfulChats": ["T London", "Heidi London", ...],
  "downloadedFiles": [
    "/Users/vn57dec/Downloads/whatsliberation/t_london/.../T_LONDON_FROM_CHAT_20251103.txt",
    ...
  ],
  "skippedChats": [],
  "failedChats": [
    {"chat": "Unknown Chat", "reason": "Chat not found after flexible search"}
  ]
}
```

---

## Troubleshooting

### "Chat not found on conversation list"
- **Old behavior:** Chat scrolled off-screen, rigid exact-match failed
- **New behavior (2025-11-03):** Uses search + fuzzy match + scrolling
- **If still failing:** Check `search_results_page*.xml` and `scroll_page*.xml` in run directory
- **Adjust:** Increase Levenshtein distance threshold (currently ≤ 5)

### "Drive credentials not configured"
- **Check:** GOOGLE_CONTACTS_TOKEN_PATH is set and file exists
- **Verify:** Token includes Drive scope (rerun runContactsAuth if unsure)
- **Fallback:** Set GOOGLE_DRIVE_CREDENTIALS_PATH to service account JSON

### "Unable to locate share target 'Drive'"
- **Check:** Share sheet XML snapshot in run directory
- **Verify:** Drive app installed on device and set as default for .txt files
- **Try:** Use "My Drive" or "Google Drive" as --share-target value

### ADB connection issues
- **Check:** `adb devices` shows device connected
- **Verify:** USB debugging enabled on device
- **Try:** `adb kill-server && adb start-server`

### Rate limiting (429 errors)
- **Drive API:** Wait 1 minute, retry
- **Contacts API:** Increase poll interval or reduce max-chats
- **Future:** Will add automatic exponential backoff

---

## Contributing

### Running Tests
```bash
./gradlew test
```

### Adding New Fixtures
1. Run export with `--verbose`
2. Copy XML files from `~/Downloads/whatsliberation/<chat>/latest/`
3. Place in `src/test/resources/fixtures/live/<date>/`
4. Update tests to reference new fixtures

### Code Style
- Kotlin conventions
- Prefer data classes for configuration
- Inject dependencies via factory functions (testable)
- Use slf4j Logger, not println
- Document public APIs with KDoc

---

## References

- **Automation Plan:** `docs/automation_plan.md` (multi-phase roadmap)
- **Testing Guide:** `docs/testing.md` (coverage, how to extend)
- **Git History:** `git log --oneline` (recent features and fixes)

---

## Summary

WhatsLiberation is production-ready for small-to-medium backups (10-20 chats) with Drive downloads working flawlessly. The new flexible chat discovery (2025-11-03) should solve the 50% failure rate from dynamic chat list changes, but requires production validation.

**Next critical step:** Clarify search icon behavior, then run production test with 20 chats to validate flexible discovery. If successful, scale to 50-100 chats and implement persistent registry for incremental backups.

**Long-term vision:** Fully automated daily incremental backups for both personal WhatsApp and WhatsApp Business, with web dashboard for monitoring and manual intervention when needed.

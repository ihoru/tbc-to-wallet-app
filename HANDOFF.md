# Handoff: TBC → Wallet Android app

Context for a Claude Code session continuing this project. Read this first, then README.md.

## Goal
A personal, sideloaded Android app that:
1. Reads push notifications from the TBC Bank app (Georgia) with a NotificationListenerService.
2. Parses each transaction (amount, currency, merchant or sender, card, date/time, balance).
3. Creates the matching record in **Wallet by BudgetBakers** through its official REST API.
4. Picks a category using this priority order, all agreed with the owner:
   1. The owner's own rules in the app (always win).
   2. Wallet's own record rules, fetched and applied by the app (see API facts).
   3. A payee → category map learned from the owner's last 12 months of Wallet history.
   4. No category, so Wallet files it as Unknown.
5. Routes each currency to its own Wallet account (mapped in Settings). A new currency waits in
   the queue and the app notifies the owner to map it.

## Current state
- The project compiles: `./gradlew assembleDebug test` passes (AGP 9.4.1, Gradle 9.8.1 wrapper),
  with no source changes needed. GitHub Actions (`.github/workflows/android.yml`) runs the same
  command and uploads `app-debug.apk` as a build artifact.
- The parser regexes were checked (as Java, same regex engine) against all real samples below,
  and every one parsed correctly.
- Versions pinned: AGP 9.4.1 (built-in Kotlin), Kotlin 2.4.21, KSP 2.3.12, Compose BOM 2024.12.01,
  Room 2.8.5, WorkManager 2.10.0, OkHttp 4.12.0, kotlinx-serialization 1.7.3. minSdk 26,
  target/compile 35.

## Next steps
1. Install on the owner's phone (debug APK from the CI run, or `./gradlew installDebug`) and test
   with a real TBC notification.
2. Run `./gradlew test` after parser or resolver changes. `app/src/test/.../LogicTest.kt` covers both.

## Layout
```
app/src/main/java/su/iho/tbc2wallet/
  parse/TbcNotificationParser.kt   pure Kotlin, unit-tested
  categorize/CategoryResolver.kt   priority chain + PayeeLearner (history → map)
  ingest/TbcNotificationListener.kt  listener + Ingest (dedup key, timezone) 
  ingest/Notifier.kt               alerts only when something needs attention
  api/WalletApi.kt                 OkHttp + kotlinx.serialization client
  data/Database.kt                 Room: transactions, user_rules, wallet_rules, learned, categories, accounts
  data/Settings.kt                 prefs + API token encrypted with an Android Keystore AES-GCM key
  sync/SyncWorker.kt               WorkManager: sends queue, 20 records per POST, 6-hourly safety run
  sync/Catalog.kt                  refreshes accounts/categories/rules (daily) and learned map (weekly)
  MainActivity.kt, MainViewModel.kt  Compose UI: Activity / Rules / Settings tabs
```

## TBC notification samples (English, real format; amounts, card digits and names anonymized)
The notification title is "TBC"; the app label is "TBC Bank". The package name is **not confirmed**:
the app auto-detects it from the first parsable notification titled "TBC", and it can be set in
Settings → Advanced.

Purchase:
```
42.10 GEL
TBC CARD VISA GOLD (***1234)
Shopsy 29/09/26 23:28
Balance: 1500.00 GEL
```
Purchase with Ertguli cashback lines (these are ignored, and cashback is not recorded):
```
12.00 GEL
TBC CARD VISA GOLD (***1234)
CITY GYM 29/09/26 22:15
Balance: 1542.10 GEL
You’ve received: 0.05 GEL
In Ertguli Piggy bank you have: 25.30 GEL
```
Incoming money, with and without a sender line:
```
Deposit Money: 20.00 GEL
TBC CARD
29/09/2026
John Smith
```
The parser has not seen foreign-currency purchases, refunds, reversals, ATM withdrawals or
outgoing transfers yet. Anything it can't read is stored as UNPARSED and shown for review.

## Wallet REST API facts (verified from the docs)
- Base URL `https://rest.budgetbakers.com/wallet`, paths `/v1/api/...`. Header `Authorization: Bearer <token>`.
- Tokens are created in Wallet web → Settings → REST API. **Requires the Premium plan.**
- Rate limit: about 300 requests/hour, shared with MCP. A 429 response carries `Retry-After`.
  Keep traffic low; sustained abuse can get the token revoked.
- A new token returns `409 {"error":"init_sync_in_progress"}` until the first sync finishes.
- `POST /v1/api/records` takes an array of up to 20 records. Required fields: `accountId`,
  `amount.value` (**negative = expense, positive = income**, never zero), `recordDate` (ISO-8601,
  no more than 24 hours in the future). Optional: `categoryId`, `counterParty` (≤255), `note` (≤255),
  `labelIds`, `recordState`, `place`, `transfer`.
  - `amount.currencyCode` is optional but **must match the account's currency**. This is why each
    currency has to map to its own account.
  - Omitting `categoryId` makes Wallet default the record to Unknown (agent hint
    `category.defaulted_unknown`). **Wallet does not apply its record rules to API-created records**,
    so the app applies them itself.
  - The response is a batch envelope with `results[]` (`inputIndex`, `id`, `success`, `errorType`
    of client_error or server_error). Status is 200, 207 (mixed), or 400/500 (all failed).
    Batches are not atomic.
- `GET /v1/api/records`: filters `recordDate=gte.YYYY-MM-DD` (default window 3 months),
  `categoryId=unknown` (expands to the 3 unknown categories), `counterParty=contains-i.x`,
  `source=rest`. `limit` is at most 200; paginate with `offset`/`nextOffset`.
  Each record has `category {id,name}`, `counterParty`, `note` and `source`.
- `GET /v1/api/record-rules` returns `recordRules[]` with `keywords[]`, `category {id,name}` and
  `fromAccountId`.
- `GET /v1/api/categories` returns `categories[]` with `id`, `name`, `parentName`, `archived`, `enabled`.
- `GET /v1/api/accounts` returns `accounts[]` with `id`, `name`, `currencyCode`, `isBankSync`, `archived`.
- `PATCH /v1/api/records` takes up to 10 items, and `$clear` resets fields.
- OpenAPI JSON: `https://rest.budgetbakers.com/wallet/openapi`. Reference: `/wallet/reference`.

## Design decisions and why
- **Dedup key.** Purchases use content only (amount, merchant, card, date, time, balance-after),
  so a re-posted notification is ignored. Deposits have no time, so the key also includes the
  notification key and `when`.
- **Time zone.** Notification times are parsed as `Asia/Tbilisi` and sent as UTC. A deposit
  (date only) uses the time the notification arrived if it's the same day, otherwise noon.
- **Learning.** A record the app created whose category the owner left unchanged is ignored, so the
  app doesn't reinforce its own guesses. A record whose category the owner changed counts ×3. A
  learned category is used only when it has at least 2 votes and at least 60% of the payee's votes.
  Payee key = uppercase with digits and punctuation removed.
- **Accounts.** Use manual Wallet accounts. If a mapped account has `isBankSync = true`, Settings
  shows a warning, because every transaction would be duplicated.
- **Scope.** Personal use only; it will not be published to Play (notification-access and
  financial-data review would apply).

## Known gaps and ideas
- **Possible duplicate on retry.** If the network drops after Wallet commits but before the
  response arrives, the retry creates a duplicate. Fix: before re-sending, query
  `GET /records?source=rest&amount=eq.X&recordDate=...` for a match.
- **No editing.** A category can't be changed from the app after sending. It could offer
  "Change category" through PATCH, and feed that into the learned map.
- **Unsupported formats.** Georgian-language notifications, refunds, reversals and ATM withdrawals.
  Collect samples from the UNPARSED items.
- **Fake notifications.** The Activity tab's Fake button runs typed-in text through the same
  ingest path and tags the Wallet record with a label named "fake" (`GET /v1/api/labels`, or
  `POST /v1/api/labels` if missing) plus "[fake]" in the note. Label endpoints weren't checked
  against the OpenAPI spec; if they fail, only the note marks it. Fake items are never deduplicated.
- **Launcher icon.** Only an adaptive icon exists (minSdk 26, so that's enough).
- **Owner preference.** Before doing anything on the owner's behalf that can't be fully done,
  say so upfront and let them decide.

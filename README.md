# TBC → Wallet

Reads TBC Bank push notifications on your phone and creates the matching records in
Wallet by BudgetBakers through its REST API.

## Setup
1. Open this folder in Android Studio (Ladybug or newer). If it asks for a Gradle wrapper,
   accept its suggestion (Gradle 8.11+). Accept any dependency-upgrade suggestions too.
2. Run on your phone (USB debugging) or `Build → Build APK` and install it.
3. In the app → Settings:
   - Grant **Notification access**.
   - Paste your Wallet API token (Wallet web → Settings → REST API; needs Premium).
   - Pick a Wallet account for each currency. Use manual accounts, not bank-synced ones,
     or every transaction appears twice.
4. Make a card payment. It shows up in Activity and a few seconds later in Wallet.

## How it works
- `parse/TbcNotificationParser` – reads the purchase and "Deposit Money" formats.
  Ertguli cashback lines are ignored. Unknown formats are kept as "Unreadable" for review.
- `categorize/CategoryResolver` – your rules → Wallet's record rules → learned from your
  last 12 months in Wallet → none (Wallet files it as Unknown).
- `sync/SyncWorker` – sends queued records (up to 20 per request), retries offline,
  and runs every 6 hours as a safety net.
- The token is encrypted with an Android Keystore key; nothing is backed up to the cloud.

## Tests
`./gradlew test` runs the parser and categorization tests with your real notification samples.

## Known limits
- Only the English notification text is supported. If TBC changes wording or you switch the
  TBC app to Georgian, those notifications land in "Unreadable" — send me a sample.
- Ertguli cashback isn't recorded.
- Wallet's API allows ~300 requests/hour; the app uses a handful per day.

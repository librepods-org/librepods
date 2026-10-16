# Find My network integration

This document defines the implementation boundary for locating an Apple-account-owned AirPods
accessory through Apple's crowd-sourced Find My network. It is separate from nearby BLE finding:
the AirPods do not provide their offline-finding master secret over AACP.

## Required account stack

The network path starts with the Apple account that owns the AirPods. A complete client needs all of
the following components; a rotating-key implementation alone is not sufficient:

1. Apple account sign-in, two-factor authentication, and acceptance of the iCloud/MobileMe terms.
2. Anisette headers and an Apple device/OS configuration for authenticated requests.
3. Apple Push Service registration for Find My topics.
4. MobileMe tokens, a CloudKit client, and the account's encrypted iCloud keychain.
5. Encrypted, persistent client state for the BeaconStore change token and key alignment.

The existing OpenBubbles `rustpush` implementation provides this complete stack and is the source
candidate for Android integration. Reimplementing only its HTTP calls would omit keychain and state
invariants and must not be treated as equivalent.

## Ordered location flow

After account setup, the client performs this sequence:

1. Open the private `BeaconStore` CloudKit zone for `com.apple.icloud.searchparty`.
2. Resolve that zone's encryption configuration through the account keychain.
3. Fetch changes using the stored change token; on a server-requested reset, clear the token and
   local accessory/share state, then fetch again.
4. Decrypt and join each master-beacon, naming, and key-alignment record into an accessory.
5. Derive the accessory's rotating P-224 keys for the requested time window.
6. Hash the public-key X coordinates into the identifiers accepted by the Search Party fetch API.
7. Fetch encrypted location reports from `findmyservice/v2/fetch`.
8. Derive each report key with ECDH, decrypt the AES-GCM payload, and select the newest report.
9. Persist the newest report and updated key alignment before presenting it to the user.

## Android integration shape

The Rust library should expose a small Android-facing boundary instead of leaking CloudKit or
cryptographic types into Kotlin:

- begin/continue/cancel account setup, including two-factor and terms-required states;
- restore or sign out of an encrypted account session;
- list owned AirPods accessories;
- refresh locations and return latitude, longitude, accuracy, confidence, and timestamp;
- report structured authentication, network, keychain, and no-report errors.

Credentials, decrypted beacon secrets, and raw reports must remain in encrypted app-private storage.
The UI should distinguish a stale last-known report from a live nearby BLE observation and must not
claim that signing in adds an accessory to the network; this path locates accessories already owned
by that Apple account.

## Upstream source boundary

The source candidate is OpenBubbles' `rustpush` Find My and iCloud stack. Before importing it, the PR
must preserve its license notices, identify the exact upstream revision, and keep updates reviewable.
The build must compile the Rust sources for every Android ABI shipped by LibrePods; prebuilt opaque
libraries are not an acceptable substitute.

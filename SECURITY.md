# FloatClip security model

FloatClip treats clipboard content as sensitive user data. The default design is local-first and fail-closed: no clipboard content is uploaded unless a future sync feature is explicitly enabled by the user.

## Local clipboard vault

The primary clipboard history is stored in the app-private directory and encrypted with AES-GCM using a key protected by Android Keystore. The previous plaintext SharedPreferences representation is migrated into the encrypted vault and removed after migration.

App updates preserve the private vault. Android package uninstall removes the app-private directory, so uninstall survival is provided separately by the portable backup format rather than by pretending app-private storage survives uninstall.

## Portable backup

A user may select a Storage Access Framework directory. FloatClip writes an encrypted `FloatClip.vault` envelope there only after the directory has been explicitly trusted. Re-selecting a directory after reinstall starts in write-protected mode so an existing backup cannot be overwritten by a fresh empty installation before it is authenticated and restored.

The portable envelope currently uses AES-256-GCM with PBKDF2-HMAC-SHA256, a per-file random salt, a random 96-bit GCM IV, authenticated format metadata and a user-selected six-digit PIN. A six-digit PIN has limited entropy and therefore should be viewed as convenient portable-backup protection, not as the final cryptographic identity for multi-device synchronization. A future cross-platform sync design should use a randomly generated high-entropy master key and wrap that key with user credentials / recovery material instead of using the six-digit PIN as the sole synchronization secret.

## Sync endpoint

FloatClip does not embed a private synchronization hostname. The endpoint field is empty by default and accepts only an HTTPS URL entered by the user; URLs containing embedded username/password credentials are rejected.

Version 0.5.0 only stores the endpoint and reserves an encrypted envelope format. It does not automatically upload clipboard data. Any future sync protocol must preserve the following rule: encryption and decryption happen on trusted clients, while the relay server stores/transports ciphertext only and does not receive plaintext clipboard entries or the client decryption key.

## Internal Android IPC

Runtime appearance refresh uses an app-defined signature-protected permission. The dynamic receiver is not intentionally exposed as an unauthenticated control surface.

The AccessibilityService is limited to the one-tap paste path and explicitly avoids password fields. The overlay remains a normal `TYPE_APPLICATION_OVERLAY`; FloatClip does not request signature-only OEM permissions or inject into SystemUI in the standard APK.

## OriginOS integration

OriginOS integration is ROM-locked and fail-closed. The semantic-resource bridge activates only when the expected device fingerprint, OEM package version and OEM APK hash all match. The normal standalone renderer remains the recovery path.

No proprietary vivo artwork is copied into the APK, and the standard application does not call private vivo AIDL services.

## Public repository hygiene

Private deployment hostnames, private addresses, credentials, tokens and analysis captures must not be committed. Public documentation uses generic descriptions such as “user-configured HTTPS sync endpoint”. Hiding infrastructure names is only defense-in-depth; authentication, TLS, least privilege, rate limits and ciphertext-only server handling remain the actual security controls.

## Release signing

GitHub-hosted CI APKs are compile/test artifacts only because runner debug signing identities are ephemeral. Public install/upgrade releases use the preverified stable-signature APK produced by the controlled Orange Pi build path. Release automation fails closed when that stable asset or its checksum is missing.

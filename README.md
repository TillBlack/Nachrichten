# Nachrichten

End-to-end encrypted messenger for Android without an own server.

- Messages are encrypted with **NIP-44** (ChaCha20 + HMAC-SHA256, keys from secp256k1 ECDH) and signed with **BIP-340 Schnorr** signatures.
- They travel through public **Nostr relays**. Relays only see encrypted text, but they can see who writes to whom and when.
- There is no account and no phone number. Your identity is a key pair created on the phone. The private key is stored encrypted with the Android Keystore.
- Contacts are added via QR code or a text code (`npub1…`).

Languages: English, Deutsch, Español, Русский, Hrvatski.

## Build

The APK is built by GitHub Actions on every push to `main`. The signing key `app/nachrichten.p12` is protected by a password that is stored only in the repository secret `KEYSTORE_PASSWORD`.

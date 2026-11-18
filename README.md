# Personal PDF Vault (পার্সোনাল পিডিএফ ভল্ট)

A zero-plaintext-cloud, cryptographically encrypted Personal Cloud PDF Vault Android application backed by **GitHub Releases** and **client-side AES-256-GCM + Argon2id** encryption.

---

## 1. Security & Cryptographic Architecture

- **Master Password KDF:** Argon2id (`19 MiB` OWASP minimum or `32 MiB` preset via BouncyCastle RFC 9106) with `PBKDF2-HMAC-SHA256` (`600,000+` iterations) option.
- **Vault Master Key (VMK):** Random 256-bit key generated during vault creation. Wrapped with the KDF-derived key (`AES-256-GCM`) and stored in `vault-config-vNNNN.json`. Plaintext Master Passwords and unwrapped VMKs are never persisted to disk or GitHub.
- **Per-PDF Data Encryption Keys (DEKs):** Each PDF gets a unique random 256-bit DEK wrapped with the VMK inside its encrypted `.pvmeta` sidecar.
- **Chunked File Encryption (`.pvault`):** Source PDFs are validated (`%PDF-` signature + SHA-256) and encrypted in 4 MiB chunks with a random 64-bit nonce prefix + 32-bit chunk counter and AAD binding (`PVAULT:1:<vaultId>:<assetId>:<chunkIndex>`).
- **Recovery Key System:** Random 256-bit Recovery Key wraps a separate copy of the VMK in `recoveryEnvelope`. Shown once on creation/rotation and never stored on GitHub or local storage.

---

## 2. GitHub Repository & Release Setup Guide

1. Create a public GitHub repository (e.g., `personal-pdf-vault`).
2. Control Release tag: `vault-control` — holds versioned `vault-config-v0001.json`, `vault-config-v0002.json`, etc.
3. Data Release tags: `pdf-vault-001`, `pdf-vault-002`, ... — hold paired opaque assets `asset_<hex>.pvault` and `meta_<hex>.pvmeta`.
4. Never commit or upload original unencrypted PDFs, plaintext Master Passwords, Master Keys, Recovery Keys, or Personal Access Tokens.

---

## 3. Fine-Grained Personal Access Token (PAT) Guide

- Public reading and unlocking require **no token**.
- Direct uploads, metadata updates, and cloud deletions require an owner **Fine-Grained PAT**:
  1. Open **GitHub Settings → Developer settings → Personal access tokens → Fine-grained tokens**.
  2. Restrict repository access to **Only select repositories** → select your vault repository.
  3. Grant **Repository permissions → Contents: Read and write**.
  4. Enter the token in **Security Settings** inside the app. It stays strictly in RAM for the active session.
- Alternatively, use **Method B (Prepare Encrypted Files)** to encrypt locally without a token and upload `.pvault` + `.pvmeta` via the GitHub web UI.

---

## 4. Recovery Key & Threat Model Notes

- Keep your 256-bit Recovery Key offline (printed or in an offline password manager).
- If both Master Password and Recovery Key are lost, encrypted PDFs cannot be decrypted.
- Anyone can download public GitHub Release assets, so always use a strong, high-entropy Master Passphrase.
- Password changes or key rotations cannot remotely revoke already-downloaded plaintext PDFs or previously copied encrypted files.

---

## 5. Troubleshooting

- **401 / 403 Errors:** Verify your Fine-Grained PAT is active and scoped to `Contents: Read and write` on the configured repository.
- **Incomplete Asset Pair Warning:** Ensure both `asset_<id>.pvault` and `meta_<id>.pvmeta` exist in the Release. If `.pvmeta` was deleted, restore it from **Sync & Diagnostics** if present in local encrypted cache.
- **Auth Tag Verification Failure:** Indicates either an incorrect Master Password/Recovery Key or a corrupted/tampered encrypted asset.

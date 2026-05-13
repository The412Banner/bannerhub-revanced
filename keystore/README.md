# BannerHub ReVanced — Stable Release Keystore

This directory holds `bannerhub.keystore`, the **public test keystore** used to sign every release of bannerhub-revanced from `v1.1.0-604` onward. It is intentionally committed to the repository — including the passwords below — so anyone can reproduce identical signing without coordinating secrets, and so users get an OTA-style update flow between stables (Android only allows in-place updates when the signing certificate matches).

## Why a public test key?

This is not a security boundary. The threat model is: "someone replaces our release with a malicious one and tricks users into installing it." Signing the legitimate release with a publicly-known key doesn't protect against that — an attacker can sign their replacement with the same key. **Trust the GitHub release page**, not the cert.

What the stable cert *does* give you: a consistent installer identity, so Android will install new BannerHub releases on top of old ones without `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. That's the whole goal.

## Keystore details

| Field | Value |
| --- | --- |
| File | `keystore/bannerhub.keystore` |
| Type | JKS (Java KeyStore) |
| Alias | `bannerhub` |
| Store password | `bannerhub` |
| Key password | `bannerhub` |
| Key algorithm | RSA 2048 |
| Signature algorithm | SHA384withRSA |
| DN | `CN=BannerHub, OU=ReVanced, O=The412Banner, C=US` |
| Validity | 100 years (until 2126-04-19) |

## Certificate fingerprints

Verify before installing any release:

- **SHA-1**: `1F:51:B2:5E:5C:9F:58:08:E0:CF:45:17:4F:CC:B3:8D:67:CA:6D:E5`
- **SHA-256**: `10:89:5A:31:1F:E0:4F:95:F8:2E:4D:A5:C9:A6:C0:41:BA:92:82:BF:21:1F:1B:57:8F:E1:CB:EB:89:4C:E0:BA`
- **Serial**: `5ee03b1e340fd1ac`

Every CI release run prints these via `apksigner verify --print-certs` so you can cross-check the build logs against this file.

To verify locally:

```bash
keytool -list -v -keystore keystore/bannerhub.keystore -storepass bannerhub
apksigner verify --print-certs BannerHub-V6-<version>-Patched-<variant>.apk
```

The cert SHA-256 from `apksigner verify` should match the SHA-256 above byte-for-byte.

## How it was generated

```bash
keytool -genkeypair -v \
  -keystore keystore/bannerhub.keystore \
  -alias bannerhub \
  -keyalg RSA -keysize 2048 \
  -validity 36500 \
  -storepass bannerhub -keypass bannerhub \
  -dname "CN=BannerHub, OU=ReVanced, O=The412Banner, C=US"
```

Generated 2026-05-13. Never rotate — rotation would force every existing user to uninstall and reinstall to switch certs.

## CI usage

`.github/workflows/release.yml` invokes `apksigner sign` against each variant APK after revanced-cli's output and before artifact upload, with signature schemes:

- v1 (JAR signing): **enabled** — Android 6.0 and earlier
- v2 (APK Signature Scheme v2): **enabled** — Android 7.0+
- v3 (APK Signature Scheme v3): **enabled** — Android 9.0+ (supports rotation, though we never plan to)
- v4: **disabled** — requires a sidecar `.idsig` file that we don't distribute

Followed by `apksigner verify --print-certs` so CI logs surface the cert SHA-256 every run.

## One-time migration from old ephemeral-key builds

Releases up to and including `v1.0.0-604` were signed with revanced-cli's per-run ephemeral keystore (a fresh debug key generated inside each CI run, then discarded). Those builds cannot be in-place upgraded to releases signed with this keystore — Android refuses with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.

**To switch over**: uninstall your current `v1.0.0-604` BannerHub-ReVanced variant once, then install the new build. From that point forward, every stable signed with this keystore updates in place — no further uninstalls.

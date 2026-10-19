# Security Policy

## Supported Versions

| Version | Supported          |
| ------- | ------------------ |
| 3.5.x   | :white_check_mark: |
| < 3.5   | :x:                |

---

## Security Architecture & Design Principles

FastShare is designed specifically for **local area network (LAN) and peer-to-peer (P2P)** high-speed file sharing without intermediary cloud relays. Security is enforced through multiple interlocking layers:

1. **Confined Path Traversal Protection**:
   - All file and directory operations pass through canonicalization (`realpath` / `abspath`).
   - Paths must reside strictly within detected root drives or explicitly authorized user directories.
   - Any path traversal attacks (`../`, `%2e%2e/`, absolute escaped drive letters) are rejected with HTTP 400/403.

2. **Timing-Attack Resistant Authentication**:
   - Password verification utilizes constant-time comparison (`hmac.compare_digest`).
   - Session tokens are generated using cryptographically strong pseudo-random number generators (`secrets.token_urlsafe(32)` / OS CSPRNG).

3. **Android Application Sandboxing**:
   - Internal activities (`RemoteBrowserActivity`) are configured with `android:exported="false"` to prevent unauthorized third-party apps on the device from launching internal intents or injecting remote endpoints.
   - File sharing relies on secure Android `FileProvider` with content URIs and explicit read permissions.

4. **Network Exposure Guard**:
   - The web server binds to local interfaces and LAN subnets.
   - Credentials are saved locally with user-only file permissions and are strictly excluded from git tracking (`.fastshare_cred.json`).

---

## Reporting a Vulnerability

If you discover a potential security vulnerability in FastShare, please do not file a public GitHub issue. Instead, report it privately via GitHub Security Advisories or email the maintainer.

We will review your submission and provide an assessment within 48 hours.

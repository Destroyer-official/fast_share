# Changelog

All notable changes to the FastShare project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [3.5.0] - 2026-10-19

### Added
- **Apple Liquid Glass + Material 3 Expressive UI**: Complete redesign of web and Android interfaces with real-time blur, specular edge highlights, spring physics, and dynamic cosmic aurora gradient backdrops.
- **Autonomous Gradle Wrapper (`gradlew`, `gradlew.bat`)**: Out-of-the-box zero-dependency build setup for Android APK compilation.
- **CLI Options**: Added `--help` (`-h`), `--version` (`-v`), and `--no-browser` (`-n`) flags to both Python (`share.py`) and Rust (`fastshare_rs`) runners.
- **Comprehensive Test Suite (`tests/test_fastshare.py`)**: 10 integration tests covering full lifecycle, file streaming, SHA-256 byte verification, directory CRUD, streaming zip, and token authentication.
- **GitHub Actions CI/CD Pipeline**: Multi-platform automated testing and release builds for Windows, Linux, macOS, and Android.
- **Android Localization Support**: Added standard `strings.xml` resource catalog for app titles and messages.

### Changed
- **Android Security Hardening**: Set `android:exported="false"` on `RemoteBrowserActivity` to prevent third-party apps from launching internal activities.
- **Repository Optimization**: Excluded temporary test screenshots, build artifacts, test dumps, and credentials (`.fastshare_cred.json`) via updated production `.gitignore`.
- **Rust Engine Enhancements**: Upgraded socket receive and send buffers to 4 MB for RFC 1323 TCP window scaling on gigabit LAN networks.

---

## [3.0.0] - 2026-09-15

### Added
- Ecosystem Radar (UDP 8002 auto-discovery protocol) for zero-config LAN peer discovery between Android and PC.
- In-page Liquid Glass modal authentication eliminating intrusive browser-native 401 popups.
- Direct raw streaming parallel uploads via `/api/raw` with atomic `.part` staging.
- Streaming STORED ZIP generator for instantaneous multi-gigabyte directory downloads without disk buffering.

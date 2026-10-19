# Contributing to FastShare

Thank you for your interest in contributing to **FastShare**! We welcome contributions to our Android app, Rust engine, Python implementation, and Web client.

---

## 1. Development Setup

### Prerequisites
- **Python**: 3.10+
- **Rust**: 1.75+ with Cargo
- **Android SDK**: API 34+ with JDK 17
- **Git**

### Clone the Repository
```bash
git clone https://github.com/Destroyer-official/fast_share.git
cd fast_share
```

---

## 2. Running and Testing Components

### Python Server
```bash
# Run server
python share.py [port] [--user USERNAME] [--password PASSWORD] [--no-browser]

# Run automated integration tests
python -m unittest tests/test_fastshare.py
```

### Rust Core Server
```bash
# Check and compile
cargo check --manifest-path fastshare_rs/Cargo.toml
cargo build --release --manifest-path fastshare_rs/Cargo.toml

# Run binary
./fastshare_rs/target/release/fastshare_rs [port]
```

### Android Application
```bash
cd android

# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Run lint checks
./gradlew lint
```

---

## 3. Pull Request Guidelines

1. **Branch Naming**: Use descriptive branch names: `feature/name`, `fix/issue-description`.
2. **Code Style**:
   - Python: Follow PEP 8 guidelines.
   - Rust: Format code with `cargo fmt` and verify with `cargo clippy`.
   - Android: Follow official Kotlin/Java Android architecture best practices.
3. **Tests**: Ensure `python -m unittest tests/test_fastshare.py` passes with 0 failures before opening a pull request.
4. **Documentation**: Update the README or API docs if you modify or add any API endpoints.

---

## 4. Code of Conduct

Please be respectful, collaborative, and constructive when opening issues, reviewing PRs, and participating in discussions.

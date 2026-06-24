# Contributing to LibrePods

Thank you for your interest in contributing to LibrePods! This project aims to liberate AirPods from Apple's ecosystem, and we welcome contributions of all kinds.

## Table of Contents

- [Code of Conduct](#code-of-conduct)
- [How Can I Contribute?](#how-can-i-contribute)
- [Development Setup](#development-setup)
- [Making Changes](#making-changes)
- [Submitting Changes](#submitting-changes)
- [Style Guidelines](#style-guidelines)
- [Additional Resources](#additional-resources)

## Code of Conduct

This project adheres to a code of conduct. By participating, you are expected to uphold this code. Please be respectful and constructive in all interactions.

## How Can I Contribute?

### Reporting Bugs

Before creating bug reports, please check existing issues. When creating a bug report, include:

- **Device Information**: Phone model, OS version, AirPods model
- **LibrePods Version**: Found in app settings
- **Root Status** (Android): LSPosed version if applicable
- **Steps to Reproduce**: Clear, numbered steps
- **Expected vs Actual Behavior**
- **Logs**: Use the troubleshooting feature in the app

### Suggesting Features

Feature requests are welcome! Please:
- Check if the feature has already been requested
- Explain the use case and expected behavior
- Consider if it aligns with the project's goals

### Code Contributions

All code contributions follow the standard GitHub workflow:
1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Submit a pull request

See [Making Changes](#making-changes) for detailed instructions.

### Documentation

Improvements to documentation are always welcome, including:
- README updates
- Protocol documentation
- Setup guides
- Code comments

### Translations

Help make LibrePods accessible to more users by contributing translations. See [Translation Guide](#translation-guide) for details.

## Development Setup

### Prerequisites

Choose the platform you want to contribute to:

#### Android Development

**Requirements:**
- Android Studio Ladybug (2024.2.1+)
- JDK 17+
- Android SDK: API 33 (min) and API 36 (target)
- Root access with Xposed Framework (LSPosed recommended)

**Note**: Root is required due to an [Android Bluetooth stack limitation](https://issuetracker.google.com/issues/371713238). Exception: ColorOS/OxygenOS 16 users.

#### Linux Development

**Requirements:**
- Qt 6 (base, connectivity, multimedia)
- CMake 3.22+
- OpenSSL development headers
- libpulse development headers
- C++17 compatible compiler

<details>
<summary>Installation commands for different distributions</summary>

**Arch Linux / EndeavourOS:**
```bash
sudo pacman -S qt6-base qt6-connectivity qt6-multimedia cmake openssl libpulse
```

**Debian / Ubuntu:**
```bash
sudo apt-get install qt6-base-dev qt6-declarative-dev qt6-connectivity-dev \
    qt6-multimedia-dev cmake libssl-dev libpulse-dev \
    qml6-module-qtquick-controls qml6-module-qtquick-layouts
```

**Fedora:**
```bash
sudo dnf install qt6-qtbase-devel qt6-qtconnectivity-devel \
    qt6-qtmultimedia-devel qt6-qtdeclarative-devel cmake \
    openssl-devel pulseaudio-libs-devel
```
</details>

### Building the Project

#### Android

```bash
git clone https://github.com/YOUR_USERNAME/librepods.git
cd librepods/android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

#### Linux

```bash
git clone https://github.com/YOUR_USERNAME/librepods.git
cd librepods/linux
mkdir build && cd build
cmake ..
make -j $(nproc)
./librepods
```

For troubleshooting, see [linux/README.md](linux/README.md).

## Making Changes

### 1. Fork and Clone

Fork the repository on GitHub, then:

```bash
git clone https://github.com/YOUR_USERNAME/librepods.git
cd librepods
git remote add upstream https://github.com/kavishdevar/librepods.git
```

### 2. Create a Branch

```bash
git checkout -b feat/your-feature-name
# or
git checkout -b fix/bug-description
```

### 3. Make Your Changes

- Write clear, readable code
- Follow the [style guidelines](#style-guidelines)
- Add comments for complex logic
- Update documentation if needed

### 4. Test Your Changes

**Android:**
- Build and install the APK
- Test on a physical device with AirPods
- Verify no regressions in existing features

**Linux:**
- Build and run the application
- Test core functionality
- Verify system tray integration

### 5. Commit Your Changes

```bash
git add .
git commit -m "type(scope): description"
```

See [Commit Message Guidelines](#commit-messages) for format details.

## Submitting Changes

### Pull Request Process

1. **Push to your fork:**
   ```bash
   git push origin your-branch-name
   ```

2. **Create a Pull Request** on GitHub with:
   - **Clear title**: `type(scope): Brief description`
   - **Description** including:
     - What changed and why
     - Related issue number (closes #123)
     - Testing performed
     - Screenshots for UI changes

3. **Wait for review** (Note: Maintainer on hiatus until May 2026)

4. **Address feedback** if requested

### Pull Request Checklist

- [ ] Code builds without errors
- [ ] Tested on target platform
- [ ] Follows style guidelines
- [ ] Documentation updated (if applicable)
- [ ] Commit messages follow convention
- [ ] PR description is clear and complete

## Style Guidelines

### Commit Messages

Follow [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<scope>): <description>

[optional body]

[optional footer]
```

**Types:**
- `feat`: New feature
- `fix`: Bug fix
- `docs`: Documentation changes
- `style`: Code style changes (formatting)
- `refactor`: Code refactoring
- `test`: Adding tests
- `chore`: Maintenance tasks

**Examples:**
```
feat(android): add German translation
fix(linux): resolve system tray detection
docs: update development setup guide
```

### Code Style

#### Kotlin (Android)

- Follow [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html)
- 4 spaces for indentation
- Meaningful variable and function names
- Use `camelCase` for variables/functions, `PascalCase` for classes

#### C++ (Linux)

- Follow [Qt Coding Style](https://wiki.qt.io/Qt_Coding_Style)
- Use `camelCase` for variables/functions
- Use `PascalCase` for classes
- Include proper header guards
- Use C++17 features where appropriate

## Additional Resources

### Documentation

- **Protocol Specifications**: [AAP Definitions.md](AAP%20Definitions.md)
- **Control Commands**: [docs/control_commands.md](docs/control_commands.md)
- **Linux Setup**: [linux/README.md](linux/README.md)

### External Links

- [AAP Protocol (Third-party)](https://github.com/tyalie/AAP-Protocol-Defintion)
- [Android Bluetooth Bug](https://issuetracker.google.com/issues/371713238) - Please upvote!

### Community

- **Discussions**: [GitHub Discussions](https://github.com/kavishdevar/librepods/discussions)
- **Issues**: [GitHub Issues](https://github.com/kavishdevar/librepods/issues)

---

## Translation Guide

Translations help make LibrePods accessible to users worldwide. Currently supported: 10 languages (Android), 3 languages (Linux).

### Android Translations

1. Create translation directory:
   ```bash
   mkdir -p android/app/src/main/res/values-{LANG_CODE}
   ```

2. Copy base strings file:
   ```bash
   cp android/app/src/main/res/values/strings.xml \
      android/app/src/main/res/values-{LANG_CODE}/
   ```

3. Translate all `<string>` tags while preserving:
   - Placeholders: `%1$s`, `%d`
   - XML entities: `\'` for apostrophes
   - Technical terms from Apple's official translations

4. Test:
   ```bash
   cd android
   ./gradlew assembleDebug
   adb shell "setprop persist.sys.locale {LANG_CODE}; setprop ctl.restart zygote"
   ```

### Linux Translations

1. Copy template:
   ```bash
   cd linux/translations
   cp librepods_tr.ts librepods_{LANG_CODE}.ts
   ```

2. Edit with Qt Linguist (recommended) or manually:
   ```xml
   <message>
       <source>English text</source>
       <translation>Translated text</translation>
   </message>
   ```

3. Test:
   ```bash
   mkdir build && cd build
   cmake .. && make
   LANG={LANG_CODE}.UTF-8 ./librepods
   ```

### Needed Languages

High-priority: German, Japanese, Korean, Hindi, Arabic, Russian

---

## License

By contributing, you agree that your contributions will be licensed under the GNU GPL v3.0 License.

**Thank you for contributing to LibrePods!** 🎉


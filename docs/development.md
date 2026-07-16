# Development Guide

## Deterministic Build Setup

This project uses a reproducible build system across macOS/Linux local dev and CI/CD.

### Local Development

#### Prerequisites

The project is configured to use **Gradle Toolchains** with **BellSoft Liberica JDK 25** for build-time determinism.

##### Option 1: Using mise (recommended)

[mise](https://mise.jdx.dev/) is a modern version manager that reads `.tool-versions` for Java versioning.

```bash
# Install mise (one-time)
curl https://mise.jdx.dev/install.sh | sh

# Then, in the repo:
mise install
```

This sets `JAVA_HOME` correctly for your terminal, Gradle, and IntelliJ.

##### Option 2: Using asdf

[asdf](https://asdf-vm.com/) is a general version manager.

```bash
# Install asdf (one-time)
git clone https://github.com/asdf-vm/asdf.git ~/.asdf
cd ~/.asdf && git checkout "$(git describe --abbrev=0 --tags)"

# Add the Java plugin
asdf plugin add java https://github.com/halcyon/asdf-java.git

# In the repo:
asdf install
```

#### How It Works

1. **`.tool-versions` file** pins `java liberica-25.0.3+11` using asdf's whitespace-delimited syntax.
2. **Gradle Wrapper** (`./gradlew`) pins Gradle 9.2.1.
3. **Gradle Toolchains** auto-downloads BellSoft Liberica JDK 25 if missing (enabled by Foojay resolver in `settings.gradle.kts`).
4. **Result**: Consistent Java version across:
   - Shell commands (`./gradlew build`, `java -version`)
   - IntelliJ "Gradle JVM" setting
   - IntelliJ "Project SDK" setting

#### Verifying Setup

```bash
# Check Java version (should be BellSoft Liberica 25.0.3)
java -version

# Verify Gradle uses correct toolchain
./gradlew --version

# Build (auto-downloads JDK if needed)
make build
```

#### Git Hooks (lefthook)

This repo uses [lefthook](https://github.com/evilmartians/lefthook) for git hook management.
Config lives at `.config/lefthook.yml`. Pre-push hooks run lint and build+test gates.
Hooks are local, so each developer installs once:

```bash
brew install lefthook   # one-time
make hooks              # installs hook shims into .git/hooks/
```

### CI/CD (GitHub Actions)

The `.github/workflows/build.yml` workflow:

- Runs on **ubuntu-24.04** (pinned, not `-latest`)
- Uses `actions/setup-java@v5` with `distribution: liberica` + `java-version-file: .tool-versions`
- Logs Java, Gradle, and OS versions for drift detection
- Uploads test/lint reports on failure

**Key insight**: CI uses the same JDK vendor as local dev, but may differ in patch version. See [JDK Patch Versioning](#jdk-patch-versioning) below.

### Gradle Configuration

#### `settings.gradle.kts` (Foojay Resolver)

```kotlin
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
```

This enables **auto-download** of BellSoft Liberica JDK if missing. Without it, Gradle fails to find the toolchain.

#### `build.gradle.kts` (Toolchain Vendor)

```kotlin
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
        vendor = JvmVendorSpec.BELLSOFT  // Explicitly specify BellSoft Liberica
    }
}
```

The explicit vendor removes ambiguity: Gradle will prefer BellSoft Liberica JDK 25 over other vendors.

#### `gradle.properties` (Daemon & Caching)

```properties
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.daemon=true
org.gradle.jvmargs=-Xmx2g -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8
```

Enables:
- Parallel task execution
- Incremental builds (faster rebuilds)
- Gradle daemon (faster commands)
- 2GB max heap for Gradle itself

### Docker

The `Dockerfile` uses BellSoft's Debian-based Liberica JDK and JRE images, pinned to release `25.0.3-11`.

**Future optimization**: Pin images by SHA256 digest for byte-for-byte reproducibility. Trade-off: maintenance burden vs. max determinism.

---

## JDK Patch Versioning

### The Limitation

**Gradle Toolchains cannot pin patch-level JDK versions** (e.g., 25.0.3). It can only pin major version (25) + vendor (BellSoft).

Example:
- ✅ **Supported**: Java 25 + BellSoft
- ❌ **NOT supported**: Java 25.0.3 + BellSoft

### Strategy

1. **Local dev**: `.tool-versions` pins the exact BellSoft Liberica release for `mise` and `asdf`.
2. **CI/CD**: GitHub Actions reads that same pin and logs the exact Java patch version:
   ```text
   java -version
   # Output starts with: openjdk version "25.0.3"
   ```
3. **When to bump patch**: Patch updates to JDK are **intentional commits**. Never rely on automatic patch upgrades.

### Reproducing the Current Pin

Use the exact release identifiers shown below. When BellSoft publishes a newer Java 25 update, update both spellings together: mise/asdf uses `+` before the build number, while BellSoft's container tag uses `-`.

```bash
# Update local version manager
mise use --path .tool-versions java@liberica-25.0.3+11

# Update .tool-versions
cat .tool-versions
# java liberica-25.0.3+11

# Keep both Dockerfile Java images on the corresponding 25.0.3-11 tag
rg 'liberica-openj(dk|re)-debian' Dockerfile

# Verify CI picks it up
git add .tool-versions
git commit -m "Pin BellSoft Liberica JDK 25.0.3+11"
```

CI will log the new patch version, and you'll have an audit trail.

---

## Common Commands

### Local Development

```bash
# Install Java (one-time)
mise install  # or: asdf install

# Build
make build

# Test
make test

# Static analysis (SpotBugs + PMD)
make lint

# Run dev server (Spring Boot + Vite)
make dev

# Run backend only
make dev-backend

# Docker stack (Qdrant)
make compose-up
make compose-down
```

### Troubleshooting

#### "gradle: command not found"
- Run `mise install` or `asdf install` to set `JAVA_HOME`
- Verify: `echo $JAVA_HOME` should point to a BellSoft Liberica 25 JDK

#### Gradle downloads JDK every time
- Ensure `settings.gradle.kts` has Foojay resolver (added in `0.8.0`)
- Check `~/.gradle/jdks/` for cached toolchains

#### IntelliJ doesn't pick up Java version
- Open IntelliJ settings → Build, Execution, Deployment → Gradle
- Set "Gradle JVM" to "Use JAVA_HOME"
- Set "Project SDK" to BellSoft Liberica 25 (or refresh if it's auto-detected)

#### CI build fails but local works
- Check CI logs for Java version (e.g., `java -version`)
- Ensure your local patch matches: `java -version 2>&1 | grep -o '"[^"]*"'`
- If CI is on 25.0.4 and you're on 25.0.3, bump locally and re-test

---

## References

- [Gradle Toolchains](https://docs.gradle.org/current/userguide/toolchains.html)
- [Foojay Resolver Convention](https://plugins.gradle.org/plugin/org.gradle.toolchains.foojay-resolver-convention)
- [BellSoft Liberica JDK](https://bell-sw.com/libericajdk/)
- [mise version manager](https://mise.jdx.dev/)
- [asdf version manager](https://asdf-vm.com/)

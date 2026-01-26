# Development Guide

## Deterministic Build Setup

This project uses a reproducible build system across macOS/Linux local dev and CI/CD.

### Local Development

#### Prerequisites

The project is configured to use **Gradle Toolchains** with **Temurin JDK 25** for build-time determinism.

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

1. **`.tool-versions` file** pins `java = temurin 25` (or your desired version).
2. **Gradle Wrapper** (`./gradlew`) pins Gradle 9.2.1.
3. **Gradle Toolchains** auto-downloads Temurin JDK 25 if missing (enabled by Foojay resolver in `settings.gradle.kts`).
4. **Result**: Consistent Java version across:
   - Shell commands (`gradle build`, `java -version`)
   - IntelliJ "Gradle JVM" setting
   - IntelliJ "Project SDK" setting

#### Verifying Setup

```bash
# Check Java version (should be Temurin 25.x.x)
java -version

# Verify Gradle uses correct toolchain
./gradlew --version

# Build (auto-downloads JDK if needed)
make build
```

### CI/CD (GitHub Actions)

The `.github/workflows/build.yml` workflow:

- Runs on **ubuntu-24.04** (pinned, not `-latest`)
- Uses `actions/setup-java@v5` with `distribution: temurin` + `java-version: 25`
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

This enables **auto-download** of Temurin JDK if missing. Without it, Gradle fails to find the toolchain.

#### `build.gradle.kts` (Toolchain Vendor)

```kotlin
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
        vendor = JvmVendorSpec.ADOPTIUM  // Explicitly specify Temurin
    }
}
```

The explicit vendor removes ambiguity: Gradle will prefer Temurin JDK 25 over other vendors.

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

The `Dockerfile` uses `eclipse-temurin:25-jdk` (build) and `eclipse-temurin:25-jre` (runtime), sourced from `public.ecr.aws` (not Docker Hub).

**Future optimization**: Pin images by SHA256 digest for byte-for-byte reproducibility (e.g., `eclipse-temurin:25-jdk@sha256:abc...`). Trade-off: maintenance burden vs. max determinism.

---

## JDK Patch Versioning

### The Limitation

**Gradle Toolchains cannot pin patch-level JDK versions** (e.g., 25.0.3). It can only pin major version (25) + vendor (Temurin).

Example:
- ✅ **Supported**: Java 25 + Temurin
- ❌ **NOT supported**: Java 25.0.3 + Temurin

### Strategy

1. **Local dev**: Patch version determined by `mise`/`asdf` + Foojay resolver.
2. **CI/CD**: GitHub Actions logs exact Java patch version:
   ```text
   java -version
   # Output: openjdk version "25.0.3" 2024-XX-XX
   ```
3. **When to bump patch**: Patch updates to JDK are **intentional commits**. Never rely on automatic patch upgrades.

### Intentional Patch Bumping

When you want to upgrade from 25.0.3 → 25.0.4:

```bash
# Update local version manager
mise use java@temurin 25.0.4  # or asdf local java temurin-25.0.4

# Update .tool-versions
cat .tool-versions
# java = temurin 25.0.4

# Verify CI picks it up
git add .tool-versions
git commit -m "Bump JDK patch: 25.0.3 → 25.0.4"
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
- Verify: `echo $JAVA_HOME` should point to a Temurin 25 JDK

#### Gradle downloads JDK every time
- Ensure `settings.gradle.kts` has Foojay resolver (added in `0.8.0`)
- Check `~/.gradle/jdks/` for cached toolchains

#### IntelliJ doesn't pick up Java version
- Open IntelliJ settings → Build, Execution, Deployment → Gradle
- Set "Gradle JVM" to "Use JAVA_HOME"
- Set "Project SDK" to Temurin 25 (or refresh if it's auto-detected)

#### CI build fails but local works
- Check CI logs for Java version (e.g., `java -version`)
- Ensure your local patch matches: `java -version 2>&1 | grep -o '"[^"]*"'`
- If CI is on 25.0.4 and you're on 25.0.3, bump locally and re-test

---

## References

- [Gradle Toolchains](https://docs.gradle.org/current/userguide/toolchains.html)
- [Foojay Resolver Convention](https://plugins.gradle.org/plugin/org.gradle.toolchains.foojay-resolver-convention)
- [Eclipse Temurin JDK](https://adoptium.net/)
- [mise version manager](https://mise.jdx.dev/)
- [asdf version manager](https://asdf-vm.com/)

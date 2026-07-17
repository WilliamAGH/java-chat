# Development Guide

## Deterministic Build Setup

This project uses a reproducible build system across macOS/Linux local dev and CI/CD.

### Local Development

#### Prerequisites

The Gradle build requires a **BellSoft Liberica Java 25** toolchain. Exact release pinning is
owned by `.tool-versions` for local version managers and CI; Docker uses separately pinned
Liberica image tags.

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

1. **`.tool-versions` file** pins `java liberica-25.0.3+11` using asdf's whitespace-delimited syntax. `mise`/`asdf` use this exact local release, and GitHub Actions passes the same entry to `actions/setup-java`.
2. **Gradle Wrapper** (`./gradlew`) pins Gradle 9.2.1.
3. **Gradle Toolchains** constrain build tools to Java major version 25 and BellSoft. The Foojay resolver can provision a matching toolchain if needed, but Gradle does not encode the `25.0.3+11` patch/build pin.
4. **Result**: the version-manager/CI layer owns the exact JDK release, while Gradle rejects the wrong vendor or Java major for compilation, tests, and Javadoc.

#### Verifying Setup

```bash
# Check the exact local version-manager pin (BellSoft Liberica 25.0.3+11)
java -version

# List the Gradle toolchains available for the Java 25/BellSoft requirement
./gradlew -q javaToolchains

# Build (Foojay can provision a toolchain satisfying Gradle's Java 25/BellSoft constraint)
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

The `.github/workflows/build.yml` workflow runs two jobs:

**Frontend job:**
- Runs on **ubuntu-24.04**
- Uses Node.js version pinned via `frontend/.nvmrc`
- Runs: `npm ci`, `npm run validate`, `npm run test`, `npm run build`

**Build job:**
- Runs on **ubuntu-24.04** (pinned, not `-latest`)
- Uses `actions/setup-java@v5` with `distribution: liberica` + `java-version-file: .tool-versions`, which requests the exact Liberica release recorded in that file
- Logs Java, Gradle, and OS versions for drift detection
- Uploads test/lint reports on failure
- Builds the Dockerfile's pinned Liberica stages and checks runtime readiness with synthetic, non-secret smoke configuration

**Key insight**: CI's exact JDK input is `.tool-versions`; Gradle's independent toolchain
constraint enforces only the Java major and vendor. See [JDK Patch Versioning](#jdk-patch-versioning) below.

### Gradle Configuration

#### `settings.gradle.kts` (Foojay Resolver)

```kotlin
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
```

This enables Gradle to provision a toolchain that satisfies the configured Java 25/BellSoft
requirement when it is not already available. It does not turn Gradle's toolchain specification
into an exact patch/build pin.

#### `build.gradle.kts` (Toolchain Vendor)

```kotlin
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
        vendor = JvmVendorSpec.BELLSOFT  // Explicitly specify BellSoft Liberica
    }
}
```

The explicit vendor removes ambiguity: Gradle selects BellSoft Java 25 rather than another
vendor. `languageVersion` represents the Java major, so this block does not independently pin
`25.0.3+11`.

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

### The Boundary

**Gradle Toolchains cannot pin patch-level JDK versions** (for example, `25.0.3+11`).
They constrain the Java major (`25`) and vendor (`BellSoft`). The exact release is owned by the
version-manager/CI input in `.tool-versions` and by the explicit Docker image tags.

Example:
- ✅ **Supported**: Java 25 + BellSoft
- ❌ **NOT supported**: Java 25.0.3 + BellSoft

### Strategy

1. **Local dev**: `.tool-versions` pins the exact BellSoft Liberica release for `mise` and `asdf`.
2. **CI/CD**: GitHub Actions reads the same exact `java-version-file` entry; `java -version` in the workflow logs proves the installed release:
   ```text
   java -version
   # Output starts with: openjdk version "25.0.3"
   ```
3. **Gradle**: the build that follows validates only Java 25 + BellSoft; it cannot independently reject a different BellSoft 25 patch.
4. **When to bump patch**: Patch updates are intentional commits. Update `.tool-versions` and both Docker Liberica image tags together after verifying the corresponding release.

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

CI will log the resolved release, and the commit provides the audit trail.

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
- Check the CI `java -version` line against the exact Liberica entry in `.tool-versions`.
- Ensure your local release matches: `java -version 2>&1 | grep -o '"[^"]*"'`.
- Treat a mismatch as version-manager/CI pin-resolution drift; Gradle's Java 25/BellSoft constraint does not repair a patch mismatch.

---

## References

- [Gradle Toolchains](https://docs.gradle.org/current/userguide/toolchains.html)
- [Foojay Resolver Convention](https://plugins.gradle.org/plugin/org.gradle.toolchains.foojay-resolver-convention)
- [BellSoft Liberica JDK](https://bell-sw.com/libericajdk/)
- [mise version manager](https://mise.jdx.dev/)
- [asdf version manager](https://asdf-vm.com/)

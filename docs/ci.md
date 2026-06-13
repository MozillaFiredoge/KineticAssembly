# Continuous Integration

The default GitHub Actions workflow is intentionally Java-only.

```text
.github/workflows/build.yml
```

It runs:

```text
./gradlew --no-daemon build
```

This validates the NeoForge mod sources and produces a jar artifact without
requiring the local NVIDIA PhysX checkout. The `PhysX/` directory is ignored and
is expected to be supplied locally by developers who are building the native
bridge.

## Maven Publishing

The Maven publishing workflow is separate from the normal CI build:

```text
.github/workflows/publish-maven.yml
```

It runs on `v*` tags and manual dispatch. The workflow runs `./gradlew publish`
for all Stonecutter nodes, then merges the generated local `repo/` Maven layout
into the `gh-pages` branch under:

```text
maven/
```

The public Maven URL is:

```text
https://mozillafiredoge.github.io/KineticAssembly/maven
```

## Native Builds

Native packaging is opt-in:

```text
./gradlew -Pkinetic_assembly.bundleNativeLinux=true build
```

Native smoke testing is also local-only for now:

```text
./gradlew nativeSmokeTest
```

Both commands require a local PhysX checkout/install that matches
`tools/native/build-linux.sh`.

## First Commit Checklist

Before committing, check:

```text
git status --short --ignored
./gradlew build
```

The commit should include source, docs, Gradle wrapper files, `.gitignore`, and
the CI workflow. It should not include `PhysX/`, `build/`, `.gradle/`, `run/`,
IDE metadata, or generated native binaries.

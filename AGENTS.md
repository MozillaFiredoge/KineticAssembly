# Repository Guidelines

## Project Structure & Module Organization

This is a NeoForge Minecraft mod with Java gameplay code and a native PhysX JNI bridge. Java sources live under `src/main/java/com/firedoge/kineticassembly`, grouped by API, backend, mechanics, Minecraft integration, mixins, networking, rendering, and platform hooks. Resources are in `src/main/resources`, including mixin config, language assets, access transformers, and native library placeholders. Native C++ code is under `src/main/cpp/kinetic_assembly`, with headers in `include/kinetic_assembly` and implementations in `src`. Tests and smoke checks live in `src/test/java`. Design notes and subsystem docs are in `docs/`.

## Build, Test, and Development Commands

- `./gradlew build`: compiles Java, processes resources, and builds the mod jar.
- `./gradlew runClient`: starts the NeoForge development client.
- `./gradlew runServer`: starts a local development server with `--nogui`.
- `./gradlew buildNativeLinux`: builds the Linux x86_64 JNI bridge into `build/native/linux-x86_64`.
- `./gradlew nativeSmokeTest`: runs the native PhysX lifecycle smoke test; requires local PhysX native dependencies.
- `./gradlew -Pkinetic_assembly.bundleNativeLinux=true build`: includes generated Linux native libraries in the jar resources.

Native builds expect a local PhysX checkout at `PhysX/physx` unless `PHYSX_ROOT`, `PHYSX_LIB_DIR`, or `PHYSX_INCLUDE_DIR` is set.

## Coding Style & Naming Conventions

Target Java 21 and UTF-8. Use 4-space indentation, explicit package organization, and existing naming patterns: `PhysX*` for native backend types, `Assembly*` for assembly systems, and `Clientbound*Payload` for network payloads. Keep public API classes in `api` stable and implementation details in backend or Minecraft-specific packages. C++ files use `snake_case` filenames and the `kinetic_assembly` include namespace path.

## Testing Guidelines

Run `./gradlew build` before submitting Java changes. Use `./gradlew nativeSmokeTest` when touching `backend/physx`, `nativebridge`, JNI Java declarations, or `src/main/cpp/kinetic_assembly`. Add focused tests or smoke checks under `src/test/java`, using names that describe the behavior being verified.

## Commit & Pull Request Guidelines

History uses short, direct commit subjects such as `Stable core` and `Data Storage`; keep subjects concise and mention docs or CI only when relevant, for example `[skip ci] readme`. Pull requests should describe the changed subsystem, include reproduction steps or validation commands, link related issues when available, and call out native build requirements. Include screenshots or logs for visible client behavior, debug rendering, or runtime profiling changes.

## Security & Configuration Tips

Do not commit `PhysX/`, `build/`, `.gradle/`, `run/`, IDE files, logs, or generated native binaries. Keep platform native libraries generated locally unless intentionally packaged through the Gradle native bundle path.

# API Artifacts

KineticAssembly publishes a small compile-time API artifact for dependent mods
and a full NeoForge runtime artifact for players and modpacks.

Assembly is the gameplay-facing mechanics layer. External mods should integrate
through `KineticAssembly.api()` and `com.firedoge.kineticassembly.mechanics`
rather than through Minecraft implementation packages, mixins, native handles,
or backend classes.

## Public API Boundary

The compile-time API artifact contains:

- `com.firedoge.kineticassembly.KineticAssembly`
- `com.firedoge.kineticassembly.mechanics.*`
- selected immutable value types from `com.firedoge.kineticassembly.api`:
  - `PhysicsVector`
  - `PhysicsQuaternion`
  - `PhysicsPose`
  - `PhysicsBoxCollider`
  - `PhysicsMassProperties`

Everything else is implementation detail unless it is explicitly promoted in
this document or in `docs/mechanics-api.md`.

Implementation-detail packages include:

- `backend`
- `nativebridge`
- `minecraft`
- `mixin`
- `network`
- `render`
- `platform`
- raw backend-facing physics interfaces in `api`

## Artifact Shape

The current Gradle build creates these Maven publications for each Stonecutter
node:

```text
com.firedoge.kineticassembly:kinetic_assembly-api-<minecraft_version>:<mod_version>
com.firedoge.kineticassembly:kinetic_assembly-neoforge-<minecraft_version>:<mod_version>
```

The API artifact is compile-only. It contains a small `KineticAssembly` entry
point stub so dependent mods can compile against `KineticAssembly.api()`, but
that stub is not a runtime implementation. Players and development runs still
need the full NeoForge runtime mod.

The public mechanics package now includes capability discovery, owner-tagged
body creation, structured `MechanicsResult` failures, continuous force/torque
entry points, and physics tick phase listeners.

## Consumer Example

```gradle
repositories {
    maven {
        url = uri("https://mozillafiredoge.github.io/KineticAssembly/maven")
    }
}

dependencies {
    compileOnly "com.firedoge.kineticassembly:kinetic_assembly-api-1.21.1:0.1.0-alpha.1"
    runtimeOnly "com.firedoge.kineticassembly:kinetic_assembly-neoforge-1.21.1:0.1.0-alpha.1"
}
```

See `examples/neoforge-consumer` for a minimal dependent mod.

For local testing before GitHub Pages publication:

```text
./gradlew publish
```

This writes artifacts to the local project `repo/` directory.

The GitHub Actions workflow `.github/workflows/publish-maven.yml` publishes the
same Maven layout to the `gh-pages` branch under `/maven` when a `v*` tag is
pushed or when the workflow is run manually.

## Versioning Rule

The API is experimental while the project is below `1.0.0`.

Before `1.0.0`, dependent mods should pin an exact API artifact version. After a
stable API release, incompatible public API changes should require a major
version bump.

Minecraft versions are part of the artifact id because public signatures include
Minecraft and NeoForge types such as `ServerLevel` and `ResourceKey<Level>`.

## Not Yet Included

The API artifact still does not include:

- assembly creation from block volumes as a public API;
- raycast, overlap, or contact query APIs;
- Fabric runtime support.

Those should be added incrementally without exposing implementation packages.

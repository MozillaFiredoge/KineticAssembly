# KineticAssembly

KineticAssembly is an experimental NeoForge mod that connects Minecraft gameplay to a
NVIDIA PhysX-backed mechanics layer. Its current focus is assemblies: detached
block assemblies that keep Minecraft's block logic, redstone, block entities,
collision, interaction, and persistence while being driven by a physics body.

The goal is a small physics substrate where Minecraft keeps owning most vanilla
logic, while PhysX owns rigid body movement and collision.

## Status

Current scope is considered feature-complete enough for maintenance mode.
Remaining work should be driven by concrete, reproducible issues rather than new
architecture expansion.

Tested project baseline:

- Minecraft `1.21.1`
- NeoForge `21.1.230`
- Java `21`
- Parchment `2024.11.17`

## Features

- PhysX-backed server mechanics layer with dynamic rigid bodies.
- Terrain collision extraction and batching for loaded Minecraft chunks.
- Assembly assembly from blocks or block volumes.
- Reserved plot chunks that let vanilla Minecraft tick assembly blocks.
- Redstone and block/fluid scheduled tick migration and persistence.
- Block entity support for common containers and functional blocks.
- Entity query projection for pressure plates, item pickup, item frames, and
  similar vanilla logic.
- Assembly block collision on server and client so players/entities can stand on
  basic moving assemblies.
- Item frame and painting support inside assemblies, including comparator reads
  for item frames.
- Persistent assembly save/restore through per-level `SavedData`.
- Optional debug visual proxies and runtime profiling commands.
- GPU PhysX deformable-volume prototype for FEM panels and full blocks.

## Current Limits

- This is still experimental mod infrastructure, not a polished gameplay mod.
- Assembly collision is projected from block AABBs. It covers basic standing and
  blocking, but is not a full precision OBB/contact solution.
- Fast moving platforms, rotating platforms, velocity inheritance, joints,
  cloth, vehicles, and final gameplay integrations are outside the current
  implemented scope.
- FEM support is an early GPU-only deformable-volume prototype with sampled
  marker visuals, not a finished block-mesh gameplay system.
- Liquid placement inside assemblies is not supported yet.
- Native PhysX packaging is only automated for Linux x86_64 at the moment.

## Build

```text
./gradlew build
```

Run the dev client:

```text
./gradlew runClient
```

The normal build does not bundle the native PhysX bridge. For Linux x86_64,
native packaging can be built and bundled with:

```text
./gradlew buildNativeLinux
./gradlew -Pkinetic_assembly.bundleNativeLinux=true build
```

During dev runs, the Gradle config points `kinetic_assembly.nativeLibraryPath` at:

```text
build/native/linux-x86_64
```

See [docs/native-packaging.md](docs/native-packaging.md) for platform and loader
details.

## Useful Commands

Assembly commands:

```text
/kinetic_assembly assembly assemble_block <pos> [mass] [debugProxy]
/kinetic_assembly assembly assemble_box <from> <to> [mass] [debugProxy]
/kinetic_assembly assembly list [limit]
/kinetic_assembly assembly pick [maxDistance]
/kinetic_assembly assembly break [maxDistance]
/kinetic_assembly assembly impulse <idPrefix> <x> <y> <z>
/kinetic_assembly assembly remove <idPrefix>
/kinetic_assembly assembly status
```

Mechanics API smoke-test commands:

```text
/kinetic_assembly mechanics spawn_box [size] [mass] [debugProxy]
/kinetic_assembly mechanics list [limit]
/kinetic_assembly mechanics impulse <idPrefix> <x> <y> <z>
/kinetic_assembly mechanics remove <idPrefix>
/kinetic_assembly mechanics show <idPrefix>
/kinetic_assembly mechanics hide <idPrefix>
```

FEM deformable volume prototype commands:

```text
/kinetic_assembly fem spawn_panel [width] [depth] [thickness] [density] [youngs] [voxels]
/kinetic_assembly fem spawn_block [size] [density] [youngs] [voxels]
/kinetic_assembly fem list [limit]
/kinetic_assembly fem status <idPrefix>
/kinetic_assembly fem remove <idPrefix>
/kinetic_assembly fem clear
```

Client status:

```text
/kinetic_assembly_client assembly_status
```

## Documentation Map

- [docs/milestones_2.md](docs/milestones_2.md): current assembly milestone state.
- [docs/assembly.md](docs/assembly.md): assembly ownership model and commands.
- [docs/assembly-interactions.md](docs/assembly-interactions.md): interaction
  routing.
- [docs/assembly-query-bridge.md](docs/assembly-query-bridge.md): entity query
  projection.
- [docs/mechanics-api.md](docs/mechanics-api.md): public mechanics API surface.
- [docs/api-artifacts.md](docs/api-artifacts.md): compile-time API and runtime
  Maven artifact layout.
- [examples/neoforge-consumer](examples/neoforge-consumer): minimal dependent
  NeoForge mod using the public mechanics API artifact.
- [docs/chunk-collision-pipeline.md](docs/chunk-collision-pipeline.md):
  terrain collision build pipeline.
- [docs/runtime-profiling.md](docs/runtime-profiling.md): profiling and runtime
  status.
- [docs/elastic-panel-prototype.md](docs/elastic-panel-prototype.md): first
  elastic gameplay loop prototype.
- [docs/fem-deformable-volume-prototype.md](docs/fem-deformable-volume-prototype.md):
  PhysX deformable-volume panel and block prototype.
- [docs/native-packaging.md](docs/native-packaging.md): native bridge packaging.
- [docs/provenance.md](docs/provenance.md): source provenance and clean-room
  policy for future work.

## Development Notes

The central design rule is to keep vanilla Minecraft authoritative wherever
possible. Assembly blocks live in reserved plot chunks so redstone, block
entities, scheduled ticks, entity queries, comparator reads, and normal block
updates can continue using Minecraft's own systems. KineticAssembly maps those plot
chunks to a moving physics body and bridges the places where vanilla world
coordinates no longer match physical world coordinates.

For new work, prefer narrow compatibility fixes over broad rewrites. Add a
focused repro first, then patch the specific bridge boundary that fails.

## Third-party References

- NVIDIA PhysX SDK: <https://github.com/NVIDIA-Omniverse/PhysX>
- PhysX license: BSD-3-Clause, see
  <https://github.com/NVIDIA-Omniverse/PhysX/blob/main/LICENSE.md>

KineticAssembly uses PhysX through a native bridge. Third-party software remains under
its own license terms.

## License

See LICENSE.

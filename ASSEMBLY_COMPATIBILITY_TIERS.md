# Assembly Compatibility Tiers

KineticAssembly is a physics assembly framework for engineering gameplay. Its
compatibility target is not complete vanilla-world emulation. Each Minecraft
version should instead declare the highest assembly tier that has been ported,
tested, and is reasonable to support.

The tier is a support claim, not a roadmap promise. A version may compile at a
higher feature level in source, but it should only claim the tier that has been
validated on that Minecraft version.

## Tier 0: Runtime Baseline

The mod builds, loads, and starts on the target Minecraft version.

Required support:

- Java sources compile for the target version.
- Resources, mixins, access transformers, and mod metadata load.
- Client and server development runs reach startup without loader failures.
- The native bridge can be built or explicitly marked unavailable for that
  platform.
- Mechanics API smoke paths can create, step, snapshot, and remove simple
  physics bodies when the native bridge is available.

This tier does not claim usable moving block assemblies.

## Tier 1: Physical Assembly

Blocks can become a server-authoritative rigid assembly driven by physics.

Required support:

- Assemble a block or bounded block volume into one physics-owned assembly.
- Keep a stable assembly id and mechanics body id.
- Sync root pose to clients.
- Render the assembly in a recognizable form.
- Collide the assembly root with host terrain at the expected coarse level.
- Remove or discard assemblies without leaking runtime state.
- Save enough assembly state that a restart does not corrupt the world.

This tier is enough for physics prototypes and migration bring-up. It does not
claim broad vanilla interaction compatibility.

## Tier 2: Interactive Plot Assembly

Assemblies use plot-backed Minecraft data so selected vanilla behavior keeps
running while the assembly moves through the host world.

Required support:

- Reserved plot chunks or equivalent plot storage are authoritative for assembly
  block state.
- Block state, block entity data, scheduled ticks, and selected entity queries
  are bridged through plot/local/world coordinate conversions.
- Players and ordinary entities can stand on and collide with basic moving
  assemblies.
- Picking, breaking, placement, and common interaction routes work through
  explicit assembly bridges.
- Client rendering, selection, particles, and block/entity presentation use the
  assembly root pose consistently.
- Assembly persistence restores body pose, block state, plot ownership, and
  enough ticking state for normal continued play.

This tier still accepts gaps for high-speed motion, rotating support, fluids,
exotic block behaviors, and specialized mod blocks. Those gaps should be tracked
as explicit compatibility cases, not hidden behind a claim of vanilla parity.

## Tier 3: Engineering Integration

The project exposes stable gameplay-facing integration points for other mods and
higher-level mechanical systems.

Required support:

- Mechanics API contracts are documented and versioned enough for external use.
- Assemblies can be addressed through stable ids and snapshots without exposing
  native handles.
- Joints, constraints, forces, and mass properties have clear ownership rules.
- Optional compatibility adapters are explicit and isolated from core assembly
  code.
- New integrations add thin Minecraft hooks and keep behavior in service or
  adapter classes.

This tier is the intended long-term product direction: the player experience is
engineering movable structures, vehicles, machines, and physical systems.

## Tier 4: Broad Vanilla Compatibility

The project attempts to support most vanilla gameplay behavior inside moving
assemblies.

This is not the default target. It creates wide mixin coupling, makes future
Minecraft migrations expensive, and should only be accepted for narrowly scoped
features with clear engineering value. Work in this tier must be opt-in,
auditable, and backed by version-specific tests.

## Current Version Assignment

| Minecraft version | Current tier | Notes |
| --- | --- | --- |
| 1.21.1 | Tier 2: Interactive Plot Assembly | This is the current baseline version. The codebase contains plot-backed assembly storage, client/server presentation, collision, selected interaction bridges, persistence, and block/entity behavior bridges. It is not Tier 3 until the public integration surface is treated as stable, and it is not Tier 4 by design. |
| 1.21.11 | Tier 1: Physical Assembly | The version has been ported far enough to build and start through the Stonecutter path, with version overlays for changed resources and APIs. It should remain Tier 1 until the Tier 2 gameplay checks are run directly on 1.21.11. After those checks pass, promote it to Tier 2. |

## Promotion Rules

A Minecraft version can move up one tier only after the tier's required support
has been verified on that version. A passing build on one version does not imply
the same tier on another version.

Suggested validation gates:

- Tier 0: `./gradlew build`, `./gradlew :<version>:runServer`, and
  `./gradlew :<version>:runClient` startup checks.
- Tier 1: assemble block, assemble box, impulse, terrain collision, client pose
  sync, remove, save, and reload smoke checks.
- Tier 2: plot chunk ticking, block entity data, redstone/scheduled ticks,
  picking, breaking, placement, player/entity collision, persistence, and client
  rendering regression checks.
- Tier 3: mechanics API examples, adapter tests, lifecycle ownership checks, and
  compatibility smoke tests for each supported integration.
- Tier 4: explicit feature-by-feature tests; no broad claim without coverage.

## Mixin Policy

Detailed rules are in `MIXIN_COMPATIBILITY.md`.

Every assembly mixin should map to a tier and should be as thin as possible:

- Tier 0 mixins keep the mod loadable.
- Tier 1 mixins bridge physics root state, pose sync, and lifecycle.
- Tier 2 mixins bridge plot-backed interaction, collision, rendering, and
  persistence boundaries.
- Tier 3 mixins should usually be integration adapters, not core behavior.
- Tier 4 mixins require a written justification and a regression test because
  they are the highest migration risk.

Core behavior should live in KineticAssembly services, APIs, or adapter classes.
Mixins should identify the Minecraft hook point and delegate.

# Mixin Compatibility Policy

KineticAssembly mixins are compatibility adapters. They should identify a
Minecraft hook point, translate the minimal arguments needed by KineticAssembly,
delegate to ordinary project code, and return.

Main assembly behavior should live in services, APIs, adapters, or data classes
outside `mixin`. This keeps version ports narrow: a Minecraft update should
mostly change hook signatures and call sites, not the behavior being ported.

## Allowed In Mixins

- Mixin annotations, shadows, accessors, redirects, wraps, and inject method
  signatures.
- Minimal type checks needed to decide whether a hook applies.
- Reading or writing mixin-owned bridge fields that cannot live elsewhere, such
  as per-entity cached state added through the mixin.
- Calling a KineticAssembly service method and applying its simple result.
- Cancelling, replacing a return value, or forwarding to the vanilla method when
  the service says the assembly path handled the hook.

## Keep Out Of Mixins

- Multi-step gameplay algorithms.
- Long coordinate conversion flows.
- Packet reconstruction policy.
- Collision resolution policy.
- Persistence format decisions.
- Render particle generation loops.
- Cross-version behavior forks that can be represented as a small compatibility
  facade.
- Broad vanilla behavior emulation without a tier justification and tests.

If a mixin needs more than a small branch and one service call, add or extend a
normal KineticAssembly class first.

## Service Placement

Use the nearest ordinary package that owns the behavior:

- `minecraft.assembly`: server/client assembly state, plot ownership,
  persistence, collision routing, entity support, placement, and lifecycle.
- `render`: client presentation, selection, projected particles, lighting, and
  render-specific bridges.
- `network`: payload encoding, packet bridge policy, and protocol helpers.
- `mechanics`: public gameplay-facing physics API and stable snapshots.
- `minecraft.compat`: explicit Minecraft-version compatibility facades.
- `compat.<mod>`: optional external mod adapters.

Version overlays under `versions/<mc>/src/.../mixin` should be thinner than the
common mixin whenever possible. If a version overlay needs more logic than the
common file, move the shared behavior to a service and leave only the changed
Minecraft signature in the overlay.

## Current Risk Inventory

Highest priority for extraction:

- `mixin/collision/EntityAssemblyCollisionMixin`: entity support state,
  inherited motion, on-ground preservation, sprint particles, and collision
  routing are all migration-sensitive. Keep bridge fields in the mixin, but move
  policy decisions into `AssemblyEntityMotor`, `AssemblyEntityCollision`, or a
  new focused service.
- `mixin/network/ClientPacketListenerAssemblyEntityMixin`: client packet
  reconstruction and passenger sync are version-fragile. Packet interpretation
  should be ordinary network or assembly code.
- `mixin/level/ServerLevelMixin`: persistence, ticking, and lifecycle hooks are
  core assembly behavior. The mixin should call an assembly lifecycle service.
- `mixin/network/ServerGamePacketListenerAssemblyMoveMixin` and
  `ServerboundMovePlayerPacketAssemblyMixin`: movement protocol behavior should
  live in an assembly movement protocol service.
- `mixin/render/EntityRendererMixin` and `LevelRendererMixin`: render culling,
  light probes, breaking overlays, and projected effects should live in render
  bridge classes.
- `mixin/entity/PlayerMixin`, `ServerPlayerAssemblyMixin`, and
  `LocalPlayerAssemblyRidingMixin`: player state transitions should be owned by
  player/assembly services.

Lower-risk mixins:

- Accessor-only mixins.
- Small placement and query mixins that already delegate to assembly services.
- Render or level mixins whose body is a single service call and a cancel/return
  action.

## Extraction Pattern

1. Name the hook boundary in the mixin method.
2. Move behavior into a service method with ordinary Minecraft types.
3. Return a small result object when the service needs to tell the mixin whether
   to cancel, replace a value, or keep vanilla behavior.
4. Keep version-specific signatures in overlays, but call the same service when
   semantics are shared.
5. Build all Stonecutter versions after each extraction.

Prefer several small extractions over one large rewrite. The goal is to make
future Minecraft ports cheap without changing gameplay semantics during cleanup.

## First Extraction

`versions/1.21.11/.../ClientLevelMixin` now delegates plot terrain particle
generation to `ClientAssemblyLevelEffects`. The mixin remains responsible for
hooking `ClientLevel`, finding the plot projection, and cancelling the vanilla
effect. Particle sampling and creation are ordinary render code.

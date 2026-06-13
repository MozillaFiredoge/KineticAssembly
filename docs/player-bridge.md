# Vanilla Player Bridge

M23.4 pivots the main player integration away from full PhysX-owned player
movement.

The normal Minecraft player remains vanilla-authoritative. PhysX receives a
player proxy body that follows the server player's current position before each
physics step. This lets PhysX objects query and collide against a player-shaped
body without rewriting Minecraft movement, networking, inventory, dimensions,
mounts, fluids, or special movement states.

## Commands

```text
/kinetic_assembly player_proxy enable [mass] [debugProxy]
/kinetic_assembly player_proxy disable
/kinetic_assembly player_proxy status
```

`enable` creates a `PLAYER_PROXY` mechanics body for the command source. The
prototype uses the same standing-player box as M23.3:

```text
halfExtents = (0.30, 0.90, 0.30)
body center = player feet position + 0.90Y
```

`PlayerProxyManager.syncBeforePhysics` runs before `ServerPhysicsRuntime.tick`.
For each enabled player, it updates the proxy body pose from the vanilla player
position and sets the proxy velocity from the player's movement since the last
sync. The velocity is not used to move the player; it is supplied so PhysX bodies
can observe an approximate moving player during the next solver step.

The proxy is removed on `disable`, `/kinetic_assembly clear`, dimension changes, missing
players, and server shutdown.

## Relationship To M23.3

`/kinetic_assembly player_physics ...` remains an experimental PhysX-owned agent mode.
`/kinetic_assembly player_proxy ...` is the safer default bridge for normal gameplay.

The two modes are mutually exclusive for one player. A player cannot have both a
PhysX-owned player body and a vanilla-following proxy body at the same time.

## Current Limits

- The proxy is a dynamic box teleported before each physics step, not a proper
  PhysX kinematic actor or capsule controller.
- The bridge does not yet convert PhysX contacts into vanilla knockback,
  damage, or interaction results.
- Player raycast/use/break/place routing into assemblies is still a follow-up
  task.
- Proxy collision behavior should be treated as prototype-level until contact
  events and filtering are added.


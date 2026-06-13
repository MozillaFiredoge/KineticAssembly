# Player Physics Prototype

M23.3 adds the first PhysX-owned player body prototype.

This is intentionally narrow. It proves that a Minecraft player can be bound to
a mechanics body and synchronized from PhysX after the server physics step. It
does not yet replace the full vanilla movement stack with a proper character
controller.

M23.4 adds the safer default path in `docs/player-bridge.md`: vanilla players
remain Minecraft-authoritative while PhysX receives a player proxy body. Use
this M23.3 mode only as an experimental agent/RL-style control path.

## Commands

```text
/kinetic_assembly player_physics enable [mass] [debugProxy]
/kinetic_assembly player_physics disable
/kinetic_assembly player_physics status
/kinetic_assembly player_physics impulse <x> <y> <z>
```

`enable` creates a `PLAYER` mechanics body for the command source. The current
prototype uses a dynamic box with half extents `(0.30, 0.90, 0.30)` as a rough
standing player collider. The body center is stored at the player's feet
position plus `0.90` on Y.

After `ServerPhysicsRuntime` steps PhysX, `PlayerPhysicsManager` synchronizes
the server player position from the body pose:

```text
player feet position = body center - player half height
```

While active, the manager keeps vanilla gravity disabled for that player,
clears vanilla delta movement, and resets fall distance. `disable`, `/kinetic_assembly
clear`, dimension changes, missing bodies, and server shutdown restore the
player's previous gravity flag and remove the mechanics body.

## Current Limits

- The collider is a dynamic box, not a capsule or PhysX character controller.
- Vanilla client input is not converted into PhysX movement intent yet.
- Ground movement, step height, swimming, ladders, elytra, mounts, portals,
  fall damage, and network interpolation still need explicit follow-up work.
- The command only targets the command source player.
- Debug proxy visualization is optional and remains a debug aid, not authority.
- This mode is mutually exclusive with `/kinetic_assembly player_proxy`.

## Next Work

The next slice should route simple player movement intent into the player body:

- capture forward/strafe/jump intent;
- convert it into bounded target velocity or impulses;
- keep PhysX as the owner of final pose;
- preserve the existing enable/disable fallback path.

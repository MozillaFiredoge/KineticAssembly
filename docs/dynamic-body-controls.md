# Dynamic Body Controls

M12 adds command-level controls for dynamic debug boxes.

## Commands

```text
/kinetic_assembly spawn_box
/kinetic_assembly spawn_box <size>
/kinetic_assembly spawn_box <size> <mass>
/kinetic_assembly set_velocity <x> <y> <z>
```

`spawn_box` creates a uniform dynamic PhysX box:

- default size: `1.0`
- default mass: `1.0`
- allowed size range: `0.05` to `16.0`
- allowed mass range: `0.01` to `10000.0`

The `BlockDisplay` proxy scales to match the box size.

`set_velocity` finds the nearest dynamic physics box within 32 blocks of the
command source and sets its linear velocity. This is a direct velocity override,
not an impulse.

## Diagnostics

`/kinetic_assembly physics_status` includes:

- `dynamicBoxes`: active dynamic debug boxes.
- `sample`: dynamic object id, pose position, and linear velocity.

This makes it easier to test:

- different body masses;
- larger or smaller boxes;
- dynamic-static collision against chunk terrain;
- dynamic-dynamic collision between multiple boxes;
- proxy sync after high velocity movement.

## Current Limits

- Only uniform boxes are exposed through commands.
- `set_velocity` targets nearest dynamic box by distance, not by explicit id.
- There is no angular velocity or impulse command yet.
- Mass and size are not persisted beyond the active runtime scene.
- Individual debug object listing and removal are covered by M13 in
  `docs/debug-object-management.md`.

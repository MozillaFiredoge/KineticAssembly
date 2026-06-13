# Elastic Panel Prototype

This prototype is the first gameplay loop for future FEM work. It is not a
native PhysX FEM soft body yet; it is a server-side elastic gameplay component
that can later be replaced by a real deformable simulation.

## Loop

An elastic panel converts load into deformation, deformation into interaction,
and deformation into redstone output:

1. Players, entities, and mechanics bodies above the panel add load.
2. The panel solves a small damped spring model and moves a block-display visual.
3. Falling entities and mechanics bodies get a softened rebound.
4. When deformation reaches signal level 8 or higher, the panel places a
   temporary redstone block at its output position.

The output block is only placed into air and is removed when the panel releases
or when the panel is removed.

## Commands

```text
/kinetic_assembly elastic spawn_panel [width] [depth] [stiffness] [maxDeflection] [output]
/kinetic_assembly elastic list [limit]
/kinetic_assembly elastic status <idPrefix>
/kinetic_assembly elastic remove <idPrefix>
/kinetic_assembly elastic clear
```

Defaults:

```text
width=3
depth=3
stiffness=2
maxDeflection=0.65
```

If `output` is omitted, the output is placed just outside the positive-X edge of
the panel. The output position must be air.

## Current Limits

- The panel is not persistent across server restarts.
- It uses a simplified load model, not tetrahedral FEM.
- It does not create a true collision shape; place it on or near an existing
  surface for stable testing.
- Redstone output is binary through a temporary redstone block, while command
  status reports the full 0-15 deformation signal.

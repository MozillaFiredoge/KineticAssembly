# FEM Deformable Volume Prototype

This prototype connects PhysX 5 deformable volumes into the server physics
runtime. It uses `PxDeformableVolume`, not the deprecated `PxSoftBody` alias.
The first supported shapes are a thin panel and a complete cubic block.

## Requirements

PhysX deformable volumes require a GPU scene. Start the game with GPU dynamics
enabled and a CUDA-capable PhysX runtime available. In a dev run, set this in
`run/config/kinetic_assembly-common.toml` and restart the world:

```toml
enableGpuDynamics = true
```

If CUDA or GPU dynamics are missing, spawn commands fail cleanly instead of
falling back to a visual-only mock. See [gpu-dynamics.md](gpu-dynamics.md) for
runtime status fields and native library placement.

```text
./gradlew buildNativeLinux
./gradlew runClient
```

## Commands

```text
/kinetic_assembly fem spawn_panel [width] [depth] [thickness] [density] [youngs] [voxels]
/kinetic_assembly fem spawn_block [size] [density] [youngs] [voxels]
/kinetic_assembly fem list [limit]
/kinetic_assembly fem status <idPrefix>
/kinetic_assembly fem remove <idPrefix>
/kinetic_assembly fem clear
```

Defaults:

```text
density=100
youngs=200000
voxels=5
```

`spawn_panel` creates a horizontal deformable slab. `spawn_block` creates a full
deformable cube. Both are inserted into the same PhysX scene as terrain
colliders, so gravity and world collision are part of the loop.

## Current Loop

1. Build or reuse the level's PhysX scene and nearby terrain collision.
2. Create a PhysX deformable volume from a box mesh.
3. Step it with the normal server physics tick.
4. Read back collision vertices from the GPU, compute deformation metrics, and
   display sampled markers in the world.
5. For block volumes, estimate a local orientation from the FEM vertex cloud and
   update a full slime-block shell so rotation, compression, and stretching are
   visible at a glance.
6. Remove the native volume and marker entities through `remove`, `clear`, or
   server shutdown.

## Current Limits

- This is a low-level closed loop, not final gameplay.
- It renders sampled vertex markers plus a block AABB shell, not a skinned
  Minecraft mesh.
- No redstone output is attached to FEM objects yet.
- FEM objects are not persisted across server restarts.
- Player interaction is indirect through existing PhysX terrain/rigid-body
  collision plumbing.

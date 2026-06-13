# GPU Dynamics

M15 adds an opt-in path for PhysX GPU rigid body dynamics and a physics-only
stress command.

## Configuration

Enable the request in the common config:

```toml
enableGpuDynamics = true
```

For a dev run, this usually means editing `run/config/kinetic_assembly-common.toml`.
Changing the default in `PhysXConfig.java` only affects newly generated config
files; an existing instance config can still override it.

The setting is applied when a `ServerPhysicsScene` is created. Scenes are
created lazily, so enabling this option no longer creates GPU scenes for every
dimension at server tick startup. Restart the world or server after changing
the setting so existing scenes are recreated.

GPU dynamics requires:

- A PhysX build with GPU support.
- An NVIDIA driver with CUDA driver support.
- The PhysX GPU runtime beside the native bridge:
  - Linux: `libPhysXGpu_64.so`
  - Windows: `PhysXGpu_64.dll`

If any requirement is missing, scene creation falls back to CPU PhysX. The
server should remain usable.

## Status

Use:

```text
/kinetic_assembly physics_status
```

Relevant fields:

```text
gpuRequested=true
gpuScenes=3
gpuStatus=enabled
```

`gpuRequested=true` means config asked for GPU dynamics. `gpuScenes` is the
number of live scenes that actually got a valid CUDA context and were created
with `PxSceneFlag::eENABLE_GPU_DYNAMICS` plus GPU broadphase.

`gpuStatus=no_scenes` means no physics scene has been created yet. This is the
expected startup state until a command or runtime event creates a scene.

If `gpuRequested=true` and `gpuScenes=0`, the runtime fell back to CPU. Check
that `PhysXGpu_64.dll` or `libPhysXGpu_64.so` is next to the bridge and that
the machine has a compatible NVIDIA driver.

`gpuStatus` reports the native fallback reason. Common values are:

- `enabled`: the scene is using GPU dynamics.
- `no_scenes`: no physics scene exists yet.
- `cuda_context_manager_unavailable`: PhysX could not load the GPU module or
  create the CUDA context manager. Check `PhysXGpu_64.dll` /
  `libPhysXGpu_64.so` and the NVIDIA CUDA driver.
- `cuda_context_invalid`: PhysX loaded the GPU path, but the CUDA context is not
  usable on this machine.
- `gpu_scene_create_failed_cpu_fallback`: CUDA context creation succeeded, but
  the GPU scene itself failed and CPU fallback was used.
- `unsupported_platform`: this PhysX/platform combination does not expose GPU
  rigid body support.

## Stress Command

Use:

```text
/kinetic_assembly spawn_stress_grid <countX> <countY> <countZ> [spacing] [size] [mass]
```

Defaults:

```text
spacing=1.2
size=1.0
mass=1.0
```

The command creates dynamic PhysX boxes only. It intentionally does not spawn
`BlockDisplay` render proxies, so the test measures physics throughput before
Minecraft entity rendering and networking dominate the result.

The current safety cap is 20,000 dynamic bodies per command. Existing terrain
collision batching still applies, and the command queues nearby terrain chunks
around the source position.

## Runtime Model

GPU dynamics is intentionally lazy and shared:

- `ServerPhysicsRuntime` no longer pre-creates scenes for every loaded
  dimension during server tick.
- A scene is created when a command or runtime path first needs physics in that
  specific dimension.
- Native PhysX creates at most one CUDA context manager per process and shares
  it across GPU scenes.

This keeps normal gameplay from paying the cost of empty GPU scenes. GPU
dynamics should still be treated as an experimental benchmark/RL mode rather
than the default real-time Minecraft physics path.

Use the runtime profiling fields documented in `docs/runtime-profiling.md`
before comparing CPU and GPU solver speed. `lastStepMs` only covers the native
scene step; high `lastTickMs` with low `lastStepMs` points at Java/JNI,
terrain queueing, or debug entity sync overhead.

## Native Build Notes

The CMake project copies `PhysXGpu` into the bridge output directory when it is
found in the selected PhysX build output. For Linux local dev, this means
`build/native/linux-x86_64` should contain both:

```text
libkinetic_assembly_native.so
libPhysXGpu_64.so
```

For bundled Linux jars, `-Pkinetic_assembly.bundleNativeLinux=true` includes the GPU
runtime when it exists in the native build output. Windows jar packaging remains
manual, but GPU tests need `PhysXGpu_64.dll` in the same native resource
directory as `kinetic_assembly_native.dll`.

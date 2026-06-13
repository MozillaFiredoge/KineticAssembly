# Native Packaging

M7 packages the Linux JNI bridge into the mod jar as a generated resource.

## Gradle Tasks

```text
./gradlew buildNativeLinux
./gradlew copyNativeLinux
./gradlew -Pkinetic_assembly.bundleNativeLinux=true build
```

`buildNativeLinux` runs `tools/native/build-linux.sh` and produces:

```text
build/native/linux-x86_64/libkinetic_assembly_native.so
```

`copyNativeLinux` copies that file into:

```text
build/generated/nativeResources/native/linux-x86_64/libkinetic_assembly_native.so
```

If the native build output also contains `libPhysXGpu_64.so`, `copyNativeLinux`
includes it in the same resource directory so opt-in GPU dynamics can find the
PhysX GPU runtime.

When `-Pkinetic_assembly.bundleNativeLinux=true` is set, `processResources` depends on
`copyNativeLinux`, so the final jar includes the Linux native library.

A normal `./gradlew build` does not require a local PhysX checkout and does not
bundle native libraries. This keeps CI and first-commit validation lightweight.

## Native CMake Builds

The JNI bridge CMake project is platform-aware. It derives the expected PhysX
binary directory from the host platform:

```text
Linux x86_64:   linux.x86_64
Windows x86_64: win.x86_64.<vc compiler>.<crt>
macOS x86_64:   mac.x86_64
macOS arm64:    mac.arm64
```

The expected PhysX directory can be overridden when a local PhysX build uses a
different output name:

```text
PHYSX_CONFIG=release
PHYSX_PLATFORM_BIN_NAME=win.x86_64.vc143.mt
PHYSX_LIB_DIR=/path/to/PhysX/libs
PHYSX_INCLUDE_DIR=/path/to/PhysX/include
KINETIC_ASSEMBLY_BUILD_CONFIG=RelWithDebInfo
```

`PHYSX_CONFIG` selects the PhysX subdirectory under the platform binary
directory. Valid values are `debug`, `checked`, `profile`, and `release`. When
unset, the bridge maps normal release-like CMake builds, including
`RelWithDebInfo`, to the PhysX `release` directory.

`KINETIC_ASSEMBLY_BUILD_CONFIG` controls the bridge's own CMake build configuration.
For the Windows helper script, it defaults to `RelWithDebInfo`, or to `Debug`
when `PHYSX_CONFIG=debug`.

On Windows, the bridge also matches its MSVC runtime to
`PHYSX_WINDOWS_CRT_SUFFIX`:

```text
PHYSX_WINDOWS_CRT_SUFFIX=mt   -> /MT or /MTd
PHYSX_WINDOWS_CRT_SUFFIX=md   -> /MD or /MDd
```

The default is `mt`, matching PhysX's public `vc17win64` presets. A linker error
mentioning `_ITERATOR_DEBUG_LEVEL` or `RuntimeLibrary` usually means a Debug
PhysX `.lib` was linked into a release bridge, or the PhysX CRT suffix does not
match the bridge's `PHYSX_WINDOWS_CRT_SUFFIX`.

For Windows bundled jars, `kinetic_assembly_native.dll` is not always self-contained. If
the bridge was linked against PhysX import libraries, the jar must also contain
the dynamic PhysX DLL dependencies in the same resource directory:

```text
/native/windows-x86_64/kinetic_assembly_native.dll
/native/windows-x86_64/PhysXFoundation_64.dll
/native/windows-x86_64/PhysXCommon_64.dll
/native/windows-x86_64/PhysX_64.dll
```

The loader extracts and preloads these PhysX dependency DLLs before loading
`kinetic_assembly_native.dll`. A runtime error saying `Can't find dependent libraries`
means one of these DLLs, or one of their own runtime dependencies, is missing.

GPU dynamics additionally needs:

```text
/native/windows-x86_64/PhysXGpu_64.dll
```

The loader treats `PhysXGpu_64.dll` as optional so CPU-only startup does not
fail on machines without a usable CUDA driver. If GPU dynamics is enabled but
the DLL cannot be loaded by PhysX, `/kinetic_assembly physics_status` should show
`gpuRequested=true` and `gpuScenes=0`.

When available, linking against PhysX static libraries is preferred because it
keeps the bundled mod jar to one platform bridge DLL. The CMake lookup checks
static PhysX libraries before dynamic import libraries.

Available helper scripts:

```text
tools/native/build-linux.sh
tools/native/build-windows.ps1
tools/native/build-macos.sh
```

These scripts only build the native bridge into `build/native/<platform>/`.
They do not change the default Gradle jar packaging behavior.

## Loader Path

`NativeLibraryLoader` tries these locations in order:

1. `System.loadLibrary`, for libraries already on `java.library.path`.
2. `kinetic_assembly.nativeLibraryPath` or `KINETIC_ASSEMBLY_NATIVE_PATH`, for development.
3. `/native/<platform>/<mapped-library-name>` inside the mod jar.

For Linux x86_64, the bundled resource path is:

```text
/native/linux-x86_64/libkinetic_assembly_native.so
```

The NeoForge dev run still sets `kinetic_assembly.nativeLibraryPath` to
`build/native/linux-x86_64`, which keeps local iteration direct. Packaged jars
can use the bundled resource fallback when built with
`-Pkinetic_assembly.bundleNativeLinux=true`.

## Current Scope

Only Linux x86_64 is packaged by Gradle. Windows and macOS native bridge builds
now have CMake/script entry points, but copying `.dll` or `.dylib` files into
the jar should be added as explicit packaging tasks after those platforms are
validated locally.

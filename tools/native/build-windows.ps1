$ErrorActionPreference = "Stop"

$ProjectRoot = Resolve-Path "$PSScriptRoot/../.."
$PhysXRoot = if ($env:PHYSX_ROOT) { $env:PHYSX_ROOT } else { Join-Path $ProjectRoot "PhysX/physx" }
$BuildConfig = if ($env:KINETIC_ASSEMBLY_BUILD_CONFIG) {
    $env:KINETIC_ASSEMBLY_BUILD_CONFIG
} elseif ($env:PHYSX_CONFIG -and $env:PHYSX_CONFIG.ToLowerInvariant() -eq "debug") {
    "Debug"
} else {
    "RelWithDebInfo"
}
$CMakeArgs = @(
    "-DCMAKE_BUILD_TYPE=$BuildConfig",
    "-DPHYSX_ROOT=$PhysXRoot"
)

if ($env:PHYSX_LIB_DIR) {
    $CMakeArgs += "-DPHYSX_LIB_DIR=$env:PHYSX_LIB_DIR"
}
if ($env:PHYSX_INCLUDE_DIR) {
    $CMakeArgs += "-DPHYSX_INCLUDE_DIR=$env:PHYSX_INCLUDE_DIR"
}
if ($env:PHYSX_CONFIG) {
    $CMakeArgs += "-DPHYSX_CONFIG=$env:PHYSX_CONFIG"
}
if ($env:PHYSX_PLATFORM_BIN_NAME) {
    $CMakeArgs += "-DPHYSX_PLATFORM_BIN_NAME=$env:PHYSX_PLATFORM_BIN_NAME"
}
if ($env:PHYSX_WINDOWS_COMPILER_SUFFIX) {
    $CMakeArgs += "-DPHYSX_WINDOWS_COMPILER_SUFFIX=$env:PHYSX_WINDOWS_COMPILER_SUFFIX"
}
if ($env:PHYSX_WINDOWS_CRT_SUFFIX) {
    $CMakeArgs += "-DPHYSX_WINDOWS_CRT_SUFFIX=$env:PHYSX_WINDOWS_CRT_SUFFIX"
}

cmake -S "$ProjectRoot/src/main/cpp/kinetic_assembly" -B "$ProjectRoot/build/native/windows-x86_64" @CMakeArgs
cmake --build "$ProjectRoot/build/native/windows-x86_64" --config $BuildConfig

#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROJECT_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)
PHYSX_ROOT=${PHYSX_ROOT:-"$PROJECT_ROOT/PhysX/physx"}

MACHINE=$(uname -m)
case "$MACHINE" in
    arm64|aarch64)
        OUTPUT_PLATFORM="macos-aarch64"
        ;;
    x86_64|amd64)
        OUTPUT_PLATFORM="macos-x86_64"
        ;;
    *)
        OUTPUT_PLATFORM="macos-$MACHINE"
        ;;
esac

CMAKE_ARGS="-DCMAKE_BUILD_TYPE=RelWithDebInfo -DPHYSX_ROOT=$PHYSX_ROOT"
if [ "${PHYSX_LIB_DIR:-}" ]; then
    CMAKE_ARGS="$CMAKE_ARGS -DPHYSX_LIB_DIR=$PHYSX_LIB_DIR"
fi
if [ "${PHYSX_INCLUDE_DIR:-}" ]; then
    CMAKE_ARGS="$CMAKE_ARGS -DPHYSX_INCLUDE_DIR=$PHYSX_INCLUDE_DIR"
fi
if [ "${PHYSX_CONFIG:-}" ]; then
    CMAKE_ARGS="$CMAKE_ARGS -DPHYSX_CONFIG=$PHYSX_CONFIG"
fi
if [ "${PHYSX_PLATFORM_BIN_NAME:-}" ]; then
    CMAKE_ARGS="$CMAKE_ARGS -DPHYSX_PLATFORM_BIN_NAME=$PHYSX_PLATFORM_BIN_NAME"
fi

cmake -S "$PROJECT_ROOT/src/main/cpp/kinetic_assembly" -B "$PROJECT_ROOT/build/native/$OUTPUT_PLATFORM" $CMAKE_ARGS
cmake --build "$PROJECT_ROOT/build/native/$OUTPUT_PLATFORM" --config RelWithDebInfo

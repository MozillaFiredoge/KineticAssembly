cmake_minimum_required(VERSION 3.25)

if(POLICY CMP0091)
    cmake_policy(SET CMP0091 NEW)
endif()

project(kinetic_assembly_native LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

find_package(JNI REQUIRED)
find_package(Threads REQUIRED)

set(PHYSX_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../PhysX/physx" CACHE PATH "PhysX SDK root")
set(PHYSX_LIB_DIR "" CACHE PATH "Directory containing built PhysX libraries")
set(PHYSX_INCLUDE_DIR "${PHYSX_ROOT}/include" CACHE PATH "Directory containing PhysX headers")
set(PHYSX_CONFIG "" CACHE STRING "PhysX build configuration directory: debug, checked, profile, or release")
set(PHYSX_PLATFORM_BIN_NAME "" CACHE STRING "PhysX platform output directory, e.g. linux.x86_64 or win.x86_64.vc143.mt")
set(PHYSX_WINDOWS_COMPILER_SUFFIX "" CACHE STRING "Windows PhysX compiler suffix override, e.g. vc143")
set(PHYSX_WINDOWS_CRT_SUFFIX "mt" CACHE STRING "Windows PhysX CRT suffix, usually mt or md")

add_library(kinetic_assembly_native SHARED
    src/jni_bridge.cpp
    src/physx_body.cpp
    src/physx_context.cpp
    src/physx_shape.cpp
    src/physx_world.cpp
    src/conversions.cpp
)

target_include_directories(kinetic_assembly_native
    PRIVATE
        include
        ${JNI_INCLUDE_DIRS}
)

if(EXISTS "${PHYSX_INCLUDE_DIR}/PxPhysicsAPI.h")
    target_include_directories(kinetic_assembly_native PRIVATE "${PHYSX_INCLUDE_DIR}")
else()
    message(WARNING "PhysX headers were not found at ${PHYSX_INCLUDE_DIR}; building native bridge without PhysX")
endif()

function(kinetic_assembly_detect_msvc_compiler_suffix out_var)
    if(NOT MSVC)
        set(${out_var} "" PARENT_SCOPE)
    elseif(MSVC_VERSION GREATER_EQUAL 1930)
        set(${out_var} "vc143" PARENT_SCOPE)
    elseif(MSVC_VERSION GREATER_EQUAL 1920)
        set(${out_var} "vc142" PARENT_SCOPE)
    elseif(MSVC_VERSION GREATER_EQUAL 1910)
        set(${out_var} "vc141" PARENT_SCOPE)
    else()
        set(${out_var} "vc140" PARENT_SCOPE)
    endif()
endfunction()

function(kinetic_assembly_detect_physx_platform out_var)
    if(PHYSX_PLATFORM_BIN_NAME)
        set(${out_var} "${PHYSX_PLATFORM_BIN_NAME}" PARENT_SCOPE)
        return()
    endif()

    string(TOLOWER "${CMAKE_SYSTEM_PROCESSOR}" KINETIC_ASSEMBLY_SYSTEM_PROCESSOR)
    if(KINETIC_ASSEMBLY_SYSTEM_PROCESSOR MATCHES "^(amd64|x86_64)$")
        set(KINETIC_ASSEMBLY_ARCH_SUFFIX "64")
        set(KINETIC_ASSEMBLY_ARCH_NAME "x86_64")
    elseif(KINETIC_ASSEMBLY_SYSTEM_PROCESSOR MATCHES "^(aarch64|arm64)$")
        set(KINETIC_ASSEMBLY_ARCH_SUFFIX "64")
        set(KINETIC_ASSEMBLY_ARCH_NAME "aarch64")
    else()
        set(KINETIC_ASSEMBLY_ARCH_SUFFIX "64")
        set(KINETIC_ASSEMBLY_ARCH_NAME "${KINETIC_ASSEMBLY_SYSTEM_PROCESSOR}")
    endif()

    if(WIN32)
        if(PHYSX_WINDOWS_COMPILER_SUFFIX)
            set(KINETIC_ASSEMBLY_COMPILER_SUFFIX "${PHYSX_WINDOWS_COMPILER_SUFFIX}")
        else()
            kinetic_assembly_detect_msvc_compiler_suffix(KINETIC_ASSEMBLY_COMPILER_SUFFIX)
        endif()
        if(NOT KINETIC_ASSEMBLY_COMPILER_SUFFIX)
            set(KINETIC_ASSEMBLY_COMPILER_SUFFIX "vc143")
        endif()
        set(${out_var} "win.x86_${KINETIC_ASSEMBLY_ARCH_SUFFIX}.${KINETIC_ASSEMBLY_COMPILER_SUFFIX}.${PHYSX_WINDOWS_CRT_SUFFIX}" PARENT_SCOPE)
    elseif(APPLE)
        if(KINETIC_ASSEMBLY_ARCH_NAME STREQUAL "aarch64")
            set(${out_var} "mac.arm64" PARENT_SCOPE)
        else()
            set(${out_var} "mac.x86_${KINETIC_ASSEMBLY_ARCH_SUFFIX}" PARENT_SCOPE)
        endif()
    elseif(UNIX)
        set(${out_var} "linux.${KINETIC_ASSEMBLY_ARCH_NAME}" PARENT_SCOPE)
    else()
        set(${out_var} "" PARENT_SCOPE)
    endif()
endfunction()

function(kinetic_assembly_detect_physx_config out_var)
    if(PHYSX_CONFIG)
        string(TOLOWER "${PHYSX_CONFIG}" KINETIC_ASSEMBLY_CONFIG)
    else()
        string(TOLOWER "${CMAKE_BUILD_TYPE}" KINETIC_ASSEMBLY_CMAKE_BUILD_TYPE)
        if(KINETIC_ASSEMBLY_CMAKE_BUILD_TYPE STREQUAL "debug")
            set(KINETIC_ASSEMBLY_CONFIG "debug")
        elseif(KINETIC_ASSEMBLY_CMAKE_BUILD_TYPE STREQUAL "checked")
            set(KINETIC_ASSEMBLY_CONFIG "checked")
        elseif(KINETIC_ASSEMBLY_CMAKE_BUILD_TYPE STREQUAL "profile")
            set(KINETIC_ASSEMBLY_CONFIG "profile")
        else()
            set(KINETIC_ASSEMBLY_CONFIG "release")
        endif()
    endif()

    set(KINETIC_ASSEMBLY_KNOWN_CONFIGS debug checked profile release)
    if(NOT KINETIC_ASSEMBLY_CONFIG IN_LIST KINETIC_ASSEMBLY_KNOWN_CONFIGS)
        message(FATAL_ERROR "Unsupported PHYSX_CONFIG=${PHYSX_CONFIG}; expected debug, checked, profile, or release")
    endif()

    set(${out_var} "${KINETIC_ASSEMBLY_CONFIG}" PARENT_SCOPE)
endfunction()

kinetic_assembly_detect_physx_platform(KINETIC_ASSEMBLY_PHYSX_PLATFORM_BIN_NAME)
kinetic_assembly_detect_physx_config(KINETIC_ASSEMBLY_PHYSX_CONFIG)

if(MSVC)
    string(TOLOWER "${PHYSX_WINDOWS_CRT_SUFFIX}" KINETIC_ASSEMBLY_WINDOWS_CRT_SUFFIX)
    if(KINETIC_ASSEMBLY_WINDOWS_CRT_SUFFIX STREQUAL "md")
        set(KINETIC_ASSEMBLY_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>DLL")
    else()
        set(KINETIC_ASSEMBLY_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
    endif()
    set_property(TARGET kinetic_assembly_native PROPERTY MSVC_RUNTIME_LIBRARY "${KINETIC_ASSEMBLY_MSVC_RUNTIME_LIBRARY}")
endif()

set(KINETIC_ASSEMBLY_PHYSX_INSTALL_PRESETS)
if(WIN32)
    list(APPEND KINETIC_ASSEMBLY_PHYSX_INSTALL_PRESETS
        vc17win64-cpu-only
        vc17win64
        vc16win64-cpu-only
        vc16win64
    )
elseif(APPLE)
    list(APPEND KINETIC_ASSEMBLY_PHYSX_INSTALL_PRESETS
        mac-clang-cpu-only
        mac-clang
        osx-clang-cpu-only
        osx-clang
    )
elseif(UNIX)
    if(CMAKE_SYSTEM_PROCESSOR MATCHES "^(aarch64|arm64)$")
        list(APPEND KINETIC_ASSEMBLY_PHYSX_INSTALL_PRESETS
            linux-aarch64-gcc-cpu-only
            linux-aarch64-gcc
            linux-aarch64-clang-cpu-only
            linux-aarch64-clang
        )
    else()
        list(APPEND KINETIC_ASSEMBLY_PHYSX_INSTALL_PRESETS
            linux-gcc-cpu-only
            linux-gcc
            linux-clang-cpu-only
            linux-clang
        )
    endif()
endif()

set(KINETIC_ASSEMBLY_PHYSX_LIBRARY_HINTS "${PHYSX_LIB_DIR}")
if(KINETIC_ASSEMBLY_PHYSX_PLATFORM_BIN_NAME)
    list(APPEND KINETIC_ASSEMBLY_PHYSX_LIBRARY_HINTS
        "${PHYSX_ROOT}/bin/${KINETIC_ASSEMBLY_PHYSX_PLATFORM_BIN_NAME}"
    )
endif()
foreach(KINETIC_ASSEMBLY_PHYSX_INSTALL_PRESET IN LISTS KINETIC_ASSEMBLY_PHYSX_INSTALL_PRESETS)
    if(KINETIC_ASSEMBLY_PHYSX_PLATFORM_BIN_NAME)
        list(APPEND KINETIC_ASSEMBLY_PHYSX_LIBRARY_HINTS
            "${PHYSX_ROOT}/install/${KINETIC_ASSEMBLY_PHYSX_INSTALL_PRESET}/PhysX/bin/${KINETIC_ASSEMBLY_PHYSX_PLATFORM_BIN_NAME}"
        )
    endif()
    list(APPEND KINETIC_ASSEMBLY_PHYSX_LIBRARY_HINTS
        "${PHYSX_ROOT}/install/${KINETIC_ASSEMBLY_PHYSX_INSTALL_PRESET}/PhysX/lib"
    )
endforeach()

set(KINETIC_ASSEMBLY_PHYSX_LIBRARY_SUFFIXES "${KINETIC_ASSEMBLY_PHYSX_CONFIG}" "")

set(KINETIC_ASSEMBLY_PHYSX_CORE_NAMES PhysX_static_64 PhysX_static PhysX_64 PhysX)
set(KINETIC_ASSEMBLY_PHYSX_COMMON_NAMES PhysXCommon_static_64 PhysXCommon_static PhysXCommon_64 PhysXCommon)
set(KINETIC_ASSEMBLY_PHYSX_FOUNDATION_NAMES PhysXFoundation_static_64 PhysXFoundation_static PhysXFoundation_64 PhysXFoundation)
set(KINETIC_ASSEMBLY_PHYSX_EXTENSIONS_NAMES PhysXExtensions PhysXExtensions_64 PhysXExtensions_static PhysXExtensions_static_64)
set(KINETIC_ASSEMBLY_PHYSX_COOKING_NAMES PhysXCooking_static_64 PhysXCooking_static PhysXCooking_64 PhysXCooking)
set(KINETIC_ASSEMBLY_PHYSX_PVD_NAMES PhysXPvdSDK PhysXPvdSDK_64 PhysXPvdSDK_static PhysXPvdSDK_static_64)
set(KINETIC_ASSEMBLY_PHYSX_GPU_RUNTIME_NAMES PhysXGpu_64.dll libPhysXGpu_64.so libPhysXGpu_64.dylib PhysXGpu.dll libPhysXGpu.so libPhysXGpu.dylib)

message(STATUS "PhysX platform bin name: ${KINETIC_ASSEMBLY_PHYSX_PLATFORM_BIN_NAME}")
message(STATUS "PhysX library config: ${KINETIC_ASSEMBLY_PHYSX_CONFIG}")
if(MSVC)
    message(STATUS "MSVC runtime library: ${KINETIC_ASSEMBLY_MSVC_RUNTIME_LIBRARY}")
endif()

foreach(KINETIC_ASSEMBLY_PHYSX_CACHE_VAR
    KINETIC_ASSEMBLY_PHYSX_LIB
    KINETIC_ASSEMBLY_PHYSX_COMMON_LIB
    KINETIC_ASSEMBLY_PHYSX_FOUNDATION_LIB
    KINETIC_ASSEMBLY_PHYSX_EXTENSIONS_LIB
    KINETIC_ASSEMBLY_PHYSX_COOKING_LIB
    KINETIC_ASSEMBLY_PHYSX_PVD_LIB
    KINETIC_ASSEMBLY_PHYSX_GPU_RUNTIME
)
    unset(${KINETIC_ASSEMBLY_PHYSX_CACHE_VAR} CACHE)
    unset(${KINETIC_ASSEMBLY_PHYSX_CACHE_VAR})
endforeach()

find_library(KINETIC_ASSEMBLY_PHYSX_LIB NAMES ${KINETIC_ASSEMBLY_PHYSX_CORE_NAMES} PATHS ${KINETIC_ASSEMBLY_PHYSX_LIBRARY_HINTS} PATH_SUFFIXES ${KINETIC_ASSEMBLY_PHYSX_LIBRARY_SUFFIXES} NO_DEFAULT_PATH NO_CACHE)
find_library(KINETIC_ASSEMBLY_PHYSX_COMMON_LIB NAMES ${KINETIC_ASSEMBLY_PHYSX_COMMON_NAMES} PATHS ${KINETIC_ASSEMBLY_PHYSX_LIBRARY_HINTS} PATH_SUFFIXES ${KINETIC_ASSEMBLY_PHYSX_LIBRARY_SUFFIXES} NO_DEFAULT_PATH NO_CACHE)
find_library(KINETIC_ASSEMBLY_PHYSX_FOUNDATION_LIB NAMES ${KINETIC_ASSEMBLY_PHYSX_FOUNDATION_NAMES} PATHS ${KINETIC_ASSEMBLY_PHYSX_LIBRARY_HINTS} PATH_SUFFIXES ${KINETIC_ASSEMBLY_PHYSX_LIBRARY_SUFFIXES} NO_DEFAULT_PATH NO_CACHE)
find_library(KINETIC_ASSEMBLY_PHYSX_EXTENSIONS_LIB NAMES ${KINETIC_ASSEMBLY_PHYSX_EXTENSIONS_NAMES} PATHS ${KINETIC_ASSEMBLY_PHYSX_LIBRARY_HINTS} PATH_SUFFIXES ${KINETIC_ASSEMBLY_PHYSX_LIBRARY_SUFFIXES} NO_DEFAULT_PATH NO_CACHE)
find_library(KINETIC_ASSEMBLY_PHYSX_COOKING_LIB NAMES ${KINETIC_ASSEMBLY_PHYSX_COOKING_NAMES} PATHS ${KINETIC_ASSEMBLY_PHYSX_LIBRARY_HINTS} PATH_SUFFIXES ${KINETIC_ASSEMBLY_PHYSX_LIBRARY_SUFFIXES} NO_DEFAULT_PATH NO_CACHE)
find_library(KINETIC_ASSEMBLY_PHYSX_PVD_LIB NAMES ${KINETIC_ASSEMBLY_PHYSX_PVD_NAMES} PATHS ${KINETIC_ASSEMBLY_PHYSX_LIBRARY_HINTS} PATH_SUFFIXES ${KINETIC_ASSEMBLY_PHYSX_LIBRARY_SUFFIXES} NO_DEFAULT_PATH NO_CACHE)
find_file(KINETIC_ASSEMBLY_PHYSX_GPU_RUNTIME NAMES ${KINETIC_ASSEMBLY_PHYSX_GPU_RUNTIME_NAMES} PATHS ${KINETIC_ASSEMBLY_PHYSX_LIBRARY_HINTS} PATH_SUFFIXES ${KINETIC_ASSEMBLY_PHYSX_LIBRARY_SUFFIXES} NO_DEFAULT_PATH NO_CACHE)

set(KINETIC_ASSEMBLY_PLATFORM_LIBS)
if(WIN32)
    list(APPEND KINETIC_ASSEMBLY_PLATFORM_LIBS ws2_32 winmm)
elseif(APPLE)
    list(APPEND KINETIC_ASSEMBLY_PLATFORM_LIBS ${CMAKE_DL_LIBS} Threads::Threads)
elseif(UNIX)
    list(APPEND KINETIC_ASSEMBLY_PLATFORM_LIBS ${CMAKE_DL_LIBS} Threads::Threads rt)
endif()

if(KINETIC_ASSEMBLY_PHYSX_LIB AND KINETIC_ASSEMBLY_PHYSX_COMMON_LIB AND KINETIC_ASSEMBLY_PHYSX_FOUNDATION_LIB AND KINETIC_ASSEMBLY_PHYSX_EXTENSIONS_LIB AND KINETIC_ASSEMBLY_PHYSX_PVD_LIB)
    target_compile_definitions(kinetic_assembly_native PRIVATE KINETIC_ASSEMBLY_WITH_PHYSX=1)
    if(KINETIC_ASSEMBLY_PHYSX_COOKING_LIB)
        target_compile_definitions(kinetic_assembly_native PRIVATE KINETIC_ASSEMBLY_WITH_DEFORMABLE_VOLUME=1)
    else()
        target_compile_definitions(kinetic_assembly_native PRIVATE KINETIC_ASSEMBLY_WITH_DEFORMABLE_VOLUME=0)
    endif()
    foreach(KINETIC_ASSEMBLY_PHYSX_FOUND_LIB
        ${KINETIC_ASSEMBLY_PHYSX_LIB}
        ${KINETIC_ASSEMBLY_PHYSX_COMMON_LIB}
        ${KINETIC_ASSEMBLY_PHYSX_FOUNDATION_LIB}
        ${KINETIC_ASSEMBLY_PHYSX_EXTENSIONS_LIB}
        ${KINETIC_ASSEMBLY_PHYSX_COOKING_LIB}
        ${KINETIC_ASSEMBLY_PHYSX_PVD_LIB}
    )
        string(TOLOWER "${KINETIC_ASSEMBLY_PHYSX_FOUND_LIB}" KINETIC_ASSEMBLY_PHYSX_FOUND_LIB_LOWER)
        if(KINETIC_ASSEMBLY_PHYSX_FOUND_LIB_LOWER MATCHES "static")
            target_compile_definitions(kinetic_assembly_native PRIVATE PX_PHYSX_STATIC_LIB)
        endif()
    endforeach()
    target_link_libraries(kinetic_assembly_native
        PRIVATE
            ${KINETIC_ASSEMBLY_PHYSX_EXTENSIONS_LIB}
            ${KINETIC_ASSEMBLY_PHYSX_COOKING_LIB}
            ${KINETIC_ASSEMBLY_PHYSX_PVD_LIB}
            ${KINETIC_ASSEMBLY_PHYSX_LIB}
            ${KINETIC_ASSEMBLY_PHYSX_COMMON_LIB}
            ${KINETIC_ASSEMBLY_PHYSX_FOUNDATION_LIB}
            ${KINETIC_ASSEMBLY_PLATFORM_LIBS}
    )
    if(APPLE)
        set_target_properties(kinetic_assembly_native PROPERTIES
            BUILD_RPATH "@loader_path"
            INSTALL_RPATH "@loader_path"
        )
    elseif(UNIX)
        set_target_properties(kinetic_assembly_native PROPERTIES
            BUILD_RPATH "$ORIGIN"
            INSTALL_RPATH "$ORIGIN"
        )
    endif()
    if(KINETIC_ASSEMBLY_PHYSX_GPU_RUNTIME)
        add_custom_command(TARGET kinetic_assembly_native POST_BUILD
            COMMAND ${CMAKE_COMMAND} -E copy_if_different
                "${KINETIC_ASSEMBLY_PHYSX_GPU_RUNTIME}"
                "$<TARGET_FILE_DIR:kinetic_assembly_native>"
        )
    endif()
    message(STATUS "Building native bridge with PhysX from ${PHYSX_ROOT}")
    message(STATUS "PhysX libraries:")
    message(STATUS "  PhysX: ${KINETIC_ASSEMBLY_PHYSX_LIB}")
    message(STATUS "  PhysXCommon: ${KINETIC_ASSEMBLY_PHYSX_COMMON_LIB}")
    message(STATUS "  PhysXFoundation: ${KINETIC_ASSEMBLY_PHYSX_FOUNDATION_LIB}")
    message(STATUS "  PhysXExtensions: ${KINETIC_ASSEMBLY_PHYSX_EXTENSIONS_LIB}")
    message(STATUS "  PhysXCooking: ${KINETIC_ASSEMBLY_PHYSX_COOKING_LIB}")
    message(STATUS "  PhysXPvdSDK: ${KINETIC_ASSEMBLY_PHYSX_PVD_LIB}")
    if(KINETIC_ASSEMBLY_PHYSX_GPU_RUNTIME)
        message(STATUS "  PhysXGpu runtime: ${KINETIC_ASSEMBLY_PHYSX_GPU_RUNTIME}")
    else()
        message(STATUS "  PhysXGpu runtime: <not found; GPU dynamics will fall back unless runtime is available through PhysX loader hooks or system paths>")
    endif()
else()
    target_compile_definitions(kinetic_assembly_native PRIVATE KINETIC_ASSEMBLY_WITH_PHYSX=0)
    target_compile_definitions(kinetic_assembly_native PRIVATE KINETIC_ASSEMBLY_WITH_DEFORMABLE_VOLUME=0)
    message(WARNING "Built PhysX libraries were not found; building native bridge stub. Set PHYSX_LIB_DIR after building PhysX.")
endif()

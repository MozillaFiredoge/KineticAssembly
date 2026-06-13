package com.firedoge.kineticassembly.nativebridge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class NativeLibraryLoader {
    private static final Set<String> LOADED = ConcurrentHashMap.newKeySet();
    private static final List<String> WINDOWS_PHYSX_DEPENDENCIES = List.of(
            "PhysXFoundation_64.dll",
            "PhysXCommon_64.dll",
            "PhysX_64.dll"
    );
    private static final List<String> WINDOWS_OPTIONAL_PHYSX_DEPENDENCIES = List.of(
            "PhysXGpu_64.dll"
    );

    private NativeLibraryLoader() {
    }

    public static boolean isLoaded(String libraryName) {
        return LOADED.contains(libraryName);
    }

    public static synchronized void load(String libraryName) {
        if (LOADED.contains(libraryName)) {
            return;
        }

        if (loadFromConfiguredPath(libraryName)) {
            LOADED.add(libraryName);
            return;
        }

        NativeException bundledLoadFailure = null;
        try {
            loadBundled(libraryName, null);
            LOADED.add(libraryName);
            return;
        } catch (NativeException exception) {
            bundledLoadFailure = exception;
        }

        try {
            System.loadLibrary(libraryName);
            LOADED.add(libraryName);
        } catch (UnsatisfiedLinkError error) {
            if (bundledLoadFailure != null) {
                error.addSuppressed(bundledLoadFailure);
            }
            throw new NativeException(loadFailureMessage(libraryName, NativePlatform.detect()), error);
        }
    }

    private static boolean loadFromConfiguredPath(String libraryName) {
        String configuredPath = System.getProperty("kinetic_assembly.nativeLibraryPath");
        if (configuredPath == null || configuredPath.isBlank()) {
            configuredPath = System.getenv("KINETIC_ASSEMBLY_NATIVE_PATH");
        }
        if (configuredPath == null || configuredPath.isBlank()) {
            return false;
        }

        Path path = Paths.get(configuredPath);
        String mappedName = System.mapLibraryName(libraryName);
        Path library = Files.isDirectory(path) ? path.resolve(mappedName) : path;
        if (!Files.isRegularFile(library)) {
            return false;
        }

        Path libraryDirectory = Files.isDirectory(path) ? path : library.getParent();
        if (libraryDirectory != null) {
            loadConfiguredDependencies(libraryDirectory, NativePlatform.detect());
        }
        try {
            System.load(library.toAbsolutePath().toString());
        } catch (UnsatisfiedLinkError error) {
            throw new NativeException(loadFailureMessage(libraryName, NativePlatform.detect()), error);
        }
        return true;
    }

    private static void loadBundled(String libraryName, UnsatisfiedLinkError directLoadFailure) {
        NativePlatform platform = NativePlatform.detect();
        String mappedName = System.mapLibraryName(libraryName);
        String resourcePath = "/native/" + platform.resourcePath() + "/" + mappedName;

        try {
            Path tempDirectory = Files.createTempDirectory("kinetic_assembly-natives");
            tempDirectory.toFile().deleteOnExit();
            for (String dependencyName : bundledDependencyNames(platform)) {
                Optional<Path> dependency = extractBundledIfPresent(platform, dependencyName, tempDirectory);
                if (dependency.isPresent()) {
                    System.load(dependency.get().toAbsolutePath().toString());
                }
            }
            for (String dependencyName : optionalBundledDependencyNames(platform)) {
                Optional<Path> dependency = extractBundledIfPresent(platform, dependencyName, tempDirectory);
                dependency.ifPresent(NativeLibraryLoader::tryLoadOptional);
            }

            Path extractedLibrary = extractRequiredBundled(resourcePath, mappedName, tempDirectory, directLoadFailure);
            System.load(extractedLibrary.toAbsolutePath().toString());
        } catch (IOException | UnsatisfiedLinkError exception) {
            throw new NativeException(loadFailureMessage(libraryName, platform), exception);
        }
    }

    private static void loadConfiguredDependencies(Path directory, NativePlatform platform) {
        for (String dependencyName : bundledDependencyNames(platform)) {
            Path dependency = directory.resolve(dependencyName);
            if (Files.isRegularFile(dependency)) {
                System.load(dependency.toAbsolutePath().toString());
            }
        }
        for (String dependencyName : optionalBundledDependencyNames(platform)) {
            Path dependency = directory.resolve(dependencyName);
            if (Files.isRegularFile(dependency)) {
                tryLoadOptional(dependency);
            }
        }
    }

    private static List<String> bundledDependencyNames(NativePlatform platform) {
        return platform == NativePlatform.WINDOWS_X86_64 ? WINDOWS_PHYSX_DEPENDENCIES : List.of();
    }

    private static List<String> optionalBundledDependencyNames(NativePlatform platform) {
        return platform == NativePlatform.WINDOWS_X86_64 ? WINDOWS_OPTIONAL_PHYSX_DEPENDENCIES : List.of();
    }

    private static void tryLoadOptional(Path library) {
        try {
            System.load(library.toAbsolutePath().toString());
        } catch (UnsatisfiedLinkError ignored) {
            // Optional PhysX GPU runtime can be absent or unusable on CPU-only hosts.
        }
    }

    private static Optional<Path> extractBundledIfPresent(NativePlatform platform, String fileName, Path tempDirectory) throws IOException {
        String resourcePath = "/native/" + platform.resourcePath() + "/" + fileName;
        try (InputStream input = NativeLibraryLoader.class.getResourceAsStream(resourcePath)) {
            if (input == null) {
                return Optional.empty();
            }
            return Optional.of(copyNativeResource(input, fileName, tempDirectory));
        }
    }

    private static Path extractRequiredBundled(String resourcePath, String fileName, Path tempDirectory, UnsatisfiedLinkError directLoadFailure) throws IOException {
        try (InputStream input = NativeLibraryLoader.class.getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new NativeException("Missing bundled native library resource: " + resourcePath, directLoadFailure);
            }
            return copyNativeResource(input, fileName, tempDirectory);
        }
    }

    private static Path copyNativeResource(InputStream input, String fileName, Path tempDirectory) throws IOException {
        Path extractedLibrary = tempDirectory.resolve(fileName);
        Files.copy(input, extractedLibrary, StandardCopyOption.REPLACE_EXISTING);
        extractedLibrary.toFile().deleteOnExit();
        return extractedLibrary;
    }

    private static String loadFailureMessage(String libraryName, NativePlatform platform) {
        String message = "Failed to load native library " + libraryName;
        if (platform == NativePlatform.WINDOWS_X86_64) {
            message += "; Windows bundled builds must include kinetic_assembly_native.dll and any dynamic PhysX DLL dependencies "
                    + WINDOWS_PHYSX_DEPENDENCIES
                    + " in /native/" + platform.resourcePath()
                    + "/, or the bridge must be linked against static PhysX libraries. GPU dynamics also needs PhysXGpu_64.dll available next to the bridge";
        }
        return message;
    }
}

package com.firedoge.kineticassembly.nativebridge;

import java.util.Locale;

public enum NativePlatform {
    LINUX_X86_64("linux-x86_64"),
    WINDOWS_X86_64("windows-x86_64"),
    MACOS_X86_64("macos-x86_64"),
    MACOS_AARCH64("macos-aarch64");

    private final String resourcePath;

    NativePlatform(String resourcePath) {
        this.resourcePath = resourcePath;
    }

    public String resourcePath() {
        return resourcePath;
    }

    public static NativePlatform detect() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = normalizeArch(System.getProperty("os.arch", ""));

        if (os.contains("linux") && arch.equals("x86_64")) {
            return LINUX_X86_64;
        }
        if (os.contains("windows") && arch.equals("x86_64")) {
            return WINDOWS_X86_64;
        }
        if ((os.contains("mac") || os.contains("darwin")) && arch.equals("x86_64")) {
            return MACOS_X86_64;
        }
        if ((os.contains("mac") || os.contains("darwin")) && arch.equals("aarch64")) {
            return MACOS_AARCH64;
        }
        throw new NativeException("Unsupported native platform: " + os + "/" + arch);
    }

    private static String normalizeArch(String arch) {
        String normalized = arch.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "amd64", "x86-64", "x64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> normalized;
        };
    }
}

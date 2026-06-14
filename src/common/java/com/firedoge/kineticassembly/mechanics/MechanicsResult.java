package com.firedoge.kineticassembly.mechanics;

import java.util.Objects;
import java.util.Optional;

public record MechanicsResult<T>(boolean success, Optional<T> value, MechanicsResultCode code, String message) {
    public MechanicsResult {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
        if (success && code != MechanicsResultCode.SUCCESS) {
            throw new IllegalArgumentException("Successful results must use SUCCESS");
        }
        if (!success && code == MechanicsResultCode.SUCCESS) {
            throw new IllegalArgumentException("Failed results must not use SUCCESS");
        }
    }

    public static MechanicsResult<Void> ok() {
        return new MechanicsResult<>(true, Optional.empty(), MechanicsResultCode.SUCCESS, "");
    }

    public static <T> MechanicsResult<T> ok(T value) {
        return new MechanicsResult<>(true, Optional.of(Objects.requireNonNull(value, "value")), MechanicsResultCode.SUCCESS, "");
    }

    public static <T> MechanicsResult<T> failure(MechanicsResultCode code, String message) {
        return new MechanicsResult<>(false, Optional.empty(), code, message == null ? "" : message);
    }
}

package com.firedoge.kineticassembly.nativebridge;

public class NativeException extends RuntimeException {
    public NativeException(String message) {
        super(message);
    }

    public NativeException(String message, Throwable cause) {
        super(message, cause);
    }
}

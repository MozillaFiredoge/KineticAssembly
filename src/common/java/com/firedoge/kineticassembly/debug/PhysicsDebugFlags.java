package com.firedoge.kineticassembly.debug;

public final class PhysicsDebugFlags {
    private boolean renderCollisionShapes;
    private boolean logNativeCalls;

    public boolean renderCollisionShapes() {
        return renderCollisionShapes;
    }

    public void setRenderCollisionShapes(boolean renderCollisionShapes) {
        this.renderCollisionShapes = renderCollisionShapes;
    }

    public boolean logNativeCalls() {
        return logNativeCalls;
    }

    public void setLogNativeCalls(boolean logNativeCalls) {
        this.logNativeCalls = logNativeCalls;
    }
}

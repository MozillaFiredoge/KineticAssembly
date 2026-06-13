package com.firedoge.kineticassembly.render;

import java.util.Objects;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormatElement;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

final class TransformingVertexConsumer implements VertexConsumer {
    private final VertexConsumer delegate;
    private final Matrix4f pose;
    private final Matrix3f normal;

    TransformingVertexConsumer(VertexConsumer delegate, Matrix4f pose, Matrix3f normal) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.pose = new Matrix4f(Objects.requireNonNull(pose, "pose"));
        this.normal = new Matrix3f(Objects.requireNonNull(normal, "normal"));
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        Vector3f transformed = pose.transformPosition(x, y, z, new Vector3f());
        delegate.addVertex(transformed.x(), transformed.y(), transformed.z());
        return this;
    }

    @Override
    public VertexConsumer setColor(int red, int green, int blue, int alpha) {
        delegate.setColor(red, green, blue, alpha);
        return this;
    }

    @Override
    public VertexConsumer setColor(int color) {
        delegate.setColor(color);
        return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
        delegate.setUv(u, v);
        return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
        delegate.setUv1(u, v);
        return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
        delegate.setUv2(u, v);
        return this;
    }

    @Override
    public VertexConsumer setNormal(float normalX, float normalY, float normalZ) {
        Vector3f transformed = normal.transform(normalX, normalY, normalZ, new Vector3f()).normalize();
        delegate.setNormal(transformed.x(), transformed.y(), transformed.z());
        return this;
    }

    @Override
    public VertexConsumer setLineWidth(float width) {
        delegate.setLineWidth(width);
        return this;
    }

    @Override
    public VertexConsumer misc(VertexFormatElement element, int... rawData) {
        delegate.misc(element, rawData);
        return this;
    }
}

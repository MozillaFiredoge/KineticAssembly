package com.firedoge.kineticassembly.minecraft.assembly;

import com.firedoge.kineticassembly.api.PhysicsVector;

import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.AbstractHurtingProjectile;
import net.minecraft.world.phys.Vec3;

public final class AssemblyEntityKicking {
    private AssemblyEntityKicking() {
    }

    public static boolean shouldKick(Entity entity) {
        return !entity.getType().is(AssemblyEntityTags.RETAIN_IN_ASSEMBLY);
    }

    public static void kickEntity(Entity entity, AssemblyManager.PlotProjection projection) {
        Vec3 anchor = anchor(entity);
        Vec3 plotPosition = entity.position().add(anchor);
        PhysicsVector worldPosition = projection.toWorld(plotPosition);
        PhysicsVector worldMovement = projection.directionToWorld(entity.getDeltaMovement());
        PhysicsVector worldLook = projection.directionToWorld(entity.getLookAngle());
        if (!finite(worldPosition) || !finite(worldMovement) || !finite(worldLook)) {
            return;
        }

        entity.moveTo(worldPosition.x() - anchor.x, worldPosition.y() - anchor.y, worldPosition.z() - anchor.z);
        entity.setDeltaMovement(worldMovement.x(), worldMovement.y(), worldMovement.z());
        entity.lookAt(
                EntityAnchorArgument.Anchor.FEET,
                entity.position().add(worldLook.x(), worldLook.y(), worldLook.z())
        );
        fixArrowRotation(entity);
    }

    public static void kickEntity(Entity entity, AssemblyPlotProjection projection) {
        Vec3 anchor = anchor(entity);
        Vec3 projectedPosition = projection.plotToWorld(entity.position().add(anchor)).subtract(anchor);
        Vec3 projectedMovement = projection.plotDirectionToWorld(entity.getDeltaMovement());
        Vec3 projectedLook = projection.plotDirectionToWorld(entity.getLookAngle());

        if (!finite(projectedPosition) || !finite(projectedMovement) || !finite(projectedLook)) {
            return;
        }

        entity.moveTo(projectedPosition);
        entity.setDeltaMovement(projectedMovement.add(gainedVelocity(entity, projection)));
        entity.lookAt(EntityAnchorArgument.Anchor.FEET, entity.position().add(projectedLook));
        fixArrowRotation(entity);
    }

    private static Vec3 gainedVelocity(Entity entity, AssemblyPlotProjection projection) {
        if (entity instanceof AbstractHurtingProjectile projectile && projectile.accelerationPower == 0.0D) {
            return projection.pointVelocity(entity.position());
        }
        return Vec3.ZERO;
    }

    private static Vec3 anchor(Entity entity) {
        if (entity instanceof FallingBlockEntity) {
            return new Vec3(0.0D, entity.getBbHeight() * 0.5D, 0.0D);
        }
        return Vec3.ZERO;
    }

    private static void fixArrowRotation(Entity entity) {
        if (!(entity instanceof AbstractArrow)) {
            return;
        }

        Vec3 deltaMovement = entity.getDeltaMovement();
        double horizontal = deltaMovement.horizontalDistance();
        entity.setYRot((float) (Mth.atan2(deltaMovement.x, deltaMovement.z) * 180.0D / Math.PI));
        entity.setXRot((float) (Mth.atan2(deltaMovement.y, horizontal) * 180.0D / Math.PI));
    }

    private static boolean finite(Vec3 vector) {
        return Double.isFinite(vector.x)
                && Double.isFinite(vector.y)
                && Double.isFinite(vector.z);
    }

    private static boolean finite(PhysicsVector vector) {
        return Double.isFinite(vector.x())
                && Double.isFinite(vector.y())
                && Double.isFinite(vector.z());
    }
}

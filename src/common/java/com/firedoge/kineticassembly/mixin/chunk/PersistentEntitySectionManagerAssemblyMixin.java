package com.firedoge.kineticassembly.mixin.chunk;

import java.util.List;

import com.firedoge.kineticassembly.minecraft.assembly.AssemblyContainers;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyEntityKicking;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PersistentEntitySectionManager.class)
public class PersistentEntitySectionManagerAssemblyMixin {
    @Shadow
    @Final
    public EntitySectionStorage<EntityAccess> sectionStorage;

    @Inject(method = "processChunkUnload", at = @At("HEAD"))
    private void kinetic_assembly$detachAssemblyPlayerPassenger(long chunkPos, CallbackInfoReturnable<Boolean> cir) {
        List<EntitySection<EntityAccess>> sections = this.sectionStorage
                .getExistingSectionsInChunk(chunkPos)
                .toList();

        for (EntitySection<EntityAccess> section : sections) {
            for (EntityAccess entityAccess : section.getEntities().toList()) {
                Entity entity = (Entity) entityAccess;
                boolean inPlot = AssemblyContainers.container(entity.level())
                        .map(container -> container.inPlotBounds(entity.chunkPosition()))
                        .orElse(false);
                if (!inPlot
                        || entity.getRemovalReason() != null && !entity.getRemovalReason().shouldSave()
                        || !AssemblyEntityKicking.shouldKick(entity)
                        || !entity.isVehicle()
                        || !entity.hasExactlyOnePlayerPassenger()) {
                    continue;
                }
                entity.getPassengers().getFirst().removeVehicle();
            }
        }
    }
}

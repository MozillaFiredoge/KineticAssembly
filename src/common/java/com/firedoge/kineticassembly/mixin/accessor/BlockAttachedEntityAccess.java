package com.firedoge.kineticassembly.mixin.accessor;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.decoration.BlockAttachedEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(BlockAttachedEntity.class)
public interface BlockAttachedEntityAccess {
    @Accessor("pos")
    void kinetic_assembly$setAttachmentPos(BlockPos pos);
}

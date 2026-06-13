package com.firedoge.kineticassembly.mixin.level;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

import com.firedoge.kineticassembly.api.PhysicsVector;
import com.firedoge.kineticassembly.minecraft.assembly.ServerAssemblyContainer;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyContainerHolder;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyEntityBridge;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyEntityKicking;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyManager;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.BlockAttachedEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin implements AssemblyContainerHolder {
    @Unique
    private final ServerAssemblyContainer kinetic_assembly$assemblyContainer =
            new ServerAssemblyContainer((ServerLevel) (Object) this);
    @Unique
    private boolean kinetic_assembly$projectingAssemblyEffect;
    @Unique
    private boolean kinetic_assembly$projectingAssemblyEntity;
    @Unique
    private final Map<BlockPos, Long> kinetic_assembly$plotPrimedTntBlocks = new HashMap<>();

    @Override
    public ServerAssemblyContainer kinetic_assembly$assemblyContainer() {
        return kinetic_assembly$assemblyContainer;
    }

    @Inject(method = "tick(Ljava/util/function/BooleanSupplier;)V", at = @At("HEAD"))
    private void kinetic_assembly$tickPlotBlockUpdatePrimes(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        kinetic_assembly$assemblyContainer.tickPlotBlockUpdatePrimes();
    }

    @Inject(
            method = "playSeededSound(Lnet/minecraft/world/entity/player/Player;DDDLnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void kinetic_assembly$playPlotSound(
            Player player,
            double x,
            double y,
            double z,
            Holder<SoundEvent> sound,
            SoundSource category,
            float volume,
            float pitch,
            long seed,
            CallbackInfo ci
    ) {
        if (kinetic_assembly$projectingAssemblyEffect) {
            return;
        }
        PhysicsVector world = AssemblyManager.INSTANCE
                .plotPositionToWorld(kinetic_assembly$self(), new Vec3(x, y, z))
                .orElse(null);
        if (world == null) {
            return;
        }

        kinetic_assembly$projectingAssemblyEffect = true;
        try {
            kinetic_assembly$self().playSeededSound(player, world.x(), world.y(), world.z(), sound, category, volume, pitch, seed);
        } finally {
            kinetic_assembly$projectingAssemblyEffect = false;
        }
        ci.cancel();
    }

    @Inject(method = "globalLevelEvent", at = @At("HEAD"), cancellable = true)
    private void kinetic_assembly$globalPlotLevelEvent(int id, BlockPos pos, int data, CallbackInfo ci) {
        if (kinetic_assembly$projectingAssemblyEffect) {
            return;
        }
        PhysicsVector world = kinetic_assembly$plotBlockWorldCenter(pos);
        if (world == null) {
            return;
        }

        if (kinetic_assembly$self().getGameRules().getBoolean(GameRules.RULE_GLOBAL_SOUND_EVENTS)) {
            kinetic_assembly$self().getServer().getPlayerList().broadcastAll(new ClientboundLevelEventPacket(id, pos, data, true));
        } else {
            kinetic_assembly$self().getServer().getPlayerList().broadcast(
                    null,
                    world.x(),
                    world.y(),
                    world.z(),
                    64.0D,
                    kinetic_assembly$self().dimension(),
                    new ClientboundLevelEventPacket(id, pos, data, false)
            );
        }
        ci.cancel();
    }

    @Inject(method = "levelEvent", at = @At("HEAD"), cancellable = true)
    private void kinetic_assembly$plotLevelEvent(Player player, int type, BlockPos pos, int data, CallbackInfo ci) {
        if (kinetic_assembly$projectingAssemblyEffect) {
            return;
        }
        PhysicsVector world = kinetic_assembly$plotBlockWorldCenter(pos);
        if (world == null) {
            return;
        }

        kinetic_assembly$self().getServer().getPlayerList().broadcast(
                player,
                world.x(),
                world.y(),
                world.z(),
                64.0D,
                kinetic_assembly$self().dimension(),
                new ClientboundLevelEventPacket(type, pos, data, false)
        );
        ci.cancel();
    }

    @Inject(
            method = "sendParticles(Lnet/minecraft/core/particles/ParticleOptions;DDDIDDDD)I",
            at = @At("HEAD"),
            cancellable = true
    )
    private <T extends ParticleOptions> void kinetic_assembly$sendPlotParticles(
            T type,
            double posX,
            double posY,
            double posZ,
            int particleCount,
            double xOffset,
            double yOffset,
            double zOffset,
            double speed,
            CallbackInfoReturnable<Integer> cir
    ) {
        if (kinetic_assembly$projectingAssemblyEffect) {
            return;
        }
        PhysicsVector world = AssemblyManager.INSTANCE
                .plotPositionToWorld(kinetic_assembly$self(), new Vec3(posX, posY, posZ))
                .orElse(null);
        if (world == null) {
            return;
        }

        kinetic_assembly$projectingAssemblyEffect = true;
        try {
            cir.setReturnValue(kinetic_assembly$self().sendParticles(type, world.x(), world.y(), world.z(), particleCount, xOffset, yOffset, zOffset, speed));
        } finally {
            kinetic_assembly$projectingAssemblyEffect = false;
        }
    }

    @Inject(
            method = "sendParticles(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/core/particles/ParticleOptions;ZDDDIDDDD)Z",
            at = @At("HEAD"),
            cancellable = true
    )
    private <T extends ParticleOptions> void kinetic_assembly$sendPlotParticlesToPlayer(
            ServerPlayer player,
            T type,
            boolean longDistance,
            double posX,
            double posY,
            double posZ,
            int particleCount,
            double xOffset,
            double yOffset,
            double zOffset,
            double speed,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (kinetic_assembly$projectingAssemblyEffect) {
            return;
        }
        PhysicsVector world = AssemblyManager.INSTANCE
                .plotPositionToWorld(kinetic_assembly$self(), new Vec3(posX, posY, posZ))
                .orElse(null);
        if (world == null) {
            return;
        }

        kinetic_assembly$projectingAssemblyEffect = true;
        try {
            cir.setReturnValue(kinetic_assembly$self().sendParticles(player, type, longDistance, world.x(), world.y(), world.z(), particleCount, xOffset, yOffset, zOffset, speed));
        } finally {
            kinetic_assembly$projectingAssemblyEffect = false;
        }
    }

    @Inject(method = "gameEvent", at = @At("HEAD"), cancellable = true)
    private void kinetic_assembly$plotGameEvent(Holder<GameEvent> gameEvent, Vec3 pos, GameEvent.Context context, CallbackInfo ci) {
        if (kinetic_assembly$projectingAssemblyEffect) {
            return;
        }
        PhysicsVector world = AssemblyManager.INSTANCE.plotPositionToWorld(kinetic_assembly$self(), pos).orElse(null);
        if (world == null) {
            return;
        }

        kinetic_assembly$projectingAssemblyEffect = true;
        try {
            kinetic_assembly$self().gameEvent(gameEvent, new Vec3(world.x(), world.y(), world.z()), context);
        } finally {
            kinetic_assembly$projectingAssemblyEffect = false;
        }
        ci.cancel();
    }

    @Redirect(
            method = "runBlockEvents",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/players/PlayerList;broadcast(Lnet/minecraft/world/entity/player/Player;DDDDLnet/minecraft/resources/ResourceKey;Lnet/minecraft/network/protocol/Packet;)V"
            )
    )
    private void kinetic_assembly$broadcastPlotBlockEvent(
            PlayerList playerList,
            Player except,
            double x,
            double y,
            double z,
            double radius,
            ResourceKey<Level> dimension,
            Packet<?> packet
    ) {
        if (packet instanceof ClientboundBlockEventPacket blockEvent) {
            PhysicsVector world = AssemblyManager.INSTANCE
                    .plotPositionToWorld(kinetic_assembly$self(), Vec3.atCenterOf(blockEvent.getPos()))
                    .orElse(null);
            if (world != null) {
                playerList.broadcast(except, world.x(), world.y(), world.z(), radius, dimension, packet);
                return;
            }
        }
        playerList.broadcast(except, x, y, z, radius, dimension, packet);
    }

    @Inject(method = "addFreshEntity", at = @At("HEAD"), cancellable = true)
    private void kinetic_assembly$addPlotEntityAtWorldPosition(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        ServerLevel level = kinetic_assembly$self();
        if (kinetic_assembly$projectingAssemblyEntity || entity.level() != level) {
            return;
        }
        Vec3 plotPosition = entity.position();
        AssemblyManager.PlotProjection projection = kinetic_assembly$entityPlotProjection(plotPosition);
        if (projection == null) {
            AssemblyEntityBridge.debugAttachedEntity(
                    "server-add",
                    level,
                    entity,
                    "result=pass-through reason=projection-null plotPosition=" + plotPosition
                            + " adjacentProjection=" + AssemblyEntityBridge
                            .plotProjectionAtOrAdjacent(level, BlockPos.containing(plotPosition))
                            .isPresent()
            );
            return;
        }
        if (entity instanceof PrimedTnt && !kinetic_assembly$claimPlotPrimedTnt(level, plotPosition)) {
            cir.setReturnValue(false);
            return;
        }

        if (!AssemblyEntityKicking.shouldKick(entity)) {
            if (entity instanceof BlockAttachedEntity blockAttachedEntity) {
                AssemblyEntityBridge.debugAttachedEntity(
                        "server-add",
                        level,
                        entity,
                        "result=register-retained projection=" + projection.getClass().getSimpleName()
                                + " plotPosition=" + plotPosition
                );
                kinetic_assembly$addRetainedPlotAttachedEntity(level, blockAttachedEntity, plotPosition, cir);
            }
            return;
        }

        AssemblyEntityKicking.kickEntity(entity, projection);

        kinetic_assembly$projectingAssemblyEntity = true;
        try {
            boolean added = level.addFreshEntity(entity);
            cir.setReturnValue(added);
        } finally {
            kinetic_assembly$projectingAssemblyEntity = false;
        }
    }

    @Unique
    private void kinetic_assembly$addRetainedPlotAttachedEntity(
            ServerLevel level,
            BlockAttachedEntity entity,
            Vec3 plotPosition,
            CallbackInfoReturnable<Boolean> cir
    ) {
        BlockPos plotAnchor = entity.getPos();
        AABB plotBounds = entity.getBoundingBox();
        AssemblyEntityBridge.registerPlotAttachedEntity(level, entity, plotAnchor, plotPosition, plotBounds);

        kinetic_assembly$projectingAssemblyEntity = true;
        try {
            boolean added = level.addFreshEntity(entity);
            if (!added) {
                AssemblyEntityBridge.unregisterPlotAttachedEntity(level, entity);
            }
            AssemblyEntityBridge.debugAttachedEntity(
                    "server-add-retained",
                    level,
                    entity,
                    "added=" + added + " plotAnchor=" + plotAnchor + " plotPosition=" + plotPosition + " plotBounds=" + plotBounds
            );
            cir.setReturnValue(added);
        } finally {
            kinetic_assembly$projectingAssemblyEntity = false;
        }
    }

    @Unique
    private boolean kinetic_assembly$claimPlotPrimedTnt(ServerLevel level, Vec3 plotPosition) {
        long gameTime = level.getGameTime();
        kinetic_assembly$plotPrimedTntBlocks.entrySet().removeIf(entry -> entry.getValue() != gameTime);
        BlockPos plotBlock = BlockPos.containing(plotPosition.x, plotPosition.y, plotPosition.z).immutable();
        Long previous = kinetic_assembly$plotPrimedTntBlocks.put(plotBlock, gameTime);
        return previous == null || previous.longValue() != gameTime;
    }

    @ModifyConstant(method = "destroyBlockProgress", constant = @Constant(doubleValue = 1024.0D))
    private double kinetic_assembly$sendPlotDestroyProgressPastVanillaRange(double originalDistance) {
        return Double.MAX_VALUE;
    }

    @Inject(method = "shouldTickBlocksAt", at = @At("HEAD"), cancellable = true)
    private void kinetic_assembly$shouldTickPlotBlocksAt(long chunkPos, CallbackInfoReturnable<Boolean> cir) {
        if (kinetic_assembly$assemblyContainer.plotChunkHolder(new ChunkPos(chunkPos)).isPresent()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "isPositionTickingWithEntitiesLoaded", at = @At("HEAD"), cancellable = true)
    private void kinetic_assembly$isPlotPositionTickingWithEntitiesLoaded(long chunkPos, CallbackInfoReturnable<Boolean> cir) {
        if (kinetic_assembly$assemblyContainer.plotChunkHolder(new ChunkPos(chunkPos)).isPresent()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "isNaturalSpawningAllowed(Lnet/minecraft/world/level/ChunkPos;)Z", at = @At("HEAD"), cancellable = true)
    private void kinetic_assembly$isNaturalSpawningAllowedInPlot(ChunkPos chunkPos, CallbackInfoReturnable<Boolean> cir) {
        if (kinetic_assembly$assemblyContainer.plotChunkHolder(chunkPos).isPresent()) {
            cir.setReturnValue(true);
        }
    }

    @Unique
    private PhysicsVector kinetic_assembly$plotBlockWorldCenter(BlockPos pos) {
        return AssemblyManager.INSTANCE.plotPositionToWorld(kinetic_assembly$self(), Vec3.atCenterOf(pos))
                .orElse(null);
    }

    @Unique
    private AssemblyManager.PlotProjection kinetic_assembly$entityPlotProjection(Vec3 plotPosition) {
        BlockPos origin = BlockPos.containing(plotPosition);
        AssemblyManager.PlotProjection projection = AssemblyManager.INSTANCE
                .plotProjection(kinetic_assembly$self(), origin)
                .orElse(null);
        if (projection != null) {
            return projection;
        }
        for (Direction direction : Direction.values()) {
            projection = AssemblyManager.INSTANCE
                    .plotProjection(kinetic_assembly$self(), origin.relative(direction))
                    .orElse(null);
            if (projection != null) {
                return projection;
            }
        }
        return null;
    }

    @Unique
    private ServerLevel kinetic_assembly$self() {
        return (ServerLevel) (Object) this;
    }
}

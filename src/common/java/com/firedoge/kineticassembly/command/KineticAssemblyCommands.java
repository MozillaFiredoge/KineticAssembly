package com.firedoge.kineticassembly.command;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import com.firedoge.kineticassembly.KineticAssembly;
import com.firedoge.kineticassembly.api.PhysicsPose;
import com.firedoge.kineticassembly.api.PhysicsQuaternion;
import com.firedoge.kineticassembly.api.PhysicsVector;
import com.firedoge.kineticassembly.compat.aerodynamics.AerodynamicsCompat;
import com.firedoge.kineticassembly.mechanics.MechanicsBodySnapshot;
import com.firedoge.kineticassembly.mechanics.MechanicsBoxDefinition;
import com.firedoge.kineticassembly.mechanics.MechanicsDebugProxy;
import com.firedoge.kineticassembly.mechanics.MechanicsDebugProxies;
import com.firedoge.kineticassembly.mechanics.MechanicsJointSnapshot;
import com.firedoge.kineticassembly.mechanics.MechanicsWorld;
import com.firedoge.kineticassembly.minecraft.elastic.ElasticPanelManager;
import com.firedoge.kineticassembly.minecraft.elastic.ElasticPanelSnapshot;
import com.firedoge.kineticassembly.minecraft.fem.FemVolumeManager;
import com.firedoge.kineticassembly.minecraft.fem.FemVolumeSnapshot;
import com.firedoge.kineticassembly.minecraft.player.PlayerPhysicsManager;
import com.firedoge.kineticassembly.minecraft.player.PlayerPhysicsSnapshot;
import com.firedoge.kineticassembly.minecraft.player.PlayerProxyManager;
import com.firedoge.kineticassembly.minecraft.player.PlayerProxySnapshot;
import com.firedoge.kineticassembly.minecraft.scene.PhysicsObjectSnapshot;
import com.firedoge.kineticassembly.minecraft.scene.PhysicsObjectType;
import com.firedoge.kineticassembly.minecraft.scene.ServerPhysicsRuntime;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyBreakResult;
import com.firedoge.kineticassembly.minecraft.assembly.ServerAssemblyContainer;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyContainers;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyLoadingTicketType;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyManager;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyPickResult;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyProfiler;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblySnapshot;
import com.firedoge.kineticassembly.minecraft.assembly.AssemblyVerticalTrace;

import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Unit;
import net.minecraft.world.phys.Vec3;

public final class KineticAssemblyCommands {
    private KineticAssemblyCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("kinetic_assembly")
                .requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("spawn_box")
                        .executes(context -> spawnBox(context.getSource(), 1.0F, 1.0F))
                        .then(Commands.argument("size", FloatArgumentType.floatArg(0.05F, 16.0F))
                                .executes(context -> spawnBox(
                                        context.getSource(),
                                        FloatArgumentType.getFloat(context, "size"),
                                        1.0F
                                ))
                                .then(Commands.argument("mass", FloatArgumentType.floatArg(0.01F, 10000.0F))
                                        .executes(context -> spawnBox(
                                                context.getSource(),
                                                FloatArgumentType.getFloat(context, "size"),
                                                FloatArgumentType.getFloat(context, "mass")
                                        )))))
                .then(Commands.literal("spawn_stress_grid")
                        .then(Commands.argument("countX", IntegerArgumentType.integer(1, 128))
                                .then(Commands.argument("countY", IntegerArgumentType.integer(1, 128))
                                        .then(Commands.argument("countZ", IntegerArgumentType.integer(1, 128))
                                                .executes(context -> spawnStressGrid(
                                                        context.getSource(),
                                                        IntegerArgumentType.getInteger(context, "countX"),
                                                        IntegerArgumentType.getInteger(context, "countY"),
                                                        IntegerArgumentType.getInteger(context, "countZ"),
                                                        1.2F,
                                                        1.0F,
                                                        1.0F
                                                ))
                                                .then(Commands.argument("spacing", FloatArgumentType.floatArg(0.05F, 16.0F))
                                                        .executes(context -> spawnStressGrid(
                                                                context.getSource(),
                                                                IntegerArgumentType.getInteger(context, "countX"),
                                                                IntegerArgumentType.getInteger(context, "countY"),
                                                                IntegerArgumentType.getInteger(context, "countZ"),
                                                                FloatArgumentType.getFloat(context, "spacing"),
                                                                1.0F,
                                                                1.0F
                                                        ))
                                                        .then(Commands.argument("size", FloatArgumentType.floatArg(0.05F, 16.0F))
                                                                .executes(context -> spawnStressGrid(
                                                                        context.getSource(),
                                                                        IntegerArgumentType.getInteger(context, "countX"),
                                                                        IntegerArgumentType.getInteger(context, "countY"),
                                                                        IntegerArgumentType.getInteger(context, "countZ"),
                                                                        FloatArgumentType.getFloat(context, "spacing"),
                                                                        FloatArgumentType.getFloat(context, "size"),
                                                                        1.0F
                                                                ))
                                                                .then(Commands.argument("mass", FloatArgumentType.floatArg(0.01F, 10000.0F))
                                                                        .executes(context -> spawnStressGrid(
                                                                                context.getSource(),
                                                                                IntegerArgumentType.getInteger(context, "countX"),
                                                                                IntegerArgumentType.getInteger(context, "countY"),
                                                                                IntegerArgumentType.getInteger(context, "countZ"),
                                                                                FloatArgumentType.getFloat(context, "spacing"),
                                                                                FloatArgumentType.getFloat(context, "size"),
                                                                                FloatArgumentType.getFloat(context, "mass")
                                                                        )))))))))
                .then(Commands.literal("set_velocity")
                        .then(Commands.argument("x", FloatArgumentType.floatArg(-100.0F, 100.0F))
                                .then(Commands.argument("y", FloatArgumentType.floatArg(-100.0F, 100.0F))
                                        .then(Commands.argument("z", FloatArgumentType.floatArg(-100.0F, 100.0F))
                                                .executes(context -> setVelocity(
                                                        context.getSource(),
                                                        FloatArgumentType.getFloat(context, "x"),
                                                        FloatArgumentType.getFloat(context, "y"),
                                                        FloatArgumentType.getFloat(context, "z")
                                                ))))))
                .then(Commands.literal("list_boxes")
                        .executes(context -> listBoxes(context.getSource(), 8))
                        .then(Commands.argument("limit", IntegerArgumentType.integer(1, 20))
                                .executes(context -> listBoxes(
                                        context.getSource(),
                                        IntegerArgumentType.getInteger(context, "limit")
                                ))))
                .then(Commands.literal("remove_nearest")
                        .executes(context -> removeNearest(context.getSource(), 32.0F))
                        .then(Commands.argument("maxDistance", FloatArgumentType.floatArg(1.0F, 256.0F))
                                .executes(context -> removeNearest(
                                        context.getSource(),
                                        FloatArgumentType.getFloat(context, "maxDistance")
                                ))))
                .then(Commands.literal("remove_box")
                        .then(Commands.argument("idPrefix", StringArgumentType.word())
                                .executes(context -> removeBox(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "idPrefix")
                                ))))
                .then(blockCommands())
                .then(assemblyCommands())
                .then(playerPhysicsCommands())
                .then(playerProxyCommands())
                .then(mechanicsCommands())
                .then(elasticCommands())
                .then(femCommands())
                .then(Commands.literal("aero_debug_particles")
                        .executes(context -> aeroDebugParticlesStatus(context.getSource()))
                        .then(Commands.argument("enabled", BoolArgumentType.bool())
                                .executes(context -> aeroDebugParticles(
                                        context.getSource(),
                                        BoolArgumentType.getBool(context, "enabled")
                                ))))
                .then(Commands.literal("physics_status")
                        .executes(context -> physicsStatus(context.getSource())))
                .then(Commands.literal("assembly_profile")
                        .executes(context -> assemblyProfileStatus(context.getSource()))
                        .then(Commands.literal("start")
                                .executes(context -> assemblyProfileStart(context.getSource(), 200))
                                .then(Commands.argument("ticks", IntegerArgumentType.integer(1, 6000))
                                        .executes(context -> assemblyProfileStart(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "ticks")
                                        ))))
                        .then(Commands.literal("stop")
                                .executes(context -> assemblyProfileStop(context.getSource()))))
                .then(Commands.literal("vertical_trace")
                        .executes(context -> verticalTrace(context.getSource(), 200))
                        .then(Commands.argument("ticks", IntegerArgumentType.integer(1, 1200))
                                .executes(context -> verticalTrace(
                                        context.getSource(),
                                        IntegerArgumentType.getInteger(context, "ticks")
                                ))))
                .then(Commands.literal("clear")
                        .executes(context -> clear(context.getSource()))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> playerPhysicsCommands() {
        return Commands.literal("player_physics")
                .then(Commands.literal("enable")
                        .executes(context -> playerPhysicsEnable(
                                context.getSource(),
                                PlayerPhysicsManager.DEFAULT_PLAYER_MASS,
                                false
                        ))
                        .then(Commands.argument("mass", FloatArgumentType.floatArg(0.01F, 10000.0F))
                                .executes(context -> playerPhysicsEnable(
                                        context.getSource(),
                                        FloatArgumentType.getFloat(context, "mass"),
                                        false
                                ))
                                .then(Commands.argument("debugProxy", BoolArgumentType.bool())
                                        .executes(context -> playerPhysicsEnable(
                                                context.getSource(),
                                                FloatArgumentType.getFloat(context, "mass"),
                                                BoolArgumentType.getBool(context, "debugProxy")
                                        )))))
                .then(Commands.literal("disable")
                        .executes(context -> playerPhysicsDisable(context.getSource())))
                .then(Commands.literal("status")
                        .executes(context -> playerPhysicsStatus(context.getSource())))
                .then(Commands.literal("impulse")
                        .then(Commands.argument("x", FloatArgumentType.floatArg(-100000.0F, 100000.0F))
                                .then(Commands.argument("y", FloatArgumentType.floatArg(-100000.0F, 100000.0F))
                                        .then(Commands.argument("z", FloatArgumentType.floatArg(-100000.0F, 100000.0F))
                                                .executes(context -> playerPhysicsImpulse(
                                                        context.getSource(),
                                                        FloatArgumentType.getFloat(context, "x"),
                                                        FloatArgumentType.getFloat(context, "y"),
                                                        FloatArgumentType.getFloat(context, "z")
                                                ))))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> playerProxyCommands() {
        return Commands.literal("player_proxy")
                .then(Commands.literal("enable")
                        .executes(context -> playerProxyEnable(
                                context.getSource(),
                                PlayerProxyManager.DEFAULT_PROXY_MASS,
                                false
                        ))
                        .then(Commands.argument("mass", FloatArgumentType.floatArg(0.01F, 10000.0F))
                                .executes(context -> playerProxyEnable(
                                        context.getSource(),
                                        FloatArgumentType.getFloat(context, "mass"),
                                        false
                                ))
                                .then(Commands.argument("debugProxy", BoolArgumentType.bool())
                                        .executes(context -> playerProxyEnable(
                                                context.getSource(),
                                                FloatArgumentType.getFloat(context, "mass"),
                                                BoolArgumentType.getBool(context, "debugProxy")
                                        )))))
                .then(Commands.literal("disable")
                        .executes(context -> playerProxyDisable(context.getSource())))
                .then(Commands.literal("status")
                        .executes(context -> playerProxyStatus(context.getSource())));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> mechanicsCommands() {
        return Commands.literal("mechanics")
                .then(Commands.literal("spawn_box")
                        .executes(context -> mechanicsSpawnBox(context.getSource(), 1.0F, 1.0F, false))
                        .then(Commands.argument("size", FloatArgumentType.floatArg(0.05F, 16.0F))
                                .executes(context -> mechanicsSpawnBox(
                                        context.getSource(),
                                        FloatArgumentType.getFloat(context, "size"),
                                        1.0F,
                                        false
                                ))
                                .then(Commands.argument("mass", FloatArgumentType.floatArg(0.01F, 10000.0F))
                                        .executes(context -> mechanicsSpawnBox(
                                                context.getSource(),
                                                FloatArgumentType.getFloat(context, "size"),
                                                FloatArgumentType.getFloat(context, "mass"),
                                                false
                                        ))
                                        .then(Commands.argument("debugProxy", BoolArgumentType.bool())
                                                .executes(context -> mechanicsSpawnBox(
                                                        context.getSource(),
                                                        FloatArgumentType.getFloat(context, "size"),
                                                        FloatArgumentType.getFloat(context, "mass"),
                                                        BoolArgumentType.getBool(context, "debugProxy")
                                                ))))))
                .then(Commands.literal("list")
                        .executes(context -> mechanicsList(context.getSource(), 8))
                        .then(Commands.argument("limit", IntegerArgumentType.integer(1, 50))
                                .executes(context -> mechanicsList(
                                        context.getSource(),
                                        IntegerArgumentType.getInteger(context, "limit")
                                ))))
                .then(Commands.literal("impulse")
                        .then(Commands.argument("idPrefix", StringArgumentType.word())
                                .then(Commands.argument("x", FloatArgumentType.floatArg(-100000.0F, 100000.0F))
                                        .then(Commands.argument("y", FloatArgumentType.floatArg(-100000.0F, 100000.0F))
                                                .then(Commands.argument("z", FloatArgumentType.floatArg(-100000.0F, 100000.0F))
                                                        .executes(context -> mechanicsImpulse(
                                                                context.getSource(),
                                                                StringArgumentType.getString(context, "idPrefix"),
                                                                FloatArgumentType.getFloat(context, "x"),
                                                                FloatArgumentType.getFloat(context, "y"),
                                                                FloatArgumentType.getFloat(context, "z")
                                                        )))))))
                .then(Commands.literal("torque_impulse")
                        .then(Commands.argument("idPrefix", StringArgumentType.word())
                                .then(Commands.argument("x", FloatArgumentType.floatArg(-100000.0F, 100000.0F))
                                        .then(Commands.argument("y", FloatArgumentType.floatArg(-100000.0F, 100000.0F))
                                                .then(Commands.argument("z", FloatArgumentType.floatArg(-100000.0F, 100000.0F))
                                                        .executes(context -> mechanicsTorqueImpulse(
                                                                context.getSource(),
                                                                StringArgumentType.getString(context, "idPrefix"),
                                                                FloatArgumentType.getFloat(context, "x"),
                                                                FloatArgumentType.getFloat(context, "y"),
                                                                FloatArgumentType.getFloat(context, "z")
                                                        )))))))
                .then(Commands.literal("remove")
                        .then(Commands.argument("idPrefix", StringArgumentType.word())
                                .executes(context -> mechanicsRemove(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "idPrefix")
                                ))))
                .then(Commands.literal("show")
                        .then(Commands.argument("idPrefix", StringArgumentType.word())
                                .executes(context -> mechanicsShowProxy(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "idPrefix")
                                ))))
                .then(Commands.literal("hide")
                        .then(Commands.argument("idPrefix", StringArgumentType.word())
                                .executes(context -> mechanicsHideProxy(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "idPrefix")
                                ))))
                .then(Commands.literal("joint_fixed")
                        .then(Commands.argument("firstBodyPrefix", StringArgumentType.word())
                                .then(Commands.argument("secondBodyPrefix", StringArgumentType.word())
                                        .executes(context -> mechanicsFixedJoint(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "firstBodyPrefix"),
                                                StringArgumentType.getString(context, "secondBodyPrefix")
                                        ))
                                        .then(Commands.argument("anchorX", FloatArgumentType.floatArg(-30_000_000.0F, 30_000_000.0F))
                                                .then(Commands.argument("anchorY", FloatArgumentType.floatArg(-30_000_000.0F, 30_000_000.0F))
                                                        .then(Commands.argument("anchorZ", FloatArgumentType.floatArg(-30_000_000.0F, 30_000_000.0F))
                                                                .executes(context -> mechanicsFixedJointAtAnchor(
                                                                        context.getSource(),
                                                                        StringArgumentType.getString(context, "firstBodyPrefix"),
                                                                        StringArgumentType.getString(context, "secondBodyPrefix"),
                                                                        FloatArgumentType.getFloat(context, "anchorX"),
                                                                        FloatArgumentType.getFloat(context, "anchorY"),
                                                                        FloatArgumentType.getFloat(context, "anchorZ")
                                                                ))))))))
                .then(mechanicsRevoluteCommand())
                .then(mechanicsPrismaticCommand())
                .then(Commands.literal("joint_distance")
                        .then(Commands.argument("firstBodyPrefix", StringArgumentType.word())
                                .then(Commands.argument("secondBodyPrefix", StringArgumentType.word())
                                        .then(Commands.argument("minDistance", FloatArgumentType.floatArg(0.0F))
                                                .then(Commands.argument("maxDistance", FloatArgumentType.floatArg(0.0001F))
                                                        .executes(context -> mechanicsDistanceJoint(
                                                                context.getSource(),
                                                                StringArgumentType.getString(context, "firstBodyPrefix"),
                                                                StringArgumentType.getString(context, "secondBodyPrefix"),
                                                                FloatArgumentType.getFloat(context, "minDistance"),
                                                                FloatArgumentType.getFloat(context, "maxDistance"),
                                                                0.0F,
                                                                0.0F
                                                        ))
                                                        .then(Commands.argument("stiffness", FloatArgumentType.floatArg(0.0F))
                                                                .then(Commands.argument("damping", FloatArgumentType.floatArg(0.0F))
                                                                        .executes(context -> mechanicsDistanceJoint(
                                                                                context.getSource(),
                                                                                StringArgumentType.getString(context, "firstBodyPrefix"),
                                                                                StringArgumentType.getString(context, "secondBodyPrefix"),
                                                                                FloatArgumentType.getFloat(context, "minDistance"),
                                                                                FloatArgumentType.getFloat(context, "maxDistance"),
                                                                                FloatArgumentType.getFloat(context, "stiffness"),
                                                                        FloatArgumentType.getFloat(context, "damping")
                                                                        )))))))))
                .then(mechanicsDistanceAnchorsCommand())
                .then(Commands.literal("joints")
                        .executes(context -> mechanicsJointList(context.getSource(), 8))
                        .then(Commands.argument("limit", IntegerArgumentType.integer(1, 50))
                                .executes(context -> mechanicsJointList(
                                        context.getSource(),
                                        IntegerArgumentType.getInteger(context, "limit")
                                ))))
                .then(Commands.literal("joint_remove")
                        .then(Commands.argument("jointPrefix", StringArgumentType.word())
                                .executes(context -> mechanicsJointRemove(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "jointPrefix")
                                ))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> mechanicsRevoluteCommand() {
        var axisZ = Commands.argument("axisZ", FloatArgumentType.floatArg(-1.0F, 1.0F))
                .executes(context -> mechanicsRevoluteJoint(
                        context.getSource(),
                        StringArgumentType.getString(context, "firstBodyPrefix"),
                        StringArgumentType.getString(context, "secondBodyPrefix"),
                        FloatArgumentType.getFloat(context, "anchorX"),
                        FloatArgumentType.getFloat(context, "anchorY"),
                        FloatArgumentType.getFloat(context, "anchorZ"),
                        FloatArgumentType.getFloat(context, "axisX"),
                        FloatArgumentType.getFloat(context, "axisY"),
                        FloatArgumentType.getFloat(context, "axisZ")
                ));
        var axisY = Commands.argument("axisY", FloatArgumentType.floatArg(-1.0F, 1.0F))
                .then(axisZ);
        var axisX = Commands.argument("axisX", FloatArgumentType.floatArg(-1.0F, 1.0F))
                .then(axisY);
        var anchorZ = Commands.argument("anchorZ", FloatArgumentType.floatArg(-30_000_000.0F, 30_000_000.0F))
                .then(axisX);
        var anchorY = Commands.argument("anchorY", FloatArgumentType.floatArg(-30_000_000.0F, 30_000_000.0F))
                .then(anchorZ);
        var anchorX = Commands.argument("anchorX", FloatArgumentType.floatArg(-30_000_000.0F, 30_000_000.0F))
                .then(anchorY);
        var secondBody = Commands.argument("secondBodyPrefix", StringArgumentType.word())
                .then(anchorX);
        var firstBody = Commands.argument("firstBodyPrefix", StringArgumentType.word())
                .then(secondBody);
        return Commands.literal("joint_revolute").then(firstBody);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> mechanicsPrismaticCommand() {
        var axisZ = Commands.argument("axisZ", FloatArgumentType.floatArg(-1.0F, 1.0F))
                .executes(context -> mechanicsPrismaticJoint(
                        context.getSource(),
                        StringArgumentType.getString(context, "firstBodyPrefix"),
                        StringArgumentType.getString(context, "secondBodyPrefix"),
                        FloatArgumentType.getFloat(context, "anchorX"),
                        FloatArgumentType.getFloat(context, "anchorY"),
                        FloatArgumentType.getFloat(context, "anchorZ"),
                        FloatArgumentType.getFloat(context, "axisX"),
                        FloatArgumentType.getFloat(context, "axisY"),
                        FloatArgumentType.getFloat(context, "axisZ")
                ));
        var axisY = Commands.argument("axisY", FloatArgumentType.floatArg(-1.0F, 1.0F))
                .then(axisZ);
        var axisX = Commands.argument("axisX", FloatArgumentType.floatArg(-1.0F, 1.0F))
                .then(axisY);
        var anchorZ = Commands.argument("anchorZ", FloatArgumentType.floatArg(-30_000_000.0F, 30_000_000.0F))
                .then(axisX);
        var anchorY = Commands.argument("anchorY", FloatArgumentType.floatArg(-30_000_000.0F, 30_000_000.0F))
                .then(anchorZ);
        var anchorX = Commands.argument("anchorX", FloatArgumentType.floatArg(-30_000_000.0F, 30_000_000.0F))
                .then(anchorY);
        var secondBody = Commands.argument("secondBodyPrefix", StringArgumentType.word())
                .then(anchorX);
        var firstBody = Commands.argument("firstBodyPrefix", StringArgumentType.word())
                .then(secondBody);
        return Commands.literal("joint_prismatic").then(firstBody);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> mechanicsDistanceAnchorsCommand() {
        var damping = Commands.argument("damping", FloatArgumentType.floatArg(0.0F))
                .executes(context -> mechanicsDistanceJointAtAnchors(
                        context.getSource(),
                        StringArgumentType.getString(context, "firstBodyPrefix"),
                        StringArgumentType.getString(context, "secondBodyPrefix"),
                        FloatArgumentType.getFloat(context, "firstAnchorX"),
                        FloatArgumentType.getFloat(context, "firstAnchorY"),
                        FloatArgumentType.getFloat(context, "firstAnchorZ"),
                        FloatArgumentType.getFloat(context, "secondAnchorX"),
                        FloatArgumentType.getFloat(context, "secondAnchorY"),
                        FloatArgumentType.getFloat(context, "secondAnchorZ"),
                        FloatArgumentType.getFloat(context, "minDistance"),
                        FloatArgumentType.getFloat(context, "maxDistance"),
                        FloatArgumentType.getFloat(context, "stiffness"),
                        FloatArgumentType.getFloat(context, "damping")
                ));
        var stiffness = Commands.argument("stiffness", FloatArgumentType.floatArg(0.0F))
                .then(damping);
        var maxDistance = Commands.argument("maxDistance", FloatArgumentType.floatArg(0.0001F))
                .executes(context -> mechanicsDistanceJointAtAnchors(
                        context.getSource(),
                        StringArgumentType.getString(context, "firstBodyPrefix"),
                        StringArgumentType.getString(context, "secondBodyPrefix"),
                        FloatArgumentType.getFloat(context, "firstAnchorX"),
                        FloatArgumentType.getFloat(context, "firstAnchorY"),
                        FloatArgumentType.getFloat(context, "firstAnchorZ"),
                        FloatArgumentType.getFloat(context, "secondAnchorX"),
                        FloatArgumentType.getFloat(context, "secondAnchorY"),
                        FloatArgumentType.getFloat(context, "secondAnchorZ"),
                        FloatArgumentType.getFloat(context, "minDistance"),
                        FloatArgumentType.getFloat(context, "maxDistance"),
                        0.0F,
                        0.0F
                ))
                .then(stiffness);
        var minDistance = Commands.argument("minDistance", FloatArgumentType.floatArg(0.0F))
                .then(maxDistance);
        var secondAnchorZ = Commands.argument("secondAnchorZ", FloatArgumentType.floatArg(-30_000_000.0F, 30_000_000.0F))
                .then(minDistance);
        var secondAnchorY = Commands.argument("secondAnchorY", FloatArgumentType.floatArg(-30_000_000.0F, 30_000_000.0F))
                .then(secondAnchorZ);
        var secondAnchorX = Commands.argument("secondAnchorX", FloatArgumentType.floatArg(-30_000_000.0F, 30_000_000.0F))
                .then(secondAnchorY);
        var firstAnchorZ = Commands.argument("firstAnchorZ", FloatArgumentType.floatArg(-30_000_000.0F, 30_000_000.0F))
                .then(secondAnchorX);
        var firstAnchorY = Commands.argument("firstAnchorY", FloatArgumentType.floatArg(-30_000_000.0F, 30_000_000.0F))
                .then(firstAnchorZ);
        var firstAnchorX = Commands.argument("firstAnchorX", FloatArgumentType.floatArg(-30_000_000.0F, 30_000_000.0F))
                .then(firstAnchorY);
        var secondBody = Commands.argument("secondBodyPrefix", StringArgumentType.word())
                .then(firstAnchorX);
        var firstBody = Commands.argument("firstBodyPrefix", StringArgumentType.word())
                .then(secondBody);
        return Commands.literal("joint_distance_anchors").then(firstBody);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> elasticCommands() {
        return Commands.literal("elastic")
                .then(Commands.literal("spawn_panel")
                        .executes(context -> elasticSpawnPanel(context.getSource(), 3.0F, 3.0F, 2.0F, 0.65F, null))
                        .then(Commands.argument("width", FloatArgumentType.floatArg(1.0F, 16.0F))
                                .executes(context -> elasticSpawnPanel(
                                        context.getSource(),
                                        FloatArgumentType.getFloat(context, "width"),
                                        3.0F,
                                        2.0F,
                                        0.65F,
                                        null
                                ))
                                .then(Commands.argument("depth", FloatArgumentType.floatArg(1.0F, 16.0F))
                                        .executes(context -> elasticSpawnPanel(
                                                context.getSource(),
                                                FloatArgumentType.getFloat(context, "width"),
                                                FloatArgumentType.getFloat(context, "depth"),
                                                2.0F,
                                                0.65F,
                                                null
                                        ))
                                        .then(Commands.argument("stiffness", FloatArgumentType.floatArg(0.1F, 100.0F))
                                                .executes(context -> elasticSpawnPanel(
                                                        context.getSource(),
                                                        FloatArgumentType.getFloat(context, "width"),
                                                        FloatArgumentType.getFloat(context, "depth"),
                                                        FloatArgumentType.getFloat(context, "stiffness"),
                                                        0.65F,
                                                        null
                                                ))
                                                .then(Commands.argument("maxDeflection", FloatArgumentType.floatArg(0.05F, 2.0F))
                                                        .executes(context -> elasticSpawnPanel(
                                                                context.getSource(),
                                                                FloatArgumentType.getFloat(context, "width"),
                                                                FloatArgumentType.getFloat(context, "depth"),
                                                                FloatArgumentType.getFloat(context, "stiffness"),
                                                                FloatArgumentType.getFloat(context, "maxDeflection"),
                                                                null
                                                        ))
                                                        .then(Commands.argument("output", BlockPosArgument.blockPos())
                                                                .executes(context -> elasticSpawnPanel(
                                                                        context.getSource(),
                                                                        FloatArgumentType.getFloat(context, "width"),
                                                                        FloatArgumentType.getFloat(context, "depth"),
                                                                        FloatArgumentType.getFloat(context, "stiffness"),
                                                                        FloatArgumentType.getFloat(context, "maxDeflection"),
                                                                        BlockPosArgument.getLoadedBlockPos(context, "output")
                                                                ))))))))
                .then(Commands.literal("list")
                        .executes(context -> elasticList(context.getSource(), 8))
                        .then(Commands.argument("limit", IntegerArgumentType.integer(1, 50))
                                .executes(context -> elasticList(
                                        context.getSource(),
                                        IntegerArgumentType.getInteger(context, "limit")
                                ))))
                .then(Commands.literal("status")
                        .then(Commands.argument("idPrefix", StringArgumentType.word())
                                .executes(context -> elasticStatus(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "idPrefix")
                                ))))
                .then(Commands.literal("remove")
                        .then(Commands.argument("idPrefix", StringArgumentType.word())
                                .executes(context -> elasticRemove(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "idPrefix")
                                ))))
                .then(Commands.literal("clear")
                        .executes(context -> elasticClear(context.getSource())));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> femCommands() {
        return Commands.literal("fem")
                .then(Commands.literal("spawn_panel")
                        .executes(context -> femSpawnPanel(
                                context.getSource(),
                                3.0F,
                                3.0F,
                                0.25F,
                                FemVolumeManager.defaultDensity(),
                                FemVolumeManager.defaultYoungs(),
                                FemVolumeManager.defaultVoxels()
                        ))
                        .then(Commands.argument("width", FloatArgumentType.floatArg(0.25F, 16.0F))
                                .then(Commands.argument("depth", FloatArgumentType.floatArg(0.25F, 16.0F))
                                        .then(Commands.argument("thickness", FloatArgumentType.floatArg(0.05F, 4.0F))
                                                .executes(context -> femSpawnPanel(
                                                        context.getSource(),
                                                        FloatArgumentType.getFloat(context, "width"),
                                                        FloatArgumentType.getFloat(context, "depth"),
                                                        FloatArgumentType.getFloat(context, "thickness"),
                                                        FemVolumeManager.defaultDensity(),
                                                        FemVolumeManager.defaultYoungs(),
                                                        FemVolumeManager.defaultVoxels()
                                                ))
                                                .then(Commands.argument("density", FloatArgumentType.floatArg(0.01F, 10000.0F))
                                                        .then(Commands.argument("youngs", FloatArgumentType.floatArg(100.0F, 10_000_000.0F))
                                                                .then(Commands.argument("voxels", IntegerArgumentType.integer(1, 16))
                                                                        .executes(context -> femSpawnPanel(
                                                                                context.getSource(),
                                                                                FloatArgumentType.getFloat(context, "width"),
                                                                                FloatArgumentType.getFloat(context, "depth"),
                                                                                FloatArgumentType.getFloat(context, "thickness"),
                                                                                FloatArgumentType.getFloat(context, "density"),
                                                                                FloatArgumentType.getFloat(context, "youngs"),
                                                                                IntegerArgumentType.getInteger(context, "voxels")
                                                                        )))))))))
                .then(Commands.literal("spawn_block")
                        .executes(context -> femSpawnBlock(
                                context.getSource(),
                                1.0F,
                                FemVolumeManager.defaultDensity(),
                                FemVolumeManager.defaultYoungs(),
                                FemVolumeManager.defaultVoxels()
                        ))
                        .then(Commands.argument("size", FloatArgumentType.floatArg(0.1F, 8.0F))
                                .executes(context -> femSpawnBlock(
                                        context.getSource(),
                                        FloatArgumentType.getFloat(context, "size"),
                                        FemVolumeManager.defaultDensity(),
                                        FemVolumeManager.defaultYoungs(),
                                        FemVolumeManager.defaultVoxels()
                                ))
                                .then(Commands.argument("density", FloatArgumentType.floatArg(0.01F, 10000.0F))
                                        .then(Commands.argument("youngs", FloatArgumentType.floatArg(100.0F, 10_000_000.0F))
                                                .then(Commands.argument("voxels", IntegerArgumentType.integer(1, 16))
                                                        .executes(context -> femSpawnBlock(
                                                                context.getSource(),
                                                                FloatArgumentType.getFloat(context, "size"),
                                                                FloatArgumentType.getFloat(context, "density"),
                                                                FloatArgumentType.getFloat(context, "youngs"),
                                                                IntegerArgumentType.getInteger(context, "voxels")
                                                        )))))))
                .then(Commands.literal("list")
                        .executes(context -> femList(context.getSource(), 8))
                        .then(Commands.argument("limit", IntegerArgumentType.integer(1, 50))
                                .executes(context -> femList(
                                        context.getSource(),
                                        IntegerArgumentType.getInteger(context, "limit")
                                ))))
                .then(Commands.literal("status")
                        .then(Commands.argument("idPrefix", StringArgumentType.word())
                                .executes(context -> femStatus(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "idPrefix")
                                ))))
                .then(Commands.literal("remove")
                        .then(Commands.argument("idPrefix", StringArgumentType.word())
                                .executes(context -> femRemove(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "idPrefix")
                                ))))
                .then(Commands.literal("clear")
                        .executes(context -> femClear(context.getSource())));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> blockCommands() {
        return Commands.literal("block")
                .then(Commands.literal("detach")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(context -> detachBlock(
                                        context.getSource(),
                                        BlockPosArgument.getLoadedBlockPos(context, "pos"),
                                        null,
                                        false
                                ))
                                .then(Commands.argument("mass", FloatArgumentType.floatArg(0.01F, 10000.0F))
                                        .executes(context -> detachBlock(
                                                context.getSource(),
                                                BlockPosArgument.getLoadedBlockPos(context, "pos"),
                                                FloatArgumentType.getFloat(context, "mass"),
                                                false
                                        ))
                                        .then(Commands.argument("debugProxy", BoolArgumentType.bool())
                                                .executes(context -> detachBlock(
                                                        context.getSource(),
                                                        BlockPosArgument.getLoadedBlockPos(context, "pos"),
                                                        FloatArgumentType.getFloat(context, "mass"),
                                                        BoolArgumentType.getBool(context, "debugProxy")
                                                ))))))
                .then(Commands.literal("detach_box")
                        .then(Commands.argument("from", BlockPosArgument.blockPos())
                                .then(Commands.argument("to", BlockPosArgument.blockPos())
                                        .executes(context -> detachBlockBox(
                                                context.getSource(),
                                                BlockPosArgument.getLoadedBlockPos(context, "from"),
                                                BlockPosArgument.getLoadedBlockPos(context, "to"),
                                                null,
                                                false
                                        ))
                                        .then(Commands.argument("mass", FloatArgumentType.floatArg(0.01F, 10000.0F))
                                                .executes(context -> detachBlockBox(
                                                        context.getSource(),
                                                        BlockPosArgument.getLoadedBlockPos(context, "from"),
                                                        BlockPosArgument.getLoadedBlockPos(context, "to"),
                                                        FloatArgumentType.getFloat(context, "mass"),
                                                        false
                                                ))
                                                .then(Commands.argument("debugProxy", BoolArgumentType.bool())
                                                        .executes(context -> detachBlockBox(
                                                                context.getSource(),
                                                                BlockPosArgument.getLoadedBlockPos(context, "from"),
                                                                BlockPosArgument.getLoadedBlockPos(context, "to"),
                                                                FloatArgumentType.getFloat(context, "mass"),
                                                                BoolArgumentType.getBool(context, "debugProxy")
                                                        )))))))
                .then(Commands.literal("list")
                        .executes(context -> detachedBlockList(context.getSource(), 8))
                        .then(Commands.argument("limit", IntegerArgumentType.integer(1, 50))
                                .executes(context -> detachedBlockList(
                                        context.getSource(),
                                        IntegerArgumentType.getInteger(context, "limit")
                                ))))
                .then(Commands.literal("restore")
                        .then(Commands.argument("idPrefix", StringArgumentType.word())
                                .executes(context -> restoreDetachedBlock(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "idPrefix")
                                ))))
                .then(Commands.literal("remove")
                        .then(Commands.argument("idPrefix", StringArgumentType.word())
                                .executes(context -> removeDetachedBlock(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "idPrefix")
                                ))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> assemblyCommands() {
        return Commands.literal("assembly")
                .then(Commands.literal("assemble_block")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(context -> assembleAssemblyBlock(
                                        context.getSource(),
                                        BlockPosArgument.getLoadedBlockPos(context, "pos"),
                                        null,
                                        false
                                ))
                                .then(Commands.argument("mass", FloatArgumentType.floatArg(0.01F, 10000.0F))
                                        .executes(context -> assembleAssemblyBlock(
                                                context.getSource(),
                                                BlockPosArgument.getLoadedBlockPos(context, "pos"),
                                                FloatArgumentType.getFloat(context, "mass"),
                                                false
                                        ))
                                        .then(Commands.argument("debugProxy", BoolArgumentType.bool())
                                                .executes(context -> assembleAssemblyBlock(
                                                        context.getSource(),
                                                        BlockPosArgument.getLoadedBlockPos(context, "pos"),
                                                        FloatArgumentType.getFloat(context, "mass"),
                                                        BoolArgumentType.getBool(context, "debugProxy")
                                                ))))))
                .then(Commands.literal("assemble_box")
                        .then(Commands.argument("from", BlockPosArgument.blockPos())
                                .then(Commands.argument("to", BlockPosArgument.blockPos())
                                        .executes(context -> assembleAssemblyBox(
                                                context.getSource(),
                                                BlockPosArgument.getLoadedBlockPos(context, "from"),
                                                BlockPosArgument.getLoadedBlockPos(context, "to"),
                                                null,
                                                false
                                        ))
                                        .then(Commands.argument("mass", FloatArgumentType.floatArg(0.01F, 10000.0F))
                                                .executes(context -> assembleAssemblyBox(
                                                        context.getSource(),
                                                        BlockPosArgument.getLoadedBlockPos(context, "from"),
                                                        BlockPosArgument.getLoadedBlockPos(context, "to"),
                                                        FloatArgumentType.getFloat(context, "mass"),
                                                        false
                                                ))
                                                .then(Commands.argument("debugProxy", BoolArgumentType.bool())
                                                        .executes(context -> assembleAssemblyBox(
                                                                context.getSource(),
                                                                BlockPosArgument.getLoadedBlockPos(context, "from"),
                                                                BlockPosArgument.getLoadedBlockPos(context, "to"),
                                                                FloatArgumentType.getFloat(context, "mass"),
                                                                BoolArgumentType.getBool(context, "debugProxy")
                                                        )))))))
                .then(Commands.literal("list")
                        .executes(context -> assemblyList(context.getSource(), 8))
                        .then(Commands.argument("limit", IntegerArgumentType.integer(1, 50))
                                .executes(context -> assemblyList(
                                        context.getSource(),
                                        IntegerArgumentType.getInteger(context, "limit")
                                ))))
                .then(Commands.literal("pick")
                        .executes(context -> assemblyPick(context.getSource(), 16.0F))
                        .then(Commands.argument("maxDistance", FloatArgumentType.floatArg(0.1F, 128.0F))
                                .executes(context -> assemblyPick(
                                        context.getSource(),
                                        FloatArgumentType.getFloat(context, "maxDistance")
                                ))))
                .then(Commands.literal("break")
                        .executes(context -> assemblyBreak(context.getSource(), 16.0F))
                        .then(Commands.argument("maxDistance", FloatArgumentType.floatArg(0.1F, 128.0F))
                                .executes(context -> assemblyBreak(
                                        context.getSource(),
                                        FloatArgumentType.getFloat(context, "maxDistance")
                                ))))
                .then(Commands.literal("impulse")
                        .then(Commands.argument("idPrefix", StringArgumentType.word())
                                .then(Commands.argument("x", FloatArgumentType.floatArg(-100000.0F, 100000.0F))
                                        .then(Commands.argument("y", FloatArgumentType.floatArg(-100000.0F, 100000.0F))
                                                .then(Commands.argument("z", FloatArgumentType.floatArg(-100000.0F, 100000.0F))
                                                        .executes(context -> assemblyImpulse(
                                                                context.getSource(),
                                                                StringArgumentType.getString(context, "idPrefix"),
                                                                FloatArgumentType.getFloat(context, "x"),
                                                                FloatArgumentType.getFloat(context, "y"),
                                                                FloatArgumentType.getFloat(context, "z")
                                                        )))))))
                .then(Commands.literal("torque_impulse")
                        .then(Commands.argument("idPrefix", StringArgumentType.word())
                                .then(Commands.argument("x", FloatArgumentType.floatArg(-100000.0F, 100000.0F))
                                        .then(Commands.argument("y", FloatArgumentType.floatArg(-100000.0F, 100000.0F))
                                                .then(Commands.argument("z", FloatArgumentType.floatArg(-100000.0F, 100000.0F))
                                                        .executes(context -> assemblyTorqueImpulse(
                                                                context.getSource(),
                                                                StringArgumentType.getString(context, "idPrefix"),
                                                                FloatArgumentType.getFloat(context, "x"),
                                                                FloatArgumentType.getFloat(context, "y"),
                                                                FloatArgumentType.getFloat(context, "z")
                                                        )))))))
                .then(Commands.literal("remove")
                        .then(Commands.argument("idPrefix", StringArgumentType.word())
                                .executes(context -> removeAssembly(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "idPrefix")
                                ))))
                .then(Commands.literal("force_load")
                        .then(Commands.argument("idPrefix", StringArgumentType.word())
                                .executes(context -> forceLoadAssembly(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "idPrefix")
                                ))))
                .then(Commands.literal("unforce_load")
                        .then(Commands.argument("idPrefix", StringArgumentType.word())
                                .executes(context -> unforceLoadAssembly(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "idPrefix")
                                ))))
                .then(Commands.literal("status")
                        .executes(context -> assemblyStatus(context.getSource())));
    }

    private static int spawnBox(CommandSourceStack source, float size, float mass) {
        try {
            Vec3 position = source.getPosition();
            ServerPhysicsRuntime.SpawnedDebugBox spawned = ServerPhysicsRuntime.INSTANCE.spawnDebugBox(source.getLevel(), position, size, mass);
            source.sendSuccess(() -> Component.literal(
                    "Spawned physics box " + spawned.object().id() + " bound to entity " + spawned.entityId()
                            + "; size=" + String.format("%.2f", size)
                            + ", mass=" + String.format("%.2f", mass)
                            + "; queued " + spawned.terrainChunkQueueCount() + " terrain chunks"
            ), true);
            return 1;
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Failed to spawn physics box: " + exception.getMessage()));
            return 0;
        }
    }

    private static int spawnStressGrid(CommandSourceStack source, int countX, int countY, int countZ, float spacing, float size, float mass) {
        try {
            ServerPhysicsRuntime.StressGridResult spawned = ServerPhysicsRuntime.INSTANCE.spawnStressGrid(
                    source.getLevel(),
                    source.getPosition(),
                    countX,
                    countY,
                    countZ,
                    spacing,
                    size,
                    mass
            );
            ServerPhysicsRuntime.RuntimeStatus status = ServerPhysicsRuntime.INSTANCE.status();
            source.sendSuccess(() -> Component.literal(
                    "Spawned stress grid " + spawned.created() + "/" + spawned.requested() + " physics-only boxes"
                            + "; dims=" + countX + "x" + countY + "x" + countZ
                            + ", spacing=" + String.format("%.2f", spacing)
                            + ", size=" + String.format("%.2f", size)
                            + ", mass=" + String.format("%.2f", mass)
                            + "; gpuRequested=" + status.gpuDynamicsRequested()
                            + ", gpuScenes=" + status.gpuDynamicsSceneCount()
                            + ", gpuStatus=" + status.gpuDynamicsStatus()
                            + "; queued " + spawned.terrainChunkQueueCount() + " terrain chunks"
            ), true);
            return spawned.created();
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Failed to spawn stress grid: " + exception.getMessage()));
            return 0;
        }
    }

    private static int elasticSpawnPanel(CommandSourceStack source, float width, float depth, float stiffness, float maxDeflection, BlockPos outputPos) {
        try {
            ElasticPanelSnapshot panel = ElasticPanelManager.INSTANCE.spawnPanel(
                    source.getLevel(),
                    source.getPosition(),
                    width,
                    depth,
                    stiffness,
                    maxDeflection,
                    outputPos
            );
            source.sendSuccess(() -> Component.literal(
                    "Spawned elastic panel " + shortId(panel)
                            + "; id=" + panel.id()
                            + "; size=" + String.format("%.2f", width) + "x" + String.format("%.2f", depth)
                            + "; stiffness=" + String.format("%.2f", stiffness)
                            + "; maxDeflection=" + String.format("%.2f", maxDeflection)
                            + "; output=" + describeBlockPos(panel.outputPos())
            ), true);
            return 1;
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Failed to spawn elastic panel: " + exception.getMessage()));
            return 0;
        }
    }

    private static int elasticList(CommandSourceStack source, int limit) {
        Vec3 origin = source.getPosition();
        List<ElasticPanelSnapshot> allSnapshots = ElasticPanelManager.INSTANCE.snapshots(source.getLevel()).stream()
                .sorted(Comparator.comparingDouble(snapshot -> distanceSqr(snapshot, origin)))
                .toList();
        if (allSnapshots.isEmpty()) {
            source.sendFailure(Component.literal("No elastic panels in this level"));
            return 0;
        }

        List<ElasticPanelSnapshot> snapshots = allSnapshots.stream()
                .limit(limit)
                .toList();
        source.sendSuccess(() -> Component.literal(
                "Showing " + snapshots.size() + " of " + allSnapshots.size() + " elastic panels in this level"
        ), false);
        for (ElasticPanelSnapshot snapshot : snapshots) {
            double distance = Math.sqrt(distanceSqr(snapshot, origin));
            source.sendSuccess(() -> Component.literal(describeElasticPanel(snapshot, distance)), false);
        }
        return snapshots.size();
    }

    private static int elasticStatus(CommandSourceStack source, String idPrefix) {
        Optional<ElasticPanelSnapshot> maybePanel = findElasticPanel(source, idPrefix);
        if (maybePanel.isEmpty()) {
            return 0;
        }
        double distance = Math.sqrt(distanceSqr(maybePanel.get(), source.getPosition()));
        source.sendSuccess(() -> Component.literal(describeElasticPanel(maybePanel.get(), distance)), false);
        return 1;
    }

    private static int elasticRemove(CommandSourceStack source, String idPrefix) {
        Optional<ElasticPanelSnapshot> maybePanel = findElasticPanel(source, idPrefix);
        if (maybePanel.isEmpty()) {
            return 0;
        }

        ElasticPanelSnapshot panel = maybePanel.get();
        return ElasticPanelManager.INSTANCE.remove(source.getLevel(), panel.id())
                .map(removed -> {
                    source.sendSuccess(() -> Component.literal(
                            "Removed elastic panel " + shortId(removed)
                                    + "; output=" + describeBlockPos(removed.outputPos())
                                    + "; lastDeflection=" + String.format("%.3f", removed.deflection())
                                    + "; lastSignal=" + removed.signal()
                    ), true);
                    return 1;
                })
                .orElseGet(() -> {
                    source.sendFailure(Component.literal("Elastic panel " + panel.id() + " no longer exists"));
                    return 0;
                });
    }

    private static int elasticClear(CommandSourceStack source) {
        int removed = ElasticPanelManager.INSTANCE.forgetLevel(source.getLevel());
        source.sendSuccess(() -> Component.literal("Cleared " + removed + " elastic panels in this level"), true);
        return removed;
    }

    private static int femSpawnPanel(CommandSourceStack source, float width, float depth, float thickness, float density, float youngs, int voxels) {
        try {
            Vec3 center = source.getPosition().add(0.0D, (thickness * 0.5D) + 1.0D, 0.0D);
            FemVolumeSnapshot volume = FemVolumeManager.INSTANCE.spawnPanel(
                    source.getLevel(),
                    center,
                    width,
                    depth,
                    thickness,
                    density,
                    youngs,
                    voxels
            );
            source.sendSuccess(() -> Component.literal(
                    "Spawned FEM panel " + describeFemVolume(volume, 0.0D)
            ), true);
            return 1;
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Failed to spawn FEM panel: " + exception.getMessage()));
            return 0;
        }
    }

    private static int femSpawnBlock(CommandSourceStack source, float size, float density, float youngs, int voxels) {
        try {
            Vec3 center = source.getPosition().add(0.0D, (size * 0.5D) + 1.0D, 0.0D);
            FemVolumeSnapshot volume = FemVolumeManager.INSTANCE.spawnBlock(
                    source.getLevel(),
                    center,
                    size,
                    density,
                    youngs,
                    voxels
            );
            source.sendSuccess(() -> Component.literal(
                    "Spawned FEM block " + describeFemVolume(volume, 0.0D)
            ), true);
            return 1;
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Failed to spawn FEM block: " + exception.getMessage()));
            return 0;
        }
    }

    private static int femList(CommandSourceStack source, int limit) {
        Vec3 origin = source.getPosition();
        List<FemVolumeSnapshot> allSnapshots = FemVolumeManager.INSTANCE.snapshots(source.getLevel()).stream()
                .sorted(Comparator.comparingDouble(snapshot -> distanceSqr(snapshot, origin)))
                .toList();
        if (allSnapshots.isEmpty()) {
            source.sendFailure(Component.literal("No FEM volumes in this level"));
            return 0;
        }

        List<FemVolumeSnapshot> snapshots = allSnapshots.stream()
                .limit(limit)
                .toList();
        source.sendSuccess(() -> Component.literal(
                "Showing " + snapshots.size() + " of " + allSnapshots.size() + " FEM volumes in this level"
        ), false);
        for (FemVolumeSnapshot snapshot : snapshots) {
            double distance = Math.sqrt(distanceSqr(snapshot, origin));
            source.sendSuccess(() -> Component.literal(describeFemVolume(snapshot, distance)), false);
        }
        return snapshots.size();
    }

    private static int femStatus(CommandSourceStack source, String idPrefix) {
        Optional<FemVolumeSnapshot> maybeVolume = findFemVolume(source, idPrefix);
        if (maybeVolume.isEmpty()) {
            return 0;
        }
        double distance = Math.sqrt(distanceSqr(maybeVolume.get(), source.getPosition()));
        source.sendSuccess(() -> Component.literal(describeFemVolume(maybeVolume.get(), distance)), false);
        return 1;
    }

    private static int femRemove(CommandSourceStack source, String idPrefix) {
        Optional<FemVolumeSnapshot> maybeVolume = findFemVolume(source, idPrefix);
        if (maybeVolume.isEmpty()) {
            return 0;
        }

        FemVolumeSnapshot volume = maybeVolume.get();
        return FemVolumeManager.INSTANCE.remove(source.getLevel(), volume.id())
                .map(removed -> {
                    source.sendSuccess(() -> Component.literal(
                            "Removed FEM volume " + shortId(removed)
                                    + "; kind=" + removed.kind()
                                    + "; markers=" + removed.visualMarkerCount()
                    ), true);
                    return 1;
                })
                .orElseGet(() -> {
                    source.sendFailure(Component.literal("FEM volume " + volume.id() + " no longer exists"));
                    return 0;
                });
    }

    private static int femClear(CommandSourceStack source) {
        int removed = FemVolumeManager.INSTANCE.forgetLevel(source.getLevel());
        source.sendSuccess(() -> Component.literal("Cleared " + removed + " FEM volumes in this level"), true);
        return removed;
    }

    private static int playerProxyEnable(CommandSourceStack source, float mass, boolean debugProxy) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Player proxy can only be enabled by a player"));
            return 0;
        }
        try {
            PlayerProxySnapshot snapshot = PlayerProxyManager.INSTANCE.enable(player, mass, debugProxy);
            source.sendSuccess(() -> Component.literal(
                    "Enabled PhysX player proxy for " + snapshot.playerName()
                            + "; body=" + snapshot.body().id()
                            + "; pos=" + describeVector(snapshot.body().pose().position())
                            + "; halfExtents=" + describeVector(snapshot.body().halfExtents())
                            + "; mass=" + String.format("%.2f", snapshot.body().mass())
                            + "; debugProxy=" + snapshot.debugProxy()
            ), true);
            return 1;
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Failed to enable player proxy: " + exception.getMessage()));
            return 0;
        }
    }

    private static int playerProxyDisable(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Player proxy can only be disabled by a player"));
            return 0;
        }
        return PlayerProxyManager.INSTANCE.disable(player)
                .map(snapshot -> {
                    source.sendSuccess(() -> Component.literal(
                            "Disabled PhysX player proxy for " + snapshot.playerName()
                                    + "; body=" + snapshot.body().id()
                                    + "; lastPos=" + describeVector(snapshot.body().pose().position())
                                    + "; lastVel=" + describeVector(snapshot.body().linearVelocity())
                    ), true);
                    return 1;
                })
                .orElseGet(() -> {
                    source.sendFailure(Component.literal("Player proxy is not enabled for " + player.getScoreboardName()));
                    return 0;
                });
    }

    private static int playerProxyStatus(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Player proxy status can only be queried by a player"));
            return 0;
        }
        Optional<PlayerProxySnapshot> maybeSnapshot = PlayerProxyManager.INSTANCE.snapshot(player);
        if (maybeSnapshot.isEmpty()) {
            source.sendFailure(Component.literal("Player proxy is not enabled for " + player.getScoreboardName()));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(describePlayerProxy(maybeSnapshot.get())), false);
        return 1;
    }

    private static int playerPhysicsEnable(CommandSourceStack source, float mass, boolean debugProxy) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Player physics can only be enabled by a player"));
            return 0;
        }
        try {
            PlayerPhysicsSnapshot snapshot = PlayerPhysicsManager.INSTANCE.enable(player, mass, debugProxy);
            source.sendSuccess(() -> Component.literal(
                    "Enabled PhysX player body for " + snapshot.playerName()
                            + "; body=" + snapshot.body().id()
                            + "; pos=" + describeVector(snapshot.body().pose().position())
                            + "; halfExtents=" + describeVector(snapshot.body().halfExtents())
                            + "; mass=" + String.format("%.2f", snapshot.body().mass())
                            + "; debugProxy=" + snapshot.debugProxy()
            ), true);
            return 1;
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Failed to enable player physics: " + exception.getMessage()));
            return 0;
        }
    }

    private static int playerPhysicsDisable(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Player physics can only be disabled by a player"));
            return 0;
        }
        return PlayerPhysicsManager.INSTANCE.disable(player)
                .map(snapshot -> {
                    source.sendSuccess(() -> Component.literal(
                            "Disabled PhysX player body for " + snapshot.playerName()
                                    + "; body=" + snapshot.body().id()
                                    + "; lastPos=" + describeVector(snapshot.body().pose().position())
                                    + "; lastVel=" + describeVector(snapshot.body().linearVelocity())
                    ), true);
                    return 1;
                })
                .orElseGet(() -> {
                    source.sendFailure(Component.literal("Player physics is not enabled for " + player.getScoreboardName()));
                    return 0;
                });
    }

    private static int playerPhysicsStatus(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Player physics status can only be queried by a player"));
            return 0;
        }
        Optional<PlayerPhysicsSnapshot> maybeSnapshot = PlayerPhysicsManager.INSTANCE.snapshot(player);
        if (maybeSnapshot.isEmpty()) {
            source.sendFailure(Component.literal("Player physics is not enabled for " + player.getScoreboardName()));
            return 0;
        }
        PlayerPhysicsSnapshot snapshot = maybeSnapshot.get();
        source.sendSuccess(() -> Component.literal(describePlayerPhysics(snapshot)), false);
        return 1;
    }

    private static int playerPhysicsImpulse(CommandSourceStack source, float x, float y, float z) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Player physics impulse can only be applied by a player"));
            return 0;
        }
        Optional<PlayerPhysicsSnapshot> before = PlayerPhysicsManager.INSTANCE.snapshot(player);
        if (before.isEmpty()) {
            source.sendFailure(Component.literal("Player physics is not enabled for " + player.getScoreboardName()));
            return 0;
        }
        PhysicsVector impulse = new PhysicsVector(x, y, z);
        Optional<PlayerPhysicsSnapshot> after = PlayerPhysicsManager.INSTANCE.applyImpulse(player, impulse);
        if (after.isEmpty()) {
            source.sendFailure(Component.literal("Failed to apply impulse to PhysX player body"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "Applied impulse " + describeVector(impulse)
                        + " to PhysX player body " + after.get().body().id()
                        + "; oldVel=" + describeVector(before.get().body().linearVelocity())
                        + "; newVel=" + describeVector(after.get().body().linearVelocity())
        ), true);
        return 1;
    }

    private static int mechanicsSpawnBox(CommandSourceStack source, float size, float mass, boolean debugProxy) {
        try {
            float halfExtent = size * 0.5F;
            Vec3 position = source.getPosition();
            MechanicsWorld world = KineticAssembly.api().world(source.getLevel());
            MechanicsBodySnapshot body = world.createDynamicBox(MechanicsBoxDefinition.gameplayDynamicBox(
                    new PhysicsPose(
                            new PhysicsVector(position.x(), position.y() + halfExtent + 1.5D, position.z()),
                            PhysicsQuaternion.IDENTITY
                    ),
                    new PhysicsVector(halfExtent, halfExtent, halfExtent),
                    mass
            ));

            String proxyStatus = debugProxy
                    ? MechanicsDebugProxies.show(source.getLevel(), body.id())
                            .map(proxy -> "debugProxy=" + proxy.entityId() + (proxy.created() ? " created" : " existing"))
                            .orElse("debugProxy=<failed>")
                    : "debugProxy=<none>";

            source.sendSuccess(() -> Component.literal(
                    "Spawned mechanics box " + body.id()
                            + "; size=" + String.format("%.2f", size)
                            + ", mass=" + String.format("%.2f", mass)
                            + "; " + proxyStatus
            ), true);
            return 1;
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Failed to spawn mechanics box: " + exception.getMessage()));
            return 0;
        }
    }

    private static int mechanicsList(CommandSourceStack source, int limit) {
        Optional<MechanicsWorld> maybeWorld = KineticAssembly.api().existingWorld(source.getLevel());
        if (maybeWorld.isEmpty()) {
            source.sendFailure(Component.literal("No mechanics world exists in this level"));
            return 0;
        }

        Vec3 origin = source.getPosition();
        List<MechanicsBodySnapshot> allSnapshots = maybeWorld.get().snapshots().stream()
                .filter(snapshot -> !snapshot.closed())
                .sorted(Comparator.comparingDouble(snapshot -> distanceSqr(snapshot, origin)))
                .toList();
        if (allSnapshots.isEmpty()) {
            source.sendFailure(Component.literal("No mechanics bodies in this level"));
            return 0;
        }

        List<MechanicsBodySnapshot> snapshots = allSnapshots.stream()
                .limit(limit)
                .toList();
        source.sendSuccess(() -> Component.literal(
                "Showing " + snapshots.size() + " of " + allSnapshots.size() + " mechanics bodies in this level"
        ), false);
        for (MechanicsBodySnapshot snapshot : snapshots) {
            double distance = Math.sqrt(distanceSqr(snapshot, origin));
            source.sendSuccess(() -> Component.literal(describeMechanicsBody(snapshot, distance)), false);
        }
        return snapshots.size();
    }

    private static int mechanicsImpulse(CommandSourceStack source, String idPrefix, float x, float y, float z) {
        Optional<MechanicsBodySnapshot> maybeBody = findMechanicsBody(source, idPrefix);
        if (maybeBody.isEmpty()) {
            return 0;
        }

        MechanicsWorld world = KineticAssembly.api().existingWorld(source.getLevel()).orElseThrow();
        MechanicsBodySnapshot body = maybeBody.get();
        PhysicsVector impulse = new PhysicsVector(x, y, z);
        if (!world.applyLinearImpulse(body.id(), impulse)) {
            source.sendFailure(Component.literal("Failed to apply impulse to mechanics body " + body.id()));
            return 0;
        }
        PhysicsVector newVelocity = world.snapshot(body.id())
                .map(MechanicsBodySnapshot::linearVelocity)
                .orElse(PhysicsVector.ZERO);
        source.sendSuccess(() -> Component.literal(
                "Applied impulse " + describeVector(impulse)
                        + " to mechanics body " + body.id()
                        + "; oldVel=" + describeVector(body.linearVelocity())
                        + "; newVel=" + describeVector(newVelocity)
        ), true);
        return 1;
    }

    private static int mechanicsTorqueImpulse(CommandSourceStack source, String idPrefix, float x, float y, float z) {
        Optional<MechanicsBodySnapshot> maybeBody = findMechanicsBody(source, idPrefix);
        if (maybeBody.isEmpty()) {
            return 0;
        }

        MechanicsWorld world = KineticAssembly.api().existingWorld(source.getLevel()).orElseThrow();
        MechanicsBodySnapshot body = maybeBody.get();
        PhysicsVector impulse = new PhysicsVector(x, y, z);
        if (!world.applyAngularImpulse(body.id(), impulse)) {
            source.sendFailure(Component.literal("Failed to apply torque impulse to mechanics body " + body.id()));
            return 0;
        }
        PhysicsVector newAngularVelocity = world.snapshot(body.id())
                .map(MechanicsBodySnapshot::angularVelocity)
                .orElse(PhysicsVector.ZERO);
        source.sendSuccess(() -> Component.literal(
                "Applied torque impulse " + describeVector(impulse)
                        + " to mechanics body " + body.id()
                        + "; oldAngVel=" + describeVector(body.angularVelocity())
                        + "; newAngVel=" + describeVector(newAngularVelocity)
        ), true);
        return 1;
    }

    private static int mechanicsRemove(CommandSourceStack source, String idPrefix) {
        Optional<MechanicsBodySnapshot> maybeBody = findMechanicsBody(source, idPrefix);
        if (maybeBody.isEmpty()) {
            return 0;
        }

        MechanicsWorld world = KineticAssembly.api().existingWorld(source.getLevel()).orElseThrow();
        MechanicsBodySnapshot body = maybeBody.get();
        if (!world.removeBody(body.id())) {
            source.sendFailure(Component.literal("Failed to remove mechanics body " + body.id()));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "Removed mechanics body " + body.id()
                        + "; lastPos=" + describeVector(body.pose().position())
                        + "; lastVel=" + describeVector(body.linearVelocity())
        ), true);
        return 1;
    }

    private static int mechanicsShowProxy(CommandSourceStack source, String idPrefix) {
        Optional<MechanicsBodySnapshot> maybeBody = findMechanicsBody(source, idPrefix);
        if (maybeBody.isEmpty()) {
            return 0;
        }

        MechanicsBodySnapshot body = maybeBody.get();
        Optional<MechanicsDebugProxy> proxy = MechanicsDebugProxies.show(source.getLevel(), body.id());
        if (proxy.isEmpty()) {
            source.sendFailure(Component.literal("Failed to show debug proxy for mechanics body " + body.id()));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                (proxy.get().created() ? "Created" : "Found existing")
                        + " debug proxy " + proxy.get().entityId()
                        + " for mechanics body " + body.id()
        ), true);
        return 1;
    }

    private static int mechanicsHideProxy(CommandSourceStack source, String idPrefix) {
        Optional<MechanicsBodySnapshot> maybeBody = findMechanicsBody(source, idPrefix);
        if (maybeBody.isEmpty()) {
            return 0;
        }

        MechanicsBodySnapshot body = maybeBody.get();
        if (!MechanicsDebugProxies.hide(source.getLevel(), body.id())) {
            source.sendFailure(Component.literal("No debug proxy is bound to mechanics body " + body.id()));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Hid debug proxy for mechanics body " + body.id()), true);
        return 1;
    }

    private static int mechanicsFixedJoint(CommandSourceStack source, String firstBodyPrefix, String secondBodyPrefix) {
        Optional<MechanicsBodySnapshot> maybeFirstBody = findMechanicsBody(source, firstBodyPrefix);
        if (maybeFirstBody.isEmpty()) {
            return 0;
        }
        Optional<MechanicsBodySnapshot> maybeSecondBody = findMechanicsBody(source, secondBodyPrefix);
        if (maybeSecondBody.isEmpty()) {
            return 0;
        }

        MechanicsBodySnapshot firstBody = maybeFirstBody.get();
        MechanicsBodySnapshot secondBody = maybeSecondBody.get();
        if (firstBody.id().equals(secondBody.id())) {
            source.sendFailure(Component.literal("Fixed joint requires two different mechanics bodies"));
            return 0;
        }

        MechanicsWorld world = KineticAssembly.api().existingWorld(source.getLevel()).orElseThrow();
        try {
            MechanicsJointSnapshot joint = world.createFixedJoint(firstBody.id(), secondBody.id());
            source.sendSuccess(() -> Component.literal(
                    "Created fixed joint " + joint.id()
                            + "; first=" + joint.firstBodyId()
                            + "; second=" + joint.secondBodyId()
            ), true);
            return 1;
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Failed to create fixed joint: " + exception.getMessage()));
            return 0;
        }
    }

    private static int mechanicsFixedJointAtAnchor(
            CommandSourceStack source,
            String firstBodyPrefix,
            String secondBodyPrefix,
            float anchorX,
            float anchorY,
            float anchorZ
    ) {
        Optional<MechanicsBodySnapshot> maybeFirstBody = findMechanicsBody(source, firstBodyPrefix);
        if (maybeFirstBody.isEmpty()) {
            return 0;
        }
        Optional<MechanicsBodySnapshot> maybeSecondBody = findMechanicsBody(source, secondBodyPrefix);
        if (maybeSecondBody.isEmpty()) {
            return 0;
        }

        MechanicsBodySnapshot firstBody = maybeFirstBody.get();
        MechanicsBodySnapshot secondBody = maybeSecondBody.get();
        if (firstBody.id().equals(secondBody.id())) {
            source.sendFailure(Component.literal("Fixed joint requires two different mechanics bodies"));
            return 0;
        }

        PhysicsVector anchor = new PhysicsVector(anchorX, anchorY, anchorZ);
        MechanicsWorld world = KineticAssembly.api().existingWorld(source.getLevel()).orElseThrow();
        try {
            MechanicsJointSnapshot joint = world.createFixedJointAtWorldAnchor(firstBody.id(), secondBody.id(), anchor);
            source.sendSuccess(() -> Component.literal(
                    "Created fixed joint " + joint.id()
                            + "; first=" + joint.firstBodyId()
                            + "; second=" + joint.secondBodyId()
                            + "; anchor=" + describeVector(anchor)
            ), true);
            return 1;
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Failed to create fixed joint at anchor: " + exception.getMessage()));
            return 0;
        }
    }

    private static int mechanicsRevoluteJoint(
            CommandSourceStack source,
            String firstBodyPrefix,
            String secondBodyPrefix,
            float anchorX,
            float anchorY,
            float anchorZ,
            float axisX,
            float axisY,
            float axisZ
    ) {
        Optional<MechanicsBodySnapshot> maybeFirstBody = findMechanicsBody(source, firstBodyPrefix);
        if (maybeFirstBody.isEmpty()) {
            return 0;
        }
        Optional<MechanicsBodySnapshot> maybeSecondBody = findMechanicsBody(source, secondBodyPrefix);
        if (maybeSecondBody.isEmpty()) {
            return 0;
        }

        MechanicsBodySnapshot firstBody = maybeFirstBody.get();
        MechanicsBodySnapshot secondBody = maybeSecondBody.get();
        if (firstBody.id().equals(secondBody.id())) {
            source.sendFailure(Component.literal("Revolute joint requires two different mechanics bodies"));
            return 0;
        }

        PhysicsVector anchor = new PhysicsVector(anchorX, anchorY, anchorZ);
        PhysicsVector axis = new PhysicsVector(axisX, axisY, axisZ);
        MechanicsWorld world = KineticAssembly.api().existingWorld(source.getLevel()).orElseThrow();
        try {
            MechanicsJointSnapshot joint = world.createRevoluteJointAtWorldAxis(firstBody.id(), secondBody.id(), anchor, axis);
            source.sendSuccess(() -> Component.literal(
                    "Created revolute joint " + joint.id()
                            + "; first=" + joint.firstBodyId()
                            + "; second=" + joint.secondBodyId()
                            + "; anchor=" + describeVector(anchor)
                            + "; axis=" + describeVector(axis)
            ), true);
            return 1;
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Failed to create revolute joint: " + exception.getMessage()));
            return 0;
        }
    }

    private static int mechanicsPrismaticJoint(
            CommandSourceStack source,
            String firstBodyPrefix,
            String secondBodyPrefix,
            float anchorX,
            float anchorY,
            float anchorZ,
            float axisX,
            float axisY,
            float axisZ
    ) {
        Optional<MechanicsBodySnapshot> maybeFirstBody = findMechanicsBody(source, firstBodyPrefix);
        if (maybeFirstBody.isEmpty()) {
            return 0;
        }
        Optional<MechanicsBodySnapshot> maybeSecondBody = findMechanicsBody(source, secondBodyPrefix);
        if (maybeSecondBody.isEmpty()) {
            return 0;
        }

        MechanicsBodySnapshot firstBody = maybeFirstBody.get();
        MechanicsBodySnapshot secondBody = maybeSecondBody.get();
        if (firstBody.id().equals(secondBody.id())) {
            source.sendFailure(Component.literal("Prismatic joint requires two different mechanics bodies"));
            return 0;
        }

        PhysicsVector anchor = new PhysicsVector(anchorX, anchorY, anchorZ);
        PhysicsVector axis = new PhysicsVector(axisX, axisY, axisZ);
        MechanicsWorld world = KineticAssembly.api().existingWorld(source.getLevel()).orElseThrow();
        try {
            MechanicsJointSnapshot joint = world.createPrismaticJointAtWorldAxis(firstBody.id(), secondBody.id(), anchor, axis);
            source.sendSuccess(() -> Component.literal(
                    "Created prismatic joint " + joint.id()
                            + "; first=" + joint.firstBodyId()
                            + "; second=" + joint.secondBodyId()
                            + "; anchor=" + describeVector(anchor)
                            + "; axis=" + describeVector(axis)
            ), true);
            return 1;
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Failed to create prismatic joint: " + exception.getMessage()));
            return 0;
        }
    }

    private static int mechanicsDistanceJoint(
            CommandSourceStack source,
            String firstBodyPrefix,
            String secondBodyPrefix,
            float minDistance,
            float maxDistance,
            float stiffness,
            float damping
    ) {
        Optional<MechanicsBodySnapshot> maybeFirstBody = findMechanicsBody(source, firstBodyPrefix);
        if (maybeFirstBody.isEmpty()) {
            return 0;
        }
        Optional<MechanicsBodySnapshot> maybeSecondBody = findMechanicsBody(source, secondBodyPrefix);
        if (maybeSecondBody.isEmpty()) {
            return 0;
        }

        MechanicsBodySnapshot firstBody = maybeFirstBody.get();
        MechanicsBodySnapshot secondBody = maybeSecondBody.get();
        if (firstBody.id().equals(secondBody.id())) {
            source.sendFailure(Component.literal("Distance joint requires two different mechanics bodies"));
            return 0;
        }
        if (minDistance > maxDistance) {
            source.sendFailure(Component.literal("Distance joint minimum distance must be less than or equal to maximum distance"));
            return 0;
        }

        MechanicsWorld world = KineticAssembly.api().existingWorld(source.getLevel()).orElseThrow();
        try {
            MechanicsJointSnapshot joint = world.createDistanceJoint(
                    firstBody.id(),
                    secondBody.id(),
                    minDistance,
                    maxDistance,
                    stiffness,
                    damping
            );
            source.sendSuccess(() -> Component.literal(
                    "Created distance joint " + joint.id()
                            + "; first=" + joint.firstBodyId()
                            + "; second=" + joint.secondBodyId()
                            + "; min=" + String.format(Locale.ROOT, "%.3f", minDistance)
                            + "; max=" + String.format(Locale.ROOT, "%.3f", maxDistance)
                            + "; stiffness=" + String.format(Locale.ROOT, "%.3f", stiffness)
                            + "; damping=" + String.format(Locale.ROOT, "%.3f", damping)
            ), true);
            return 1;
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Failed to create distance joint: " + exception.getMessage()));
            return 0;
        }
    }

    private static int mechanicsDistanceJointAtAnchors(
            CommandSourceStack source,
            String firstBodyPrefix,
            String secondBodyPrefix,
            float firstAnchorX,
            float firstAnchorY,
            float firstAnchorZ,
            float secondAnchorX,
            float secondAnchorY,
            float secondAnchorZ,
            float minDistance,
            float maxDistance,
            float stiffness,
            float damping
    ) {
        Optional<MechanicsBodySnapshot> maybeFirstBody = findMechanicsBody(source, firstBodyPrefix);
        if (maybeFirstBody.isEmpty()) {
            return 0;
        }
        Optional<MechanicsBodySnapshot> maybeSecondBody = findMechanicsBody(source, secondBodyPrefix);
        if (maybeSecondBody.isEmpty()) {
            return 0;
        }

        MechanicsBodySnapshot firstBody = maybeFirstBody.get();
        MechanicsBodySnapshot secondBody = maybeSecondBody.get();
        if (firstBody.id().equals(secondBody.id())) {
            source.sendFailure(Component.literal("Distance joint requires two different mechanics bodies"));
            return 0;
        }
        if (minDistance > maxDistance) {
            source.sendFailure(Component.literal("Distance joint minimum distance must be less than or equal to maximum distance"));
            return 0;
        }

        PhysicsVector firstAnchor = new PhysicsVector(firstAnchorX, firstAnchorY, firstAnchorZ);
        PhysicsVector secondAnchor = new PhysicsVector(secondAnchorX, secondAnchorY, secondAnchorZ);
        MechanicsWorld world = KineticAssembly.api().existingWorld(source.getLevel()).orElseThrow();
        try {
            MechanicsJointSnapshot joint = world.createDistanceJointAtWorldAnchors(
                    firstBody.id(),
                    firstAnchor,
                    secondBody.id(),
                    secondAnchor,
                    minDistance,
                    maxDistance,
                    stiffness,
                    damping
            );
            source.sendSuccess(() -> Component.literal(
                    "Created anchored distance joint " + joint.id()
                            + "; first=" + joint.firstBodyId()
                            + "; second=" + joint.secondBodyId()
                            + "; firstAnchor=" + describeVector(firstAnchor)
                            + "; secondAnchor=" + describeVector(secondAnchor)
                            + "; min=" + String.format(Locale.ROOT, "%.3f", minDistance)
                            + "; max=" + String.format(Locale.ROOT, "%.3f", maxDistance)
                            + "; stiffness=" + String.format(Locale.ROOT, "%.3f", stiffness)
                            + "; damping=" + String.format(Locale.ROOT, "%.3f", damping)
            ), true);
            return 1;
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Failed to create anchored distance joint: " + exception.getMessage()));
            return 0;
        }
    }

    private static int mechanicsJointList(CommandSourceStack source, int limit) {
        Optional<MechanicsWorld> maybeWorld = KineticAssembly.api().existingWorld(source.getLevel());
        if (maybeWorld.isEmpty()) {
            source.sendFailure(Component.literal("No mechanics world exists in this level"));
            return 0;
        }

        List<MechanicsJointSnapshot> allSnapshots = maybeWorld.get().jointSnapshots().stream()
                .filter(snapshot -> !snapshot.closed())
                .toList();
        if (allSnapshots.isEmpty()) {
            source.sendFailure(Component.literal("No mechanics joints in this level"));
            return 0;
        }

        List<MechanicsJointSnapshot> snapshots = allSnapshots.stream()
                .limit(limit)
                .toList();
        source.sendSuccess(() -> Component.literal(
                "Showing " + snapshots.size() + " of " + allSnapshots.size() + " mechanics joints in this level"
        ), false);
        for (MechanicsJointSnapshot snapshot : snapshots) {
            source.sendSuccess(() -> Component.literal(describeMechanicsJoint(snapshot)), false);
        }
        return snapshots.size();
    }

    private static int mechanicsJointRemove(CommandSourceStack source, String jointPrefix) {
        Optional<MechanicsJointSnapshot> maybeJoint = findMechanicsJoint(source, jointPrefix);
        if (maybeJoint.isEmpty()) {
            return 0;
        }

        MechanicsWorld world = KineticAssembly.api().existingWorld(source.getLevel()).orElseThrow();
        MechanicsJointSnapshot joint = maybeJoint.get();
        if (!world.removeJoint(joint.id())) {
            source.sendFailure(Component.literal("Failed to remove mechanics joint " + joint.id()));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "Removed mechanics joint " + joint.id()
                        + "; first=" + joint.firstBodyId()
                        + "; second=" + joint.secondBodyId()
        ), true);
        return 1;
    }

    private static int detachBlock(CommandSourceStack source, BlockPos pos, Float massOverride, boolean debugProxy) {
        try {
            AssemblySnapshot assembly = massOverride == null
                    ? AssemblyManager.INSTANCE.assembleBlock(source.getLevel(), pos, debugProxy)
                    : AssemblyManager.INSTANCE.assembleBlock(source.getLevel(), pos, massOverride, debugProxy);
            source.sendSuccess(() -> Component.literal(
                    "Detached block " + blockName(assembly)
                            + " at " + describeBlockPos(assembly.firstBlock().sourcePos())
                            + " into assembly " + assembly.id()
                            + "; body=" + assembly.body().id()
                            + "; plot=" + describeAssemblyPlot(assembly)
                            + "; blocks=" + assembly.blockCount()
                            + "; mass=" + String.format("%.2f", assembly.body().mass())
                            + massSource(massOverride)
                            + "; debugProxy=" + debugProxy
            ), true);
            return 1;
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Failed to detach block: " + exception.getMessage()));
            return 0;
        }
    }

    private static int detachBlockBox(CommandSourceStack source, BlockPos from, BlockPos to, Float massOverride, boolean debugProxy) {
        return assembleAssemblyBox(source, from, to, massOverride, debugProxy);
    }

    private static int assembleAssemblyBlock(CommandSourceStack source, BlockPos pos, Float massOverride, boolean debugProxy) {
        try {
            AssemblySnapshot assembly = massOverride == null
                    ? AssemblyManager.INSTANCE.assembleBlock(source.getLevel(), pos, debugProxy)
                    : AssemblyManager.INSTANCE.assembleBlock(source.getLevel(), pos, massOverride, debugProxy);
            source.sendSuccess(() -> Component.literal(
                    "Assembled assembly " + assembly.id()
                            + "; body=" + assembly.body().id()
                            + "; plot=" + describeAssemblyPlot(assembly)
                            + "; block=" + blockName(assembly)
                            + "; source=" + describeAssemblyBounds(assembly)
                            + "; mass=" + String.format("%.2f", assembly.body().mass())
                            + massSource(massOverride)
                            + "; debugProxy=" + debugProxy
            ), true);
            return 1;
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Failed to assemble assembly block: " + exception.getMessage()));
            return 0;
        }
    }

    private static int assembleAssemblyBox(CommandSourceStack source, BlockPos from, BlockPos to, Float massOverride, boolean debugProxy) {
        try {
            AssemblySnapshot assembly = massOverride == null
                    ? AssemblyManager.INSTANCE.assembleBox(source.getLevel(), from, to, debugProxy)
                    : AssemblyManager.INSTANCE.assembleBox(source.getLevel(), from, to, massOverride, debugProxy);
            source.sendSuccess(() -> Component.literal(
                    "Assembled assembly " + assembly.id()
                            + "; body=" + assembly.body().id()
                            + "; plot=" + describeAssemblyPlot(assembly)
                            + "; blocks=" + assembly.blockCount()
                            + "; bounds=" + describeAssemblyBounds(assembly)
                            + "; mass=" + String.format("%.2f", assembly.body().mass())
                            + massSource(massOverride)
                            + "; debugProxy=" + debugProxy
            ), true);
            return assembly.blockCount();
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Failed to assemble assembly: " + exception.getMessage()));
            return 0;
        }
    }

    private static String massSource(Float massOverride) {
        return massOverride == null ? " (auto)" : " (override)";
    }

    private static int detachedBlockList(CommandSourceStack source, int limit) {
        return assemblyList(source, limit);
    }

    private static int assemblyList(CommandSourceStack source, int limit) {
        Vec3 origin = source.getPosition();
        List<AssemblySnapshot> allSnapshots = AssemblyManager.INSTANCE.snapshots(source.getLevel()).stream()
                .filter(snapshot -> !snapshot.body().closed())
                .sorted(Comparator.comparingDouble(snapshot -> distanceSqr(snapshot, origin)))
                .toList();
        if (allSnapshots.isEmpty()) {
            source.sendFailure(Component.literal("No assemblies in this level"));
            return 0;
        }

        List<AssemblySnapshot> snapshots = allSnapshots.stream()
                .limit(limit)
                .toList();
        source.sendSuccess(() -> Component.literal(
                "Showing " + snapshots.size() + " of " + allSnapshots.size() + " assemblies in this level"
        ), false);
        for (AssemblySnapshot snapshot : snapshots) {
            double distance = Math.sqrt(distanceSqr(snapshot, origin));
            source.sendSuccess(() -> Component.literal(describeAssembly(snapshot, distance)), false);
        }
        return snapshots.size();
    }

    private static int restoreDetachedBlock(CommandSourceStack source, String idPrefix) {
        Optional<AssemblySnapshot> maybeAssembly = findAssembly(source, idPrefix);
        if (maybeAssembly.isEmpty()) {
            return 0;
        }

        AssemblySnapshot assembly = maybeAssembly.get();
        try {
            return AssemblyManager.INSTANCE.restoreOriginal(source.getLevel(), assembly.id())
                    .map(restored -> {
                        source.sendSuccess(() -> Component.literal(
                                "Restored assembly " + restored.id()
                                        + " at " + describeAssemblyBounds(restored)
                                        + "; blocks=" + restored.blockCount()
                                        + " and removed mechanics body " + restored.body().id()
                        ), true);
                        return 1;
                    })
                    .orElseGet(() -> {
                        source.sendFailure(Component.literal("Assembly " + assembly.id() + " no longer exists"));
                        return 0;
                    });
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Failed to restore assembly: " + exception.getMessage()));
            return 0;
        }
    }

    private static int removeDetachedBlock(CommandSourceStack source, String idPrefix) {
        return removeAssembly(source, idPrefix);
    }

    private static int removeAssembly(CommandSourceStack source, String idPrefix) {
        Optional<AssemblySnapshot> maybeAssembly = findAssembly(source, idPrefix);
        if (maybeAssembly.isEmpty()) {
            return 0;
        }

        AssemblySnapshot assembly = maybeAssembly.get();
        return AssemblyManager.INSTANCE.remove(source.getLevel(), assembly.id())
                .map(removed -> {
                    source.sendSuccess(() -> Component.literal(
                            "Removed assembly " + removed.id()
                                    + "; body=" + removed.body().id()
                                    + "; source=" + describeAssemblyBounds(removed)
                                    + "; blocks=" + removed.blockCount()
                                    + "; block=" + blockName(removed)
                    ), true);
                    return 1;
                })
                .orElseGet(() -> {
                    source.sendFailure(Component.literal("Assembly " + assembly.id() + " no longer exists"));
                    return 0;
                });
    }

    private static int forceLoadAssembly(CommandSourceStack source, String idPrefix) {
        Optional<AssemblySnapshot> maybeAssembly = findAssembly(source, idPrefix);
        if (maybeAssembly.isEmpty()) {
            return 0;
        }

        AssemblySnapshot assembly = maybeAssembly.get();
        ServerAssemblyContainer container = AssemblyContainers.requireServer(source.getLevel());
        return container.assembly(assembly.id())
                .map(active -> {
                    boolean added = container.addForceLoadTicket(
                            active,
                            AssemblyLoadingTicketType.COMMAND_FORCED,
                            Unit.INSTANCE
                    );
                    source.sendSuccess(() -> Component.literal(
                            (added ? "Added" : "Kept")
                                    + " command force-load ticket for assembly "
                                    + assembly.id()
                    ), true);
                    return added ? 1 : 0;
                })
                .orElseGet(() -> {
                    source.sendFailure(Component.literal("Assembly " + assembly.id() + " is not active"));
                    return 0;
                });
    }

    private static int unforceLoadAssembly(CommandSourceStack source, String idPrefix) {
        Optional<AssemblySnapshot> maybeAssembly = findAssembly(source, idPrefix);
        if (maybeAssembly.isEmpty()) {
            return 0;
        }

        AssemblySnapshot assembly = maybeAssembly.get();
        ServerAssemblyContainer container = AssemblyContainers.requireServer(source.getLevel());
        return container.assembly(assembly.id())
                .map(active -> {
                    boolean removed = container.removeForceLoadTicket(
                            active,
                            AssemblyLoadingTicketType.COMMAND_FORCED,
                            Unit.INSTANCE
                    );
                    if (removed) {
                        source.sendSuccess(() -> Component.literal(
                                "Removed command force-load ticket for assembly " + assembly.id()
                        ), true);
                        return 1;
                    }
                    source.sendFailure(Component.literal("Assembly " + assembly.id() + " did not have a command force-load ticket"));
                    return 0;
                })
                .orElseGet(() -> {
                    source.sendFailure(Component.literal("Assembly " + assembly.id() + " is not active"));
                    return 0;
                });
    }

    private static int assemblyStatus(CommandSourceStack source) {
        List<AssemblySnapshot> snapshots = AssemblyManager.INSTANCE.snapshots(source.getLevel());
        int blockCount = snapshots.stream()
                .mapToInt(AssemblySnapshot::blockCount)
                .sum();
        int visualCount = snapshots.stream()
                .mapToInt(AssemblySnapshot::visualCount)
                .sum();
        int dirtyBlockCount = snapshots.stream()
                .mapToInt(AssemblySnapshot::dirtyBlockCount)
                .sum();
        long plotCount = snapshots.stream()
                .map(snapshot -> snapshot.plot().id())
                .distinct()
                .count();
        AssemblyManager.BridgeStats bridgeStats = AssemblyManager.INSTANCE.bridgeStats();
        source.sendSuccess(() -> Component.literal(
                "Assemblies=" + snapshots.size()
                        + ", plots=" + plotCount
                        + ", blocks=" + blockCount
                        + ", dirtyBlocks=" + dirtyBlockCount
                        + ", visuals=" + visualCount
                        + ", vanillaBreakActions=" + bridgeStats.vanillaBreakActions()
                        + ", vanillaUseActions=" + bridgeStats.vanillaUseActions()
                        + ", vanillaBreakAccepted=" + bridgeStats.vanillaBreakAccepted()
                        + ", vanillaUseAccepted=" + bridgeStats.vanillaUseAccepted()
                        + ", vanillaBreakRejected=" + bridgeStats.vanillaBreakRejected()
                        + ", vanillaUseRejected=" + bridgeStats.vanillaUseRejected()
                        + ", plotBlockWrites=" + bridgeStats.plotBlockWrites()
                        + ", splitEvents=" + bridgeStats.splitEvents()
                        + ", splitCreatedAssemblies=" + bridgeStats.splitCreatedAssemblies()
        ), false);
        return snapshots.size();
    }

    private static int assemblyPick(CommandSourceStack source, float maxDistance) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Assembly pick can only be used by a player"));
            return 0;
        }
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        PhysicsVector origin = new PhysicsVector(eye.x(), eye.y(), eye.z());
        PhysicsVector direction = new PhysicsVector(look.x(), look.y(), look.z());
        try {
            Optional<AssemblyPickResult> result = AssemblyManager.INSTANCE.pickBlock(
                    source.getLevel(),
                    origin,
                    direction,
                    maxDistance
            );
            if (result.isEmpty()) {
                source.sendFailure(Component.literal("No assembly block hit within " + String.format("%.2f", maxDistance) + " blocks"));
                return 0;
            }
            source.sendSuccess(() -> Component.literal(describeAssemblyPick(result.get())), false);
            return 1;
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Failed to pick assembly block: " + exception.getMessage()));
            return 0;
        }
    }

    private static int assemblyBreak(CommandSourceStack source, float maxDistance) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Assembly break can only be used by a player"));
            return 0;
        }
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        PhysicsVector origin = new PhysicsVector(eye.x(), eye.y(), eye.z());
        PhysicsVector direction = new PhysicsVector(look.x(), look.y(), look.z());
        try {
            Optional<AssemblyBreakResult> result = AssemblyManager.INSTANCE.breakPickedBlock(
                    source.getLevel(),
                    origin,
                    direction,
                    maxDistance
            );
            if (result.isEmpty()) {
                source.sendFailure(Component.literal("No assembly block hit within " + String.format("%.2f", maxDistance) + " blocks"));
                return 0;
            }
            source.sendSuccess(() -> Component.literal(describeAssemblyBreak(result.get())), true);
            return 1;
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Failed to break assembly block: " + exception.getMessage()));
            return 0;
        }
    }

    private static int assemblyImpulse(CommandSourceStack source, String idPrefix, float x, float y, float z) {
        Optional<AssemblySnapshot> maybeAssembly = findAssembly(source, idPrefix);
        if (maybeAssembly.isEmpty()) {
            return 0;
        }

        Optional<MechanicsWorld> maybeWorld = KineticAssembly.api().existingWorld(source.getLevel());
        if (maybeWorld.isEmpty()) {
            source.sendFailure(Component.literal("No mechanics world exists in this level"));
            return 0;
        }

        AssemblySnapshot assembly = maybeAssembly.get();
        PhysicsVector impulse = new PhysicsVector(x, y, z);
        MechanicsWorld world = maybeWorld.get();
        if (!world.applyLinearImpulse(assembly.body().id(), impulse)) {
            source.sendFailure(Component.literal("Failed to apply impulse to assembly " + assembly.id() + " body " + assembly.body().id()));
            return 0;
        }
        PhysicsVector newVelocity = world.snapshot(assembly.body().id())
                .map(MechanicsBodySnapshot::linearVelocity)
                .orElse(PhysicsVector.ZERO);
        source.sendSuccess(() -> Component.literal(
                "Applied impulse " + describeVector(impulse)
                        + " to assembly " + assembly.id()
                        + "; body=" + assembly.body().id()
                        + "; oldVel=" + describeVector(assembly.body().linearVelocity())
                        + "; newVel=" + describeVector(newVelocity)
        ), true);
        return 1;
    }

    private static int assemblyTorqueImpulse(CommandSourceStack source, String idPrefix, float x, float y, float z) {
        Optional<AssemblySnapshot> maybeAssembly = findAssembly(source, idPrefix);
        if (maybeAssembly.isEmpty()) {
            return 0;
        }

        Optional<MechanicsWorld> maybeWorld = KineticAssembly.api().existingWorld(source.getLevel());
        if (maybeWorld.isEmpty()) {
            source.sendFailure(Component.literal("No mechanics world exists in this level"));
            return 0;
        }

        AssemblySnapshot assembly = maybeAssembly.get();
        PhysicsVector impulse = new PhysicsVector(x, y, z);
        MechanicsWorld world = maybeWorld.get();
        if (!world.applyAngularImpulse(assembly.body().id(), impulse)) {
            source.sendFailure(Component.literal("Failed to apply torque impulse to assembly " + assembly.id() + " body " + assembly.body().id()));
            return 0;
        }
        PhysicsVector newAngularVelocity = world.snapshot(assembly.body().id())
                .map(MechanicsBodySnapshot::angularVelocity)
                .orElse(PhysicsVector.ZERO);
        source.sendSuccess(() -> Component.literal(
                "Applied torque impulse " + describeVector(impulse)
                        + " to assembly " + assembly.id()
                        + "; body=" + assembly.body().id()
                        + "; oldAngVel=" + describeVector(assembly.body().angularVelocity())
                        + "; newAngVel=" + describeVector(newAngularVelocity)
        ), true);
        return 1;
    }

    private static int setVelocity(CommandSourceStack source, float x, float y, float z) {
        PhysicsVector velocity = new PhysicsVector(x, y, z);
        return ServerPhysicsRuntime.INSTANCE.setNearestDynamicBoxVelocity(source.getLevel(), source.getPosition(), velocity, 32.0D)
                .map(result -> {
                    source.sendSuccess(() -> Component.literal(
                            "Set velocity for " + result.objectId()
                                    + " from " + describeVector(result.previousVelocity())
                                    + " to " + describeVector(result.newVelocity())
                                    + "; distance=" + String.format("%.2f", result.distance())
                    ), true);
                    return 1;
                })
                .orElseGet(() -> {
                    source.sendFailure(Component.literal("No dynamic physics box found within 32 blocks"));
                    return 0;
                });
    }

    private static int listBoxes(CommandSourceStack source, int limit) {
        Vec3 origin = source.getPosition();
        List<PhysicsObjectSnapshot> allSnapshots = ServerPhysicsRuntime.INSTANCE.snapshotsFor(source.getLevel()).stream()
                .filter(snapshot -> snapshot.type() == PhysicsObjectType.DYNAMIC_BOX && !snapshot.closed())
                .sorted(Comparator.comparingDouble(snapshot -> distanceSqr(snapshot, origin)))
                .toList();
        if (allSnapshots.isEmpty()) {
            source.sendFailure(Component.literal("No dynamic physics boxes in this level"));
            return 0;
        }

        List<PhysicsObjectSnapshot> snapshots = allSnapshots.stream()
                .limit(limit)
                .toList();
        source.sendSuccess(() -> Component.literal(
                "Showing " + snapshots.size() + " of " + allSnapshots.size() + " dynamic physics boxes in this level"
        ), false);
        for (PhysicsObjectSnapshot snapshot : snapshots) {
            double distance = Math.sqrt(distanceSqr(snapshot, origin));
            source.sendSuccess(() -> Component.literal(describeBox(snapshot, distance)), false);
        }
        return snapshots.size();
    }

    private static int removeNearest(CommandSourceStack source, float maxDistance) {
        Vec3 origin = source.getPosition();
        double maxDistanceSqr = maxDistance * maxDistance;
        Optional<PhysicsObjectSnapshot> nearest = ServerPhysicsRuntime.INSTANCE.snapshotsFor(source.getLevel()).stream()
                .filter(snapshot -> snapshot.type() == PhysicsObjectType.DYNAMIC_BOX && !snapshot.closed())
                .filter(snapshot -> distanceSqr(snapshot, origin) <= maxDistanceSqr)
                .min(Comparator.comparingDouble(snapshot -> distanceSqr(snapshot, origin)));

        if (nearest.isEmpty()) {
            source.sendFailure(Component.literal("No dynamic physics box found within " + String.format("%.2f", maxDistance) + " blocks"));
            return 0;
        }

        double distance = Math.sqrt(distanceSqr(nearest.get(), origin));
        return removeSelectedBox(source, nearest.get(), distance);
    }

    private static int removeBox(CommandSourceStack source, String idPrefix) {
        String normalizedPrefix = idPrefix.trim().toLowerCase(Locale.ROOT);
        if (normalizedPrefix.isEmpty()) {
            source.sendFailure(Component.literal("Box id prefix must not be empty"));
            return 0;
        }

        List<PhysicsObjectSnapshot> matches = ServerPhysicsRuntime.INSTANCE.snapshotsFor(source.getLevel()).stream()
                .filter(snapshot -> snapshot.type() == PhysicsObjectType.DYNAMIC_BOX && !snapshot.closed())
                .filter(snapshot -> snapshot.id().toString().toLowerCase(Locale.ROOT).startsWith(normalizedPrefix))
                .toList();
        if (matches.isEmpty()) {
            source.sendFailure(Component.literal("No dynamic physics box starts with id prefix " + idPrefix));
            return 0;
        }
        if (matches.size() > 1) {
            source.sendFailure(Component.literal("Id prefix " + idPrefix + " matches " + matches.size() + " boxes; use a longer prefix"));
            return 0;
        }

        double distance = Math.sqrt(distanceSqr(matches.get(0), source.getPosition()));
        return removeSelectedBox(source, matches.get(0), distance);
    }

    private static int removeSelectedBox(CommandSourceStack source, PhysicsObjectSnapshot snapshot, double distance) {
        return ServerPhysicsRuntime.INSTANCE.removeDynamicBox(source.getLevel(), snapshot.id())
                .map(removed -> {
                    String entity = removed.entityId() == null ? "<none>" : removed.entityId().toString();
                    source.sendSuccess(() -> Component.literal(
                            "Removed physics box " + removed.objectId()
                                    + " boundEntity=" + entity
                                    + "; lastPos=" + describeVector(removed.lastPosition())
                                    + "; lastVel=" + describeVector(removed.lastVelocity())
                                    + "; distance=" + String.format("%.2f", distance)
                    ), true);
                    return 1;
                })
                .orElseGet(() -> {
                    source.sendFailure(Component.literal("Physics box " + snapshot.id() + " no longer exists"));
                    return 0;
                });
    }

    private static int aeroDebugParticles(CommandSourceStack source, boolean enabled) {
        AerodynamicsCompat.setDebugParticlesEnabled(enabled);
        source.sendSuccess(() -> Component.literal("Aerodynamics debug particles " + (enabled ? "enabled" : "disabled")), true);
        return 1;
    }

    private static int aeroDebugParticlesStatus(CommandSourceStack source) {
        boolean enabled = AerodynamicsCompat.debugParticlesEnabled();
        source.sendSuccess(() -> Component.literal("Aerodynamics debug particles " + (enabled ? "enabled" : "disabled")), false);
        return 1;
    }

    private static int physicsStatus(CommandSourceStack source) {
        ServerPhysicsRuntime.RuntimeStatus status = ServerPhysicsRuntime.INSTANCE.status();
        AerodynamicsCompat.Status aeroStatus = AerodynamicsCompat.status();
        String sample = ServerPhysicsRuntime.INSTANCE.snapshotsFor(source.getLevel()).stream()
                .filter(snapshot -> snapshot.type() == PhysicsObjectType.DYNAMIC_BOX)
                .findFirst()
                .map(KineticAssemblyCommands::describeSample)
                .orElse("sample=<none>");
        int femVolumes = FemVolumeManager.INSTANCE.snapshots(source.getLevel()).size();
        source.sendSuccess(() -> Component.literal(
                "PhysX linked=" + status.nativeLinked()
                        + ", gpuRequested=" + status.gpuDynamicsRequested()
                        + ", gpuScenes=" + status.gpuDynamicsSceneCount()
                        + ", gpuStatus=" + status.gpuDynamicsStatus()
                        + ", scenes=" + status.sceneCount()
                        + ", objects=" + status.objectCount()
                        + ", dynamicBoxes=" + status.dynamicBoxCount()
                        + ", terrainColliders=" + status.terrainColliderCount()
                        + ", aeroCompat=" + aeroStatus.describe()
                        + ", terrainChunks=" + status.terrainChunkCount()
                        + ", femVolumes=" + femVolumes
                        + ", terrainQueued=" + status.terrainQueuedChunkCount()
                        + ", terrainBuilt=" + status.terrainBuiltChunkCount()
                        + ", terrainDirty=" + status.terrainDirtyChunkCount()
                        + ", boundEntities=" + status.boundEntityCount()
                        + ", proxyRecreated=" + status.debugProxyRecreateCount()
                        + ", lastProxyRecreated=" + status.lastDebugProxyRecreateCount()
                        + ", lastTickMs=" + String.format("%.3f", status.lastRuntimeTickMillis())
                        + ", lastQueueActiveMs=" + String.format("%.3f", status.lastQueueActiveMillis())
                        + ", lastTerrainProcessMs=" + String.format("%.3f", status.lastTerrainProcessMillis())
                        + ", lastStepPhaseMs=" + String.format("%.3f", status.lastStepPhaseMillis())
                        + ", lastSyncEntitiesMs=" + String.format("%.3f", status.lastSyncEntitiesMillis())
                        + ", syncObjectLookupMs=" + String.format("%.3f", status.lastSyncObjectLookupMillis())
                        + ", syncEntityLookupMs=" + String.format("%.3f", status.lastSyncEntityLookupMillis())
                        + ", syncRecreateMs=" + String.format("%.3f", status.lastSyncRecreateMillis())
                        + ", syncPoseReadMs=" + String.format("%.3f", status.lastSyncPoseReadMillis())
                        + ", syncApplyMs=" + String.format("%.3f", status.lastSyncApplyMillis())
                        + ", lastStepMs=" + String.format("%.3f", status.lastStepMillis())
                        + ", activeSnapshots=" + status.lastActiveSnapshotCount()
                        + ", activeDynamics=" + status.lastActiveDynamicCount()
                        + ", activeTerrainQueued=" + status.lastActiveTerrainQueuedCount()
                        + ", activeTerrainSkippedHeight=" + status.lastActiveTerrainSkippedHeightCount()
                        + ", activeTerrainScanLimit=" + status.activeTerrainMaxScansPerTick()
                        + ", syncedEntities=" + status.lastSyncedEntityCount()
                        + ", entityPoseSyncs=" + status.lastEntityPoseSyncCount()
                        + ", syncRemoved=" + status.lastSyncRemovedBindingCount()
                        + ", syncMissingEntities=" + status.lastSyncMissingEntityCount()
                        + ", proxySyncTransform=" + status.debugProxySyncTransform()
                        + ", entitySyncLimit=" + status.maxEntityPoseSyncsPerTick()
                        + ", lastTerrainChunks=" + status.lastTerrainChunkBuildCount()
                        + ", lastTerrainAdded=" + status.lastTerrainColliderBuildCount()
                        + ", lastTerrainPartial=" + status.lastTerrainPartialColliderBuildCount()
                        + ", lastTerrainBuildMs=" + String.format("%.3f", status.lastTerrainBuildMillis())
                        + ", " + sample
        ), false);
        return status.objectCount();
    }

    private static int assemblyProfileStart(CommandSourceStack source, int ticks) {
        int sampledTicks = AssemblyProfiler.startWindow(ticks);
        source.sendSuccess(() -> Component.literal(
                "Started assembly profiler for " + sampledTicks + " ticks; report will be logged as [kinetic_assembly-assembly-profile]"
        ), false);
        return sampledTicks;
    }

    private static int assemblyProfileStop(CommandSourceStack source) {
        String summary = AssemblyProfiler.stopWindow();
        source.sendSuccess(() -> Component.literal(summary), false);
        return 1;
    }

    private static int assemblyProfileStatus(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal(AssemblyProfiler.status()), false);
        return 1;
    }

    private static int verticalTrace(CommandSourceStack source, int ticks) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Vertical trace can only be enabled by a player"));
            return 0;
        }
        int enabledTicks = AssemblyVerticalTrace.enable(player, ticks);
        source.sendSuccess(() -> Component.literal(
                "Enabled assembly vertical trace for " + player.getGameProfile().getName()
                        + " for " + enabledTicks + " ticks; search logs for [assembly-vertical]"
        ), false);
        return enabledTicks;
    }

    private static int clear(CommandSourceStack source) {
        int playerProxies = PlayerProxyManager.INSTANCE.forgetLevel(source.getLevel());
        int playerBindings = PlayerPhysicsManager.INSTANCE.forgetLevel(source.getLevel());
        int removed = ServerPhysicsRuntime.INSTANCE.clearLevel(source.getLevel());
        int assemblies = AssemblyManager.INSTANCE.forgetLevel(source.getLevel());
        int elasticPanels = ElasticPanelManager.INSTANCE.forgetLevel(source.getLevel());
        int femVolumes = FemVolumeManager.INSTANCE.forgetLevel(source.getLevel());
        source.sendSuccess(() -> Component.literal(
                "Cleared " + removed + " physics objects in this level"
                        + (playerProxies > 0 ? "; released " + playerProxies + " player proxies" : "")
                        + (playerBindings > 0 ? "; released " + playerBindings + " player physics bindings" : "")
                        + (assemblies > 0 ? "; forgot " + assemblies + " assembly records" : "")
                        + (elasticPanels > 0 ? "; cleared " + elasticPanels + " elastic panels" : "")
                        + (femVolumes > 0 ? "; cleared " + femVolumes + " FEM volumes" : "")
        ), true);
        return removed + playerProxies + playerBindings + assemblies + elasticPanels + femVolumes;
    }

    private static String describeSample(PhysicsObjectSnapshot snapshot) {
        return "sample=" + snapshot.id()
                + " type=" + snapshot.type()
                + " pos=" + describeVector(snapshot.pose().position())
                + " vel=" + describeVector(snapshot.linearVelocity())
                + " angVel=" + describeVector(snapshot.angularVelocity());
    }

    private static String describeBox(PhysicsObjectSnapshot snapshot, double distance) {
        return shortId(snapshot)
                + " id=" + snapshot.id()
                + " pos=" + describeVector(snapshot.pose().position())
                + " vel=" + describeVector(snapshot.linearVelocity())
                + " angVel=" + describeVector(snapshot.angularVelocity())
                + " distance=" + String.format("%.2f", distance);
    }

    private static Optional<ElasticPanelSnapshot> findElasticPanel(CommandSourceStack source, String idPrefix) {
        String normalizedPrefix = idPrefix.trim().toLowerCase(Locale.ROOT);
        if (normalizedPrefix.isEmpty()) {
            source.sendFailure(Component.literal("Elastic panel id prefix must not be empty"));
            return Optional.empty();
        }

        List<ElasticPanelSnapshot> matches = ElasticPanelManager.INSTANCE.snapshots(source.getLevel()).stream()
                .filter(snapshot -> snapshot.id().toString().toLowerCase(Locale.ROOT).startsWith(normalizedPrefix))
                .toList();
        if (matches.isEmpty()) {
            source.sendFailure(Component.literal("No elastic panel starts with id prefix " + idPrefix));
            return Optional.empty();
        }
        if (matches.size() > 1) {
            source.sendFailure(Component.literal("Id prefix " + idPrefix + " matches " + matches.size() + " elastic panels; use a longer prefix"));
            return Optional.empty();
        }
        return Optional.of(matches.get(0));
    }

    private static Optional<FemVolumeSnapshot> findFemVolume(CommandSourceStack source, String idPrefix) {
        String normalizedPrefix = idPrefix.trim().toLowerCase(Locale.ROOT);
        if (normalizedPrefix.isEmpty()) {
            source.sendFailure(Component.literal("FEM volume id prefix must not be empty"));
            return Optional.empty();
        }

        List<FemVolumeSnapshot> matches = FemVolumeManager.INSTANCE.snapshots(source.getLevel()).stream()
                .filter(snapshot -> snapshot.id().toString().toLowerCase(Locale.ROOT).startsWith(normalizedPrefix))
                .toList();
        if (matches.isEmpty()) {
            source.sendFailure(Component.literal("No FEM volume starts with id prefix " + idPrefix));
            return Optional.empty();
        }
        if (matches.size() > 1) {
            source.sendFailure(Component.literal("Id prefix " + idPrefix + " matches " + matches.size() + " FEM volumes; use a longer prefix"));
            return Optional.empty();
        }
        return Optional.of(matches.get(0));
    }

    private static Optional<MechanicsBodySnapshot> findMechanicsBody(CommandSourceStack source, String idPrefix) {
        String normalizedPrefix = idPrefix.trim().toLowerCase(Locale.ROOT);
        if (normalizedPrefix.isEmpty()) {
            source.sendFailure(Component.literal("Mechanics body id prefix must not be empty"));
            return Optional.empty();
        }

        Optional<MechanicsWorld> maybeWorld = KineticAssembly.api().existingWorld(source.getLevel());
        if (maybeWorld.isEmpty()) {
            source.sendFailure(Component.literal("No mechanics world exists in this level"));
            return Optional.empty();
        }

        List<MechanicsBodySnapshot> matches = maybeWorld.get().snapshots().stream()
                .filter(snapshot -> !snapshot.closed())
                .filter(snapshot -> snapshot.id().toString().toLowerCase(Locale.ROOT).startsWith(normalizedPrefix))
                .toList();
        if (matches.isEmpty()) {
            Optional<AssemblySnapshot> assemblyMatch = findAssemblySilently(source, normalizedPrefix);
            if (assemblyMatch.isPresent()) {
                source.sendFailure(Component.literal(
                        "Prefix " + idPrefix + " matches assembly " + assemblyMatch.get().id()
                                + "; use /kinetic_assembly assembly impulse or torque_impulse " + idPrefix + " <x> <y> <z>, or use body id "
                                + assemblyMatch.get().body().id()
                ));
                return Optional.empty();
            }
            source.sendFailure(Component.literal("No mechanics body starts with id prefix " + idPrefix));
            return Optional.empty();
        }
        if (matches.size() > 1) {
            source.sendFailure(Component.literal("Id prefix " + idPrefix + " matches " + matches.size() + " mechanics bodies; use a longer prefix"));
            return Optional.empty();
        }
        return Optional.of(matches.get(0));
    }

    private static Optional<MechanicsJointSnapshot> findMechanicsJoint(CommandSourceStack source, String idPrefix) {
        String normalizedPrefix = idPrefix.trim().toLowerCase(Locale.ROOT);
        if (normalizedPrefix.isEmpty()) {
            source.sendFailure(Component.literal("Mechanics joint id prefix must not be empty"));
            return Optional.empty();
        }

        Optional<MechanicsWorld> maybeWorld = KineticAssembly.api().existingWorld(source.getLevel());
        if (maybeWorld.isEmpty()) {
            source.sendFailure(Component.literal("No mechanics world exists in this level"));
            return Optional.empty();
        }

        List<MechanicsJointSnapshot> matches = maybeWorld.get().jointSnapshots().stream()
                .filter(snapshot -> !snapshot.closed())
                .filter(snapshot -> snapshot.id().toString().toLowerCase(Locale.ROOT).startsWith(normalizedPrefix))
                .toList();
        if (matches.isEmpty()) {
            source.sendFailure(Component.literal("No mechanics joint starts with id prefix " + idPrefix));
            return Optional.empty();
        }
        if (matches.size() > 1) {
            source.sendFailure(Component.literal("Id prefix " + idPrefix + " matches " + matches.size() + " mechanics joints; use a longer prefix"));
            return Optional.empty();
        }
        return Optional.of(matches.get(0));
    }

    private static Optional<AssemblySnapshot> findAssembly(CommandSourceStack source, String idPrefix) {
        String normalizedPrefix = idPrefix.trim().toLowerCase(Locale.ROOT);
        if (normalizedPrefix.isEmpty()) {
            source.sendFailure(Component.literal("Assembly id prefix must not be empty"));
            return Optional.empty();
        }

        List<AssemblySnapshot> matches = AssemblyManager.INSTANCE.snapshots(source.getLevel()).stream()
                .filter(snapshot -> !snapshot.body().closed())
                .filter(snapshot -> snapshot.id().toString().toLowerCase(Locale.ROOT).startsWith(normalizedPrefix)
                        || snapshot.body().id().toString().toLowerCase(Locale.ROOT).startsWith(normalizedPrefix))
                .toList();
        if (matches.isEmpty()) {
            source.sendFailure(Component.literal("No assembly starts with id/body prefix " + idPrefix));
            return Optional.empty();
        }
        if (matches.size() > 1) {
            source.sendFailure(Component.literal("Id prefix " + idPrefix + " matches " + matches.size() + " assemblies; use a longer prefix"));
            return Optional.empty();
        }
        return Optional.of(matches.get(0));
    }

    private static Optional<AssemblySnapshot> findAssemblySilently(CommandSourceStack source, String normalizedPrefix) {
        return AssemblyManager.INSTANCE.snapshots(source.getLevel()).stream()
                .filter(snapshot -> !snapshot.body().closed())
                .filter(snapshot -> snapshot.id().toString().toLowerCase(Locale.ROOT).startsWith(normalizedPrefix))
                .findFirst();
    }

    private static String describeMechanicsBody(MechanicsBodySnapshot snapshot, double distance) {
        return shortId(snapshot)
                + " id=" + snapshot.id()
                + " role=" + snapshot.role()
                + " type=" + snapshot.type()
                + " pos=" + describeVector(snapshot.pose().position())
                + " vel=" + describeVector(snapshot.linearVelocity())
                + " angVel=" + describeVector(snapshot.angularVelocity())
                + " halfExtents=" + describeVector(snapshot.halfExtents())
                + " mass=" + String.format("%.2f", snapshot.mass())
                + " distance=" + String.format("%.2f", distance);
    }

    private static String describeMechanicsJoint(MechanicsJointSnapshot snapshot) {
        return shortId(snapshot)
                + " id=" + snapshot.id()
                + " type=" + snapshot.type()
                + " first=" + snapshot.firstBodyId()
                + " second=" + snapshot.secondBodyId();
    }

    private static String describeElasticPanel(ElasticPanelSnapshot snapshot, double distance) {
        return shortId(snapshot)
                + " id=" + snapshot.id()
                + " center=("
                + String.format("%.2f", snapshot.centerX())
                + ", "
                + String.format("%.2f", snapshot.topY())
                + ", "
                + String.format("%.2f", snapshot.centerZ())
                + ")"
                + " size=" + String.format("%.2f", snapshot.width()) + "x" + String.format("%.2f", snapshot.depth())
                + " deflection=" + String.format("%.3f", snapshot.deflection()) + "/" + String.format("%.3f", snapshot.maxDeflection())
                + " load=" + String.format("%.2f", snapshot.load())
                + " signal=" + snapshot.signal()
                + " powered=" + snapshot.powered()
                + " output=" + describeBlockPos(snapshot.outputPos())
                + (snapshot.outputBlocked() ? " outputBlocked=true" : "")
                + " distance=" + String.format("%.2f", distance);
    }

    private static String describeFemVolume(FemVolumeSnapshot snapshot, double distance) {
        return shortId(snapshot)
                + " id=" + snapshot.id()
                + " kind=" + snapshot.kind()
                + " center=" + describeVector(snapshot.center())
                + " dims=" + describeVector(snapshot.dimensions())
                + " density=" + String.format("%.2f", snapshot.density())
                + " youngs=" + String.format("%.0f", snapshot.youngs())
                + " voxels=" + snapshot.voxels()
                + " collisionMesh=" + snapshot.collisionVertexCount() + "v/" + snapshot.collisionTetrahedronCount() + "t"
                + " simMesh=" + snapshot.simulationVertexCount() + "v/" + snapshot.simulationTetrahedronCount() + "t"
                + " markers=" + snapshot.visualMarkerCount()
                + " maxDisp=" + String.format("%.3f", snapshot.maxDisplacement())
                + " avgDisp=" + String.format("%.3f", snapshot.averageDisplacement())
                + " scale=" + String.format("%.2f", snapshot.minScale()) + ".." + String.format("%.2f", snapshot.maxScale())
                + " volume=" + String.format("%.2f", snapshot.volumeRatio()) + "x"
                + " distance=" + String.format("%.2f", distance);
    }

    private static String describeAssembly(AssemblySnapshot snapshot, double distance) {
        MechanicsBodySnapshot body = snapshot.body();
        return shortId(snapshot)
                + " id=" + snapshot.id()
                + " state=" + snapshot.state()
                + " body=" + body.id()
                + " plot=" + describeAssemblyPlot(snapshot)
                + " block=" + blockName(snapshot)
                + " blocks=" + snapshot.blockCount()
                + " dirty=" + snapshot.dirtyBlockCount()
                + " visuals=" + snapshot.visualCount()
                + " source=" + describeAssemblyBounds(snapshot)
                + " pos=" + describeVector(body.pose().position())
                + " vel=" + describeVector(body.linearVelocity())
                + " angVel=" + describeVector(body.angularVelocity())
                + " halfExtents=" + describeVector(body.halfExtents())
                + " mass=" + String.format("%.2f", body.mass())
                + " distance=" + String.format("%.2f", distance);
    }

    private static String describeAssemblyPick(AssemblyPickResult result) {
        return "AssemblyPick id=" + result.id()
                + " body=" + result.body().id()
                + " block=" + blockName(result.blockState())
                + " local=" + describeBlockPos(result.localPos())
                + " source=" + describeBlockPos(result.block().sourcePos())
                + " worldHit=" + describeVector(result.worldHit())
                + " localHit=" + describeVector(result.localHit())
                + " distance=" + String.format("%.3f", result.distance());
    }

    private static String describeAssemblyPlot(AssemblySnapshot snapshot) {
        return snapshot.plot().describe();
    }

    private static String describeAssemblyBreak(AssemblyBreakResult result) {
        AssemblyPickResult pick = result.pick();
        return "AssemblyBreak id=" + pick.id()
                + " body=" + pick.body().id()
                + " block=" + blockName(pick.blockState())
                + " local=" + describeBlockPos(pick.localPos())
                + " source=" + describeBlockPos(pick.block().sourcePos())
                + " remainingBlocks=" + result.remainingBlocks()
                + " dirtyBlocks=" + result.dirtyBlocks()
                + " removedVisuals=" + result.removedVisuals()
                + " connectedComponents=" + result.connectedComponents()
                + " createdAssemblies=" + result.createdAssemblies()
                + " removedAssembly=" + result.removedAssembly();
    }

    private static String describePlayerPhysics(PlayerPhysicsSnapshot snapshot) {
        MechanicsBodySnapshot body = snapshot.body();
        return "PlayerPhysics player=" + snapshot.playerName()
                + " body=" + body.id()
                + " role=" + body.role()
                + " pos=" + describeVector(body.pose().position())
                + " vel=" + describeVector(body.linearVelocity())
                + " angVel=" + describeVector(body.angularVelocity())
                + " halfExtents=" + describeVector(body.halfExtents())
                + " mass=" + String.format("%.2f", body.mass())
                + " debugProxy=" + snapshot.debugProxy()
                + " syncedTicks=" + snapshot.syncedTicks();
    }

    private static String describePlayerProxy(PlayerProxySnapshot snapshot) {
        MechanicsBodySnapshot body = snapshot.body();
        return "PlayerProxy player=" + snapshot.playerName()
                + " body=" + body.id()
                + " role=" + body.role()
                + " pos=" + describeVector(body.pose().position())
                + " vel=" + describeVector(body.linearVelocity())
                + " angVel=" + describeVector(body.angularVelocity())
                + " halfExtents=" + describeVector(body.halfExtents())
                + " mass=" + String.format("%.2f", body.mass())
                + " debugProxy=" + snapshot.debugProxy()
                + " syncedTicks=" + snapshot.syncedTicks();
    }

    private static String shortId(PhysicsObjectSnapshot snapshot) {
        return snapshot.id().toString().substring(0, 8);
    }

    private static String shortId(MechanicsBodySnapshot snapshot) {
        return snapshot.id().toString().substring(0, 8);
    }

    private static String shortId(MechanicsJointSnapshot snapshot) {
        return snapshot.id().toString().substring(0, 8);
    }

    private static String shortId(AssemblySnapshot snapshot) {
        return snapshot.id().toString().substring(0, 8);
    }

    private static String shortId(ElasticPanelSnapshot snapshot) {
        return snapshot.id().toString().substring(0, 8);
    }

    private static String shortId(FemVolumeSnapshot snapshot) {
        return snapshot.id().toString().substring(0, 8);
    }

    private static double distanceSqr(PhysicsObjectSnapshot snapshot, Vec3 origin) {
        PhysicsVector position = snapshot.pose().position();
        double dx = position.x() - origin.x();
        double dy = position.y() - origin.y();
        double dz = position.z() - origin.z();
        return dx * dx + dy * dy + dz * dz;
    }

    private static double distanceSqr(MechanicsBodySnapshot snapshot, Vec3 origin) {
        PhysicsVector position = snapshot.pose().position();
        double dx = position.x() - origin.x();
        double dy = position.y() - origin.y();
        double dz = position.z() - origin.z();
        return dx * dx + dy * dy + dz * dz;
    }

    private static double distanceSqr(AssemblySnapshot snapshot, Vec3 origin) {
        return distanceSqr(snapshot.body(), origin);
    }

    private static double distanceSqr(ElasticPanelSnapshot snapshot, Vec3 origin) {
        double dx = snapshot.centerX() - origin.x();
        double dy = snapshot.topY() - origin.y();
        double dz = snapshot.centerZ() - origin.z();
        return dx * dx + dy * dy + dz * dz;
    }

    private static double distanceSqr(FemVolumeSnapshot snapshot, Vec3 origin) {
        PhysicsVector center = snapshot.center();
        double dx = center.x() - origin.x();
        double dy = center.y() - origin.y();
        double dz = center.z() - origin.z();
        return dx * dx + dy * dy + dz * dz;
    }

    private static String describeVector(PhysicsVector vector) {
        return "("
                + String.format("%.2f", vector.x())
                + ", "
                + String.format("%.2f", vector.y())
                + ", "
                + String.format("%.2f", vector.z())
                + ")";
    }

    private static String describeBlockPos(BlockPos pos) {
        return "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
    }

    private static String blockName(AssemblySnapshot snapshot) {
        String name = blockName(snapshot.firstBlockState());
        return snapshot.assembly() ? name + "+..." : name;
    }

    private static String blockName(net.minecraft.world.level.block.state.BlockState blockState) {
        return BuiltInRegistries.BLOCK.getKey(blockState.getBlock()).toString();
    }

    private static String describeAssemblyBounds(AssemblySnapshot snapshot) {
        if (!snapshot.assembly()) {
            return describeBlockPos(snapshot.firstBlock().sourcePos());
        }
        return describeBlockPos(snapshot.bounds().minSourcePos()) + " to " + describeBlockPos(snapshot.bounds().maxSourcePos());
    }
}

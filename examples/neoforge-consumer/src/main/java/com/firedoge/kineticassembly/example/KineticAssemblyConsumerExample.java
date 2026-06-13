package com.firedoge.kineticassembly.example;

import com.firedoge.kineticassembly.KineticAssembly;
import com.firedoge.kineticassembly.api.PhysicsPose;
import com.firedoge.kineticassembly.api.PhysicsQuaternion;
import com.firedoge.kineticassembly.api.PhysicsVector;
import com.firedoge.kineticassembly.mechanics.MechanicsApi;
import com.firedoge.kineticassembly.mechanics.MechanicsBodyId;
import com.firedoge.kineticassembly.mechanics.MechanicsBodySnapshot;
import com.firedoge.kineticassembly.mechanics.MechanicsBoxDefinition;
import com.firedoge.kineticassembly.mechanics.MechanicsWorld;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.common.Mod;

@Mod(KineticAssemblyConsumerExample.MODID)
public final class KineticAssemblyConsumerExample {
    public static final String MODID = "kinetic_assembly_consumer_example";

    public KineticAssemblyConsumerExample() {
    }

    public static MechanicsBodyId spawnDemoBox(ServerLevel level, Vec3 position) {
        MechanicsApi mechanics = KineticAssembly.api();
        MechanicsWorld world = mechanics.world(level);
        MechanicsBodySnapshot body = world.createDynamicBox(
                MechanicsBoxDefinition.gameplayDynamicBox(
                        new PhysicsPose(
                                new PhysicsVector(position.x, position.y + 1.5D, position.z),
                                PhysicsQuaternion.IDENTITY
                        ),
                        new PhysicsVector(0.5D, 0.5D, 0.5D),
                        20.0F
                )
        );
        world.applyLinearImpulse(body.id(), new PhysicsVector(0.0D, 6.0D, 0.0D));
        return body.id();
    }
}

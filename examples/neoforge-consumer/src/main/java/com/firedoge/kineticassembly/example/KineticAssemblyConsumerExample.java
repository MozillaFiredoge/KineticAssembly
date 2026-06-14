package com.firedoge.kineticassembly.example;

import com.firedoge.kineticassembly.KineticAssembly;
import com.firedoge.kineticassembly.api.PhysicsPose;
import com.firedoge.kineticassembly.api.PhysicsQuaternion;
import com.firedoge.kineticassembly.api.PhysicsVector;
import com.firedoge.kineticassembly.mechanics.MechanicsApi;
import com.firedoge.kineticassembly.mechanics.MechanicsBodyId;
import com.firedoge.kineticassembly.mechanics.MechanicsBodySnapshot;
import com.firedoge.kineticassembly.mechanics.MechanicsBoxDefinition;
import com.firedoge.kineticassembly.mechanics.MechanicsCapabilities;
import com.firedoge.kineticassembly.mechanics.MechanicsOwner;
import com.firedoge.kineticassembly.mechanics.MechanicsResult;
import com.firedoge.kineticassembly.mechanics.MechanicsTickPhase;
import com.firedoge.kineticassembly.mechanics.MechanicsWorld;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.common.Mod;

@Mod(KineticAssemblyConsumerExample.MODID)
public final class KineticAssemblyConsumerExample {
    public static final String MODID = "kinetic_assembly_consumer_example";
    private static final MechanicsOwner OWNER = MechanicsOwner.of(MODID, "demo");

    public KineticAssemblyConsumerExample() {
    }

    public static MechanicsBodyId spawnDemoBox(ServerLevel level, Vec3 position) {
        MechanicsApi mechanics = KineticAssembly.api();
        MechanicsCapabilities capabilities = mechanics.capabilities();
        if (!capabilities.dynamicBoxes()) {
            throw new IllegalStateException("KineticAssembly dynamic boxes are not available");
        }
        MechanicsWorld world = mechanics.world(level);
        MechanicsBodySnapshot body = world.createDynamicBox(
                OWNER,
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
        if (capabilities.forces()) {
            MechanicsResult<Void> result = world.applyForce(body.id(), new PhysicsVector(0.0D, 25.0D, 0.0D));
            if (!result.success()) {
                throw new IllegalStateException("KineticAssembly force application failed: " + result.code());
            }
        }
        return body.id();
    }

    public static AutoCloseable onPhysicsStep(MechanicsApi mechanics) {
        return mechanics.addTickListener(MechanicsTickPhase.AFTER_STEP, context -> {
            if (context.server().getTickCount() % 20 == 0) {
                mechanics.capabilities();
            }
        });
    }
}

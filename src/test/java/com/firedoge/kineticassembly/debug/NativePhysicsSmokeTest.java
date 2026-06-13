package com.firedoge.kineticassembly.debug;

import java.util.List;

import com.firedoge.kineticassembly.api.PhysicsBody;
import com.firedoge.kineticassembly.api.PhysicsBodyState;
import com.firedoge.kineticassembly.api.PhysicsBoxCollider;
import com.firedoge.kineticassembly.api.PhysicsJoint;
import com.firedoge.kineticassembly.api.PhysicsMassProperties;
import com.firedoge.kineticassembly.api.PhysicsPose;
import com.firedoge.kineticassembly.api.PhysicsQuaternion;
import com.firedoge.kineticassembly.api.PhysicsShape;
import com.firedoge.kineticassembly.api.PhysicsVector;
import com.firedoge.kineticassembly.api.PhysicsWorld;
import com.firedoge.kineticassembly.api.PhysicsWorldConfig;
import com.firedoge.kineticassembly.api.RigidBodyDefinition;
import com.firedoge.kineticassembly.backend.physx.PhysXBackend;
import com.firedoge.kineticassembly.minecraft.scene.PhysicsObject;
import com.firedoge.kineticassembly.minecraft.scene.PhysicsObjectId;
import com.firedoge.kineticassembly.minecraft.scene.PhysicsObjectSnapshot;
import com.firedoge.kineticassembly.minecraft.scene.PhysicsJointSnapshot;
import com.firedoge.kineticassembly.minecraft.scene.PhysicsSceneManager;
import com.firedoge.kineticassembly.minecraft.scene.ServerPhysicsScene;

public final class NativePhysicsSmokeTest {
    private NativePhysicsSmokeTest() {
    }

    public static void main(String[] args) {
        runMinimalLoop();
        runLifecycleChecks();
        runGpuDynamicsRequestCheck();
        runImpulseChecks();
        runBodyStateBatchCheck();
        runExplicitCompoundMassPropertiesCheck();
        runFixedJointChecks();
        runDistanceJointChecks();
        runRevoluteJointChecks();
        runPrismaticJointChecks();
        runCcdChecks();
        runSceneLayerChecks();
        runSceneJointChecks();
        System.out.println("Native physics lifecycle checks passed");
        System.out.println("Physics scene layer checks passed");
    }

    private static void runMinimalLoop() {
        PhysXBackend backend = new PhysXBackend();
        try (PhysicsWorld world = backend.createWorld(new PhysicsWorldConfig(PhysicsVector.MC_GRAVITY, 1.0F / 60.0F, 1))) {
            try (PhysicsBody ignoredGround = world.createStaticPlane(new PhysicsVector(0.0D, 1.0D, 0.0D), 0.0D);
                 PhysicsShape boxShape = world.createBoxShape(0.5F, 0.5F, 0.5F);
                 PhysicsBody box = world.createBody(RigidBodyDefinition.dynamic(
                         new PhysicsPose(new PhysicsVector(0.0D, 5.0D, 0.0D), PhysicsQuaternion.IDENTITY),
                         boxShape,
                         1.0F
                 ))) {
                double initialY = box.pose().position().y();
                for (int i = 0; i < 240; i++) {
                    world.step(1.0F / 60.0F);
                }

                PhysicsPose finalPose = box.pose();
                double finalY = finalPose.position().y();
                if (finalY >= initialY) {
                    throw new IllegalStateException("Expected the box to fall; initialY=" + initialY + ", finalY=" + finalY);
                }
                if (finalY < 0.45D || finalY > 0.75D) {
                    throw new IllegalStateException("Expected the box to rest near y=0.5; finalY=" + finalY);
                }

                System.out.printf("Native physics smoke test passed: initialY=%.4f finalY=%.4f%n", initialY, finalY);
            }
        }
    }

    private static void runLifecycleChecks() {
        PhysXBackend backend = new PhysXBackend();
        PhysicsWorld world = backend.createWorld(new PhysicsWorldConfig(PhysicsVector.MC_GRAVITY, 1.0F / 60.0F, 1));
        PhysicsBody ground = world.createStaticPlane(new PhysicsVector(0.0D, 1.0D, 0.0D), 0.0D);
        PhysicsShape sharedShape = world.createBoxShape(0.5F, 0.5F, 0.5F);
        PhysicsBody first = world.createBody(RigidBodyDefinition.dynamic(
                new PhysicsPose(new PhysicsVector(-1.0D, 4.0D, 0.0D), PhysicsQuaternion.IDENTITY),
                sharedShape,
                1.0F
        ));
        PhysicsBody second = world.createBody(RigidBodyDefinition.dynamic(
                new PhysicsPose(new PhysicsVector(1.0D, 6.0D, 0.0D), PhysicsQuaternion.IDENTITY),
                sharedShape,
                1.0F
        ));

        sharedShape.close();
        assertTrue(sharedShape.isClosed(), "Shape should report closed after close()");
        for (int i = 0; i < 120; i++) {
            world.step(1.0F / 60.0F);
        }
        assertTrue(first.pose().position().y() < 4.0D, "First body should keep simulating after shared shape close");
        assertTrue(second.pose().position().y() < 6.0D, "Second body should keep simulating after shared shape close");

        expectThrows(() -> world.createBody(RigidBodyDefinition.dynamic(
                new PhysicsPose(new PhysicsVector(0.0D, 10.0D, 0.0D), PhysicsQuaternion.IDENTITY),
                sharedShape,
                1.0F
        )), "Closed shape should not create new bodies");

        first.close();
        first.close();
        assertTrue(first.isClosed(), "Body close should be idempotent");

        world.close();
        world.close();
        assertTrue(world.isClosed(), "World close should be idempotent");
        assertTrue(ground.isClosed(), "World close should close remaining static plane bodies");
        assertTrue(second.isClosed(), "World close should close remaining dynamic bodies");

        expectThrows(() -> world.step(1.0F / 60.0F), "Closed world should reject simulation steps");
    }

    private static void runGpuDynamicsRequestCheck() {
        PhysXBackend backend = new PhysXBackend();
        try (PhysicsWorld world = backend.createWorld(new PhysicsWorldConfig(PhysicsVector.MC_GRAVITY, 1.0F / 60.0F, 1, true))) {
            try (PhysicsShape boxShape = world.createBoxShape(0.5F, 0.5F, 0.5F);
                 PhysicsBody box = world.createBody(RigidBodyDefinition.dynamic(
                         new PhysicsPose(new PhysicsVector(0.0D, 3.0D, 0.0D), PhysicsQuaternion.IDENTITY),
                         boxShape,
                         1.0F
                 ))) {
                double initialY = box.pose().position().y();
                for (int i = 0; i < 10; i++) {
                    world.step(1.0F / 60.0F);
                }
                assertTrue(box.pose().position().y() < initialY, "GPU-requested world should simulate or fall back to CPU");
                System.out.println("GPU dynamics request check passed: enabled=" + world.gpuDynamicsEnabled()
                        + " status=" + world.gpuDynamicsStatus());
            }
        }
    }

    private static void runImpulseChecks() {
        PhysXBackend backend = new PhysXBackend();
        try (PhysicsWorld world = backend.createWorld(new PhysicsWorldConfig(PhysicsVector.ZERO, 1.0F / 60.0F, 1));
             PhysicsShape boxShape = world.createBoxShape(0.5F, 0.5F, 0.5F);
             PhysicsBody box = world.createBody(RigidBodyDefinition.dynamic(
                     new PhysicsPose(new PhysicsVector(0.0D, 0.0D, 0.0D), PhysicsQuaternion.IDENTITY),
                     boxShape,
                     2.0F
             ))) {
            assertTrue(box.applyLinearImpulse(new PhysicsVector(2.0D, 0.0D, 0.0D)), "Dynamic body should accept linear impulse");
            assertTrue(box.applyAngularImpulse(new PhysicsVector(0.0D, 1.0D, 0.0D)), "Dynamic body should accept angular impulse");
            world.step(1.0F / 60.0F);

            PhysicsVector linearVelocity = box.linearVelocity();
            PhysicsVector angularVelocity = box.angularVelocity();
            assertTrue(linearVelocity.x() > 0.1D, "Linear impulse should increase x velocity; velocity=" + linearVelocity);
            assertTrue(angularVelocity.y() > 0.1D, "Angular impulse should increase y angular velocity; angularVelocity=" + angularVelocity);
            System.out.printf("Native impulse check passed: linearVx=%.4f angularVy=%.4f%n", linearVelocity.x(), angularVelocity.y());
        }
    }

    private static void runBodyStateBatchCheck() {
        PhysXBackend backend = new PhysXBackend();
        try (PhysicsWorld world = backend.createWorld(new PhysicsWorldConfig(PhysicsVector.ZERO, 1.0F / 60.0F, 1));
             PhysicsShape boxShape = world.createBoxShape(0.5F, 0.5F, 0.5F);
             PhysicsBody first = world.createBody(RigidBodyDefinition.dynamic(
                     new PhysicsPose(new PhysicsVector(-2.0D, 0.0D, 0.0D), PhysicsQuaternion.IDENTITY),
                     boxShape,
                     1.0F
             ));
             PhysicsBody second = world.createBody(RigidBodyDefinition.dynamic(
                     new PhysicsPose(new PhysicsVector(2.0D, 0.0D, 0.0D), PhysicsQuaternion.IDENTITY),
                     boxShape,
                     1.0F
             ))) {
            first.setLinearVelocity(new PhysicsVector(1.0D, 0.0D, 0.0D));
            second.setLinearVelocity(new PhysicsVector(0.0D, 2.0D, 0.0D));
            world.step(1.0F / 60.0F);

            List<PhysicsBodyState> states = world.readBodyStates(List.of(first, second));
            assertTrue(states.size() == 2, "Batch body-state read should return both dynamic bodies");
            PhysicsBodyState firstState = states.get(0);
            PhysicsBodyState secondState = states.get(1);
            assertTrue(firstState.body() == first, "First batch state should belong to the first body");
            assertTrue(secondState.body() == second, "Second batch state should belong to the second body");
            assertTrue(firstState.pose().position().x() > -2.0D, "First body should advance along x in batch state");
            assertTrue(firstState.linearVelocity().x() > 0.5D, "First batch state should include linear velocity");
            assertTrue(secondState.linearVelocity().y() > 1.0D, "Second batch state should include linear velocity");
            System.out.printf(
                    "Native body-state batch check passed: count=%d firstX=%.4f secondVy=%.4f%n",
                    states.size(),
                    firstState.pose().position().x(),
                    secondState.linearVelocity().y()
            );
        }
    }

    private static void runExplicitCompoundMassPropertiesCheck() {
        PhysXBackend backend = new PhysXBackend();
        List<PhysicsBoxCollider> boxes = List.of(
                new PhysicsBoxCollider(new PhysicsVector(-0.5D, 0.0D, 0.0D), new PhysicsVector(0.5D, 0.5D, 0.5D)),
                new PhysicsBoxCollider(new PhysicsVector(0.5D, 0.0D, 0.0D), new PhysicsVector(0.5D, 0.5D, 0.5D))
        );
        PhysicsMassProperties massProperties = new PhysicsMassProperties(
                3.0F,
                new PhysicsVector(0.25D, 0.0D, 0.0D),
                new PhysicsVector(0.5D, 1.25D, 1.25D)
        );
        try (PhysicsWorld world = backend.createWorld(new PhysicsWorldConfig(PhysicsVector.ZERO, 1.0F / 60.0F, 1));
             PhysicsBody body = world.createDynamicCompoundBoxBody(
                     new PhysicsPose(PhysicsVector.ZERO, PhysicsQuaternion.IDENTITY),
                     boxes,
                     massProperties
             )) {
            assertTrue(body.nativeHandle() != 0L, "Compound body with explicit mass properties should have a native handle");
            assertTrue(body.applyAngularImpulse(new PhysicsVector(0.0D, 2.0D, 0.0D)), "Compound body should accept angular impulse");
            world.step(1.0F / 60.0F);
            assertTrue(Math.abs(body.angularVelocity().y()) > 0.05D, "Explicit inertia compound body should rotate after angular impulse");
            System.out.printf(
                    "Native explicit compound mass-properties check passed: comX=%.4f angularVy=%.4f%n",
                    massProperties.centerOfMass().x(),
                    body.angularVelocity().y()
            );
        }
    }

    private static void runFixedJointChecks() {
        PhysXBackend backend = new PhysXBackend();
        try (PhysicsWorld world = backend.createWorld(new PhysicsWorldConfig(PhysicsVector.ZERO, 1.0F / 60.0F, 1));
             PhysicsShape boxShape = world.createBoxShape(0.5F, 0.5F, 0.5F);
             PhysicsBody first = world.createBody(RigidBodyDefinition.dynamic(
                     new PhysicsPose(new PhysicsVector(-1.0D, 0.0D, 0.0D), PhysicsQuaternion.IDENTITY),
                     boxShape,
                     1.0F
             ));
             PhysicsBody second = world.createBody(RigidBodyDefinition.dynamic(
                     new PhysicsPose(new PhysicsVector(1.0D, 0.0D, 0.0D), PhysicsQuaternion.IDENTITY),
                     boxShape,
                     1.0F
             ));
             PhysicsJoint joint = world.createFixedJoint(first, second)) {
            assertTrue(joint.nativeHandle() != 0L, "Fixed joint should have a native handle");
            first.applyLinearImpulse(new PhysicsVector(4.0D, 0.0D, 0.0D));
            for (int i = 0; i < 120; i++) {
                world.step(1.0F / 60.0F);
            }

            double separation = second.pose().position().x() - first.pose().position().x();
            assertTrue(Math.abs(separation - 2.0D) < 0.15D, "Fixed joint should preserve initial body separation; separation=" + separation);
            assertTrue(second.linearVelocity().x() > 0.1D, "Fixed joint should transfer motion to the connected body");
            double linkedSecondVx = second.linearVelocity().x();

            joint.close();
            joint.close();
            assertTrue(joint.isClosed(), "Fixed joint close should be idempotent");

            first.setLinearVelocity(PhysicsVector.ZERO);
            second.setLinearVelocity(PhysicsVector.ZERO);
            PhysicsVector firstPosition = first.pose().position();
            PhysicsVector secondPosition = second.pose().position();
            PhysicsPose anchoredFrame = new PhysicsPose(
                    new PhysicsVector(
                            (firstPosition.x() + secondPosition.x()) * 0.5D,
                            (firstPosition.y() + secondPosition.y()) * 0.5D + 0.25D,
                            (firstPosition.z() + secondPosition.z()) * 0.5D
                    ),
                    PhysicsQuaternion.IDENTITY
            );
            try (PhysicsJoint anchoredJoint = world.createFixedJointAtWorldFrame(first, second, anchoredFrame)) {
                assertTrue(anchoredJoint.nativeHandle() != 0L, "Anchored fixed joint should have a native handle");
                first.applyLinearImpulse(new PhysicsVector(0.0D, 3.0D, 0.0D));
                for (int i = 0; i < 60; i++) {
                    world.step(1.0F / 60.0F);
                }
                double anchoredDistance = distance(first.pose().position(), second.pose().position());
                assertTrue(Math.abs(anchoredDistance - separation) < 0.15D, "Anchored fixed joint should preserve body distance; distance=" + anchoredDistance);
            }
            System.out.printf("Native fixed-joint check passed: separation=%.4f secondVx=%.4f%n", separation, linkedSecondVx);
        }
    }

    private static void runDistanceJointChecks() {
        PhysXBackend backend = new PhysXBackend();
        try (PhysicsWorld world = backend.createWorld(new PhysicsWorldConfig(PhysicsVector.ZERO, 1.0F / 60.0F, 1));
             PhysicsShape boxShape = world.createBoxShape(0.5F, 0.5F, 0.5F);
             PhysicsBody first = world.createBody(RigidBodyDefinition.dynamic(
                     new PhysicsPose(new PhysicsVector(-1.0D, 0.0D, 0.0D), PhysicsQuaternion.IDENTITY),
                     boxShape,
                     1.0F
             ));
             PhysicsBody second = world.createBody(RigidBodyDefinition.dynamic(
                     new PhysicsPose(new PhysicsVector(1.0D, 0.0D, 0.0D), PhysicsQuaternion.IDENTITY),
                     boxShape,
                     1.0F
             ));
             PhysicsJoint joint = world.createDistanceJoint(first, second, 1.9F, 2.1F)) {
            assertTrue(joint.nativeHandle() != 0L, "Distance joint should have a native handle");
            first.applyLinearImpulse(new PhysicsVector(-4.0D, 0.0D, 0.0D));
            for (int i = 0; i < 180; i++) {
                world.step(1.0F / 60.0F);
            }

            double separation = distance(first.pose().position(), second.pose().position());
            assertTrue(separation <= 2.35D, "Distance joint should limit body separation; separation=" + separation);
            assertTrue(second.linearVelocity().x() < -0.05D, "Distance joint should transfer motion to the connected body");

            joint.close();
            joint.close();
            assertTrue(joint.isClosed(), "Distance joint close should be idempotent");
            System.out.printf("Native distance-joint check passed: separation=%.4f secondVx=%.4f%n", separation, second.linearVelocity().x());
        }

        try (PhysicsWorld world = backend.createWorld(new PhysicsWorldConfig(PhysicsVector.ZERO, 1.0F / 60.0F, 1));
             PhysicsShape boxShape = world.createBoxShape(0.5F, 0.5F, 0.5F);
             PhysicsBody first = world.createBody(RigidBodyDefinition.dynamic(
                     new PhysicsPose(new PhysicsVector(-2.0D, 0.0D, 0.0D), PhysicsQuaternion.IDENTITY),
                     boxShape,
                     1.0F
             ));
             PhysicsBody second = world.createBody(RigidBodyDefinition.dynamic(
                     new PhysicsPose(new PhysicsVector(2.0D, 0.0D, 0.0D), PhysicsQuaternion.IDENTITY),
                     boxShape,
                     1.0F
             ));
             PhysicsJoint joint = world.createDistanceJointAtWorldAnchors(
                     first,
                     new PhysicsVector(-1.5D, 0.0D, 0.0D),
                     second,
                     new PhysicsVector(1.5D, 0.0D, 0.0D),
                     2.9F,
                     3.1F
             )) {
            assertTrue(joint.nativeHandle() != 0L, "Anchored distance joint should have a native handle");
            first.applyLinearImpulse(new PhysicsVector(-4.0D, 0.0D, 0.0D));
            for (int i = 0; i < 180; i++) {
                world.step(1.0F / 60.0F);
            }

            double anchoredDistance = Math.abs((second.pose().position().x() - 0.5D) - (first.pose().position().x() + 0.5D));
            assertTrue(anchoredDistance <= 3.35D, "Anchored distance joint should limit anchor separation; distance=" + anchoredDistance);
            assertTrue(second.linearVelocity().x() < -0.05D, "Anchored distance joint should transfer motion to the connected body");
            System.out.printf("Native anchored distance-joint check passed: anchorDistance=%.4f secondVx=%.4f%n", anchoredDistance, second.linearVelocity().x());
        }
    }

    private static void runRevoluteJointChecks() {
        PhysXBackend backend = new PhysXBackend();
        try (PhysicsWorld world = backend.createWorld(new PhysicsWorldConfig(PhysicsVector.ZERO, 1.0F / 60.0F, 1));
             PhysicsShape boxShape = world.createBoxShape(0.2F, 0.2F, 0.2F);
             PhysicsBody support = world.createBody(RigidBodyDefinition.staticBody(
                     new PhysicsPose(PhysicsVector.ZERO, PhysicsQuaternion.IDENTITY),
                     boxShape
             ));
             PhysicsBody arm = world.createBody(RigidBodyDefinition.dynamic(
                     new PhysicsPose(new PhysicsVector(0.0D, 1.0D, 0.0D), PhysicsQuaternion.IDENTITY),
                     boxShape,
                     1.0F
             ));
             PhysicsJoint joint = world.createRevoluteJointAtWorldFrame(support, arm, PhysicsPose.IDENTITY)) {
            assertTrue(joint.nativeHandle() != 0L, "Revolute joint should have a native handle");
            arm.applyAngularImpulse(new PhysicsVector(4.0D, 0.0D, 0.0D));
            for (int i = 0; i < 120; i++) {
                world.step(1.0F / 60.0F);
            }

            PhysicsVector position = arm.pose().position();
            double radius = Math.sqrt(position.y() * position.y() + position.z() * position.z());
            assertTrue(Math.abs(radius - 1.0D) < 0.2D, "Revolute joint should preserve radius around hinge axis; radius=" + radius);
            assertTrue(Math.abs(arm.angularVelocity().x()) > 0.05D, "Revolute joint should allow angular velocity around the hinge axis");
            System.out.printf("Native revolute-joint check passed: radius=%.4f angularVx=%.4f%n", radius, arm.angularVelocity().x());
        }
    }

    private static void runPrismaticJointChecks() {
        PhysXBackend backend = new PhysXBackend();
        try (PhysicsWorld world = backend.createWorld(new PhysicsWorldConfig(PhysicsVector.ZERO, 1.0F / 60.0F, 1));
             PhysicsShape boxShape = world.createBoxShape(0.2F, 0.2F, 0.2F);
             PhysicsBody rail = world.createBody(RigidBodyDefinition.staticBody(
                     new PhysicsPose(PhysicsVector.ZERO, PhysicsQuaternion.IDENTITY),
                     boxShape
             ));
             PhysicsBody slider = world.createBody(RigidBodyDefinition.dynamic(
                     new PhysicsPose(new PhysicsVector(0.0D, 1.0D, 0.0D), PhysicsQuaternion.IDENTITY),
                     boxShape,
                     1.0F
             ));
             PhysicsJoint joint = world.createPrismaticJointAtWorldFrame(rail, slider, PhysicsPose.IDENTITY)) {
            assertTrue(joint.nativeHandle() != 0L, "Prismatic joint should have a native handle");
            slider.applyLinearImpulse(new PhysicsVector(4.0D, 0.0D, 0.0D));
            for (int i = 0; i < 120; i++) {
                world.step(1.0F / 60.0F);
            }

            PhysicsVector position = slider.pose().position();
            assertTrue(position.x() > 0.5D, "Prismatic joint should allow motion along the slide axis; x=" + position.x());
            assertTrue(Math.abs(position.y() - 1.0D) < 0.1D, "Prismatic joint should constrain off-axis y motion; y=" + position.y());
            assertTrue(Math.abs(position.z()) < 0.1D, "Prismatic joint should constrain off-axis z motion; z=" + position.z());
            System.out.printf("Native prismatic-joint check passed: pos=(%.4f, %.4f, %.4f)%n", position.x(), position.y(), position.z());
        }
    }

    private static void runCcdChecks() {
        PhysXBackend backend = new PhysXBackend();
        try (PhysicsWorld world = backend.createWorld(new PhysicsWorldConfig(PhysicsVector.ZERO, 1.0F / 20.0F, 1));
             PhysicsShape wallShape = world.createBoxShape(0.05F, 2.0F, 2.0F);
             PhysicsShape projectileShape = world.createBoxShape(0.25F, 0.25F, 0.25F);
             PhysicsBody ignoredWall = world.createBody(RigidBodyDefinition.staticBody(
                     new PhysicsPose(new PhysicsVector(0.0D, 0.0D, 0.0D), PhysicsQuaternion.IDENTITY),
                     wallShape
             ));
             PhysicsBody projectile = world.createBody(RigidBodyDefinition.dynamic(
                     new PhysicsPose(new PhysicsVector(-3.0D, 0.0D, 0.0D), PhysicsQuaternion.IDENTITY),
                     projectileShape,
                     1.0F
             ))) {
            projectile.setLinearVelocity(new PhysicsVector(200.0D, 0.0D, 0.0D));
            world.step(1.0F / 20.0F);
            PhysicsVector position = projectile.pose().position();
            assertTrue(position.x() < 0.0D, "CCD should prevent the projectile from crossing the thin wall; x=" + position.x());
            System.out.printf("Native CCD check passed: projectileX=%.4f%n", position.x());
        }
    }

    private static void runSceneLayerChecks() {
        PhysXBackend backend = new PhysXBackend();
        try (PhysicsSceneManager manager = new PhysicsSceneManager()) {
            ServerPhysicsScene scene = manager.createScene(
                    "minecraft:overworld",
                    backend,
                    new PhysicsWorldConfig(PhysicsVector.MC_GRAVITY, 1.0F / 60.0F, 1)
            );
            PhysicsObject ground = scene.createStaticPlane(new PhysicsVector(0.0D, 1.0D, 0.0D), 0.0D);
            PhysicsObject box = scene.createDynamicBox(
                    0.5F,
                    0.5F,
                    0.5F,
                    new PhysicsPose(new PhysicsVector(0.0D, 4.0D, 0.0D), PhysicsQuaternion.IDENTITY),
                    1.0F
            );
            PhysicsObjectId boxId = box.id();

            assertTrue(scene.objectCount() == 2, "Scene should track the static plane and dynamic box");
            assertTrue(scene.object(boxId).isPresent(), "Scene should look up objects by stable id");

            double initialY = box.snapshot().pose().position().y();
            for (int i = 0; i < 180; i++) {
                scene.step(1.0F / 60.0F);
            }
            PhysicsObjectSnapshot snapshot = scene.object(boxId)
                    .map(PhysicsObject::snapshot)
                    .orElseThrow();
            assertTrue(snapshot.pose().position().y() < initialY, "Scene object snapshot should reflect simulated pose");
            assertTrue(snapshot.pose().position().y() > 0.45D, "Scene object should rest above the ground plane");

            assertTrue(scene.removeObject(boxId), "Scene should remove object by stable id");
            assertTrue(scene.object(boxId).isEmpty(), "Removed object id should no longer resolve");
            assertTrue(box.isClosed(), "Removing a scene object should close the object");

            manager.closeScene(scene.sceneKey());
            assertTrue(scene.isClosed(), "Closing scene through manager should close the scene");
            assertTrue(ground.isClosed(), "Closing scene should close remaining objects");
            expectThrows(() -> scene.step(1.0F / 60.0F), "Closed scene should reject steps");
        }
    }

    private static void runSceneJointChecks() {
        PhysXBackend backend = new PhysXBackend();
        try (PhysicsSceneManager manager = new PhysicsSceneManager()) {
            ServerPhysicsScene scene = manager.createScene(
                    "minecraft:joint_smoke",
                    backend,
                    new PhysicsWorldConfig(PhysicsVector.ZERO, 1.0F / 60.0F, 1)
            );
            PhysicsObject first = scene.createDynamicBox(
                    0.5F,
                    0.5F,
                    0.5F,
                    new PhysicsPose(new PhysicsVector(-1.0D, 0.0D, 0.0D), PhysicsQuaternion.IDENTITY),
                    1.0F
            );
            PhysicsObject second = scene.createDynamicBox(
                    0.5F,
                    0.5F,
                    0.5F,
                    new PhysicsPose(new PhysicsVector(1.0D, 0.0D, 0.0D), PhysicsQuaternion.IDENTITY),
                    1.0F
            );
            PhysicsJointSnapshot joint = scene.createFixedJoint(first.id(), second.id());
            assertTrue(scene.jointCount() == 1, "Scene should track fixed joints");
            assertTrue(scene.joint(joint.id()).isPresent(), "Scene should look up joints by stable id");
            assertTrue(scene.removeJoint(joint.id()), "Scene should remove a fixed joint by stable id");

            PhysicsJointSnapshot distanceJoint = scene.createDistanceJoint(first.id(), second.id(), 1.5F, 2.5F, 0.0F, 0.0F);
            assertTrue(scene.jointCount() == 1, "Scene should track distance joints");
            assertTrue(scene.joint(distanceJoint.id()).isPresent(), "Scene should look up distance joints by stable id");
            assertTrue(scene.removeJoint(distanceJoint.id()), "Scene should remove a distance joint by stable id");

            PhysicsJointSnapshot anchoredDistanceJoint = scene.createDistanceJointAtWorldAnchors(
                    first.id(),
                    new PhysicsVector(-0.5D, 0.0D, 0.0D),
                    second.id(),
                    new PhysicsVector(0.5D, 0.0D, 0.0D),
                    0.9F,
                    1.1F,
                    0.0F,
                    0.0F
            );
            assertTrue(scene.jointCount() == 1, "Scene should track anchored distance joints");
            assertTrue(scene.joint(anchoredDistanceJoint.id()).isPresent(), "Scene should look up anchored distance joints by stable id");
            assertTrue(scene.removeJoint(anchoredDistanceJoint.id()), "Scene should remove an anchored distance joint by stable id");

            PhysicsJointSnapshot revoluteJoint = scene.createRevoluteJointAtWorldFrame(first.id(), second.id(), PhysicsPose.IDENTITY);
            assertTrue(scene.jointCount() == 1, "Scene should track revolute joints");
            assertTrue(scene.joint(revoluteJoint.id()).isPresent(), "Scene should look up revolute joints by stable id");
            assertTrue(scene.removeJoint(revoluteJoint.id()), "Scene should remove a revolute joint by stable id");

            PhysicsJointSnapshot prismaticJoint = scene.createPrismaticJointAtWorldFrame(first.id(), second.id(), PhysicsPose.IDENTITY);
            assertTrue(scene.jointCount() == 1, "Scene should track prismatic joints");
            assertTrue(scene.joint(prismaticJoint.id()).isPresent(), "Scene should look up prismatic joints by stable id");
            assertTrue(scene.removeJoint(prismaticJoint.id()), "Scene should remove a prismatic joint by stable id");

            PhysicsJointSnapshot anchoredJoint = scene.createFixedJointAtWorldFrame(
                    first.id(),
                    second.id(),
                    new PhysicsPose(new PhysicsVector(0.0D, 0.25D, 0.0D), PhysicsQuaternion.IDENTITY)
            );
            assertTrue(scene.jointCount() == 1, "Scene should track anchored fixed joints");
            assertTrue(scene.joint(anchoredJoint.id()).isPresent(), "Scene should look up anchored joints by stable id");

            assertTrue(scene.removeObject(first.id()), "Scene should remove a jointed object");
            assertTrue(scene.jointCount() == 0, "Removing a body should cascade-remove attached joints");
            assertTrue(scene.joint(anchoredJoint.id()).isEmpty(), "Cascade-removed joint id should no longer resolve");
            assertTrue(scene.object(second.id()).isPresent(), "Second object should remain in the scene after first body removal");

            manager.closeScene(scene.sceneKey());
            assertTrue(scene.isClosed(), "Closing joint smoke scene should close the scene");
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static double distance(PhysicsVector first, PhysicsVector second) {
        double x = first.x() - second.x();
        double y = first.y() - second.y();
        double z = first.z() - second.z();
        return Math.sqrt(x * x + y * y + z * z);
    }

    private static void expectThrows(Runnable action, String message) {
        try {
            action.run();
        } catch (IllegalArgumentException | IllegalStateException expected) {
            return;
        }
        throw new IllegalStateException(message);
    }
}

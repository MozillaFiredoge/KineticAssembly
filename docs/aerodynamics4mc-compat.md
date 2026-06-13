# Aerodynamics4MC Compatibility

KineticAssembly integrates with Aerodynamics4MC as an optional runtime compatibility layer. It uses reflection so this mod does not need a hard compile-time dependency on Aerodynamics4MC.

## Tick Flow

On each server tick, before PhysX advances:

1. Check that `aerodynamics4mc` is loaded.
2. Sample gameplay wind for active assembly physics bodies through `AeroWindApi.sampleGameplay` with `SamplePolicy.SERVER_COARSE_ONLY`.
3. Build or reuse a reduced-order geometry profile for the assembly.
4. Estimate `Cd` and `Cl` from configured coefficients plus coarse gameplay wind shelter, turbulence, confidence, updraft, and shear.
5. Generate force from dynamic pressure and projected area; generate moment only from force applied at the geometry reference offset.
6. Convert the force and moment from the wind frame to PhysX world coordinates.
7. Apply them as linear and angular impulses to the assembly body.

`/kinetic_assembly physics_status` reports sampler, profile, formula, and impulse counters through `aeroCompat=...`.

## Debug Particles

Use `/kinetic_assembly aero_debug_particles true` to show server-side vector particles around sampled assemblies:

- Blue: sampled gameplay wind velocity.
- Red: generated aerodynamic force on the body.
- Purple: generated aerodynamic moment converted to the body/world frame.

Use `/kinetic_assembly aero_debug_particles false` to disable them. The default is controlled by `enableAerodynamicsDebugParticles`.

## Geometry Profile

The profile voxelizer consumes each `AssemblyBlock.bodyLocalCollisionBoxes()` entry. This keeps non-full-block collision geometry such as fences, stairs, slabs, doors, and other multipart voxel shapes instead of treating every block as a full cube.

The wind frame is rebuilt from relative wind:

```text
relativeWind = sampledWindVelocity - bodyLinearVelocity
x = relative wind direction
y = closest body-local up axis
z = x cross y
```

The internal `32x32x32` occupancy profile is used only to estimate projected area in the current wind frame and a characteristic length. It is not uploaded to, or solved by, Aerodynamics4MC.

```text
q = 0.5 * airDensity * relativeWindSpeed^2
shelterDerate = 1 - shelterFactor * 0.65
forceScale = q * projectedArea * confidence * shelterDerate * aerodynamicsForceScale
drag = forceScale * Cd
lift = forceScale * Cl
```

`Cd` starts from `aerodynamicsDragCoefficient` and is boosted by turbulence and shear. `Cl` starts from `aerodynamicsLiftCoefficient` and is gated by a simple angle/updraft proxy. Both are capped by `aerodynamicsMaxForceCoefficient`.

Server physics samples only `SERVER_COARSE_ONLY`, so L2 data is not used for force or moment impulses. L2 remains a visual/local-flow effect owned by Aerodynamics4MC.

```text
moment = cross(referenceBodyLocal, force)
momentLimit = q * projectedArea * characteristicLength * aerodynamicsMaxMomentCoefficient
```

The profile is cached per dimension and assembly id. It rebuilds when the assembly geometry, wind-frame bucket, or voxel spacing changes.

## Configuration

Common config keys:

- `enableAerodynamicsCoupling`: enable empirical force and moment impulses.
- `aerodynamicsMaxWindSpeedMetersPerSecond`: cap relative wind speed used by the empirical formula.
- `aerodynamicsDragCoefficient`: base `Cd`.
- `aerodynamicsLiftCoefficient`: base `Cl`.
- `aerodynamicsForceScale`: empirical multiplier applied to force and moment before impulse limiting.
- `aerodynamicsMaxForceCoefficient`: cap for modified `Cd` and `Cl`.
- `aerodynamicsMaxMomentCoefficient`: cap for generated moment by dynamic pressure, projected area, and characteristic length.
- `aerodynamicsMaxLinearImpulse`: per-body linear impulse limit in `N*s`.
- `aerodynamicsMaxLinearDeltaVelocityPerTick`: mass-scaled linear impulse limit; effective linear impulse is also capped to `bodyMass * thisValue`.
- `aerodynamicsMaxAngularImpulse`: per-body angular impulse limit in `N*m*s`.
- `enableAerodynamicsDebugParticles`: enable the wind/force/moment particle overlay by default.
- `aerodynamicsDebugParticleIntervalTicks`: particle overlay update interval.

## Q-Criterion

KineticAssembly no longer runs an Aerodynamics4MC wind-tunnel solver in this compatibility path, so it does not compute Q-criterion diagnostics. Use Aerodynamics4MC diagnostics directly for flow-field visualization.

# Continuous Collision Detection

KineticAssembly enables swept continuous collision detection by default for PhysX
scenes and dynamic rigid bodies.

Native setup:

- `PxSceneFlag::eENABLE_CCD` is enabled when a scene is created.
- The simulation filter shader adds `PxPairFlag::eDETECT_CCD_CONTACT` to normal
  contact pairs while preserving trigger behavior.
- Dynamic bodies created by the backend raise `PxRigidBodyFlag::eENABLE_CCD`.
- `ccdMaxPasses` is set to `4` to give fast bodies a few CCD passes before
  remaining time is dropped.

This is intentionally not configurable yet. The current gameplay and mechanics
layers should assume CCD is part of the default safety baseline.

Limits:

- CCD reduces tunneling risk but does not make arbitrarily fast motion free.
- Higher fixed-step rates still improve stability for extreme speeds.
- Trigger shapes do not use `eDETECT_CCD_CONTACT`.
- Native force/impulse and vehicle APIs should still avoid generating
  unrealistic one-tick velocity spikes unless the caller explicitly wants a
  projectile-like body.

Regression coverage:

```text
./gradlew nativeSmokeTest
```

The native smoke test includes a fast dynamic box crossing a thin static wall in
one 20 TPS step. The test fails if the projectile crosses the wall.

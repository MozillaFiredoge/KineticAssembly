# Physics Resource Lifecycle

This project keeps Minecraft-facing code away from raw native handles. Native
resources are owned by Java wrapper objects and must be released through their
`close()` methods.

## Ownership Rules

- `PhysicsWorld` owns the native scene.
- `PhysicsWorld` tracks every `PhysicsBody` and `PhysicsShape` created through
  it.
- `PhysicsBody` owns one native actor.
- `PhysicsShape` owns one native shared shape.
- Shapes may be shared by multiple bodies in the same world.
- Shapes cannot be shared across worlds.
- Closing a world closes any remaining bodies and shapes created by that world.
- Closing a body or shape manually removes it from the world's tracked resource
  list.
- Every `close()` method must be idempotent.

## Shape Sharing

PhysX actors keep their own reference to an attached shape, so a Java
`PhysicsShape` can be closed after bodies have been created from it. Existing
bodies continue simulating, but that closed shape cannot be used to create more
bodies.

## Recommended Order

For explicit cleanup:

```text
body.close()
shape.close()
world.close()
```

When code is short-lived, try-with-resources is preferred. If a caller forgets to
close bodies or shapes, `world.close()` performs the final cleanup.

## Guard Rails

- Creating a body from a closed shape throws.
- Creating a body from a shape owned by another world throws.
- Destroying a body through the wrong world throws.
- Stepping a closed world throws.

These checks keep invalid native handle use close to the Java boundary before
Minecraft integration adds more lifecycle paths.

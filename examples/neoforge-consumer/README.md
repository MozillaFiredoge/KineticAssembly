# KineticAssembly NeoForge Consumer Example

This is a minimal dependent mod that compiles against the KineticAssembly API
artifact and uses the full NeoForge runtime artifact at runtime.

From the repository root, publish local artifacts first:

```text
./gradlew publish
```

Then build the example:

```text
./gradlew -p examples/neoforge-consumer build
```

The example prefers the local `repo/` directory when it exists. Otherwise it
resolves artifacts from:

```text
https://mozillafiredoge.github.io/KineticAssembly/maven
```

The example code intentionally imports only `KineticAssembly`, `mechanics.*`,
and the small public value types from `api`.

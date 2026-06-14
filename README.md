<div align="center">

# KineticAssembly
![Preview](docs/assets/preview.gif)

**PhysX-powered server mechanics substrate for NeoForge mods.**

[![Build](https://img.shields.io/github/actions/workflow/status/MozillaFiredoge/KineticAssembly/.github/workflows/build.yml?style=flat-square&branch=main&label=build)](https://github.com/MozillaFiredoge/KineticAssembly/actions)
[![License](https://img.shields.io/badge/license-MIT-green?style=flat-square)](https://opensource.org/licenses/MIT)
[![API Version](https://img.shields.io/badge/version-0.1.0--alpha.1-blue?style=flat-square)](https://github.com/MozillaFiredoge/KineticAssembly/packages)
[![1.21.1](https://img.shields.io/badge/NeoForge-1.21.1-orange?style=flat-square)](https://neoforged.net/)
[![1.21.11](https://img.shields.io/badge/NeoForge-1.21.11-orange?style=flat-square)](https://neoforged.net/)

</div>

---
### Development
```gradle
repositories {
    maven { url = uri("https://mozillafiredoge.github.io/KineticAssembly/maven") }
}

dependencies {
    compileOnly "com.firedoge.kineticassembly:kinetic_assembly-api-1.21.1:0.1.0-alpha.1"
    runtimeOnly "com.firedoge.kineticassembly:kinetic_assembly-neoforge-1.21.1:0.1.0-alpha.1"
}
```

### Quick API Example
```java
MechanicsWorld world = KineticAssembly.api().world(serverLevel);

MechanicsResult<MechanicsAssemblySnapshot> result = world.assembleBox(
    firstBlock,
    secondBlock,
    MechanicsAssemblyOptions.owned(MechanicsOwner.of("my_mod", "airframe"))
);

if (result.success()) {
    MechanicsBodySnapshot body = result.value().orElseThrow().body();
    world.applyForce(body.id(), new PhysicsVector(0, 25, 0));
}
```

> Full API reference: [docs/mechanics-api.md](docs/mechanics-api.md) ·  
> Example consumer mod: [examples/neoforge-consumer](examples/neoforge-consumer)

---

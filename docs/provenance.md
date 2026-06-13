# Provenance And Clean-Room Policy

This repository is intended to contain independently developed KineticAssembly code.

## Allowed Inputs

- Author-owned design notes and implementation work.
- Minecraft, NeoForge, Gradle, CMake, Java, and PhysX public APIs and official
  documentation.
- Source code from dependencies only when their licenses are compatible with the
  intended project distribution and their notices are preserved.
- General domain knowledge about physics simulation, coordinate transforms,
  networking, rendering, persistence, and Minecraft modding.

## Disallowed Inputs

- Copying, translating, or mechanically adapting source code from projects whose
  licenses are incompatible with this project.
- Recreating implementation details from a file-by-file or symbol-by-symbol
  comparison against an incompatible project.
- Using migration tables, parity checklists, or behavior maps from an
  incompatible project as implementation instructions.
- Reusing proprietary or non-compatible data formats, constants, class names, or
  comments unless they are independently required by a public API.

## Assembly Work

Moving-block and assembly behavior must be specified from first principles in
KineticAssembly-owned design documents. Acceptable requirements describe the problem
domain directly, such as plot/local/world coordinate spaces, server-authoritative
physics poses, client interpolation, entity support state, chunk residency, and
persistence.

Implementation work should cite KineticAssembly design documents, official API
documentation, or compatible dependencies. It should not cite or target parity
with a non-compatible project.

## Review Checklist

Before publishing a clean initial history:

- Search for external project names and remove migration/parity language.
- Confirm no source comments cite incompatible projects as implementation
  sources.
- Confirm copied notices are present for compatible third-party code.
- Keep any uncertain implementation area isolated until it can be audited or
  rewritten from KineticAssembly-owned requirements.

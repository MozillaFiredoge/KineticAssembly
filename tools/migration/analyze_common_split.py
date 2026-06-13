#!/usr/bin/env python3
"""Inventory helper for a future common/neoforge/fabric source split.

The script is intentionally read-only with respect to source files. It scans the
current tree and writes a Markdown report under build/reports by default.
"""

from __future__ import annotations

import argparse
import re
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path


MIXIN_ANNOTATIONS = (
    "Accessor",
    "Inject",
    "Invoker",
    "ModifyArg",
    "ModifyArgs",
    "ModifyConstant",
    "ModifyExpressionValue",
    "ModifyReceiver",
    "ModifyReturnValue",
    "ModifyVariable",
    "Mixin",
    "Overwrite",
    "Redirect",
    "WrapOperation",
    "WrapWithCondition",
)

FRAGILE_MIXIN_ANNOTATIONS = (
    "ModifyArg",
    "ModifyArgs",
    "ModifyConstant",
    "ModifyExpressionValue",
    "ModifyReceiver",
    "ModifyReturnValue",
    "ModifyVariable",
    "Overwrite",
    "Redirect",
    "WrapOperation",
    "WrapWithCondition",
)

PLATFORM_PATTERNS = {
    "neoforge_api": (
        r"\bnet\.neoforged\.",
        r"\bNeoForge\b",
        r"\bIEventBus\b",
        r"\bModContainer\b",
        r"@Mod\b",
        r"@EventBusSubscriber\b",
        r"@SubscribeEvent\b",
    ),
    "fabric_api": (
        r"\bnet\.fabricmc\.",
    ),
}

CLIENT_HINT_PATTERNS = (
    r"\bnet\.minecraft\.client\.",
    r"\bcom\.mojang\.blaze3d\.",
    r"\bLocalPlayer\b",
    r"\bMinecraft\b",
    r"\bRenderSystem\b",
    r"\bVertexConsumer\b",
    r"\bRenderLevelStageEvent\b",
    r"\bRegisterClientCommandsEvent\b",
)

FACADE_PATTERNS = {
    "loader_entrypoint": (
        r"@Mod\b",
        r"@EventBusSubscriber\b",
        r"\bIEventBus\b",
        r"\bModContainer\b",
        r"\bFMLCommonSetupEvent\b",
        r"\bFMLClientSetupEvent\b",
        r"\bNeoForge\.EVENT_BUS\b",
    ),
    "events": (
        r"@SubscribeEvent\b",
        r"\bPlayerEvent\b",
        r"\bBlockEvent\b",
        r"\bChunkEvent\b",
        r"\bLevelEvent\b",
        r"\bServerStartingEvent\b",
        r"\bServerStoppingEvent\b",
        r"\bServerStoppedEvent\b",
        r"\bServerTickEvent\b",
    ),
    "commands": (
        r"\bRegisterCommandsEvent\b",
        r"\bRegisterClientCommandsEvent\b",
    ),
    "networking": (
        r"\bRegisterPayloadHandlersEvent\b",
        r"\bPayloadRegistrar\b",
        r"\bIPayloadContext\b",
    ),
    "config": (
        r"\bModConfigSpec\b",
        r"\bModConfig\b",
    ),
    "paths": (
        r"\bFMLPaths\b",
    ),
    "io_flush": (
        r"\bIOUtilities\b",
    ),
    "mod_lookup": (
        r"\bModList\b",
    ),
    "loader_hooks": (
        r"\bCommonHooks\b",
    ),
    "client_render": (
        r"\bRenderLevelStageEvent\b",
        r"\bRenderHighlightEvent\b",
        r"\bModelData\b",
        r"\bIConfigScreenFactory\b",
        r"\bConfigurationScreen\b",
    ),
}

RESOURCE_PLATFORM_HINTS = {
    "neoforge_metadata": (
        r"(^|/)META-INF/neoforge\.mods\.toml$",
        r"(^|/)META-INF/accesstransformer\.cfg$",
    ),
    "mixin_config": (
        r"\.mixins\.json$",
    ),
    "native_packaging": (
        r"(^|/)native/",
    ),
}

JAVA_SOURCE_ROOTS = (
    ("common", "src/common/java"),
    ("neoforge", "src/neoforge/java"),
    ("fabric", "src/fabric/java"),
    ("main", "src/main/java"),
    ("test", "src/test/java"),
)

RESOURCE_ROOTS = (
    "src/common/resources",
    "src/neoforge/resources",
    "src/neoforge/templates",
    "src/fabric/resources",
    "src/fabric/templates",
    "src/main/resources",
    "src/main/templates",
)


@dataclass(frozen=True)
class JavaFileInfo:
    path: Path
    rel_path: str
    source_root: str
    top_bucket: str
    package_name: str
    line_count: int
    is_mixin: bool
    is_client_hint: bool
    platform_hits: tuple[str, ...]
    facade_hits: tuple[str, ...]
    annotation_counts: tuple[tuple[str, int], ...]
    fragile_annotation_count: int

    @property
    def category(self) -> str:
        if self.source_root in ("neoforge", "fabric"):
            return "platform-bound"
        if self.platform_hits or self.facade_hits or self.top_bucket == "platform":
            if self.top_bucket == "platform" and not self.platform_hits and not self.facade_hits:
                return "common-platform-facade"
            return "platform-bound"
        if self.is_mixin:
            return "mixin-candidate"
        if self.is_client_hint or self.top_bucket == "render":
            return "client-common-candidate"
        return "common-candidate"


@dataclass(frozen=True)
class ResourceInfo:
    path: Path
    rel_path: str
    hints: tuple[str, ...]


def repo_root_from_script() -> Path:
    return Path(__file__).resolve().parents[2]


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="replace")


def rel_to_repo(path: Path, repo_root: Path) -> str:
    return path.relative_to(repo_root).as_posix()


def java_source_root(path: Path, repo_root: Path) -> str:
    for name, rel_root in JAVA_SOURCE_ROOTS:
        root = repo_root / rel_root
        try:
            path.relative_to(root)
            return name
        except ValueError:
            continue
    return "unknown"


def package_name(text: str) -> str:
    match = re.search(r"^\s*package\s+([a-zA-Z0-9_.]+)\s*;", text, re.MULTILINE)
    return match.group(1) if match else ""


def top_bucket(path: Path, repo_root: Path) -> str:
    source_root = java_source_root(path, repo_root)
    rel_root = next(
        (rel_root for name, rel_root in JAVA_SOURCE_ROOTS if name == source_root),
        None,
    )
    if rel_root is None:
        return "unknown"
    root = repo_root / rel_root
    try:
        rel = path.relative_to(root)
    except ValueError:
        return "unknown"

    package_root = Path("com/firedoge/kineticassembly")
    try:
        inner = rel.relative_to(package_root)
    except ValueError:
        return rel.parts[0] if rel.parts else "unknown"

    if len(inner.parts) == 1:
        return inner.parts[0]
    return inner.parts[0]


def pattern_hits(patterns: dict[str, tuple[str, ...]], text: str) -> tuple[str, ...]:
    hits: list[str] = []
    for name, regexes in patterns.items():
        if any(re.search(regex, text) for regex in regexes):
            hits.append(name)
    return tuple(hits)


def has_any(regexes: tuple[str, ...], text: str) -> bool:
    return any(re.search(regex, text) for regex in regexes)


def annotation_counts(text: str) -> tuple[tuple[str, int], ...]:
    counts: list[tuple[str, int]] = []
    for name in MIXIN_ANNOTATIONS:
        count = len(re.findall(rf"@{re.escape(name)}\b", text))
        if count:
            counts.append((name, count))
    return tuple(counts)


def analyze_java_file(path: Path, repo_root: Path) -> JavaFileInfo:
    text = read_text(path)
    rel_path = rel_to_repo(path, repo_root)
    annotations = annotation_counts(text)
    annotation_counter = dict(annotations)
    fragile_count = sum(annotation_counter.get(name, 0) for name in FRAGILE_MIXIN_ANNOTATIONS)
    package = package_name(text)
    source_root = java_source_root(path, repo_root)
    bucket = top_bucket(path, repo_root)
    platform_hits = pattern_hits(PLATFORM_PATTERNS, text)
    facade_hits = pattern_hits(FACADE_PATTERNS, text)
    is_mixin = bucket == "mixin" or "@Mixin" in text or "org.spongepowered.asm.mixin" in text
    is_client_hint = has_any(CLIENT_HINT_PATTERNS, text)
    return JavaFileInfo(
        path=path,
        rel_path=rel_path,
        source_root=source_root,
        top_bucket=bucket,
        package_name=package,
        line_count=len(text.splitlines()),
        is_mixin=is_mixin,
        is_client_hint=is_client_hint,
        platform_hits=platform_hits,
        facade_hits=facade_hits,
        annotation_counts=annotations,
        fragile_annotation_count=fragile_count,
    )


def analyze_resource_file(path: Path, repo_root: Path) -> ResourceInfo:
    rel_path = rel_to_repo(path, repo_root)
    hints: list[str] = []
    for name, regexes in RESOURCE_PLATFORM_HINTS.items():
        if any(re.search(regex, rel_path) for regex in regexes):
            hints.append(name)
    return ResourceInfo(path=path, rel_path=rel_path, hints=tuple(hints))


def java_files(repo_root: Path) -> list[Path]:
    files: list[Path] = []
    for _, rel_root in JAVA_SOURCE_ROOTS:
        root = repo_root / rel_root
        if root.exists():
            files.extend(sorted(root.rglob("*.java")))
    return files


def resource_files(repo_root: Path) -> list[Path]:
    files: list[Path] = []
    for rel_root in RESOURCE_ROOTS:
        root = repo_root / rel_root
        if root.exists():
            files.extend(sorted(path for path in root.rglob("*") if path.is_file()))
    return files


def markdown_table(headers: tuple[str, ...], rows: list[tuple[object, ...]]) -> str:
    if not rows:
        return "_None._\n"

    lines = [
        "| " + " | ".join(headers) + " |",
        "| " + " | ".join("---" for _ in headers) + " |",
    ]
    for row in rows:
        lines.append("| " + " | ".join(str(cell) for cell in row) + " |")
    return "\n".join(lines) + "\n"


def format_hits(values: tuple[str, ...]) -> str:
    return ", ".join(values) if values else "-"


def format_annotations(values: tuple[tuple[str, int], ...]) -> str:
    if not values:
        return "-"
    return ", ".join(f"@{name}={count}" for name, count in values)


def build_report(java_infos: list[JavaFileInfo], resource_infos: list[ResourceInfo]) -> str:
    category_counts = Counter(info.category for info in java_infos)
    source_root_counts = Counter(info.source_root for info in java_infos)
    bucket_counts = Counter(info.top_bucket for info in java_infos)
    platform_bound = [info for info in java_infos if info.category == "platform-bound"]
    mixins = [info for info in java_infos if info.is_mixin]
    fragile_mixins = [info for info in mixins if info.fragile_annotation_count]
    main_files = [info for info in java_infos if info.source_root == "main"]
    common_files = [info for info in java_infos if info.source_root == "common"]
    neoforge_files = [info for info in java_infos if info.source_root == "neoforge"]
    fabric_files = [info for info in java_infos if info.source_root == "fabric"]
    test_files = [info for info in java_infos if info.source_root == "test"]

    facade_to_files: dict[str, list[JavaFileInfo]] = defaultdict(list)
    for info in java_infos:
        for facade in info.facade_hits:
            facade_to_files[facade].append(info)

    bucket_rows: list[tuple[object, ...]] = []
    for bucket, count in sorted(bucket_counts.items()):
        bucket_infos = [info for info in java_infos if info.top_bucket == bucket]
        bucket_rows.append(
            (
                bucket,
                count,
                sum(1 for info in bucket_infos if info.category == "platform-bound"),
                sum(1 for info in bucket_infos if info.is_mixin),
                sum(1 for info in bucket_infos if info.is_client_hint),
            )
        )

    platform_rows = [
        (
            info.rel_path,
            format_hits(info.platform_hits),
            format_hits(info.facade_hits),
        )
        for info in sorted(platform_bound, key=lambda item: item.rel_path)
    ]

    facade_rows = [
        (
            facade,
            len(files),
            "<br>".join(info.rel_path for info in sorted(files, key=lambda item: item.rel_path)),
        )
        for facade, files in sorted(facade_to_files.items())
    ]

    fragile_rows = [
        (
            info.rel_path,
            info.fragile_annotation_count,
            format_annotations(
                tuple(
                    (name, count)
                    for name, count in info.annotation_counts
                    if name in FRAGILE_MIXIN_ANNOTATIONS
                )
            ),
        )
        for info in sorted(fragile_mixins, key=lambda item: (-item.fragile_annotation_count, item.rel_path))
    ]

    resource_rows = [
        (
            info.rel_path,
            format_hits(info.hints),
        )
        for info in resource_infos
    ]

    report: list[str] = []
    report.append("# Common Split Inventory\n")
    report.append(
        "This report is generated by `tools/migration/analyze_common_split.py`. "
        "It is an inventory only; it does not move or rewrite source files.\n"
    )

    report.append("## Summary\n")
    summary_rows = [
        ("common Java files", len(common_files)),
        ("neoforge Java files", len(neoforge_files)),
        ("fabric Java files", len(fabric_files)),
        ("main Java files", len(main_files)),
        ("test Java files", len(test_files)),
        ("all Java files", len(java_infos)),
        ("common candidates", category_counts["common-candidate"]),
        ("common platform facades", category_counts["common-platform-facade"]),
        ("client common candidates", category_counts["client-common-candidate"]),
        ("mixin candidates", category_counts["mixin-candidate"]),
        ("platform-bound Java files", category_counts["platform-bound"]),
        ("mixin files", len(mixins)),
        ("fragile mixin files", len(fragile_mixins)),
        ("resource/template files", len(resource_infos)),
    ]
    report.append(markdown_table(("Metric", "Count"), summary_rows))

    report.append("## Java Buckets\n")
    report.append(
        markdown_table(
            ("Bucket", "Files", "Platform-bound", "Mixin", "Client hints"),
            bucket_rows,
        )
    )

    report.append("## Direct Platform Bindings\n")
    report.append(
        "Files listed here import or reference loader-specific APIs, or match a known "
        "platform facade seam.\n"
    )
    report.append(markdown_table(("File", "Platform hits", "Facade seams"), platform_rows))

    report.append("## Suggested Facade Seams\n")
    report.append(markdown_table(("Facade", "Files", "Locations"), facade_rows))

    report.append("## Mixin Risk Inventory\n")
    report.append(
        "Fragile annotations include redirects, overwrites, modify injections, and "
        "MixinExtras wrappers. These should be validated per Minecraft version.\n"
    )
    mixin_summary_rows = [
        ("mixin files", len(mixins)),
        ("fragile mixin files", len(fragile_mixins)),
        (
            "fragile annotation sites",
            sum(info.fragile_annotation_count for info in fragile_mixins),
        ),
    ]
    report.append(markdown_table(("Metric", "Count"), mixin_summary_rows))
    report.append(markdown_table(("File", "Fragile sites", "Annotations"), fragile_rows))

    report.append("## Resources And Templates\n")
    report.append(markdown_table(("File", "Hints"), resource_rows))

    report.append("## Suggested First Moves\n")
    first_moves = [
        "Keep the script in inventory mode until the report matches expectations.",
        "Create common/neoforge source sets before moving files.",
        "Move pure API, backend, mechanics, nativebridge, and most physics classes first.",
        "Introduce small platform facades for config paths, IO flush, mod lookup, loader hooks, networking, commands, and render events.",
        "Treat mixins as common only after each target version compiles and launches.",
        "Add Fabric only after NeoForge 1.21.1 and NeoForge 1.21.11 share the same common source set.",
    ]
    report.extend(f"- {line}\n" for line in first_moves)

    report.append("\n## Source Root Counts\n")
    report.append(markdown_table(("Source root", "Files"), sorted(source_root_counts.items())))

    return "\n".join(report)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Generate a common/neoforge/fabric split inventory report."
    )
    parser.add_argument(
        "--repo-root",
        type=Path,
        default=repo_root_from_script(),
        help="Repository root. Defaults to the script's containing repository.",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=None,
        help="Markdown report output path. Defaults to build/reports/common-split-report.md.",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    repo_root = args.repo_root.resolve()
    output = args.output
    if output is None:
        output = repo_root / "build/reports/common-split-report.md"
    elif not output.is_absolute():
        output = repo_root / output

    java_infos = [analyze_java_file(path, repo_root) for path in java_files(repo_root)]
    resource_infos = [
        analyze_resource_file(path, repo_root) for path in resource_files(repo_root)
    ]
    report = build_report(java_infos, resource_infos)

    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(report, encoding="utf-8")

    category_counts = Counter(info.category for info in java_infos)
    mixin_files = sum(1 for info in java_infos if info.is_mixin)
    fragile_sites = sum(info.fragile_annotation_count for info in java_infos)
    print(f"Wrote {output}")
    print(f"Java files: {len(java_infos)}")
    print(f"Common candidates: {category_counts['common-candidate']}")
    print(f"Client common candidates: {category_counts['client-common-candidate']}")
    print(f"Mixin candidates: {category_counts['mixin-candidate']}")
    print(f"Platform-bound files: {category_counts['platform-bound']}")
    print(f"Mixin files: {mixin_files}")
    print(f"Fragile mixin annotation sites: {fragile_sites}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

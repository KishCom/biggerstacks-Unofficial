# Bigger Stacks (Unofficial) — agent notes

NeoForge 1.21.1 mod that raises stack sizes past 64. Two patching mechanisms:

1. **Mixins** — `src/main/resources/biggerstacks.mixins.json`, gated by mod id in `portb.biggerstacks.TransformerEngine` (the mixin plugin, also registers the TransformerLib launch plugin).
2. **TransformerLib XML patches** — `src/main/resources/transformers/*.xml`, applied at class-load time by the TransformerLib launch plugin.

## Build environment (important)

- **Java is managed by ASDF.** Installed: `~/.asdf/installs/java/openjdk-{11.0.2,17.0.2,21.0.2}`. Plain `./gradlew` can fail with `No version is set for command java` in non-interactive shells. Fix:
  ```bash
  export JAVA_HOME=~/.asdf/installs/java/openjdk-21.0.2
  export PATH="$JAVA_HOME/bin:$PATH"
  ./gradlew build
  ```
  (Project requires Java 21; repo-root `.tool-versions` pins `openjdk-21.0.2`.)
- **Required sibling projects** (Gradle `includeBuild` in `settings.gradle`): `../SimpleLoggerWrapper`, `../BiggerStacksConfigLib`, `../BiggerStacksTransformerLib`. They must exist next to this repo; TransformerLib changes here are embedded into the jar automatically.
- `client-mappings.txt` / `server-mappings.txt` (mojmap) must exist in the repo root for the mixin annotation processor.

## sophisticatedcore compat (most fragile area)

- Compat mods are compile deps from Modrinth maven in `build.gradle`; keep them close to what users run (currently sophisticated-core 1.21.1-1.5.35.2473, backpacks 1.21.1-3.26.21.2249).
- `TransformerEngine.shouldApplyMixin` gates the compacting mixin to sophisticatedcore **>= 1.4** (API change); extend that pattern when a patch only works against a version range.
- `CompactingUpgradeWrapperMixin` injects into `compactSlot(handler, int)` (NOT `tryCompacting` — its `CompactingDefinition` param is package-private since 1.4.x) and reimplements the compacting definition lookup via public APIs + `UpgradeWrapperBaseAccessor`.
- **XML patcher semantics** (`ClassTarget.accept` in BiggerStacksTransformerLib): match by method name, optional exact `desc` filter; **throws on >1 match**, silently skips on 0 matches. If a target mod adds an overload, add a `desc` — e.g. `CookingLogic.canSmelt` needed `desc="(Lnet/minecraft/world/item/ItemStack;)Z"` for sophisticatedcore 1.5.x (added a `canSmelt(Recipe, Level)` overload).
- `replace64` replaces `BIPUSH 64` with the configured max stack size and is a **no-op if absent** (sophisticatedcore uses `99` + a `getMaxStackSize()` fallback, which is why big stacks still work there unpatched).

## Verifying against new mod versions (no game launch needed)

1. Download the jar: `curl -sL "https://api.modrinth.com/maven/maven/modrinth/<slug>/<version>/<slug>-<version>.jar" -o /tmp/x.jar`
2. `unzip` the relevant classes, inspect with `javap -p` / `javap -p -c` (use the full path to javap under the ASDF install).
3. Sources: `raw.githubusercontent.com/P3pp3rF1y/SophisticatedCore/1.21.x/...` (HEAD ≈ latest release); repo has no version tags, use the GitHub commits API per file to date changes.
4. Check every name-matched target in `src/main/resources/transformers/sophisticated_core.xml` and every `compat.sophisticatedcore` mixin against the new jar before shipping.

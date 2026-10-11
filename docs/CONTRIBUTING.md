Contributing to NeoForge
=====================

1) Keep patches to Minecraft classes together. If you need a lot of things done, you may either add to relevant forge classes or make a new class. Try not to spread out your patches across multiple disjoint lines, as this makes maintenance of your patches difficult.

2) TODO: Test Mods

3) Follow the code style of the class you're working in (braces on newlines & spaces instead of tabs in Forge classes, inline brackets in patches, etc).

## Workflow

1. Have preliminary discussions on Discord (`#neoforge-github`)
2. Fork the repository
3. Check out your fork
4. Fetch tags from the upstream repository by running `git remote add upstream https://github.com/neoforged/NeoForge.git` followed by `git fetch upstream --tags`
5. Make a branch
6. Run `gradlew setup` from the project root to decompile sources and apply current patches
7. Import project into your IDE (IntelliJ/Eclipse) or Reload Gradle Project
8. Modify the patched Minecraft sources in `projects/neoforge/src/main/java` as needed. The unmodified sources are available in `projects/base/src/main/java` for your reference. Do not modify these.
9. Test your changes
   - Run the game (Runs are available in the IDE)
     - Runs starting with `base -` run Vanilla without NeoForge or its patches.
     - Runs starting with `neoforge -` run NeoForge.
     - Runs starting with `tests -` run NeoForge along with the test mods in the `tests` project.
   - Run `gradlew :tests:runGameTestServer` or `tests - GameTestServer` from IDE
   - Run `gradlew :tests:runClient` or `tests - Client` from IDE
   - If possible, write an automated test under the tests project. See [NEOGAMETESTS.md](NEOGAMETESTS.md) for more info.
10. Run `gradlew genPatches` to generate patch-files from the patched sources
11. Run `gradlew applyAllFormatting` to automatically format sources
12. Check correct formatting with `gradlew checkFormatting`
13. Commit & Push
14. Make PR

## Porting

If you are interested in how NeoForge is ported to new Minecraft versions, see [the porting workflow][Porting].
Please note that currently  only maintainers can use all the needed tools.
Please do not open a porting PR without prior coordination.

Contributor License Agreement
=============================
- You grant NeoForged a license to use your code contributed to the primary codebase (everything **not** under patches) in NeoForge, under the LGPLv2.1 license.
- You assign copyright ownership of your contributions to the patches codebase (everything under patches) to NeoForged, where it will be licensed under the LGPLv2.1 license.

This is intended as a **legally binding copyright assignment** to the NeoForged project for contributions under the patches codebase. However you retain your copyright for all other contributions.

[Porting]: ../docs/PORTING.md

## Running the tests in this fork / 在本 fork 上跑测试

### NeoForge's own suites / 平台自己的套件

**中文**（CI 跑的就是这几条，见 `.github/workflows/test-prs.yml`）：
- `./gradlew :tests:runGameTestServer` —— NeoForge 自己的 gametest 全量；本 fork 当前 **195/195 全过** ✓，约 20 秒，**跑完自动退出** ✓；
- `./gradlew :tests:runClient` —— 客户端框架测试；当前 **12/12 全过** ✓。**它跑完会停在主菜单、不会自己退出** ✗，看日志里最后一条 `result=PASSED` 即可收尾（需要世界的测试在 GameTestServer 那条里，不在这里）；
- `./gradlew :tests:runUnitTests`、`./gradlew :neoforge:testProductionClient` / `:neoforge:testProductionServer` —— 单元测试与生产安装回归，CI 也跑 ✓。

**English** (these are the commands CI runs, see `.github/workflows/test-prs.yml`):
- `./gradlew :tests:runGameTestServer` — NeoForge's full gametest suite; currently **195/195** in this fork. Takes ~20 s and exits by itself.
- `./gradlew :tests:runClient` — the client-side framework tests; currently **12/12**. It **stops at the main menu and never exits** — read the last `result=PASSED` line and close it. Tests that need a world live in the GameTestServer run instead.
- `./gradlew :tests:runUnitTests`, `./gradlew :neoforge:testProductionClient` / `:neoforge:testProductionServer` — unit tests and the production-install regression, also run by CI.

### ★ Known trap: stale client-test state / 已知坑：客户端测试的残留状态

**中文**：`:tests:runClient` 若一启动就崩、日志里是
`IllegalStateException: Adding duplicate key 'ResourceKey[minecraft:test_environment / neotests:tests/…]' to registry`，
那是 **`tests/runs/client/tests/` 里上一次跑测留下的状态** ✗ ⇒ **删掉这个目录再跑** ✓（`saves/` 与它无关 ✓）。
排除桥的依据（已实测两次）：`./gradlew :tests:runClient -Pneofobric.disabled=true` 关桥跑纯 NeoForge **会复现同一崩溃** ⇒ 不是桥 ✓；只把 `tests/` 隔离、`saves/` 放回 **则不崩** ⇒ 就是它 ✓。

**English**: if `:tests:runClient` dies right at startup with
`IllegalStateException: Adding duplicate key 'ResourceKey[minecraft:test_environment / neotests:tests/…]' to registry`,
the cause is the **leftover state in `tests/runs/client/tests/`** from an earlier (crashed or half-finished) run — delete that directory and start again (`saves/` is unrelated).
How the bridge was ruled out: `-Pneofobric.disabled=true` (bridge off, plain NeoForge) reproduces the same crash, while quarantining only `tests/` and keeping `saves/` does not.

### The Fabric side / Fabric 那一侧（本 fork 的意义所在）

**中文**：把 Fabric API 的测试包放进对应运行目录的 `mods/`，再带系统属性启动：
- 服务端：`projects/neoforge/run/server/mods/` ← `fabric-api`、`fabric-api-testmods-filtered`、`fabric-gametest-api-v1`；
  启动：`JDK_JAVA_OPTIONS="-Dfabric-api.gametest -Dfabric-api.gametest.report-file=<可写路径>" ./gradlew :neoforge:runServer`
  ⇒ 当前 **`All 114 required tests passed`** ✓（跑完自动停服 ✓）；
- 客户端：`projects/neoforge/run/client/mods/` 再加 `fabric-client-gametest-api-v1`，同样的 `-Dfabric-api.gametest` 跑 `:neoforge:runClient`（跑完停在主菜单 ✓）。
- 这些包来自本仓库的 `fabric/` 子树（Fabric API 源码，含 testmods）；`…-testmods-filtered` 是其上筛掉跑不了用例后的包 ✓。
- 一条**固有差异**：`fabric-tag-api-v1-testmod` 里有一条**用例自身互斥**的断言（同一个数据包要求同时"在/不在"），任何加载器上都过不了 —— 见 [README](../README.md) 的"已知限制" ✓。

**English**: drop Fabric API's test packs into the run directory's `mods/` and pass the system property:
- Server: `projects/neoforge/run/server/mods/` gets `fabric-api`, `fabric-api-testmods-filtered` and `fabric-gametest-api-v1`; run `JDK_JAVA_OPTIONS="-Dfabric-api.gametest -Dfabric-api.gametest.report-file=<writable path>" ./gradlew :neoforge:runServer` — currently **`All 114 required tests passed`**, and the server stops by itself.
- Client: the same plus `fabric-client-gametest-api-v1` in `projects/neoforge/run/client/mods/`, and `:neoforge:runClient` with the same `-Dfabric-api.gametest` (it stops at the main menu).
- The packs come from the `fabric/` subtree (Fabric API sources including testmods); `…-testmods-filtered` is that with the unrunnable cases filtered out.
- One inherent difference: `fabric-tag-api-v1-testmod` contains an assertion that contradicts itself (the same data pack must both be and not be present), which cannot pass on any loader — see the README's known limitations.

### Diagnosis switches / 诊断开关

**中文 / English**: `-Pneofobric.disabled=true` runs NeoForge with the Fabric bridge switched off (use it for A/B); the single-stage switches such as `-Pneofobric.noPatchCache=true` are listed one by one in the run configuration inside `projects/neoforge/build.gradle`.

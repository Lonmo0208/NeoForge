# NeoFobric

> **一个拿来自娱自乐的加载器：是一坨"史" —— 但我们就是要它比 Forbric 那坨史好。**
> A loaders experiment built purely for fun: a pile of garbage — but it has to be a *better* pile of garbage than Forbric's.

对照物 / For comparison: <https://github.com/Ray-T-r/Minecraft-Forbric-mod-loader>

---

## 这是什么 / What this is

**中文**：**在 NeoForge 加载器里搭了一座桥，让 Fabric 能一起跑。** 底座是 NeoForge（本仓库是 NeoForge 26.2 的 fork）：FML 依旧是宿主 —— 它启动、它拥有游戏本体、它管模块层和它自己的模组。桥负责把 **fabric-loader 本体**（由本仓库的 `fabric-loader` 子模块现场编译）拉进同一个进程，Fabric 模组按 Fabric 自己那套规矩加载：`fabric.mod.json`、入口点、嵌套 jar、access widener / class tweaker、Fabric mixin 配置，一样不少。这里**没有 Forge** —— 已经"似"掉的那个旧 Forge 不参与任何环节。**不装任何 Fabric 模组时，它就是完完全全的 NeoForge。** 装模组时**优先选 NeoForge 版**，只有 NeoForge 版不存在时再用 Fabric 版：桥接的兼容性是"能跑"级别的，不是"保证"级别的。

**English**: **A bridge built inside the NeoForge loader so Fabric can run alongside it.** The base is NeoForge (this repository is a fork of NeoForge 26.2): FML stays the host — it boots, it owns the game, the module layer and its own mods. The bridge pulls the **real fabric-loader** (compiled from this repository's `fabric-loader` submodule) into the same process, and Fabric mods load the Fabric way: `fabric.mod.json`, entrypoints, nested jars, access wideners / class tweakers, Fabric mixin configs. **There is no Forge here** — the dead old Forge is not part of any step. **With no Fabric mods installed it is a plain NeoForge loader.** When picking mods, **prefer the NeoForge build**; use a Fabric build only when there is no NeoForge one. Compatibility here means "it runs", not "it is guaranteed".

## 现状：请当作「能玩但别当真」的测试版 / Current state: a playable-but-don't-trust-it beta

**中文**：bug 可能还有很多，兼容性也可能还不够好，**甚至无法和 FFAPI（Forgified Fabric API）一起用**（见下）。**服务端可以用** ✓ —— 进程内专服与生产安装出来的专服都实测过：能起到 `Done`，模组正常加载。**其他设备理论都能装**：凡是能装 NeoForge 26.2 + Fabric 的环境（Windows / Linux / macOS，各种启动器）理论上都可以 —— **但我们只在开发机上验证过，没有做多设备验证**，请你自己试。**机械动力（Create）的部分特性可以正常使用**，但肯定还有我们不知道的 bug；**其他 Fabric 模组同理**：能进游戏 ≠ 每个功能都对；**NeoForge 模组也可能被连锁效应波及**（桥改的是两边共用的那一层）。

**English**: There are probably still plenty of bugs, compatibility may still be rough, and it **cannot be installed together with FFAPI (Forgified Fabric API)** (see below). **The server side works** ✓ — both an in-process dedicated server and a production-installed one reach `Done` with mods loaded. **Other machines should work in theory** — anything that can run NeoForge 26.2 + Fabric (Windows / Linux / macOS, any launcher) ought to be fine, **but we only ever verified on the development machine; there is no multi-device testing**, so please try it yourself. **Parts of Create work correctly**, but there are certainly bugs we do not know about; **the same goes for other Fabric mods**: launching is not the same as every feature working; **NeoForge mods can be hit by knock-on effects too** (the bridge touches the layer both loaders share).

### 我们跑了什么测试 / What we tested

**中文**：**Fabric API 仓库自带的测试流程**跑完了：服务端套件 **`All 114 required tests passed`** ✓ —— 上游那套 gametest 全量 114 条必测项全绿，这是本仓库最硬的一条证据。客户端套件同样跑到收尾，**唯一没过的是上游测试用例自己内部互斥**（`fabric-tag-api-v1-testmod`：同一个数据包既要给 `sword_efficient` 加 `dirt`、又要移除另一个标签的内容，两条断言要求同一个包"在/不在"，本环境无解）—— 记档的已知差异，不算桥的缺口。桥内置自检：事件清单在客户端 **11/11 wired** ✓，专服没有玩家也能打出报表 ✓。生产安装链：用本仓库构建的安装器装出来的**专服**能起到 `Done`、**客户端**能进游戏。

**English**: We ran **Fabric API's own test flow** end to end: the server suite reports **`All 114 required tests passed`** ✓ — every one of upstream's required gametests is green, and that is the strongest evidence this repository has. The client suite also runs to completion; **the only failure is an upstream test case that contradicts itself** (`fabric-tag-api-v1-testmod`: one data pack both adds `dirt` to `sword_efficient` and removes content from another tag, so two assertions demand the same pack be present and absent at once — unsolvable here). That is a documented known difference, not a gap in the bridge. The bridge's built-in self-check reports its event inventory as **11/11 wired** on the client ✓, and a dedicated server prints the report even with no player ✓. Production install chain: a dedicated server installed with the installer built from this repository reaches `Done`, and the client reaches the game.

### 实测能跑的模组（39 个）/ The 39 mods that have actually been run

```
EchoChest-v26.2.0-mc26.2.x-NeoForge          FarmersDelight-26.2-3.6.28+refabricated
ForgeConfigAPIPort-v26.2.1-mc26.2.x-Fabric   FrozenLib-3.0.3-mc26.2-fabric
IllagerInvasion-v26.2.0-mc26.2.x-NeoForge    Jade-mc26.2-NeoForge-26.2.10
MutantMonsters-v26.2.2-mc26.2.x-NeoForge     NetherChested-v26.2.0-mc26.2.x-NeoForge
PuzzlesLib-v26.2.4-mc26.2.x-NeoForge         RebornCore-6.1.0
TechReborn-6.1.1                             WilderWild-4.3-mc26.2-fabric
appliedenergistics2-26.2.1-alpha             bagus_lib-26.2-27.7.0
bclib-26.201.2                               better-end-26.201.2
biolith-neoforge-3.7.0-beta.1                byepregen-neoforge-26.2-1.1.2.5
create-fly-26.2-rc-2-6.0.9-1                 create_ultimate_factory-2.2.4-fabric-26.2
delightlib-26.06.23-26.2-fabric              earthmobsmod-26.2-30.5.0
exdeorum-5.1                                 fabric-api-0.161.0+26.2
framework-neoforge-26.2-0.13.26              goldenhopper-neoforge-26.2-1.7.0
guideme-26.2.2-alpha                         iris-neoforge-1.11.4+mc26.2
jei-26.2-neoforge-30.39.0.233                mezz_config-26.2-neoforge-0.6.8-standalone
modmenu-20.0.3                               moredelight-26.06.23-26.2-fabric
sodium-neoforge-0.9.2+mc26.2                 sophisticatedbackpacks-26.2-3.26.9.2191
sophisticatedcore-26.2-1.5.5.2379            sophisticatedstorage-26.2-1.5.117.2157
toms_storage_fabric-26.2-2.11.3              twilightforest.re26-26.2-4.8.4207-hotfix-fabric
worldweaver-26.201.2
```

> **中文**：这一批能"进游戏并正常玩一阵" —— 不代表每个功能都对。
> **English**: This set launches the game and plays for a while — it does **not** mean every feature is correct.

### 目前相对稳一点的方向 / The relatively stable corner

**中文**：**末地相关内容**（bclib / Better End / WorldWeaver 这一系）目前理论上是兼容更好的：**我们只验证到"能进末地、地形（可能）生成正常"** —— 功能方块、结构、生物等**没有测试**。越跨界的内容越可能出问题（跨加载器互相依赖、双方都要接管同一段原版逻辑的地方）。

**English**: **End-dimension content** (the bclib / Better End / WorldWeaver family) is currently the better bet in theory: **we only verified that you can enter the End and that terrain (probably) generates** — functional blocks, structures and mobs there are **untested**. The more a feature crosses loaders (mods depending on each other across the border, or both sides wanting to own the same piece of vanilla logic), the more likely it breaks.

## 已知不兼容 / Known incompatibilities

**中文**：**FFAPI（Forgified Fabric API）不能同装** —— 它是 NeoForge 模组，和桥自带的那份真 Fabric API 携带**同一批包**（`net.fabricmc.fabric.*`）⇒ 同一个模块层里两个模块导出同名包 ⇒ JDK 直接拒绝启动，只能二选一。**Sinytra Connector 不能同装** —— 它和桥抢同一件差事（把 Fabric 模组带进 NeoForge）。另外：**"一起跑" ≠ "互相看得见"** —— 两边模组并排运行没问题，但"Fabric 模组直接依赖某个 NeoForge 模组的 API"（反之亦然）**不保证**。还有上游测试里那条**内部互斥**的 tag 断言（见上）。

**English**: **FFAPI (Forgified Fabric API) cannot be installed alongside this** — it is a NeoForge mod carrying the **same packages** (`net.fabricmc.fabric.*`) as the real Fabric API the bridge bundles, so two modules in one layer export the same package and the JDK refuses to boot. Pick one. **Sinytra Connector cannot be installed alongside this either** — it fights for the same job (bringing Fabric mods into NeoForge). Also note: **running side by side is not the same as seeing each other** — the two mod sets run fine in parallel, but a Fabric mod depending directly on a NeoForge mod's API (or the reverse) is **not guaranteed**. And there is that **self-contradicting** upstream tag assertion mentioned above.

## 怎么装 / 怎么构建 — Install & Build

**中文**：**从源码构建**（安装器不再预构建进仓库）：

```bash
git clone --recurse-submodules <this repo>
cd NeoFobric
./gradlew :neoforge:installerJar      # 产物在 projects/neoforge/build/libs/
```

普通克隆也行：`settings.gradle` 会在缺失时自动把 `fabric-loader` 子模块拉下来（钉在一个固定提交上）。**安装**：① 先按 NeoForge 官方方式装好 **NeoForge 26.2**；② 用上面构建出来的安装器安装；③ 把 **Fabric 模组和 NeoForge 模组一起**丢进 `mods/`。**卸载 / 回退**：把本加载器从安装配置里去掉，或用 NeoForge 自己的安装覆盖一遍即可 —— 它没有往游戏本体写不可逆的东西。

**English**: **Build from source** (no prebuilt installer is kept in the repository):

```bash
git clone --recurse-submodules <this repo>
cd NeoFobric
./gradlew :neoforge:installerJar      # artifact lands in projects/neoforge/build/libs/
```

A plain clone works too: `settings.gradle` fetches the `fabric-loader` submodule on demand (pinned to a fixed commit). **Install**: (1) install **NeoForge 26.2** the official way; (2) run the installer you just built; (3) drop **Fabric and NeoForge mods together** into `mods/`. **Uninstall / roll back**: remove this loader from the install, or just reinstall NeoForge over it — nothing irreversible is written into the game.

## 许可与出处 / Licensing & attribution

**中文**：见 [README-LICENSE.md](README-LICENSE.md)：**我们自己写的部分**沿用 NeoForge 的 `LGPL-2.1-only`；内嵌的 Fabric API 模块与 fabric-loader 是 `Apache-2.0`（各自保留声明）；我们**没有复制** FFAPI（Apache-2.0）或 Sinytra Connector（MIT）的代码，只在注释里引用它们公开的做法，并已在许可文件里致谢。

**English**: See [README-LICENSE.md](README-LICENSE.md): **our own code** follows NeoForge's `LGPL-2.1-only`; the bundled Fabric API modules and fabric-loader are `Apache-2.0` (each keeps its own notices); we did **not copy** any code from FFAPI (Apache-2.0) or Sinytra Connector (MIT) — only their publicly documented approaches are referenced in comments — and both are credited in the licence file.

---

## 关于这个项目 / About this project

**中文**：**这是个 AI 项目**（人定方向、验结果；代码、文档、排查过程大量由 AI 完成），所以它是一坨"史"，我们自己也这么叫它。但我们对这一坨有要求：**它得比 Forbric 那坨好** —— 好在它用的是**真正的 fabric-loader**（不是重新实现一遍 Fabric API 的语义）、**不丢掉 NeoForge**（底座还在、NeoForge 模组照常跑、不装 Fabric 模组时它就是普通 NeoForge）、并且把上游 **Fabric API 自己的测试流程**跑完了（114/114）还把结果原样写在这里。想要"正经"的 NeoForge ↔ Fabric 互通方案，请去看 **Sinytra Connector + FFAPI**；我们这条纯属自娱自乐的一条岔路。

**English**: **This is an AI project** (humans set the direction and check results; much of the code, documentation and debugging is done by AI), which is why we call it a pile of garbage ourselves. But this pile has standards: **it has to be better than Forbric's** — it uses the **real fabric-loader** (not a re-implementation of Fabric API semantics), it **does not throw NeoForge away** (the base stays, NeoForge mods keep working, and with no Fabric mods it is an ordinary NeoForge), and it ran **Fabric API's own test flow** to 114/114 and wrote the result down instead of hand-waving. If you want a "serious" NeoForge ↔ Fabric interop, go look at **Sinytra Connector + FFAPI**; this one is a for-fun detour.

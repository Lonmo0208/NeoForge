# NeoFobric

> **一个拿来自娱自乐的加载器：是一坨"史" —— 但我们就是要它比 Forbric 那坨史好。**

> 对照物：<https://github.com/Ray-T-r/Minecraft-Forbric-mod-loader>

---

## 这是什么

**在 NeoForge 加载器里搭了一座桥，让 Fabric 能一起跑。**

- **底座是 NeoForge**：这是一个 NeoForge 26.2 的 fork。FML 依旧是宿主 —— 它启动、它拥有游戏本体、它管模块层和它自己的模组。
- **用的「真正的 Fabric 加载器」**：桥不是"模仿 Fabric API 的实现"，也不是把 Fabric 模组翻译成 NeoForge 模组。跑起来的是一整个 **fabric-loader 本体**（由本仓库的 `fabric-loader` 子模块现场编译），Fabric 模组按 Fabric 自己那套规矩加载：`fabric.mod.json`、入口点、嵌套 jar、access widener / class tweaker、Fabric mixin 配置，一样不少。
- **没有 Forge**：已经"似"掉的那个旧 Forge 不参与任何环节，这里只有 NeoForge + Fabric。
- **不装任何 Fabric 模组时，它就是完完全全的 NeoForge 加载器** —— 一个 Fabric 模组都没有时，桥不改变任何东西。
- **装模组优先级**：**优先选 NeoForge 版**；只有 NeoForge 版不存在时再用 Fabric 版。桥接的兼容性是"能跑"级别的，不是"保证"级别的。

## 现状：请当作「能玩但别当真」的测试版

- **bug 可能还有很多**，兼容性也可能还不够好；
- **甚至无法和 FFAPI（Forgified Fabric API）一起用**（它和桥自带的那份真 Fabric API 会争同一批包名，详见下面「已知不兼容」）；
- **服务端可以用** ✓ —— 进程内专服与生产安装出来的专服都实测过：能起到 `Done`，模组正常加载；
- **其他设备理论都能装**：凡是能装 NeoForge 26.2 + Fabric 的环境（Windows / Linux / macOS，各种启动器）理论上都可以 —— **但我们只在开发机上验证过，没有做多设备验证**，请你自己试；出问题欢迎报。
- **机械动力（Create）的部分特性可以正常使用**（传送带、动部件渲染等我们逐项调过），但**肯定还有我们不知道的 bug**；
- **其他 Fabric 模组同理**：能进游戏 ≠ 每个功能都对；
- **NeoForge 模组也可能被连锁效应波及**（桥改的是两边共用的那一层）。

### 我们跑了什么测试

- **Fabric API 仓库自带的测试流程**：服务端套件 **`All 114 required tests passed`** ✓ —— 上游那套 gametest 全量 114 条必测项全绿，这是本仓库最硬的一条证据。
- **客户端套件**同样跑到测试阶段收尾：**唯一一条没过的是上游测试用例自己内部互斥**（`fabric-tag-api-v1-testmod`：同一个数据包既要给 `sword_efficient` 加 `dirt`、又要移除另一个标签的内容，两条断言要求同一个包"在/不在"，本环境无解）—— 这是记档的已知差异，不算桥的缺口。
- **桥内置自检**：事件清单在客户端 **11/11 wired** ✓；专服即使没有玩家也能打出报表 ✓。
- **生产安装链**：用本仓库构建的安装器装出来的**专服**能起到 `Done`、**客户端**能进游戏。

### 实测能跑起来的模组（39 个）

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

> 这一批能"进游戏并正常玩一阵" —— 不代表每个功能都对。

### 目前相对稳一点的方向

- **末地相关内容**（bclib / Better End / WorldWeaver 这一系）目前理论上是兼容更好的：**我们只验证到"能进末地、地形（可能）生成正常"** —— 功能方块、结构、生物等**没有测试**。
- 越跨界的内容越可能出问题（跨加载器互相依赖、双方都要接管同一段原版逻辑的地方）。

## 已知不兼容 / 边界

- **FFAPI（Forgified Fabric API）不能同装**：它是 NeoForge 模组，和桥自带的那份真 Fabric API 携带**同一批包**（`net.fabricmc.fabric.*`）⇒ 同一个模块层里两个模块导出同名包 ⇒ JDK 直接拒绝启动。两者只能选一个。
- **Sinytra Connector 不能同装**：它和桥抢同一件差事（把 Fabric 模组带进 NeoForge），会互相打架。
- **"一起跑" ≠ "互相看得见"**：桥让两边模组并排运行没问题，但"Fabric 模组直接依赖某个 NeoForge 模组的 API"（反之亦然）**不保证**。
- 上游测试里那条**内部互斥**的 tag 断言（见上）。

## 怎么装 / 怎么构建

**从源码构建**（推荐，安装器不再预构建进仓库）：

```bash
git clone --recurse-submodules <this repo>
cd NeoFobric
./gradlew :neoforge:installerJar      # 产物在 projects/neoforge/build/libs/
```

普通克隆也行：`settings.gradle` 会在缺失时自动把 `fabric-loader` 子模块拉下来（钉在一个固定提交上）。

**安装**：

1. 先按 NeoForge 官方方式装好 **NeoForge 26.2**；
2. 用上面构建出来的安装器安装（它会把这套加载器装进 `libraries/`，Fabric 侧的东西由它带）；
3. 把 **Fabric 模组和 NeoForge 模组一起**丢进 `mods/` 即可 —— 就这么用。

**卸载 / 回退**：把本加载器从安装配置里去掉，或直接用 NeoForge 自己的安装重新覆盖一遍即可；它没有往游戏本体里写不可逆的东西。

## 许可与出处

见 [README-LICENSE.md](README-LICENSE.md)：**我们自己写的部分**沿用 NeoForge 的 `LGPL-2.1-only`；内嵌的 Fabric API 模块与 fabric-loader 是 `Apache-2.0`（各自保留声明）；我们**没有复制** FFAPI（Apache-2.0）或 Sinytra Connector（MIT）的代码，只在注释里引用它们公开的做法，并已在许可文件里致谢。

---

## 关于这个项目

**这是个 AI 项目。** 人定方向、验结果；代码、文档、排查过程大量由 AI 完成 —— 所以它是一坨"史"，我们自己也这么叫它。

但我们对这一坨有要求：**它得比 Forbric 那坨好。** 好在哪：

- 它用的是**真正的 fabric-loader**，不是"重新实现一遍 Fabric API 的语义"；
- 它**不丢掉 NeoForge** —— 底座还在、NeoForge 模组照常跑、不装 Fabric 模组时它就是个普通的 NeoForge；
- 它把上游 **Fabric API 自己的测试流程跑完了**（114/114），并且把测试结果原样写在这里，而不是含糊其辞。

想要"正经"的 NeoForge ↔ Fabric 互通方案，请去看 **Sinytra Connector + FFAPI**；我们这条纯属自娱自乐的一条岔路。

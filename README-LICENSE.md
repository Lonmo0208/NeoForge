# 许可与出处 / Licensing & Attribution

**这个仓库是什么、基于什么 / What this repository is, and what it is based on**

**中文**：本仓库是 **NeoForge 26.2** 的 fork（上游：<https://github.com/neoforged/NeoForge>），在它之上加了一层 **NeoFobric 桥**：把**真正的 fabric-loader + Fabric API** 带进 NeoForge 的进程里，让两个加载器的模组并排运行。桥本身的代码在 `projects/fabricbridge/`（另有少量平台侧补丁与钩子）。

**English**: This repository is a fork of **NeoForge 26.2** (<https://github.com/neoforged/NeoForge>) with a layer added on top: the **NeoFobric bridge**, which brings the **real fabric-loader + Fabric API** into the NeoForge process so mods from both loaders run side by side. The bridge itself lives in `projects/fabricbridge/` (plus a few platform-side patches and hooks).

下面按"**谁的部分按谁的协议**"讲清楚。**不要把本仓库理解成单一协议覆盖一切** —— 它是若干部件放在一起。
Below, everything is spelled out as "**each part keeps its own licence**". **Do not read this repository as one single licence covering everything** — it is several components in one place.

---

## 一、默认协议：LGPL-2.1-only（上游 NeoForge + 我们自己写的代码）/ Section 1 — Default licence: LGPL-2.1-only (upstream NeoForge + our own code)

**中文**：除非另有说明，本仓库的代码按 **GNU Lesser General Public License v2.1** 授权，全文见 <http://www.gnu.org/licenses/old-licenses/lgpl-2.1.txt> 与仓库根目录的 `LICENSE.txt`（LGPL v2.1 全文）。版本明确是 **v2.1，且不得使用其它版本**；SPDX 记法：`SPDX-License-Identifier: LGPL-2.1-only`。**我们新加的部分沿用同一协议**：`projects/fabricbridge/**` 与各平台侧补丁／钩子的文件头都标着 `LGPL-2.1-only`，版权行沿用 `Copyright (c) NeoForged and contributors`。

**English**: Unless noted otherwise, the code in this repository is licensed under the **GNU Lesser General Public License v2.1** — see <http://www.gnu.org/licenses/old-licenses/lgpl-2.1.txt> and `LICENSE.txt` at the repository root (the full LGPL v2.1 text). The version is specifically **v2.1, and no other version shall be used**; in SPDX terms: `SPDX-License-Identifier: LGPL-2.1-only`. **Our additions follow the same licence**: the file headers in `projects/fabricbridge/**` and the platform-side patches/hooks carry `LGPL-2.1-only`, with the copyright line `Copyright (c) NeoForged and contributors`.

### 关于 LGPL 的"传染性" / On LGPL "infectivity"

**中文**：LGPL v2.1 是特意选的：**别的项目可以链接、依赖本项目的 API，而不必自己也用同一协议**。也就是说：**用这个加载器去跑你的模组，不会要求你的模组改用 LGPL**。

**English**: LGPL v2.1 was chosen on purpose: **other projects may link to and depend on this project's APIs without having to use the same licence themselves**. In other words: **running your mod on this loader does not force your mod to become LGPL**.

---

## 二、内嵌的第三方部件（各自协议，我们不改）/ Section 2 — Bundled third-party components (their own licences, unchanged)

| 部件 / Component | 在哪 / Where | 协议 / Licence |
|---|---|---|
| **Fabric API** 的两个模块：`fabric/fabric-menu-api-v1`、`fabric/fabric-screen-api-v1`（为适配 NeoForge 的调用点而自建）<br>Two Fabric API modules rebuilt here to adapt NeoForge's call sites | 仓库内源码 + 构建产物 / sources in-repo, built into artifacts | **Apache-2.0**（`Copyright (c) 2016-2019 FabricMC`，文件头保留 / headers kept） |
| **fabric-loader**（子模块 `fabric-loader/`，构建时打成 fat jar，并以**已发布坐标**嵌进安装器）<br>fabric-loader (submodule, built into a fat jar and embedded into the installer under its **released coordinates**) | 子模块 + 构建产物 / submodule + artifacts | **Apache-2.0** |
| fabric-loader fat jar 里打包的库（ASM、Gson、sponge-mixin、MixinExtras、FlatLaf、class-tweaker、sat4j 等）<br>Libraries bundled inside the loader fat jar | 构建产物 / artifacts | 各自原协议 / each under its own licence（BSD-3-Clause / Apache-2.0 / MIT / LGPL-2.1 …） |
| 你自己放进去的 Fabric / NeoForge 模组<br>Mods you install yourself | 不进本仓库 / not in this repository | 各自原协议 / their own licences |

> **中文**：安装器里同时装着 LGPL-2.1-only 的 NeoForge/桥、Apache-2.0 的 Fabric API 与 fabric-loader、以及一堆各自协议的小库 —— 这是"**聚合分发**"，每个部件各自保留声明即可。
> **English**: The installer ships LGPL-2.1-only NeoForge/bridge code, Apache-2.0 Fabric API and fabric-loader, plus a pile of small libraries under their own licences — that is **aggregation**, and each component simply keeps its own notices.

---

## 三、我们"引用"了谁（没有抄代码）/ Section 3 — Who we referenced (no code was copied)

**中文**：**FFAPI / Forgified Fabric API**（Sinytra，**Apache-2.0**）：桥里有若干处**注释**引用了它公开的做法（例如"扩展菜单怎么把 opening data 塞进 NeoForge 的扩展数据通道"、"枚举扩展该在什么时机落地"），用作对照与判据；**没有复制其代码**。**Sinytra Connector**（Su5eD，**MIT**）：同样是**参考思路**（模块层的同名包冲突怎么处理、mixin 补丁表长什么样），**没有复制代码**。**结论**：本仓库不因这两者而需要变更协议；在此**致谢**，并保留它们各自的许可说明。将来若真要搬它们的代码进来，必须按对应协议补齐版权与许可声明（Apache-2.0 还需带上 NOTICE）。

**English**: **FFAPI / Forgified Fabric API** (Sinytra, **Apache-2.0**): a handful of **comments** in the bridge reference its publicly documented approach (for example how an extended menu puts its opening data into NeoForge's extra-data channel, and when enum extensions have to be applied) as a cross-check; **no code was copied**. **Sinytra Connector** (Su5eD, **MIT**): likewise **ideas only** (how same-package conflicts across the module layer are handled, what a mixin patch table looks like); **no code was copied**. **Conclusion**: nothing about this repository needs to change licence because of either project; we **credit** them here and keep their licence notices. If their code is ever actually copied in, the corresponding notices must be added (and Apache-2.0 additionally requires the NOTICE file).

---

## 四、要不要改成"混合协议"？/ Section 4 — Should this be a "mixed licence"?

**中文**：**不需要，也不建议**把整个仓库标成 "mixed license"：那样反而说不清哪块归哪条协议。我们的做法是：① **按部件声明**（见上表）—— LGPL-2.1-only 覆盖 NeoForge 与桥，第三方部件保留各自协议；② 发布安装器时**把各方的许可文本一并带上** —— 构建已经保留了嵌套 jar 的 `META-INF`（内含各自的 `LICENSE`/`NOTICE`），发布时另附本文件、`LICENSE.txt`，以及 Fabric API 与 fabric-loader 的 Apache-2.0 文本即可；③ 不要声称 LGPL 覆盖第三方部件，也不要声称第三方协议覆盖 NeoForge/桥。

**English**: **No — and please do not** label the whole repository as "mixed license": that makes it *harder* to tell which part is under which terms. What we do instead: (1) **per-component declaration** (table above) — LGPL-2.1-only for NeoForge and the bridge, each third-party part under its own licence; (2) when shipping an installer, **carry every party's licence text** — the build already keeps each nested jar's `META-INF` (with its own `LICENSE`/`NOTICE`); attach this file, `LICENSE.txt`, and the Apache-2.0 texts of Fabric API and fabric-loader; (3) never claim LGPL covers the third-party parts, and never claim a third-party licence covers NeoForge/the bridge.

---

## 五、Minecraft EULA

**中文**：NeoForge 是面向 Minecraft: Java Edition 的软件修改，因此它以及所有基于它的项目（主要是模组）都受 Minecraft 最终用户许可协议（EULA）约束：<https://www.minecraft.net/en-us/eula>（镜像：<https://aka.ms/MinecraftEULA>）。请特别注意其中关于 Mods 的条款。

**English**: As NeoForge is a software modification made for Minecraft: Java Edition, it and all other projects made using NeoForge (primarily, Minecraft mods) are bound by the terms of the Minecraft End-User License Agreement (EULA): <https://www.minecraft.net/en-us/eula> (mirror: <https://aka.ms/MinecraftEULA>). Please pay particular attention to the section on Mods.

---

## 六、上游原文（NeoForge 的说明，未改动）/ Section 6 — Upstream text (NeoForge's own wording, unchanged)

Unless noted below, NeoForge and all its parts here in this repository are
licensed under the **GNU Lesser General Public License v2.1** (LGPL v2.1), as
seen at http://www.gnu.org/licenses/old-licenses/lgpl-2.1.txt and reproduced
in the `LICENSE-LGPLv2.1` at the root of this repository.

The version of the license is specifically v2.1, and no other version shall be
used. In SPDX terms, this means: `SPDX-License-Identifier: LGPL-2.1-only`.

The homepage for NeoForge is at https://neoforged.net, with the Git
repository located at https://github.com/neoforged/NeoForge.

The LGPL v2.1 was chosen specifically so projects may link to and depend on
NeoForge's features and APIs without being bound to be themselves licensed
under the same license (commonly known as "infectivity").

Thus, mods or other projects using NeoForge's code through the various Java
mechanics for linking and referencing libraries are not bound to be licensed
under the LGPL v2.1 by its terms and conditions, and are free to use whatever
license for their own work.

## Contributor License Agreement and the patches directory

All contributors to NeoForge are required to agree to a **Contributor License
Agreement** before their contributions are merged, which is seen in the
`docs/CONTRIBUTING.md` and reproduced below:

> - You grant NeoForged a license to use your code contributed to the primary
> codebase (everything **not** under patches) in NeoForge, under the LGPLv2.1
> license.
> - You assign copyright ownership of your contributions to the patches
> codebase (everything under patches) to NeoForged, where it will be licensed
> under the LGPLv2.1 license.
>
> This is intended as a **legally binding copyright assignment** to the
> NeoForged project for contributions under the patches codebase. However you
> retain your copyright for all other contributions.

This means that the contents of the patches codebase (that is, everything under
the `patches` subdirectory of this repository) are under the copyright
ownership of the NeoForged Team.

This is in part intended to reduce any conflicts that may arise from
identifying the ownership in whole or in part of any patch file, since all
patch files are automatically (re)generated by our tooling, and it would be
impractical to impossible for individual attribution to be made.

All other parts of any contributions remain under the copyright of the
contributors, licensed under the LGPL v2.1 license.

> **中文**（说明，非协议文本）：上面第六节是上游 NeoForge 的原话，保持英文原样以便与上游逐字对应；其中 `LICENSE-LGPLv2.1` 在本仓库里的实际文件名是 `LICENSE.txt`（内容就是 LGPL v2.1 全文）。
> **English** (note, not licence text): section 6 above is upstream NeoForge's own wording, kept verbatim in English so it can be compared with upstream character by character. The file it calls `LICENSE-LGPLv2.1` is named `LICENSE.txt` in this repository; its content is the full LGPL v2.1 text.

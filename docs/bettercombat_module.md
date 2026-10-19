# 内置 Better Combat 速查

一句话：**Better Combat 2.4.0+1.21.1 的源码已内置在 `src/main/java/com/fiercerbattles/bettercombat/`，作为 Fiercer Battles 模组的一部分运行；资源命名空间仍是 `bettercombat`，游戏里不能再另装独立的 Better Combat jar。**

## 位置

| 内容 | 位置 |
| --- | --- |
| Java 源码（原 common 模块） | `src/main/java/com/fiercerbattles/bettercombat/` |
| NeoForge 平台代码（原 neoforge 模块） | `src/main/java/com/fiercerbattles/bettercombat/neoforge/` |
| 初始化入口 | `neoforge/NeoForgeMod.initialize(modEventBus)`，由 `FiercerBattles` 构造函数调用 |
| Mixin 配置 | `src/main/resources/fiercerbattles.bettercombat.mixins.json` |
| 动画库（替换为 `资源包/animations-load/player_animations`，原版 31 个 + 自定义 7 个） | `src/main/resources/assets/bettercombat/player_animations/` |
| 音效、粒子、语言、贴图 | `src/main/resources/assets/bettercombat/` |
| 默认武器属性 | `src/main/resources/data/bettercombat/weapon_attributes/`、`data/minecraft/weapon_attributes/` |
| 匠魂及其附属模组的攻击动作（来自 kubejs `data`） | `data/tconstruct/`、`data/tinkers_katanas/`、`data/tinkers_things/` 下的 `weapon_attributes/` |
| 战技（来自 kubejs `data/tconstruct/fiercerbattles`） | `data/tconstruct/fiercerbattles/combat_skills/` |

动画、音效、粒子、网络包、配置文件仍用 `bettercombat` 命名空间（如 `bettercombat:two_handed_spin`，配置在 `config/bettercombat/`），数据包写法不变。

## 战技引用

战技文件统一放在 `data/tconstruct/fiercerbattles/combat_skills/`（与 kubejs 的 `data/tconstruct/fiercerbattles` 一致）。

武器文件里的 `"json": "axe_combat_art.json"` 这种短写法，只在**武器自己的命名空间**下查找。匠魂附属模组的武器要写完整位置，才能引用到匠魂的战技：

| 武器 | 引用 |
| --- | --- |
| `tinkers_katanas:katana` | `tconstruct:fiercerbattles/combat_skills/katana_combat_art.json` |
| `tinkers_things:halberd` | `tconstruct:fiercerbattles/combat_skills/axe_combat_art.json` |

`neoforge.mods.toml` 对 `tconstruct`、`tinkers_katanas`、`tinkers_things` 声明了可选依赖，并设为在它们之后加载。这样模组自带的武器数据优先于附属模组 jar 里的同名文件（TinkersKatanas 自带一个 `katana.json`）。

开发环境的 `run/mods` 里放了 TinkersConstruct、Mantle、TinkersKatanas、TinkersThings 和它们的前置 JsonThings，`runClient` 时可以直接测这些武器。

## 板斧连击与攻击动画导出

板斧（`tconstruct:broad_axe`）的三段普通攻击是 `bettercombat:broad_axe_attack1/2/3`：

1. 正手斜劈：举到右肩后，从右上斜劈到左下。
2. 反手斜劈：蓄到左肩后，从左上斜劈到右下。
3. 过顶下劈：终结技，举过头顶向前下劈，同时弓步。

三段都从游戏里板斧的待机姿势（`pose_two_handed_scythe`）开始并回到它，每段 20 tick，第 10 tick 刀刃扫过正前方。Better Combat 在动画播到 `endTick / 2` 时结算伤害，所以命中正好落在挥砍上。

三段都写了 `movement_speed_multiplier: 0`。内置 Better Combat 遇到移动倍率为 0 的攻击段时会跳过平滑过渡，整段挥击原地不动（`mixin/client/ClientPlayerEntityMixin`）；同样写了 0 的戟也是如此。

动画源文件和脚本在 `资源包/blender动画/匠魂手持動作/`：

- `_scripts/author_broad_axe_attacks.py`：按关键帧参数程序化生成三段动作（躯干转体、右臂瞄准、斧头朝向；左手自动沿斧柄找握点，刀刃自动朝向挥动方向，双脚按刚性腿自动贴地）。改动作就改这里的关键帧。
- `_scripts/game_preview.py`：按游戏里的渲染链（Player Animator 部件 → 右手物品 → 匠魂大工具模型）摆出任意动画 JSON 并渲染；输出路径以 `.blend` 结尾时改为烘焙成可编辑的 blend。
- `攻擊/板斧連擊/broad_axe_attack1-3.blend`：烘焙出的源文件，`預覽/broad_axe_attack1-3.gif` 是预览。

从 blend 导出到模组（`--hit-tick` 填刀刃扫过目标的那一帧）：

```bash
F:/steam/steamapps/common/Blender/blender.exe --background --disable-autoexec broad_axe_attack1.blend --python-exit-code 1 --python tools/export_bettercombat_attack.py -- --hit-tick 10
```

`tools/export_bettercombat_attack.py` 每 tick 写一个关键帧（emote v3，`torso` 是模型身体，实体本身不动），朝向取第 0 帧躯干（吸附到 90°），并逐帧反解 `rightItem`，让游戏里的大工具模型与 Blender 里的斧头重合。加 `--verify` 只比对、不写文件。

## 模组 ID 与兼容

- 不再有 `bettercombat` 这个模组 ID；事件订阅都挂在 `fiercerbattles` 上。
- `neoforge.mods.toml` 把独立的 `bettercombat` 标为 `incompatible`，同时安装会直接报错，避免同一套 Mixin 注入两次。
- 独立版 Better Mob Combat 依赖 `net.bettercombat`，已经不能用；本模组的 `mobcombat` 包是它的内置版本。
- RevolutionAndWar 通过反射查找 `net.bettercombat.logic.WeaponRegistry`，需要改成 `com.fiercerbattles.bettercombat.logic.WeaponRegistry` 才能继续联动。
- Player Animator 和 Cloth Config 仍是外部前置；tiny-config 通过 jarJar 打包进 jar。

## 与上游的差异

- 上游用 Yarn 映射（Architectury Loom），这里已转换为 Mojang 映射，并从 `net.bettercombat` 移到 `com.fiercerbattles.bettercombat`，不需要 refmap。
- `@Mod("bettercombat")` 入口改为 `NeoForgeMod.initialize`。
- `Platform` / `PlatformClient` 原本是 Architectury 的 `@ExpectPlatform` 桩，现在直接调用 `neoforge/PlatformImpl`、`PlatformClientImpl`。
- `SlashParticle` 的 Fabric `@Environment` 改为 NeoForge `@OnlyIn`。
- `SpellEngineCompatibility` 改为反射调用。FTB Teams 兼容仍是 `compileOnly`。
- 两个 Accessor 的 getter 名与上游 NeoForge 正式版字节码一致：`MinecraftClientAccessor.getMissTime()`、`LivingEntityAccessor.getAttackStrengthTicker()`。
- `AbstractClientPlayerEntityMixin.playAttackAnimation` 先把 upswing 夹到 0.01–0.99、时长至少 1 tick 再算播放速度。上游遇到 upswing 为 0 的攻击段（比如没写 `upswing` 的战技文件）会算出无穷大的速度，PlayerAnimator 的 `SpeedModifier` 随即死循环，客户端卡死。

移植时，编译结果与上游 NeoForge 正式版 jar 逐类比对过：方法与字段签名、引用的 Minecraft 成员都一致。37 个 Mixin 目标也都按上游 refmap 核对过。

## 修改须知

- 本模组自己的 Mixin 仍用字符串目标（`targets = "com.fiercerbattles.bettercombat..."`）。BC 源码就在同一个源码集里，新代码可以直接 import。
- 写 BC Mixin 时用 Mojang 名（如 `Minecraft.startAttack`、`LivingEntity.attackStrengthTicker`）。编译器不检查 Mixin 字符串，写错只会在加载时报错。
- kubejs 里同名的 `weapon_attributes` / `combat_skills` 文件优先级高于模组自带数据。改了模组内的数据后，要把 kubejs 里对应的旧副本删掉，否则会被覆盖。

Better Combat 的许可证是 All Rights Reserved（Zsolt Molnár）。内置版本仅供自用；公开发布前需要取得原作者许可，见 `META-INF/THIRD_PARTY_NOTICES.md`。

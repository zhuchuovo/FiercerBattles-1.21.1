# 钢铁卫戍 Boss 速查

一句话：**和僵尸领主同一套做法——服务器按帧号跑动作状态机，把（动作、clip 第 0 帧的世界时间、过渡 tick、播放倍速）同步给客户端；客户端按同一世界时钟采样从 Blender 烘焙出来的 clip，直接画 Blender 网格。**

召唤：`/summon fiercerbattles:iron_sentinel`，或创造模式「刷怪蛋」页的钢铁卫戍刷怪蛋。

## 文件

Java 路径相对 `src/main/java/com/fiercerbattles/`。

| 内容 | 文件 |
| --- | --- |
| 注册（实体、炮弹、刷怪蛋、属性、伤害类型键） | `boss/BossEntities.java` |
| Boss 实体、AI 状态机、命中判定、刺中举起（被刺者作为乘客）、火炮与迫击炮、弹反眩晕、处决钩子 | `boss/IronSentinel.java` |
| 动作 → clip 对照与播放方式 | `boss/IronSentinelAction.java` |
| 电锯刺中点、甩出速度、炮口位置与方向（生成的表） | `boss/IronSentinelPoints.java` |
| 炮弹（直射 / 朝天 / 迫击炮落点） | `boss/SentinelShell.java` |
| 被刺中时不能潜行挣脱（和僵尸领主的抓投共用） | `boss/BossEvents.java` |
| 客户端：资源加载（网格读取与僵尸领主共用）、按倍速采样与过渡、网格渲染与发光层、炮弹与落点提示圈；被刺中时第三人称视角（`BossClient.Held`） | `boss/client/IronSentinel*.java`、`boss/client/SentinelShellRenderer.java` |
| Boss 专属弹反：`fiercerbattles:boss_parryable` 里的伤害类型能被弹反窗口挡住，Boss 播自己的眩晕 | `parry/BossParryTarget.java`、`parry/ParryServer.incomingDamage` |
| 自带处决的目标：自己决定动作和时机、自己播放被处决的一半、自己承受收尾伤害 | `execution/ExecutionTarget.java`、`FiercerBattlesExecutionServer.start/finish` |
| 战斗音乐（客户端）：交战中的钢铁卫戍在附近时淡入、离开或脱战后淡出、死亡时交叉淡入终曲，期间压住原版音乐 | `boss/client/IronSentinelMusic.java`；`IronSentinel.isEngaged()`（同步的 `DATA_ENGAGED`） |
| 音效与音乐文件 | `sounds/boss/iron_sentinel_saw_hit.ogg`、`sounds/music/iron_sentinel_battle.ogg`、`iron_sentinel_finale.ogg`（`sounds.json` 里的 `iron_sentinel_saw_hit`、`music.iron_sentinel_battle`、`music.iron_sentinel_finale`，音乐为串流），由 `python tools/sentinel_audio.py --music <The Iron Golem Awakens.mp3> --saw <电锯音效.mp3>` 生成 |
| 迫击炮标记贴图 | `textures/entity/iron_sentinel/mortar_ring`、`mortar_fill`、`mortar_reticle`（白色，渲染时着色），由 `python tools/sentinel_mortar_textures.py` 生成 |
| 导出工具 | `tools/export_iron_sentinel.py`；重导 `saw_impale`、`saw_cannon_impale`、`skill_cannon`、`saw_cannon`、`cannon_sky`、`saw_cannon_sky` 后运行 `python tools/iron_sentinel_points.py` 更新 `IronSentinelPoints` |
| 资源 | `assets/fiercerbattles/iron_sentinel/model.json`、`iron_sentinel/clips/*.json`、`textures/entity/iron_sentinel/*.png`；玩家这一半的处决：`data/fiercerbattles/fiercerbattles_actions/iron_sentinel_execution.json` 和 `fiercerbattles_animations/iron_sentinel_execution/executioner.json`；`data/fiercerbattles/damage_type/iron_sentinel_*.json`；`data/fiercerbattles/tags/damage_type/boss_parryable.json` |

## 动画管线

源文件：`D:/code/mcmod/资源包/blender动画/BOSS/钢铁卫戍/`（模型 `iron_sentinel.blend`，动作 `动作/iron_sentinel_<clip>.blend`），20 FPS，**Blender 帧号 = 游戏 tick**。套件 README 有骨架和关键帧的说明。

- `export_iron_sentinel.py -- --model`：`Bone_A` 下所有可见网格（21 个部件：电锯两节、右手炮四件、铁链等）按物体导出成四边形面，顶点在物体自身坐标系。
- `export_iron_sentinel.py -- --name <clip>`：每个网格物体一个通道，逐 0.5 tick 采样**绝对**姿势（原版模型空间，y 向下、脚底 y=24；`Bone_A` 在髋部静止位置、离地 15 像素，所以 `HIP_OFFSET = (0, 9, 0)`）。采样在根对象自己的坐标系里做（会除掉根的缩放）。另外逐 tick 记录 `points`：电锯的刺中点 `impale`（锯尖往回 8 像素）、锯尖、左护腕、炮口位置和炮管方向。
- 循环动作（idle、walk、saw_idle、saw_walk）导出第 0…L 帧；walk 按「实际走过的距离 ÷ 动画设计速度」推进周期，idle 按世界时钟循环。
- 游戏内按 1.0 倍画（`IronSentinel.RENDER_SCALE`，碰撞箱 1.4×2.7，和原版铁傀儡一样高）：这个大小下电锯的实际长度正好是 3.5 格的攻击范围，刺中点在前方 3.2 格。

重导命令（在仓库根目录；先导模型）：

```bash
blender --background --disable-autoexec "D:/code/mcmod/资源包/blender动画/BOSS/钢铁卫戍/iron_sentinel.blend" --python-exit-code 1 --python tools/export_iron_sentinel.py -- --model
blender --background --disable-autoexec "D:/code/mcmod/资源包/blender动画/BOSS/钢铁卫戍/动作/iron_sentinel_saw_thrust.blend" --python-exit-code 1 --python tools/export_iron_sentinel.py -- --name saw_thrust
blender --background --disable-autoexec "D:/code/mcmod/资源包/blender动画/BOSS/钢铁卫戍/动作/iron_sentinel_saw_executed.blend" --python-exit-code 1 --python tools/export_iron_sentinel.py -- --name saw_executed --player 30
python tools/iron_sentinel_points.py
```

最后一条 Blender 命令同时导出处决里玩家的那一半：源文件里另有一套 `P_` 前缀的玩家骨架（`处决/one.blend` 追加进来的），导成 `humanoid_v1` 六通道 clip 和双人动作定义（几何参数按两个根对象的相对位置算，「right」和 `export_execution.py` 一样是 Blender +x）。这个双人动作**没有** `victim_clip`：钢铁卫戍用自己的模型播放 `executed` / `saw_executed`。

游戏用到的 clip：`idle`、`walk`、`attack_left`、`attack_right`、`saw_transform`、`saw_idle`、`saw_walk`、`saw_sweep_lr`、`saw_sweep_rl`、`saw_thrust`、`saw_impale`、`stagger`、`saw_stagger`、`executed`、`saw_executed`、`skill_cannon`、`saw_cannon`、`cannon_sky`、`saw_cannon_sky`、`saw_cannon_thrust`、`saw_cannon_impale`、`death`、`saw_death`。后面 10 个是为游戏新做的，由套件的 `_scripts/author_iron_sentinel_fight.py` 生成（见套件 README）。

## 行为与帧号

| 行为 | 实现 |
| --- | --- |
| 属性 | 200 生命、10 护甲、击退抗性 1、跨步 1 格、不受摔落伤害、不会消失、Boss 血条（一阶段白色，二阶段红色） |
| 移动 | 只走路：一阶段 `walk`、二阶段 `saw_walk`，0.09 格/tick；待机 `idle` / `saw_idle`（按世界时钟循环） |
| 一阶段：拳头 | 水平距离 ≤3 格（到目标碰撞箱）、高度差 <2.5、看得见时出手。`attack_left`：左臂从最左边横扫到最右边，第 16–20 帧扫过前方 ±80°、3 格，每段每个目标最多一次，伤害 3；第 7–17 帧随跨步前进 1 格（离目标碰撞箱不足 0.5 格时停步）；第 26 帧重新锁定目标，用 5 tick 过渡到 `attack_right`（右臂从右往左，同样的帧号）。打完冷却 20 tick |
| 进入二阶段 | 和目标战斗累计超过 20 秒（有目标的 tick 数 > 400），或生命低于 80%，在两次动作之间：`saw_transform`（44 帧，13 拳头裂开、33 电锯伸出），结束时进入二阶段 |
| 二阶段：电锯连招 | 水平距离 ≤4.5 格时开始一轮：左横扫 `saw_sweep_lr`、右横扫 `saw_sweep_rl`、再一次左横扫 `saw_sweep_lr`，接突刺 `saw_thrust`，四段为一轮（`COMBO`）。每段向前突进 2 格（横扫在第 4–17 帧、突刺在第 5–10 帧，同样离目标 0.5 格停步）。横扫第 15–21 帧扫过前方 ±75°、3.5 格，伤害 7；横扫在第 25 帧重新锁定目标并用 5 tick 接下一段。**快砍轮换**：每轮有一次横扫 1.5 倍速，第一轮是第一次横扫，第二轮第二次，第三轮第三次，第四轮又回到第一次（`fastSweep = 轮数 % 3`；突刺的前摇本来就是 1.5 倍速，不参与轮换）。一轮打完冷却 12 tick |
| 第三段突刺 | 不能弹反（`iron_sentinel_thrust` 不在 `boss_parryable` 里），无视盾牌；前摇到第 12 帧一律 1.5 倍速，第 7 帧前一直转向目标（每 tick 最多 20°），第 9–12 帧判定前方 ±25°、3.5 格的窄锥，伤害 7（刺中后再锯 4×1.25，整段共 12）。走路、侧移都躲不开，只有翻滚的无敌帧能躲（伤害类型不在 `ignores_dodge_roll` 里） |
| 刺中举起 | 突刺命中、目标能举起（宽 ≤1、高 ≤2.2、没在骑乘）时，从第 10 帧切到 `saw_impale`：目标成为乘客，两端都按 `IronSentinelPoints.IMPALE`（刺中点逐帧表）把它的身体中段放在锯条上；举到头顶，第 24/34/44/54 帧各锯 1.25 点（`iron_sentinel_impale`，无视盾牌），第 55 帧甩出（下一 tick 给出锯条的速度，往前下方甩飞）。被刺者按潜行挣脱不了；玩家被刺中时客户端切到第三人称背后视角 |
| 弹反 | 拳头和两次横扫（`iron_sentinel_punch`、`iron_sentinel_saw`，在 `fiercerbattles:boss_parryable` 里）能被弹反：弹反窗口内被挡下，播放弹反音效、Boss 照常吃反击伤害（`parry.counter_damage`，默认 4，无视护甲），Boss 播放自己的眩晕 `stagger` / `saw_stagger`（56 帧：双臂被弹开向外后甩、右脚后撤一步，第 16 帧起弯腰垂臂发呆，第 42 帧开始起身）。二阶段被弹反后，下一轮先出突刺：刺中就举起，没刺中就在第 22 帧接左、右、左三次横扫（这一轮是 刺→左→右→左，`PUNISH`，快砍轮换照常） |
| 处决 | 眩晕第 0–42 帧内，主手剑/斧按处决键（V）对准钢铁卫戍，任何血量都能处决（`ExecutionTarget`，不看人形白名单、血线和体型）。双人动作 `iron_sentinel_execution`：玩家 30 tick（蹲下蓄力、第 5 帧起跳、第 7 帧把剑从下往上刺进胸口熔炉、挂在剑上、第 16–17 帧蹬开胸口拔剑、落回原地后 0.56 格）；钢铁卫戍同时播放 `executed` / `saw_executed`（76 帧：第 7 帧被刺中、抽搐、第 17 帧拔剑后瘫软，第 50 帧起慢慢站起）。玩家这一半播完时造成 20 点真实伤害（`iron_sentinel_execution`：无视护甲、附魔、效果和受伤冷却，没有击退）；之后钢铁卫戍继续把起身播完（这段时间可以继续打） |
| 火炮 | 两种触发，都在两次动作之间：二阶段每打完 3 轮；或目标在 7 格以外持续 6 秒（120 tick，两个阶段都有）。一阶段播 `skill_cannon`，二阶段播 `saw_cannon`，拆成召唤、发射、收回：**召唤** 第 0–48 帧（右拳裂开变炮、扎稳，期间每 tick 最多转 10° 对准目标）；**发射** 第 48 帧起循环播放第 48–62 帧，每 15 tick（0.75 秒）一炮，共 4 炮，从炮口（`IronSentinelPoints.FIRE_MUZZLE`）朝目标身体中部直线发射，1.2 格/tick，碰到方块或敌人爆开：2 格内 4 点；然后 `cannon_sky` / `saw_cannon_sky`（32 帧）抬炮朝天，第 8/13/18 帧（每 0.25 秒）各射一发，只是动画；**收回** 从第 84 帧播到 118 帧 |
| 火炮期间靠近 | 只在二阶段、每次火炮一次：发射（包括朝天）期间目标进入 4 格内，立刻改出 `saw_cannon_thrust`（右手炮保持伸出的突刺，帧号和判定同第三段突刺），刺中就 `saw_cannon_impale`（同刺中举起），然后收回火炮 |
| 迫击炮 | 收回火炮后，每 1.5 秒（30 tick）在目标当时的脚下标记一次落点，共 3 次：地面出现 3 格半径的落点标记（`SentinelShellRenderer`：外圈标出爆炸范围，从大一圈收拢到位；中间的红色圆面从中心匀速长大，碰到外圈时正好落地；中心准星反向旋转；外圈由橙变红、越近脉动越快），25 tick 后炮弹从天而降（最后 10 tick 能看到它落下，外圈白色闪烁）：3 格内按距离 6→3 点（`iron_sentinel_mortar`，无视盾牌）。Boss 这期间照常追击和攻击 |
| 电锯命中音效 | 横扫和突刺真正打中（`hurt` 成功）时，在被打中者身上播放 `iron_sentinel_saw_hit`（约 3.2 秒的电锯切入声，单声道、随距离衰减，音高 ±5% 随机）；刺中举起由突刺命中那一下带出这声。被弹反、被翻滚躲开、拳头都不播 |
| 战斗音乐 | 来源曲 `The Iron Golem Awakens`（3:58）切成两段：**战斗** 0:00–3:23 循环播放（文件从第 6 秒开始，结尾 6 秒和开头 6 秒做等功率交叉淡化，所以每一遍无缝接回前奏）；**终曲** 3:23 到结尾。客户端：有目标的钢铁卫戍在 40 格内时战斗曲 2 秒淡入；64 格内没有交战中的钢铁卫戍连续 5 秒就 3 秒淡出（期间回来会从当前位置淡回）；正在播的那只死亡时战斗曲 3 秒淡出、同时终曲 3 秒淡入，终曲播完为止。播放期间原版背景音乐静音（`SelectMusicEvent.overrideMusic(null)`）。**音量**：先用温和压缩（阈值 -26 dB、2.5:1）把高潮拉近安静段，再把整首定到 -21 LUFS（原曲 -13.5）：最响的短时响度从 -10.3 降到 -19.1 LUFS，起伏从约 10 dB 收到约 5 dB；游戏里再乘 0.8，归在「音乐」音量滑条下（滑条为 0 时不播） |
| 死亡 | 不用原版侧倒，客户端按死亡时钟播放 `death`（二阶段 `saw_death`，60 帧）：致命一击把它打得后仰退一步，熔炉闪烁、弯腰，最后挣扎一下，第 30 帧起像被推倒的雕像一样整个向前倒下（根对象绕脚爪转动），第 44 帧脸朝下砸在地上并反弹一下，双臂摊开（电锯摊在一侧）。发光层一边闪一边熄灭，砸地时全暗；红色受伤染色只保留开头 8 tick。服务端第 30 帧倾倒声、第 44 帧砸地声和扬尘，第 70 tick 化作烟雾消失 |

调试：`IronSentinel` / `SentinelShell` 的 DEBUG 日志记录每次动作切换（含世界时间和倍速）、每次命中、每一轮的顺序和快段、火炮的触发原因、每一炮、迫击炮落点、弹反、处决。

## 修改边界

1. 帧号、距离、伤害都是 `IronSentinel` 顶部的常量，和上表一一对应；改 clip 时一起核对。服务端不读取 clip 文件；需要 clip 里位置的地方（刺中点、炮口）用 `iron_sentinel_points.py` 生成的表。
2. 倍速只影响 clip 时间：`IronSentinel.clipTime(elapsed, speed, until)` 两端共用，服务端用它算判定帧，客户端用它采样；`until` 之后恢复原速。
3. 火炮的「发射」循环、「收回」从中间开始都靠同一个 clip 的帧段；`cannon_sky`、`saw_cannon_thrust`、`saw_cannon_impale` 的首尾姿势就是 `skill_cannon` / `saw_cannon` 第 48/84 帧的扎稳瞄准姿势，所以能无缝接上。
4. Boss 自己控制朝向和水平速度；`BMCConfig` 把 `fiercerbattles:iron_sentinel` 固定排除在 mob combat 之外。
5. 处决：钢铁卫戍是 `ExecutionTarget`，执行会话期间它没有 AI（会话把它固定在锚点）；`executed` 的第 0 帧在会话的 `startGameTime`。双人动作改了时长或几何参数，要用 `--player` 重新导出。

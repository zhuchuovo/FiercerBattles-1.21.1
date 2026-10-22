# 僵尸领主 Boss 速查

一句话：**服务器按帧号跑一个动作状态机，把（动作、clip 第 0 帧的世界时间、过渡 tick）同步给客户端；客户端按同一世界时钟采样从 Blender 烘焙出来的 clip，直接画 Blender 网格。**

召唤：`/summon fiercerbattles:zombie_lord`，或创造模式「刷怪蛋」页的僵尸领主刷怪蛋（另有复苏骷髅刷怪蛋，便于单独测试弹反/处决）。

## 文件

Java 路径相对 `src/main/java/com/fiercerbattles/`。

| 内容 | 文件 |
| --- | --- |
| 注册（实体、刷怪蛋、属性、伤害类型键、内置人形白名单） | `boss/BossEntities.java` |
| Boss 实体、AI 状态机、命中判定、地震召唤 | `boss/ZombieLord.java` |
| 动作 → clip 对照与播放方式 | `boss/ZombieLordAction.java` |
| 骷髅头（螺旋升起、发射、落点、爆炸、唤起骷髅） | `boss/LordSkull.java` |
| 复苏骷髅（出土、装备、两刀一停顿） | `boss/LordSkeleton.java` |
| 客户端：资源加载、姿势采样与过渡、网格渲染、光圈、落点提示、出土下沉、地震镜头 | `boss/client/*.java` |
| 导出工具 | `tools/export_zombie_lord.py` |
| 资源 | `assets/fiercerbattles/zombie_lord/model.json`、`zombie_lord/clips/*.json`、`textures/entity/zombie_lord/*.png`；`data/fiercerbattles/damage_type/zombie_lord_*.json` |

## 动画管线

源文件：`D:/code/mcmod/资源包/blender动画/敌军动作/`（模型 `zombie_lord.blend`，动作 `动作/zombie_lord_<clip>.blend`）。场景帧率 60/3 = 20 FPS，所以 **Blender 帧号 = 游戏 tick**。

- `export_zombie_lord.py -- --model`：把 `Bone_A` 下所有可见网格（含王冠、披风、胸甲、巨剑等附件）按物体导出成四边形面，顶点在物体自身坐标系（模型像素），UV 已翻成 Minecraft 方向。
- `export_zombie_lord.py -- --name <clip>`：每个网格物体一个通道，逐 0.5 tick 采样**绝对**姿势（原版模型空间，y 向下、脚底 y=24），沿用 `AnimationClip` format 1 的 `[x,y,z,qx,qy,qz,qw]`。所以 `rightItem` 下的巨剑、逐帧摆动的披风和腰旗都原样保留，不需要 Player Animator 换算。
- 循环动作（walk）导出第 0…L 帧，客户端按「实际走过的距离 ÷ 动画设计速度」推进周期，脚不打滑。
- 游戏内整体放大 1.5 倍（`ZombieLord.RENDER_SCALE`，碰撞箱 0.9×2.9）。

重导命令（在仓库根目录；先导模型，clip 会核对物体缩放）：

```bash
blender --background --disable-autoexec "D:/code/mcmod/资源包/blender动画/敌军动作/zombie_lord.blend" --python-exit-code 1 --python tools/export_zombie_lord.py -- --model
blender --background --disable-autoexec "D:/code/mcmod/资源包/blender动画/敌军动作/动作/zombie_lord_attack1.blend" --python-exit-code 1 --python tools/export_zombie_lord.py -- --name attack1
```

游戏用到的 clip：`attack1`、`attack2`、`attack3`、`leap_slam_inplace`、`quake`、`walk`、`charge`、`stun`、`death`。`charge`、`stun` 和 `death` 由 `_scripts/author_zombie_lord.py -- charge stun death` 生成（敌军动作 README 有说明）。Boss 没有奔跑：`run` clip 和奔跑机制都已移除。

## 行为与帧号

| 行为 | 实现 |
| --- | --- |
| 属性 | 300 生命、10 护甲、击退抗性 1、跨步 1 格、不受摔落伤害、不会消失、Boss 血条 |
| 移动 | 只走路：`walk`，0.125 格/tick（2.5 米/秒）。自定义 MoveControl 直接给定水平速度，平地上就是这个速度（已在测试服实测） |
| 连招触发 | 水平距离 ≤3 格、高度差 <2.5、看得见 |
| 第一、二段 | 前方 3 格、120° 扇形；第 10–15 帧每帧扫一片 20°（加 10° 余量），第一段从右往左，第二段从左往右；每个目标每段最多一次，伤害 6；每段挥砍前（第 1–10 tick）向前推进 1.5 格，离目标碰撞箱不足 0.5 格时停步，不会顶进目标；第三段同样推进；攻击中锁定朝向、除推进外不移动 |
| 一 → 二段 | 第一段第 20 帧时重新锁定一次目标位置，用 5 tick 从当前姿势过渡到第二段第 0 帧，同时把朝向转向新目标点 |
| 第三段 | 第二段完整播完才开始；第 20 帧以 Boss 为中心 5 格范围，伤害按距离从 10 线性降到 2，扬尘；无视盾牌（伤害类型 `zombie_lord_smash` 列在 `minecraft:bypasses_shield`） |
| 腾空砸地 | 两种情况会用 `leap_slam_inplace`：① 第二段结束时、或第三段第 5 帧之前，目标在第三段 5 格范围之外、12 格以内（按到目标近侧的距离算，和第三段判定一致；第三段过了第 5 帧，目标再走远也照常砸地）；② 追击中目标连续 3 秒（60 tick）不在攻击范围内（水平 >3 格或高度差 ≥2.5）且看得见，直接跳过去，不限距离。开始时锁定一次落点。起跳前的前摇（clip 第 0–11 帧）在游戏里放慢 50%，用 17 tick 播完（`ZombieLord.leapClipTime`），之后照常：第 17 tick 起跳、第 29 tick 落在落点，全长 54 tick；5 格范围、伤害 7→2，同样无视盾牌 |
| 冲撞 | 连续打完两轮连招（第一、二、三段算一轮；第三段被腾空砸地替代也算），第二轮结束后直接释放；腾空追击或地震召唤会把轮数清零。`charge` clip：第 0–18 帧前摇（下蹲蓄力、右脚刨地两下，前 12 帧会转向目标，之后锁定方向），第 18–30 帧是一个冲刺循环，第 30–57 帧是到达后的砸地和后摇。冲刺 16 tick、0.6 格/tick（约 9.6 格），撞到的目标各受 8 点伤害并被撞飞（生物和玩家不会挡住冲刺）。冲刺时地面留下焦黑的犁痕（客户端绘制，3 秒淡出），并扬起尘土和火星。冲满全程：立即刹步、巨剑从右侧抡过头顶劈进地面（同第三段的落点姿势），冲刺结束后第 7 tick（clip 第 37 帧）以 Boss 为中心 5 格范围、伤害按距离 7→2，无视盾牌，扬尘；之后回到架势 |
| 撞墙眩晕 | 冲刺中迎面撞上墙（这一 tick 沿冲刺方向前进不到 0.6×0.6 格且发生水平碰撞；斜着擦墙会贴墙滑过去，一格高的台阶会直接跨上去）就停下、不砸地，播放 `stun` clip（80 帧）：撞墙反弹、后退 1 格、踉跄，然后低头摇晃、剑尖垂地，最后甩头回到架势。整段 80 tick（4 秒）什么都不做、不转向；第 6–68 帧头顶有三颗火花绕圈（客户端粒子）。撞墙时有撞击音效、爆炸粒子和墙体碎屑 |
| 死亡 | 播放 `death` clip（60 帧：受创后仰、拄剑单膝跪地、颤抖、向前扑倒），不再用原版的侧倒；第 16 帧插剑、第 48 帧倒地各有音效，倒地扬尘；发光层随动画熄灭，红色死亡染色只保留开头 8 tick；第 70 tick 化作烟雾消失 |
| 地震召唤 | 两种触发，都只在两次动作之间（不打断连招）：① 每损失 40% 最大生命值一次（剩 60%、20% 时各一次，不受冷却限制；一次掉过两条线就连放两次，召唤中掉过的线在召唤结束后补放）；② 目标水平距离 >12 格且看得见，冷却 400 tick（召唤结束后起算）。第 21 帧插剑：地面震颤 40 tick（碎屑粒子＋客户端镜头抖动），脚下光圈从 0.6 格扩到 5 格，第 21/25/29 帧三圈冲击波；clip 冻结在第 40 帧。整个地震召唤期间（`QUAKE` 和 `QUAKE_END`）Boss 受到的伤害减少 80%（`/kill`、虚空等无视无敌帧的伤害不受影响） |
| 骷髅头 | 第 21 帧从地下钻出 3 个，绕 Boss 螺旋上升 40 tick 到头顶，然后第 61/121/181 帧（每 3 秒）各发射一个，落点是发射瞬间目标脚下的地面；飞行 20–40 tick（平均 0.55 格/tick）；飞行中客户端在落点画 3×3 警示框；落地 3×3 范围 2 点伤害并唤起一只复苏骷髅 |
| 恢复行动 | 所有骷髅头都落地且唤起的骷髅全部死亡，或最后一个骷髅头发射满 400 tick（20 秒）；之后播放 quake 第 40–50 帧，冷却 400 tick |
| 复苏骷髅 | 30 生命；铁剑、铁头盔、锁链胸甲/护腿/靴子，不掉落；出土 30 tick（1.5 秒）期间无 AI、无敌、不可选中；挥剑走内置 mob combat（Better Combat 剑动作），每两刀之后下一刀额外等 10 tick（0.5 秒） |
| 弹反 / 处决 | 复苏骷髅已内置进处决白名单（`BossEntities.BUILTIN_HUMANOIDS`，配置文件不需要改），被弹反眩晕时任何血量都可以处决（`execution/RiposteTarget`）。Boss 的伤害类型不是原版近战类型，所以弹反挡不住、也不会让 Boss 眩晕；Boss 不在处决白名单内 |

调试：`ZombieLord` / `LordSkull` / `LordSkeleton` 的 DEBUG 日志会记录每次动作切换（含世界时间）、每次命中和伤害、骷髅头发射与落地、骷髅每次挥剑。

## 修改边界

1. 帧号、距离、伤害都是 `ZombieLord` 顶部的常量，和上表一一对应；改 clip 时一起核对。
2. 服务端不读取 clip 文件，只按常量帧号结算；客户端只负责画。重导 clip 改了时长或关键帧时，要同步改常量。
3. Boss 自己控制朝向（`tickHeadTurn`）和水平速度（`travel`）；`BMCConfig` 把 `fiercerbattles:zombie_lord` 固定排除在 mob combat 之外，避免它给 Boss 补播 Better Combat 攻击动作。
4. 召唤阶段不存档：骷髅头不保存，重新载入后 Boss 从待机重新开始。

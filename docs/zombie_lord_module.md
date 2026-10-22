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
- 循环动作（walk/run）导出第 0…L 帧，客户端按「实际走过的距离 ÷ 动画设计速度」推进周期，脚不打滑。
- 游戏内整体放大 1.5 倍（`ZombieLord.RENDER_SCALE`，碰撞箱 0.9×2.9）。

重导命令（在仓库根目录；先导模型，clip 会核对物体缩放）：

```bash
blender --background --disable-autoexec "D:/code/mcmod/资源包/blender动画/敌军动作/zombie_lord.blend" --python-exit-code 1 --python tools/export_zombie_lord.py -- --model
blender --background --disable-autoexec "D:/code/mcmod/资源包/blender动画/敌军动作/动作/zombie_lord_attack1.blend" --python-exit-code 1 --python tools/export_zombie_lord.py -- --name attack1
```

游戏用到的 clip：`attack1`、`attack2`、`attack3`、`leap_slam_inplace`、`quake`、`walk`、`run`。

## 行为与帧号

| 行为 | 实现 |
| --- | --- |
| 属性 | 400 生命、10 护甲、击退抗性 1、跨步 1 格、不受摔落伤害、不会消失、Boss 血条 |
| 移动 | 目标 >12 格：`run`，0.3 格/tick（6 米/秒）；12 格内：`walk`，0.125 格/tick（2.5 米/秒）。自定义 MoveControl 直接给定水平速度，平地上就是这个速度（已在测试服实测） |
| 连招触发 | 水平距离 ≤3 格、高度差 <2.5、看得见 |
| 第一、二段 | 前方 3 格、120° 扇形；第 10–15 帧每帧扫一片 20°（加 10° 余量），第一段从右往左，第二段从左往右；每个目标每段最多一次，伤害 6；每段第 0–3 帧向前挪 0.5 格；攻击中锁定朝向、不移动 |
| 一 → 二段 | 第一段第 20 帧时重新锁定一次目标位置，用 5 tick 从当前姿势过渡到第二段第 0 帧，同时把朝向转向新目标点 |
| 第三段 | 第二段完整播完才开始；第 20 帧以 Boss 为中心 4 格范围，伤害按距离从 10 线性降到 2，扬尘 |
| 腾空砸地 | 第二段结束时、或第三段第 20 帧之前，目标水平距离在 3–10 格之间就改用 `leap_slam_inplace`：开始时锁定一次落点，第 11 帧起跳、第 23 帧落在落点，3 格范围、伤害 7→2 |
| 地震召唤 | 追击中目标连续 100 tick（5 秒）不在 3 格内、看得见、冷却结束时触发。第 21 帧插剑：地面震颤 40 tick（碎屑粒子＋客户端镜头抖动），脚下光圈从 0.6 格扩到 5 格，第 21/25/29 帧三圈冲击波；clip 冻结在第 40 帧 |
| 骷髅头 | 第 21 帧从地下钻出 3 个，绕 Boss 螺旋上升 40 tick 到头顶，然后第 61/71/81 帧（每 0.5 秒）各发射一个，落点是发射瞬间目标脚下的地面；飞行 20–40 tick（平均 0.55 格/tick）；飞行中客户端在落点画 3×3 警示框；落地 3×3 范围 2 点伤害并唤起一只复苏骷髅 |
| 恢复行动 | 所有骷髅头都落地且唤起的骷髅全部死亡，或最后一个骷髅头发射满 400 tick（20 秒）；之后播放 quake 第 40–50 帧，冷却 400 tick |
| 复苏骷髅 | 30 生命；铁剑、铁头盔、锁链胸甲/护腿/靴子，不掉落；出土 30 tick（1.5 秒）期间无 AI、无敌、不可选中；挥剑走内置 mob combat（Better Combat 剑动作），每两刀之后下一刀额外等 10 tick（0.5 秒） |
| 弹反 / 处决 | 复苏骷髅已内置进处决白名单（`BossEntities.BUILTIN_HUMANOIDS`，配置文件不需要改），被弹反眩晕时任何血量都可以处决（`execution/RiposteTarget`）。Boss 的伤害类型不是原版近战类型，所以弹反挡不住、也不会让 Boss 眩晕；Boss 不在处决白名单内 |

调试：`ZombieLord` / `LordSkull` / `LordSkeleton` 的 DEBUG 日志会记录每次动作切换（含世界时间）、每次命中和伤害、骷髅头发射与落地、骷髅每次挥剑。

## 修改边界

1. 帧号、距离、伤害都是 `ZombieLord` 顶部的常量，和上表一一对应；改 clip 时一起核对。
2. 服务端不读取 clip 文件，只按常量帧号结算；客户端只负责画。重导 clip 改了时长或关键帧时，要同步改常量。
3. Boss 自己控制朝向（`tickHeadTurn`）和水平速度（`travel`）；`BMCConfig` 把 `fiercerbattles:zombie_lord` 固定排除在 mob combat 之外，避免它给 Boss 补播 Better Combat 攻击动作。
4. 召唤阶段不存档：骷髅头不保存，重新载入后 Boss 从待机重新开始。

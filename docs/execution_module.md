# 双人处决模块速查

一句话：**服务器决定能否处决并固定两人的位置，客户端按同一个时间播放两人的动作，服务器最后击杀目标并恢复状态。**

Blender 中 `player` 是处决者，对应 `executioner`；`player_B` 是被处决者，对应 `victim`。

流程：`Blender → 导出两份动作 JSON → 战技键请求 → 服务器校验和摆位 → 同步播放 → 击杀和收尾`。

本说明涵盖玩家无敌、音效帧配置、复杂地形摆位、自由观看镜头，以及可从数据包注册的双人动作。通用采样核心、人形模型适配、动作定义和处决会话分层；目前支持 `humanoid_v1` 标准人形骨骼。

## 直接复用的入口

| 调用场景 | 入口 | 返回值与约束 |
| --- | --- | --- |
| 客户端按键，使用准星目标 | `FiercerBattlesExecutionClient.tryRequest(minecraft)` | `true` 表示输入已被接管或请求已发出；不代表服务器已经批准。仅客户端调用。 |
| 服务端已有玩家和目标 ID | `FiercerBattlesExecutionServer.tryStart(executor, targetEntityId)` | 服务端主线程调用；仍检查血量、可见身体的距离和场地等条件，`true` 才表示启动成功。准星目标由客户端拾取，服务端不重复检查旧朝向。 |
| 查询是否正在处决 | 对应端的 `isExecuting(entity)` | 客户端和服务器各查自己的会话；客户端输入拦截可用 `blocksInput()`。 |

两类入口择一接入。现有战技入口在 `FiercerBattlesClient.tryStartCombatSkill` 中优先调用 `tryRequest`；请求被拒绝后会恢复普通战技。服务端代码不要引用客户端类。

## 改什么，读什么

下表 Java 文件均位于 `src/main/java/com/fiercerbattles/`。

| 要改的内容 | 首先阅读的文件 | 职责 |
| --- | --- | --- |
| 血量、距离、可处决生物、音效帧、当前动作 ID | `config/fiercerbattles.json`；`config/FiercerBattlesConfig.java` | `execution` 配置；服务器以自己的配置为准。 |
| 启动条件、固定位置、无敌、音效、击杀、状态恢复 | `FiercerBattlesExecutionServer.java` | 管理整个处决会话；音效使用同一起始时间，每个帧只播放一次。玩家无敌按活动会话判定，结束自动解除。 |
| 按键拾取、播放时间、镜头、输入拦截 | `FiercerBattlesExecutionClient.java` | 按键时重新拾取目标，再接收会话并驱动显示。第一人称在 0.2 秒内平滑拉到第三人称；鼠标只转镜头，结束后恢复原视角模式。 |
| 拉远镜头、鼠标环绕 | `mixin/ExecutionCameraMixin.java`；`mixin/ExecutionMouseHandlerMixin.java` | 拉远距离先平滑再做原版镜头碰撞检查；鼠标沿用灵敏度和反转设置，仅改变独立镜头角度，不能改双人动画朝向。 |
| 客户端与服务器同步 | `execution/ExecutionNetwork.java`；`FiercerBattlesExecutionServer.java` | 同步已校验的动作定义与双方动画资源，再发送携带动作 ID、哈希、双方身份、位置、朝向和同一起始时间的 Start。 |
| 两人间距、朝向、结束落点 | `execution/ExecutionGeometry.java` | 与 Blender 对应的摆位参数、56 tick 时长和最终根位移。 |
| 范围、可见性、墙边或台阶摆位 | `execution/ExecutionPlacement.java` | 按眼睛到身体边界测距，验证可见身体；支持总共 2 格高低差；尝试不同方向、共同地面高度和最多 1 格的整体挪位，检查通道、实际移动路径、支撑及结束位置。 |
| 通用采样与播放时钟 | `animation/core/AnimationClip.java`；`animation/core/AnimationPlayer.java`；`animation/core/AnimationPoseContext.java` | 与处决流程、Minecraft 模型解耦；clip 负责验证、线性位置插值和四元数球面插值，player 按共享世界时间采样。 |
| 模型适配 | `animation/rig/HumanoidRig.java`；`animation/humanoid/HumanoidAnimationAdapter.java`；`animation/humanoid/AnimationModelPart.java` | 注册 `humanoid_v1` 六个身体通道并映射到玩家、僵尸和骷髅模型部件。新骨架需新增对应 rig 与 adapter。 |
| 处决渲染衔接 | `execution/action/PairedExecutionAction.java`；`execution/client/ExecutionRenderContext.java`；`mixin/Execution*Mixin.java` | 动作定义提供两份 clip、几何和时长；渲染上下文将当前动作采样结果交给模型适配器，保持父级变换、衣物/装备跟随和结束过渡。 |
| 数据包动作注册 | `execution/action/ExecutionActionRegistry.java`；`execution/action/PairedExecutionAction.java` | 从 `fiercerbattles_actions` 读取动作 JSON，再按 `fiercerbattles_animations` 资源 ID 读取双方 clip；缺资源、格式/通道/时长不匹配或超限时记录错误并跳过动作。 |

随模组内置的默认动作资源位于 `src/main/resources/data/fiercerbattles/` 下：动作定义在 `fiercerbattles_actions/execution.json`，采样 clip 在 `fiercerbattles_animations/execution/`。Blender 导出工具仍将原始验证用资源保存在 `assets/fiercerbattles/execution/`，并同步更新内置数据包 clip。

导出工具是 `tools/export_execution.py`；源文件是 `D:/code/mcmod/资源包/blender动画/处决/execution.blend`。重导默认动作时需一起核对定义里的几何参数和时长。处决入口仍是服务端 `tryStart`；注册新动作不需要改 Java 代码。

## 用数据包注册双人动作

在世界的 `datapacks/<包名>/` 中建立以下资源。压缩包数据包使用同样的目录布局：

```text
data/<命名空间>/fiercerbattles_actions/<动作路径>.json
data/<命名空间>/fiercerbattles_animations/<动画路径>.json
```

例如动作定义 `data/my_pack/fiercerbattles_actions/finisher.json` 会注册为 `my_pack:finisher`。定义内容：

```json
{
  "rig": "fiercerbattles:humanoid_v1",
  "duration_ticks": 56,
  "executor_clip": "my_pack:finish/executor",
  "victim_clip": "my_pack:finish/victim",
  "geometry": {
    "victim_right": -0.16363336145877838,
    "victim_forward": 1.3832365274429321,
    "victim_yaw_delta": 180.0,
    "executor_end_right": 0.0,
    "executor_end_forward": 0.4798867106437683,
    "executor_end_yaw_delta": 86.124866
  }
}
```

上例的 clip 文件路径分别是 `data/my_pack/fiercerbattles_animations/finish/executor.json` 与 `victim.json`。clip 使用 Blender 导出器现有格式：`format: 1`、`duration_ticks`、`sample_step` 和 `parts`；每个部件是按时间排列的 7 数值采样 `[x,y,z,qx,qy,qz,qw]`，位置单位为模型像素。当前 rig 要求两份 clip 都有 `head`、`body`、`rightArm`、`leftArm`、`rightLeg`、`leftLeg` 六个通道，且采样时长与动作定义相等。可以直接复制默认 JSON 后替换动作采样；`sample_step` 必须能均匀覆盖从 0 到 `duration_ticks` 的时间。

`geometry` 的 `victim_right` / `victim_forward` 是被处决者相对处决者朝向的水平偏移（格），`victim_yaw_delta` 是朝向差；`executor_end_*` 是正常结束时处决者的相对水平落点和朝向变化。单个水平偏移绝对值上限为 16 格。动作期间服务端固定双方实体根位置，因此根骨水平运动必须已烘焙进 clip，不能再由渲染端推动实体。换动作时必须核对这组数值和烘焙坐标，避免双人接触错位。

把 `execution.action_id` 设成 `my_pack:finisher`（首次使用时加到 `config/fiercerbattles.json` 的 `execution` 对象中；内置默认值为 `fiercerbattles:execution`），然后进入世界执行 `/reload`。处决仍由现有准星目标、血量和允许实体配置触发，只是改用所选动作。有效动作在重载时原子注册；资源缺失、JSON 无效、不支持的 rig、采样超限、通道不全或时长不一致都会记入日志并跳过，其他有效动作照常加载。服务端在播放前向当前维度玩家压缩同步动作定义和两份 clip；客户端按 SHA-256 校验并缓存，因此其他玩家无需单独安装动画资源。

该接口注册的是供处决流程调用的**成对人形动作**，不是任意实体/任意骨架播放器命令。通用 clip 播放核心可由其他系统复用；换成非标准人形骨架前，必须为其实现 rig 通道约定及 Minecraft 客户端模型适配器。

## 配置能完成的修改

`execution.enabled` 开关；`health_threshold: 0.25` 表示严格低于 25% 血量；`range: 3.0` 为范围；`humanoid_entities` 默认包含僵尸和骷髅。修改服务端配置后重启加载。

`hurt_sound_frames` 默认 `[12, 20, 24, 28, 32, 36, 40, 48]`，播放被处决者自身的受伤声；`swing_sound_frames` 默认 `[12, 24]`，播放原版挥剑声。源动画为 20 帧/秒，一帧对应一 tick。每组配置为 `[]` 可禁用；重复帧去重，列表中的空值和 `0–56` 外的帧忽略，缺少字段或字段为 `null` 使用默认值。音效仅改变声音，不造成中途伤害或动作变化；`mixin/ExecutionLivingEntityInvoker.java` 读取生物自己的受伤音效。

增加生物时，白名单应选择拥有标准人型模型的成年怪物。服务器还要求双方站立、着地、没有骑乘或入水等状态，且摆位和结束位置有空间。台阶可在总共 2 格高低差内对齐到共同的地面高度；候选高度包括双方原高度和中间的台阶、半砖表面。场地检查沿两人之间的通道进行，移动使用短段连续碰撞检查，避免把旁边未经过的矩形角落当成障碍；双方仍需稳定落脚且结束位置安全。仅加入 ID 并不能让任意非人型模型适配此动作。

## 防止错位的四条规则

1. **一起摆位、一起计时。** 保留服务器的双方固定位置与 Start 的 `startGameTime`，不要让两个角色分别启动计时。
2. **保留源动画的相对位置。** 被处决者在处决者局部右方约 `-0.163633` 格、前方约 `1.383237` 格，朝向相差 `180°`；偏移随整体朝向旋转。直接改成正面对齐会破坏手与身体的接触位置。
3. **根位移只计算一次。** 播放期间实体位置固定，移动已经烘焙在模型动画里；不要每 tick 再把动画位移加到实体上。保留播放时统一的模型缩放、已烘焙的父级变换，以及衣服和装备的跟随处理，避免叠加普通动画变换。
4. **结束姿势交接给实体。** 成功结束时将处决者移到最终水平落点（局部前方约 `0.479887` 格，朝向增加约 `86.124866°`），保留地面高度；随后用 4 tick 过渡回普通姿势。被处决者保留结束姿势 20 tick。完成和中止都需要恢复重力、AI 与输入状态。

新增另一套双人动作时，需要扩展动作选择、双方几何与时长的传递，继续使用会话同步和渲染流程。只换一个资源 ID 不足以完成适配。

## 给后续 AI 的一句话

> 先读 docs/execution_module.md；复用现有双人处决模块完成【具体需求】。按文件索引只读相关源码，保留四条防错位规则，区分客户端请求与服务端成功启动。

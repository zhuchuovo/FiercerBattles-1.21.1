# 双人处决模块速查

一句话：**服务器决定能否处决并控制两人的移动和锚点，客户端按同一个时间播放两人的动作，服务器在原动画结束时击杀目标并恢复状态，客户端完成姿势复位后恢复镜头和输入。**

Blender 中 `player` 是处决者，对应 `executioner`；`player_B` 是被处决者，对应 `victim`。

流程：`Blender → 导出两份动作 JSON → 处决键（默认 V）请求 → 服务器校验武器并选择动作和摆位 → 5 tick 开场移动与姿态衔接 → 原动画同步播放 → 击杀与 5 tick 复位 → 恢复镜头和控制`。

本说明涵盖玩家无敌、音效帧配置、复杂地形摆位、自由观看镜头，以及可从数据包注册的双人动作。通用采样核心、人形模型适配、动作定义和处决会话分层；目前支持 `humanoid_v1` 标准人形骨骼。

## 直接复用的入口

| 调用场景 | 入口 | 返回值与约束 |
| --- | --- | --- |
| 客户端按键，使用准星目标 | `FiercerBattlesExecutionClient.tryRequest(minecraft)` | `true` 表示输入已被接管或请求已发出；不代表服务器已经批准。仅客户端调用。 |
| 服务端已有玩家和目标 ID | `FiercerBattlesExecutionServer.tryStart(executor, targetEntityId)` | 服务端主线程调用；仍检查血量、可见身体的距离和场地等条件，`true` 才表示启动成功。准星目标由客户端拾取，服务端不重复检查旧朝向。 |
| 查询是否正在处决 | 对应端的 `isExecuting(entity)` | 客户端和服务器各查自己的会话；客户端输入拦截可用 `blocksInput()`。 |

两类入口择一接入。`FiercerBattlesClient.EXECUTION_KEY` 默认 V，可在控制设置重绑，客户端 tick 调用 `tryRequest`。战技键 G 只释放战技；处决请求被拒绝只清除等待状态。客户端和服务端均通过 `execution/ExecutionWeapons.java` 检查主手：原版六种剑、匠魂 `sword` / `cleaver` 使用第一套；原版六种斧、匠魂 `hand_axe` / `broad_axe` / `minotaur_axe` 使用第二套；空手、匕首、鹤嘴锄、投斧弹药及其他模组武器不触发。匠魂板斧的 Better Combat 类别虽为 `sword`，处决仍按注册 ID 归为斧。服务端代码不要引用客户端类。

处决与近战弹反/被反弹互斥。`parry/ParryServer.isBusy(entity)` 的任一参与者不能开始处决，弹反也不能覆盖正在处决的实体；客户端请求同样阻止已有动作互相覆盖。弹反使用独立 R 键，完整规则见 [弹反模块速查](parry_module.md)。

共用模型渲染入口现还路由弹反：`execution/client/ExecutionRenderContext` 有处决 actor 时保持原流程，没有时委托 `parry/client/ParryRenderContext`；`ExecutionLivingEntityRendererMixin` 建立对应作用域，处决优先，单次姿势不叠加两套动作。其渲染 `isActive()` 因而可以表示任一动作源，服务端互斥及玩法判断仍应使用各模块的会话查询，不把它等同于处决中。`AnimationClip` / `AnimationPlayer` 及既有处决资源不变。

## 改什么，读什么

下表 Java 文件均位于 `src/main/java/com/fiercerbattles/`。

| 要改的内容 | 首先阅读的文件 | 职责 |
| --- | --- | --- |
| 血量、距离、可处决生物、音效帧、剑/斧动作 ID | `config/fiercerbattles.json`；`config/FiercerBattlesConfig.java` | `execution.action_id` 保留为剑动作；`axe_action_id` 为斧动作，旧配置缺少新字段时自动使用默认斧动作。剑受伤声沿用配置，斧受伤声固定在原动画第 13 帧。服务器以自己的配置为准。 |
| 武器限制、独立处决键 | `execution/ExecutionWeapons.java`；`FiercerBattlesClient.java` | 共用注册 ID 分类；`EXECUTION_KEY` 默认 V，和默认 G 的 `COMBAT_SKILL_KEY` 独立。 |
| 启动条件、开场移动、固定锚点、无敌、音效、击杀、状态恢复 | `FiercerBattlesExecutionServer.java` | 管理服务端处决会话；音效使用原动画起始时间，每个帧只播放一次。原动画结束时交接最终实体根位置并恢复 AI、重力和无敌状态；客户端随后另做 5 tick 视觉复位。 |
| 按键拾取、失败提示、播放时间、镜头、输入拦截 | `FiercerBattlesExecutionClient.java` | 优先使用画面准星已选中的活体目标，没有时再做六格扩展拾取，避免渲染插值与 tick 射线不一致。主手武器不支持、无目标或服务端拒绝时显示 actionbar 原因，并记录 INFO 日志。第一人称在 0.2 秒内平滑拉到第三人称；鼠标只转镜头，正常结束的 5 tick 复位完成后恢复原视角模式和输入。 |
| 拉远镜头、鼠标环绕 | `mixin/ExecutionCameraMixin.java`；`mixin/ExecutionMouseHandlerMixin.java` | 拉远距离先平滑再做原版镜头碰撞检查；鼠标沿用灵敏度和反转设置，仅改变独立镜头角度，不能改双人动画朝向。 |
| 客户端与服务器同步 | `execution/ExecutionNetwork.java`；`FiercerBattlesExecutionServer.java` | 同步已校验的动作定义与双方动画资源，再发送携带动作 ID、哈希、双方身份、开场起点、动画锚点、朝向和同一起始时间的 Start；原动画的 `startGameTime` 为请求成功的世界 tick 加 5。 |
| 两人间距、朝向、结束落点 | `execution/action/PairedExecutionAction.java`；`data/fiercerbattles/fiercerbattles_actions/*.json` | 每个动作自己的 `geometry` 与 `duration_ticks`，沿用同一摆位和播放机制。`ExecutionGeometry.java` 仅保留第一套的旧默认参数。 |
| 范围、可见性、墙边或台阶摆位 | `execution/ExecutionPlacement.java` | 按眼睛到身体边界测距，验证可见身体；支持总共 2 格高低差；尝试不同方向、共同地面高度和最多 1 格的整体挪位，检查通道、实际移动路径、支撑及结束位置。 |
| 处决阶段时间与衔接 | `execution/ExecutionTimeline.java`；`FiercerBattlesExecutionServer.java`；`FiercerBattlesExecutionClient.java` | 统一管理 5 tick 开场、原动画阶段和 5 tick 视觉复位；两端共用 `approachPosition`，沿原 `safeTravel` 验证的先升高、再平移、后下降路径到达锚点，普通姿势渐变到首帧。新增时间不计入 clip 帧号，不修改动画资源或台阶碰撞规则。 |
| 通用采样与播放时钟 | `animation/core/AnimationClip.java`；`animation/core/AnimationPlayer.java`；`animation/core/AnimationPoseContext.java` | 与处决流程、Minecraft 模型解耦；clip 负责验证、线性位置插值和四元数球面插值，player 按共享世界时间采样。沿用现有动画核心和原始采样。 |
| 模型适配 | `animation/rig/HumanoidRig.java`；`animation/humanoid/HumanoidAnimationAdapter.java`；`animation/humanoid/AnimationModelPart.java` | 注册 `humanoid_v1` 六个身体通道并映射到玩家、僵尸和骷髅模型部件。新骨架需新增对应 rig 与 adapter。 |
| 处决渲染衔接 | `execution/action/PairedExecutionAction.java`；`execution/client/ExecutionRenderContext.java`；`mixin/Execution*Mixin.java` | 动作定义提供两份 clip、几何和时长；渲染上下文将当前动作采样结果交给模型适配器，保持父级变换、衣物/装备跟随和结束过渡。 |
| 数据包动作注册 | `execution/action/ExecutionActionRegistry.java`；`execution/action/PairedExecutionAction.java` | 从 `fiercerbattles_actions` 读取动作 JSON，再按 `fiercerbattles_animations` 资源 ID 读取双方 clip；缺资源、格式/通道/时长不匹配或超限时记录错误并跳过动作。 |

随模组内置的两套动作资源位于 `src/main/resources/data/fiercerbattles/` 下：剑定义 `fiercerbattles_actions/execution.json` / clip `fiercerbattles_animations/execution/`，56 tick（2.8 秒）；斧定义 `fiercerbattles_actions/axe_execution.json` / clip `fiercerbattles_animations/axe_execution/`，30 tick（1.5 秒）。Blender 导出工具将验证用资源保存在 `assets/fiercerbattles/execution/` 或 `axe_execution/`，并同步更新对应数据包 clip。

上述时长只包含原动画。请求成功后先播放 5 tick（0.25 秒）开场，再从原动画第 0 帧开始，原动画完成后进行 5 tick（0.25 秒）复位。正常完整流程为剑 66 tick（3.3 秒）、斧 40 tick（2 秒）；`duration_ticks` 与原动画的帧号保持不变，音效仍按原动画帧号计时。

导出工具是 `tools/export_execution.py`；源文件分别是 `D:/code/mcmod/资源包/blender动画/处决/execution.blend` 和同目录的 `axe_execution.blend`。默认参数仍导出剑；斧使用 `-- --name axe_execution --duration 30 --step 0.0625`，回算时追加 `--verify`。重导时需一起核对动作定义里的几何参数和时长。斧双方偏移为 right=-0.1636333615、forward=0.9793289900 格，朝向差约 -180°；结束水平位移为 0、朝向变化 -144.676503°。处决入口仍是服务端 `tryStart`；注册新动作不需要改播放器。

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

`geometry` 的 `victim_right` / `victim_forward` 是被处决者相对处决者朝向的水平偏移（格），`victim_yaw_delta` 是朝向差；`executor_end_*` 是正常结束时处决者的相对水平落点和朝向变化。单个水平偏移绝对值上限为 16 格。5 tick 开场到达锚点后，原动画阶段由服务端固定双方实体根位置，因此根骨水平运动必须已烘焙进 clip，不能再由渲染端推动实体。换动作时必须核对这组数值和烘焙坐标，避免双人接触错位。

把剑用的 `execution.action_id` 或斧用的 `execution.axe_action_id` 设成 `my_pack:finisher`（首次使用时加到 `config/fiercerbattles.json` 的 `execution` 对象中；内置默认值分别为 `fiercerbattles:execution` 和 `fiercerbattles:axe_execution`），重启以读取配置，然后进入世界执行 `/reload`。处决仍由主手剑/斧、独立处决键、准星目标、血量和允许实体配置触发，只是改用对应所选动作。有效动作在重载时原子注册；资源缺失、JSON 无效、不支持的 rig、采样超限、通道不全或时长不一致都会记入日志并跳过，其他有效动作照常加载。服务端在播放前向当前维度玩家压缩同步动作定义和两份 clip；客户端按 SHA-256 校验并缓存，因此其他玩家无需单独安装动画资源。

该接口注册的是供处决流程调用的**成对人形动作**，不是任意实体/任意骨架播放器命令。通用 clip 播放核心可由其他系统复用；换成非标准人形骨架前，必须为其实现 rig 通道约定及 Minecraft 客户端模型适配器。

## 配置能完成的修改

`execution.enabled` 开关；`health_threshold: 0.25` 表示严格低于 25% 血量；`range: 3.0` 为范围；`humanoid_entities` 默认包含僵尸和骷髅。修改服务端配置后重启加载。

剑的 `hurt_sound_frames` 默认 `[12, 20, 24, 28, 32, 36, 40, 48]`，播放被处决者自身的受伤声；可改为 `[]` 禁用。斧不使用该列表，固定只在原动画第 13 帧播放一次受伤声；没有额外的斧受伤声多帧配置。服务器按主手武器分类调用 `hurtSoundFrames(durationTicks, axe)`，即使 `axe_action_id` 指向自定义动作仍使用该规则；动作短于 13 tick 时忽略这一帧。

`swing_sound_frames` 默认 `[12, 24]`，播放原版挥剑声，两套动作都使用该列表，`[]` 可禁用。源动画为 20 帧/秒，一帧对应一 tick；音效帧相对于原动画第 0 帧，开场 5 tick 不计入，因此斧受伤声发生在原动画开始后 0.65 秒、请求成功后 0.9 秒。配置列表中的重复帧去重，空值和超出所选原动画时长的帧忽略（剑 `0–56`、斧 `0–30`），缺少字段或字段为 `null` 使用默认值。音效仅改变声音，不造成中途伤害或动作变化；`mixin/ExecutionLivingEntityInvoker.java` 读取生物自己的受伤音效。

增加生物时，白名单应选择拥有标准人型模型的成年怪物。服务器还要求双方站立、着地、没有骑乘或入水等状态，且摆位和结束位置有空间。台阶可在总共 2 格高低差内对齐到共同的地面高度；候选高度包括双方原高度和中间的台阶、半砖表面。场地检查沿两人之间的通道进行，移动使用短段连续碰撞检查，避免把旁边未经过的矩形角落当成障碍；双方仍需稳定落脚且结束位置安全。仅加入 ID 并不能让任意非人型模型适配此动作。

## 防止错位的四条规则

1. **一起摆位、一起计时。** 保留 Start 的双方开场起点、动画锚点与 `startGameTime`；后者是请求成功 tick 加 5。双方共享 `ExecutionTimeline`，先用 5 tick 移动并衔接到首帧，再按同一时间播放原动画，不要让两个角色分别启动计时。
2. **保留源动画的相对位置。** 两套动作右方均约 `-0.163633` 格，剑动作前方约 `1.383237` 格、斧动作前方约 `0.979329` 格，朝向相差约 `180°`；读取各自 `action.geometry()`，偏移随整体朝向旋转。直接改成正面对齐会破坏手与身体的接触位置。
3. **根位移只计算一次。** 开场移动只负责从起点到锚点；原动画阶段实体位置固定，运动已经烘焙在模型动画里，不要每 tick 再把动画位移加到实体上。保留播放时统一的模型缩放、已烘焙的父级变换，以及衣服和装备的跟随处理，避免叠加普通动画变换。
4. **结束姿势交接给实体。** 原动画成功结束时读取该动作的 `executor_end_*`：剑沿局部前方约 `0.479887` 格、朝向增加约 `86.124866°`；斧水平位移为 0、朝向变化约 `-144.676503°`。保留地面高度，同时恢复服务端重力、AI 和无敌状态；客户端随后用 5 tick 过渡回普通姿势和比例，复位完成后恢复镜头与输入。视觉复位不延长服务端无敌。被处决者保留结束姿势 20 tick；中止时及时清理服务端状态和客户端控制。

新增另一套双人动作时，在现有动作定义中提供各自几何与时长，并接入所需动作选择；继续使用已有 clip、播放器、会话同步和渲染流程。只换资源 ID 而不核对源动画几何与时长会造成错位。

## 给后续 AI 的一句话

> 先读 docs/execution_module.md；复用现有双人处决模块完成【具体需求】。按文件索引只读相关源码，保留四条防错位规则，区分客户端请求与服务端成功启动。

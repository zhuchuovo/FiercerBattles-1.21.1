# 近战弹反模块速查

主手持原版或匠魂的剑、斧，站在地面上按独立弹反键（默认 **R**，控制设置可重绑）播放弹反动作，并在启动位置广播一次起手音效 `fiercerbattles:parry_use`。动作参考《黑暗之魂 3》：副手反手外挥弹开攻击，被弹反者弯腰眩晕、可被反击处决。武器检查复用 `execution/ExecutionWeapons.java` 的注册 ID 分类，与战技 G、处决 V 分开。弹反者全程（含复位）只在水平面上锁定启动位置，禁止移动、跳跃、冲刺、潜行及击退、惯性造成的水平位移；垂直方向完全不接管，重力、垂直速度与坠落距离照常，落地伤害也照常结算，结束后立即交还移动。仍可转向，镜头模式不变。骑乘或未站在地面时不能启动。客户端从发出请求起立即锁定水平位置，拒绝或超时后解除等待锁定。

服务器在原动画第 **5–10 tick，包含两端** 接受弹反。窗口内符合条件的近战伤害被取消，攻击者进入 `STAGGER`，随后受到 `parry.counter_damage`（默认 10）点 `fiercerbattles:parry_counter` 固定伤害（无视护甲与受伤冷却、无击退），并在弹反者位置广播 `fiercerbattles:parry` 音效。`STAGGER` 期间目标可被处决，血量门槛取 `max(execution.health_threshold, parry.stagger_execution_health_threshold)`（默认 60%）。同一窗口可处理多个攻击者。当前近战来源要求直接伤害实体就是攻击者，类型为 `PLAYER_ATTACK`、`MOB_ATTACK`、`MOB_ATTACK_NO_AGGRO` 或 `STING`；环境伤害、投射物及处决伤害不触发成功弹反。窗口外没有弹反保护。

例外是 Boss 自己的招式（`parry/BossParryTarget`，目前是钢铁卫戍）：直接伤害实体是 Boss、伤害类型在 `fiercerbattles:boss_parryable` 里的一击，同样在窗口内被取消、播放弹反音效和反击伤害，但不进入人形 `STAGGER`（Boss 的模型不是六部位人形），而是调用 `BossParryTarget.parried`，由 Boss 播放自己的眩晕动作；能否处决由 Boss 自己决定（见 [钢铁卫戍速查](iron_sentinel_module.md)）。

## 时间与姿势衔接

普通攻击和战技均只允许在尚未执行伤害判定的前摇中取消为弹反，或等待攻击动画播放完再弹反。已经判定但动画尚未结束时，R 键静默拒绝，不锁移动、不取消攻击；挥空也算判定，多段攻击从第一次判定起关闭取消窗口。客户端优先读取 Better Combat 攻击层的实际 `isActive()`，不可读时使用捕获的动画时长；不会把额外攻击冷却当作动画。服务端 `ParryAttackWindow` 使用动画时长与伤害阶段校验，客户端在实际播放结束时先同步结束阶段，再发送弹反请求，避免变速或网络时差造成结束后仍被拦截。网络版本为 `parry-3`。

| 状态 | 时间 | 行为 |
| --- | --- | --- |
| `PARRY` | 原动画 18 tick + 复位 5 tick，共 23 tick | 最初 3 tick 从普通姿势淡入，这 3 tick 已包含在原动画时钟中；第 5–10 tick 为有效窗口。整个 23 tick 只锁水平坐标并清零水平速度，垂直坐标、垂直速度、坠落距离和重力一律不接管；保留转向；复位结束交还移动和普通手部动作。 |
| `STAGGER` | 历史倒放 5 tick + 原动画第 2–40 帧共 39 tick + 复位 5 tick，共 49 tick | 先倒放攻击者最近 5 tick 的实际模型姿势，再从原眩晕动画第二帧（源帧 1 / tick 1）播放到末帧；首帧跳过。前 3 tick 与倒放末端衔接，计入 39 tick。整个 49 tick 由服务器禁止动作、移位并接管 AI；处决可随时接管，`ParryServer.releaseStagger` 先恢复 AI/重力再建立处决会话。 |
| `HITSTUN` | 固定 `HITSTUN_TICKS` 10 tick，原动画（5 tick）按 `playbackRate` 拉伸，无额外复位 | 玩家和人形生物受到有攻击来源的实际伤害时播放。片段第 0–1 tick（游戏第 0–2 tick）从普通姿势淡入，片段第 3–5 tick（游戏第 6–10 tick）在片段内淡回普通姿势，片段结束即交还控制。40 tick 内第 3 次硬直起 20 tick 内不再硬直（`ParryServer.Poise`，`HITSTUN_CHAIN_*`）。期间禁止动作和移动输入，但不锁位置、不取消击退。 |

倒放使用标准人形 `head/body/rightArm/leftArm/rightLeg/leftLeg` 六部位的真实渲染矩阵历史，包含当时的外部攻击动画和部件缩放。需要连续 6 个游戏 tick 的完整快照，才能形成最近 5 tick 的倒放；多次渲染同一 tick 不增加历史长度。首次出现、历史过期、不连续、部位不全或第一人称没有渲染本体时，明确记录 INFO 的 `ordinary fallback`，改用普通姿势过渡，不伪造攻击历史。`stagger` 正放从第二帧开始，不把其自身动画作为倒放素材。

PlayerAnimator 的局部 `BEND` 轴、角度与对应矩阵绑定在同一 tick，倒放时一起还原；进入原 `stagger` 的前三 tick 保留轴方向，将弯曲角度淡至 0。`PlayerAnimatorBendBridge` 通过可选后端应用网格弯曲，躯干按 PlayerAnimator 的 `torso + body` 合并规则捕获。没有 BendyLib 时 `DummyBendable` 安全空操作，继续矩阵回放，不强制安装依赖；实际网格弯曲需在具备后端的环境另行验证，不能用矩阵断言代替逐顶点验证。

第一人称手和持物通过相对手臂变换显示弹反及被反弹衔接，第三人称使用六部位模型姿势；不接管镜头。只有 `STAGGER` 停用重力，并把原重力状态保存到 `fiercerbattles:parry_restore`，同时接管并恢复 AI；`PARRY` 从不停用重力，因此不写恢复标签。结束或中止时恢复，断线、死亡、维度变化和遗留恢复标签均需清理。

## 入口与文件索引

以下 Java 路径均相对 `src/main/java/com/fiercerbattles/`。

| 要改的内容 | 文件或入口 | 约束 |
| --- | --- | --- |
| 按键、请求 | `FiercerBattlesClient.PARRY_KEY`；`parry/client/ParryClient.tryRequest(minecraft)` | 默认 R；请求等待及已有动作期间不重复启动；在空中按键时静默拒绝。 |
| 服务端启动、伤害裁定、状态恢复 | `parry/ParryServer.java`；`tryStart(ServerPlayer)`、`canParry(Entity)`、`isBusy(Entity)`、`isStaggered(Entity)`、`isMovementLocked(Entity)` | 在服务端主线程调用；有效窗口由服务器世界时间判定。`tryStart` 要求 `player.onGround()`，否则静默拒绝。成功弹反保留音效、反击伤害和眩晕，不显示成功文字。`PARRY`、`STAGGER` 全程锁水平位置并取消击退；只有 `STAGGER` 额外锁朝向、锁垂直位置并接管 AI/重力。反击伤害在 `ParryServer.counter`，先进入 `STAGGER` 再结算，`COUNTER_DAMAGE` 标记让它绕过本模块的 busy 拒绝。 |
| 反击处决（眩晕中处决） | `FiercerBattlesExecutionServer.start`；客户端 `ParryClient.takeReleasedPose` → `FiercerBattlesExecutionClient.Playing.victimStartPose` → `ExecutionRenderContext.transform` | 只放行 `isStaggered` 的目标，其余 busy 仍拒绝；释放眩晕必须在 `Session` 记录 AI/重力之前。客户端在眩晕 Stop 时记录当刻姿势，5 tick 内开始的处决用它作为接近段起点。 |
| 弹反音效、反击伤害类型 | `sound/ModSounds.PARRY`、`assets/fiercerbattles/sounds.json`、`sounds/parry/parry_clang.ogg`；`data/fiercerbattles/damage_type/parry_counter.json` 与 `data/minecraft/tags/damage_type/` | 成功弹反音效由提供的 `Voicy_ Parry #1.mp3` 转为单声道 48 kHz OGG Vorbis，保留完整约 4.3 秒；起手音效 `sounds/parry/parry_use.ogg` 由提供的 `使用弹反.mp3` 转为单声道 44.1 kHz OGG Vorbis（约 0.31 秒），注册为 `sound/ModSounds.PARRY_USE`，只在 `PARRY` 启动成功时广播一次。伤害类型列入 `bypasses_armor`、`bypasses_cooldown`、`no_knockback`。 |
| 原动画时钟、窗口与过渡长度 | `parry/ParryTimeline.java` | `IN_TICKS=3`、`REVERSE_TICKS=5`、`RETURN_TICKS=5`；窗口包含 tick 5 和 10。过渡不能再次推迟原窗口。 |
| 数据包重载、压缩同步、哈希校验 | `parry/ParryAnimations.java`；`parry/ParryNetwork.java` | 独立读取两份弹反 clip，校验 rig、时长、根参数和同步大小；先同步 bundle，再发送带世界时间、身份和 hash 的 Start。 |
| 客户端播放、锁定与清理 | `parry/client/ParryClient.java` | 复用 `AnimationPlayer`；`Kind.PARRY/STAGGER` 决定阶段，不覆盖处决会话。 |
| 实际姿势捕获、倒放与插值 | `parry/client/ParryPoseHistory.java`；`mixin/ParryPoseCaptureMixin.java` | 捕获普通渲染后的六部位矩阵和同 tick BEND；冻结时排除晚于命中时刻的快照。保留每轴缩放和完整矩阵回退，缺历史时明确记录 fallback。 |
| PlayerAnimator 局部弯曲 | `parry/client/PlayerAnimatorBendBridge.java`；`ParryRenderContext.applyBend` | 可选反射 `AnimationProcessor.getBend` / `IBendHelper.bend`；在 `ModelPart.render` 入口应用，避免矩阵回放取消 `translateAndRotate` 时漏掉网格弯曲。第一人称也有独立作用域，Dummy 后端安全空操作。 |
| 第三人称模型、衣物和装备路由 | `parry/client/ParryRenderContext.java`；`execution/client/ExecutionRenderContext.java`；`mixin/ExecutionLivingEntityRendererMixin.java` | 共用渲染入口，处决优先；单次模型变换只由一个动作源提供。弹反、眩晕和受击硬直的 clip 全程按普通实体缩放转换到共享根，玩家保留 `0.9375 × entity.getScale()`，位置同时按脚底锚点换算，开始与结束只混合姿势，不混合到更大的模型尺寸。倒放衔接和反击处决起点沿用相同转换；第一人称普通弹反继续使用原手臂相对变换。完整历史矩阵和 clip 局部采样不能重复叠加实体根。 |
| 第一人称手与持物 | `mixin/ParryFirstPersonMixin.java` | 在已有手部渲染上应用相对手臂变化，保留原持物流程和镜头。 |
| 已排队 Better Combat 攻击 | `mixin/ParryServerNetworkMixin.java` | 请求入口、排队任务和攻击动画都要拦截；任务保存 `attackEpoch`，弹反/被反弹改变 epoch 后丢弃旧任务，避免动作结束后旧攻击复活。 |
| 原版攻击与伤害兜底 | `mixin/ParryPlayerAttackMixin.java`；`mixin/ParryMobAttackMixin.java`；`parry/ParryServer.java` | 分别守住 `Player.attack`、`Mob.doHurtTarget` 和已进入伤害循环的剩余命中；保留外部攻击任务的属性清理。 |
| 弹反及被反弹玩家移动 | `mixin/ParryPlayerMovementMixin.java`；`mixin/ClientPlayerMixin.java`；`ParryClient.lockMovement` | `Kind.locksMovement()` 统一客户端与服务端锁定策略，`Kind.locksVertical()` 单独决定是否接管垂直方向，只有 `STAGGER` 为真。`PARRY` 移动包转换为水平取服务端坐标、高度取客户端坐标的 `PosRot` 包，保留原版朝向校验和落地状态；`STAGGER` 拒绝移动包。客户端 `LocalPlayer.move` 只丢弃水平位移，垂直分量照常交给原版重力；tick 前后清水平速度和锁水平坐标，请求等待期间也锁定。`HITSTUN` 保留击退。 |
| 受击硬直触发与霸体 | `parry/ParryServer.damaged`（`LivingDamageEvent.Post`）、`ParryServer.guard`；客户端 `ParryClient.reportSwing/clearSwing`，由 `mixin/AbstractClientPlayerMixin`（挥击开始）和 `mixin/MinecraftMixin`（`cancelWeaponSwing`）调用 | 客户端在每段挥击开始时发送 `hit_guard`（未指定/可打断/霸体 + 挥击 tick 数 + 动画 tick 数），判定与实际播放结束使用 `parry_attack_phase` 同步；服务端也在 Better Combat 排队攻击任务入口记录判定。未指定打断方式时用 `hitstun.default_hit_interruptible`，取值解析见 `FiercerBattlesRuntime.currentHitInterruptible`。硬直中的攻击者只拒绝近战伤害。人形生物（`hitstun.humanoid_mobs`，范围取 `execution.humanoid_entities` 中的成年生物）同样会硬直：`ParryServer.begin` 对生物调用 `MobCombatLogic.interrupt` 取消前摇与延迟命中并停止寻路，`MobCombatLogic.beginAttack` / `beginDelayedFallbackMeleeAttack` 在 `ParryServer.blocksActions` 期间不发起攻击，客户端 `ParryClient.start` 通过 `MobAnimationAccess.bmc$stopAttackAnimation` 停掉生物的攻击动作。 |

处决与弹反/被反弹互斥：处决不能选用 busy 的任一参与者；弹反也不能覆盖正在处决的实体。服务端判断使用各模块的会话查询，不能把渲染作用域的 `isActive()` 当作处决状态。相关处决入口见 [双人处决模块速查](execution_module.md)。

## 动画资源与只读导出

当前源文件为 `D:/code/mcmod/资源包/blender动画/弹反/黑魂弹反.blend`（`parry`，关键帧 0–18）和同目录 `黑魂眩晕.blend`（`stagger`，关键帧 0–40），都以 `被反弹.blend` 的 `Bone_A` 单人人形网格 rig 重新制作，20 FPS。旧版 `弹反.blend`（17 帧）与 `被反弹.blend`（15 帧）保留作参考，不再导出。文件内旧导出文本的 30/56 stopFrame 互相矛盾；这些文本是源材料，不作为运行指令，也不执行。

`tools/export_parry.py` 按实际 rig 关键帧导出，保留首末姿势及 `stagger` 的完整反应段，按 `source_frame × 20 / source_fps` 映射游戏 tick。当前默认间隔 0.0625 tick，`parry` 289 样本，`stagger` 641 样本，`hitstun` 81 样本。现有采样核心和处决资源不改。

| 用途 | 路径，相对 `src/main/resources/` |
| --- | --- |
| 服务端播放资源 | `data/fiercerbattles/fiercerbattles_animations/parry/parry.json`、`stagger.json`、`hitstun.json` |
| 导出验证资源 | `assets/fiercerbattles/parry/parry.json`、`stagger.json`、`hitstun.json` |
| 来源和网格验证报告 | `assets/fiercerbattles/parry/parry_metadata.json`、`stagger_metadata.json`、`hitstun_metadata.json` |

受击硬直源文件为同目录的 `受击硬直.blend`（以 `被反弹.blend` 的单人 rig 重新制作，关键帧 0–5），导出与验证使用 `--name hitstun`。`hitstun` 在 bundle 中是可选项：缺失时只停用受击硬直；存在时必须为 3–40 tick 且结束根参数归零。

在仓库根目录运行以下 Blender CLI 命令，使用 `--disable-autoexec` 禁止内嵌脚本，导出器不会保存源文件：

```powershell
blender --background --disable-autoexec "D:/code/mcmod/资源包/blender动画/弹反/黑魂弹反.blend" --python-exit-code 1 --python tools/export_parry.py -- --name parry
blender --background --disable-autoexec "D:/code/mcmod/资源包/blender动画/弹反/黑魂眩晕.blend" --python-exit-code 1 --python tools/export_parry.py -- --name stagger
```

只读复算验证，不写资源：

```powershell
blender --background --disable-autoexec "D:/code/mcmod/资源包/blender动画/弹反/黑魂弹反.blend" --python-exit-code 1 --python tools/export_parry.py -- --name parry --verify
blender --background --disable-autoexec "D:/code/mcmod/资源包/blender动画/弹反/黑魂眩晕.blend" --python-exit-code 1 --python tools/export_parry.py -- --name stagger --verify
```

使用已有 Python/bpy 运行时打开源文件时，同样设置 `use_scripts_auto_execute=False` 并传 `bpy.ops.wm.open_mainfile(..., use_scripts=False)`，再运行独立导出器；不要运行 `.blend` 内的 Export 文本或借用处决导出器的主程序。

复算检查首、中、末帧及全部采样的网格顶点、半采样插值、`humanoid_v1` 几何、两侧 JSON 一致性和源 SHA-256 前后不变。clip 保留 `format:1`、六通道 `[x,y,z,qx,qy,qz,qw]`、`rig` 和 `end_root {right,forward,yaw}`；当前两份动作水平根位移为 0，heading 只有可忽略的源浮点误差。`ParryAnimations` 拒绝绝对值大于 0.001 的结束根参数；如新动作有实际根位移，先设计单独的安全移动策略。

替换数据包中同名 clip 后执行 `/reload`；弹反 bundle 独立重载和同步，不使用 `fiercerbattles_actions` 的双人处决动作定义。客户端按压缩 bundle 的 hash 校验与缓存，无需另装弹反动画资源包。

## 安全修改边界

1. 玩法、网络、历史和过渡放在 `parry/` 与对应 Mixin；继续复用通用 `AnimationClip` / `AnimationPlayer`，不改变核心时钟或旧处决采样。
2. 原窗口按原动画时钟 5–10 判定，前三 tick 淡入已经计入原 18 tick；被反弹的倒放段单独占 5 tick，之后的 `stagger` 从源第二帧开始正放。
3. 倒放必须来自连续完整的实际 pose 历史；保留 fallback 日志、每轴缩放和同 tick BEND。可选网格后端缺失时保持安全空操作，不能用一张假首帧冒充倒放或宣称完成网格验证。
4. 保留互斥、攻击 epoch、所有攻击入口和恢复标签；取消攻击后仍需让已有外部任务恢复临时属性。
5. 动作 rig 只覆盖标准人形六通道；非人形攻击者的视觉表现需要专门 adapter，不能仅换 clip ID。近战来源扩展与投射物机制应分别设计，不宣称当前已经实现投射物反射。

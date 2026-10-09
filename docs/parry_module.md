# 近战弹反模块速查

主手持原版或匠魂的剑、斧，按独立弹反键（默认 **R**，控制设置可重绑）播放弹反动作。武器检查复用 `execution/ExecutionWeapons.java` 的注册 ID 分类，与战技 G、处决 V 分开。弹反者可以移动、转向，镜头模式不变。

服务器在原动画第 **5–12 tick，包含两端** 接受弹反。窗口内符合条件的近战伤害被取消，攻击者进入 `STAGGER`；不造成反伤，也不额外结算攻击伤害。同一窗口可处理多个攻击者。当前近战来源要求直接伤害实体就是攻击者，类型为 `PLAYER_ATTACK`、`MOB_ATTACK`、`MOB_ATTACK_NO_AGGRO` 或 `STING`；环境伤害、投射物及处决伤害不触发成功弹反。窗口外没有弹反保护。

## 时间与姿势衔接

| 状态 | 时间 | 行为 |
| --- | --- | --- |
| `PARRY` | 原动画 17 tick + 复位 5 tick，共 22 tick | 最初 3 tick 从普通姿势淡入，这 3 tick 已包含在原动画时钟中；第 5–12 tick 为有效窗口。复位结束恢复普通手部动作。 |
| `STAGGER` | 历史倒放 5 tick + 原动画 15 tick + 复位 5 tick，共 25 tick | 先倒放攻击者最近 5 tick 的实际模型姿势，再完整播放被反弹动作；原动画最初 3 tick 与倒放末端衔接，计入原 15 tick。整个 25 tick 由服务器禁止动作、移位并接管 AI。 |

倒放使用标准人形 `head/body/rightArm/leftArm/rightLeg/leftLeg` 六部位的真实渲染矩阵历史，包含当时的外部攻击动画和部件缩放。需要连续 6 个游戏 tick 的完整快照，才能形成最近 5 tick 的倒放；多次渲染同一 tick 不增加历史长度。首次出现、历史过期、不连续、部位不全或第一人称没有渲染本体时，明确记录 INFO 的 `ordinary fallback`，改用普通姿势过渡，不伪造攻击历史，也不倒放 `stagger` 自身的 0–5 帧。

PlayerAnimator 的局部 `BEND` 轴、角度与对应矩阵绑定在同一 tick，倒放时一起还原；进入原 `stagger` 的前三 tick 保留轴方向，将弯曲角度淡至 0。`PlayerAnimatorBendBridge` 通过可选后端应用网格弯曲，躯干按 PlayerAnimator 的 `torso + body` 合并规则捕获。没有 BendyLib 时 `DummyBendable` 安全空操作，继续矩阵回放，不强制安装依赖；实际网格弯曲需在具备后端的环境另行验证，不能用矩阵断言代替逐顶点验证。

第一人称手和持物通过相对手臂变换显示弹反及被反弹衔接，第三人称使用六部位模型姿势；不接管镜头。`STAGGER` 保存原 AI、重力状态到 `fiercerbattles:parry_restore`，结束或中止时恢复；断线、死亡、维度变化和遗留恢复标签均需清理。

## 入口与文件索引

以下 Java 路径均相对 `src/main/java/com/fiercerbattles/`。

| 要改的内容 | 文件或入口 | 约束 |
| --- | --- | --- |
| 按键、请求 | `FiercerBattlesClient.PARRY_KEY`；`parry/client/ParryClient.tryRequest(minecraft)` | 默认 R；请求等待及已有动作期间不重复启动。 |
| 服务端启动、伤害裁定、状态恢复 | `parry/ParryServer.java`；`tryStart(ServerPlayer)`、`canParry(Entity)`、`isBusy(Entity)`、`isStaggered(Entity)` | 在服务端主线程调用；有效窗口由服务器世界时间判定。`PARRY` 不锁位置，`STAGGER` 全程锁定；不要给弹反增加反伤。 |
| 原动画时钟、窗口与过渡长度 | `parry/ParryTimeline.java` | `IN_TICKS=3`、`REVERSE_TICKS=5`、`RETURN_TICKS=5`；窗口包含 tick 5 和 12。过渡不能再次推迟原窗口。 |
| 数据包重载、压缩同步、哈希校验 | `parry/ParryAnimations.java`；`parry/ParryNetwork.java` | 独立读取两份弹反 clip，校验 rig、时长、根参数和同步大小；先同步 bundle，再发送带世界时间、身份和 hash 的 Start。 |
| 客户端播放、锁定与清理 | `parry/client/ParryClient.java` | 复用 `AnimationPlayer`；`Kind.PARRY/STAGGER` 决定阶段，不覆盖处决会话。 |
| 实际姿势捕获、倒放与插值 | `parry/client/ParryPoseHistory.java`；`mixin/ParryPoseCaptureMixin.java` | 捕获普通渲染后的六部位矩阵和同 tick BEND；冻结时排除晚于命中时刻的快照。保留每轴缩放和完整矩阵回退，缺历史时明确记录 fallback。 |
| PlayerAnimator 局部弯曲 | `parry/client/PlayerAnimatorBendBridge.java`；`ParryRenderContext.applyBend` | 可选反射 `AnimationProcessor.getBend` / `IBendHelper.bend`；在 `ModelPart.render` 入口应用，避免矩阵回放取消 `translateAndRotate` 时漏掉网格弯曲。第一人称也有独立作用域，Dummy 后端安全空操作。 |
| 第三人称模型、衣物和装备路由 | `parry/client/ParryRenderContext.java`；`execution/client/ExecutionRenderContext.java`；`mixin/ExecutionLivingEntityRendererMixin.java` | 共用渲染入口，处决优先；单次模型变换只由一个动作源提供。完整历史矩阵和 clip 局部采样不能重复叠加实体根。 |
| 第一人称手与持物 | `mixin/ParryFirstPersonMixin.java` | 在已有手部渲染上应用相对手臂变化，保留原持物流程和镜头。 |
| 已排队 Better Combat 攻击 | `mixin/ParryServerNetworkMixin.java` | 请求入口、排队任务和攻击动画都要拦截；任务保存 `attackEpoch`，弹反/被反弹改变 epoch 后丢弃旧任务，避免动作结束后旧攻击复活。 |
| 原版攻击与伤害兜底 | `mixin/ParryPlayerAttackMixin.java`；`mixin/ParryMobAttackMixin.java`；`parry/ParryServer.java` | 分别守住 `Player.attack`、`Mob.doHurtTarget` 和已进入伤害循环的剩余命中；保留外部攻击任务的属性清理。 |
| 被反弹玩家移动 | `mixin/ParryPlayerMovementMixin.java` | 服务端拒绝移位，客户端配合锁定；仅 `STAGGER` 锁位置，不把 `PARRY` 也冻结。 |

处决与弹反/被反弹互斥：处决不能选用 busy 的任一参与者；弹反也不能覆盖正在处决的实体。服务端判断使用各模块的会话查询，不能把渲染作用域的 `isActive()` 当作处决状态。相关处决入口见 [双人处决模块速查](execution_module.md)。

## 动画资源与只读导出

源文件为 `D:/code/mcmod/资源包/blender动画/弹反/弹反.blend` 和同目录 `被反弹.blend`。两者使用相同 `Bone_A` 单人人形网格 rig、20 FPS，场景范围均为 0–60；真实关键帧分别只到 **17** 和 **15**，后面是保持末姿势的空尾段。文件内旧导出文本的 30/56 stopFrame 互相矛盾；这些文本是源材料，不作为运行指令，也不执行。

`tools/export_parry.py` 按实际 rig 关键帧导出，保留首末姿势及 `stagger` 的完整反应段，按 `source_frame × 20 / source_fps` 映射游戏 tick。当前默认间隔 0.0625 tick，`parry` 273 样本，`stagger` 241 样本。现有采样核心和处决资源不改。

| 用途 | 路径，相对 `src/main/resources/` |
| --- | --- |
| 服务端播放资源 | `data/fiercerbattles/fiercerbattles_animations/parry/parry.json`、`stagger.json` |
| 导出验证资源 | `assets/fiercerbattles/parry/parry.json`、`stagger.json` |
| 来源和网格验证报告 | `assets/fiercerbattles/parry/parry_metadata.json`、`stagger_metadata.json` |

在仓库根目录运行以下 Blender CLI 命令，使用 `--disable-autoexec` 禁止内嵌脚本，导出器不会保存源文件：

```powershell
blender --background --disable-autoexec "D:/code/mcmod/资源包/blender动画/弹反/弹反.blend" --python-exit-code 1 --python tools/export_parry.py -- --name parry
blender --background --disable-autoexec "D:/code/mcmod/资源包/blender动画/弹反/被反弹.blend" --python-exit-code 1 --python tools/export_parry.py -- --name stagger
```

只读复算验证，不写资源：

```powershell
blender --background --disable-autoexec "D:/code/mcmod/资源包/blender动画/弹反/弹反.blend" --python-exit-code 1 --python tools/export_parry.py -- --name parry --verify
blender --background --disable-autoexec "D:/code/mcmod/资源包/blender动画/弹反/被反弹.blend" --python-exit-code 1 --python tools/export_parry.py -- --name stagger --verify
```

使用已有 Python/bpy 运行时打开源文件时，同样设置 `use_scripts_auto_execute=False` 并传 `bpy.ops.wm.open_mainfile(..., use_scripts=False)`，再运行独立导出器；不要运行 `.blend` 内的 Export 文本或借用处决导出器的主程序。

复算检查首、中、末帧及全部采样的网格顶点、半采样插值、`humanoid_v1` 几何、两侧 JSON 一致性和源 SHA-256 前后不变。clip 保留 `format:1`、六通道 `[x,y,z,qx,qy,qz,qw]`、`rig` 和 `end_root {right,forward,yaw}`；当前两份动作水平根位移为 0，heading 只有可忽略的源浮点误差。`ParryAnimations` 拒绝绝对值大于 0.001 的结束根参数；如新动作有实际根位移，先设计单独的安全移动策略。

替换数据包中同名 clip 后执行 `/reload`；弹反 bundle 独立重载和同步，不使用 `fiercerbattles_actions` 的双人处决动作定义。客户端按压缩 bundle 的 hash 校验与缓存，无需另装弹反动画资源包。

## 安全修改边界

1. 玩法、网络、历史和过渡放在 `parry/` 与对应 Mixin；继续复用通用 `AnimationClip` / `AnimationPlayer`，不改变核心时钟或旧处决采样。
2. 原窗口按原动画时钟 5–12 判定，前三 tick 淡入已经计入原 17 tick；被反弹的倒放段单独占 5 tick，不能切掉源 `stagger` 的 0–5 帧。
3. 倒放必须来自连续完整的实际 pose 历史；保留 fallback 日志、每轴缩放和同 tick BEND。可选网格后端缺失时保持安全空操作，不能用一张假首帧冒充倒放或宣称完成网格验证。
4. 保留互斥、攻击 epoch、所有攻击入口和恢复标签；取消攻击后仍需让已有外部任务恢复临时属性。
5. 动作 rig 只覆盖标准人形六通道；非人形攻击者的视觉表现需要专门 adapter，不能仅换 clip ID。近战来源扩展与投射物机制应分别设计，不宣称当前已经实现投射物反射。

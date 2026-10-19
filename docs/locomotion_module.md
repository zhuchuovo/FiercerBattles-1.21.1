# 翻滚、奔跑与卧倒模块速查

三个单人动作共用通用 `AnimationClip`、`humanoid_v1` 人形适配和处决/弹反的渲染入口。clip 都是**原地动画**：实体根不随 clip 移动，位移由玩法负责。翻滚和卧倒由服务器会话控制；奔跑只在客户端表现疾跑，不需要网络会话。

## 时间与衔接

| 动作 | 时间 | 行为 |
| --- | --- | --- |
| `ROLL` 翻滚 | clip 16 帧，以 `ROLL_PLAYBACK_SPEED` 1.25 倍速播放（约 12.8 tick，服务端会话取整为 13 tick）；最初 2 tick 从普通姿势淡入，结束（或提前结束）后 3 tick 复位；第 12 帧（约第 9.6 tick）之前无敌，之后为恢复期；结束或提前结束后冷却 10 tick。下列帧数都按 clip 帧计，经 `rollTicks` 换算为游戏 tick | 本地客户端在每个 tick 前按正弦速度曲线覆盖水平速度，整个翻滚共 3 格（`LocomotionTimeline.rollTravel`），照常碰撞。翻滚期间强制 `Pose.SWIMMING`（0.6 格高的原版爬行碰撞箱），可滚过 1 格高的缝隙；结束或提前结束时释放，头顶空间不够站立时由原版转为爬行。客户端每 tick 把 `swimAmount` 清零，避免原版爬行倾斜在翻滚后残留。渲染根朝向固定为翻滚方向。恢复期内一移动、跳跃、攻击、使用物品或发起其他动作，客户端就提前结束翻滚（停止位移、3 tick 淡回普通姿势）并发 `RollRelease`，服务器结束会话、开始冷却并广播 Stop。开始时服务器播放 `fiercerbattles:roll` 音效。镜头视线高度在前 12 帧内从站立高度平滑下降再回升，最低降 1 格（`rollCameraDrop`）；`mixin/LocomotionCameraMixin` 在 `Camera.tick` 末尾用 `LocomotionClient.rollEyeHeight` 覆盖原版的视线高度（原版会直接降到爬行高度），并始终压在头顶天花板下 0.15 格（检查当前与下一 tick 的位置），翻滚结束后由原版从该高度继续缓动。 |
| `PRONE` 卧倒 | `prone_down` 10 tick，最初 2 tick 淡入；之后用 4 tick 交叉淡入 `prone_crawl` 循环 | 开始即强制 `Pose.SWIMMING`：原版爬行碰撞箱、视线高度和爬行速度。服务器在请求时若玩家正在疾跑，就把这次卧倒标为滑行（`Start.slide`，方向取客户端上报的按键方向）：本地客户端在 `prone_down` 期间按先快后慢的曲线滑行 2 格（`proneSlideTravel`），身体朝向固定为滑行方向。服务器在滑行期间每 tick 发少量地面方块粒子，并在第 `PRONE_IMPACT_TICK`（9）tick 躯干着地时于身体中部扬起一团尘土（每次卧倒都有）。匍匐相位由原版 `walkAnimation.position` 驱动（每单位 5.5 clip tick），停下即停在当前相位；头部叠加视线，偏航限 ±60°。 |
| `STAND` 起身 | `prone_up` 12 tick，最初 3 tick 从当前匍匐姿势交叉淡入，结束后 3 tick 复位 | 开始时释放强制姿势（请求时已检查站立空间），让原版爬行倾斜在 clip 结束前衰减完。 |
| 奔跑 | 疾跑时 3 tick 淡入 / 淡出 | 循环区间取 clip 的 `loop_start_tick`–`loop_end_tick`（4–16），相位由 `walkAnimation.position` 驱动（每单位 1.2 clip tick，即比 1:1 快 20%；疾跑约每 tick 1 单位）。头部叠加视线。PlayerAnimator 任一层活动（Better Combat 攻击等）时立即让位。 |

## 入口与文件索引

以下 Java 路径均相对 `src/main/java/com/fiercerbattles/`。

| 要改的内容 | 文件或入口 | 约束 |
| --- | --- | --- |
| 按键、请求 | `FiercerBattlesClient.ROLL_KEY`（默认左 Alt）、`PRONE_KEY`（默认 Z）；`locomotion/client/LocomotionClient.tryRoll` / `toggleProne` | 翻滚方向取按住的移动键相对镜头的方向，没有输入时向前。请求等待期间（最多 40 tick）不重复发送。 |
| 服务端会话、互斥、强制姿势、无敌帧 | `locomotion/LocomotionServer.java`；`tryRoll`、`tryProne`、`releaseRoll`、`isBusy`、`hasSession`、`incomingDamage` | 服务端主线程调用。`isBusy` 在翻滚恢复期返回 false（放行弹反、处决），`hasSession` 覆盖整个会话（受击硬直用它跳过翻滚中的玩家）。无敌帧取消除摔落和 `bypasses_invulnerability`（虚空、`/kill`）以外的所有伤害。拒绝条件：死亡、旁观、睡觉、骑乘、鞘翅、飞行、在水中、处决中、弹反中、已有会话、不在地面。趴姿中进入水、骑乘、飞行等会自动结束并广播 Stop。 |
| 时长、距离、淡入淡出、步频、恢复期、无敌帧、视线下沉 | `locomotion/LocomotionTimeline.java`（`ROLL_PLAYBACK_SPEED`、`ROLL_RECOVERY_FRAME`、`ROLL_INVULNERABLE_FRAMES`、`ROLL_CAMERA_DROP`、`RUN_TICKS_PER_STRIDE`） | 只改玩法时钟，不改 clip。 |
| 数据包重载、压缩同步、hash 校验 | `locomotion/LocomotionAnimations.java`；`locomotion/LocomotionNetwork.java` | 五个 clip 缺一不可；`roll` / `run` / `prone_up` 的 `end_root` 必须为 0；`run` 和 `prone_crawl` 必须带循环区间。登录和 `/reload` 时通过 `OnDatapackSyncEvent` 同步，开始动作和开始追踪时再补发。 |
| 客户端播放、奔跑状态、输入锁定 | `locomotion/client/LocomotionClient.java` | 翻滚、卧倒和起身过渡期间清零移动输入；趴稳后只禁跳。趴着时允许挖方块和使用物品，拒绝攻击生物。所有客户端副本同步设置强制姿势（卧倒、起身前和翻滚期间），使本地预测与远端碰撞箱一致。 |
| 渲染优先级 | `locomotion/client/LocomotionRenderContext.java`；`execution/client/ExecutionRenderContext.java`；`mixin/ExecutionLivingEntityRendererMixin.java` | 处决 > 弹反 > 翻滚/卧倒/奔跑。弹反激活时即使它为矩阵倒放返回 null，也不回落到本模块。与普通姿势的混合复用 `ParryRenderContext.blendOrdinary`。 |
| 攻击与其他动作互斥 | `mixin/MinecraftMixin.startUpswing`；`FiercerBattlesClient.resumeCombatSkill`；`FiercerBattlesExecutionClient.tryRequest`；`ParryClient.tryRequest`；`ParryServer.tryStart`；`FiercerBattlesExecutionServer.tryStart` | 本模块忙碌时不能挥动 Better Combat 武器、释放战技、处决或弹反，反之亦然。`LocomotionClient.blocksActions()` 只在动作开始时调用：翻滚恢复期内它会顺带提前结束翻滚并放行，所以不要在每 tick 的轮询里调用它。 |

## 动画资源与导出

源文件位于 `D:/code/mcmod/资源包/blender动画/新建文件夹/`（由 `one.blend` 模板制作，原 `肢體動作` 目录），同目录 `_scripts/` 保存姿势生成脚本，`預覽/` 保存预览 GIF。

| clip | 源文件 | 时长 | 说明 |
| --- | --- | --- | --- |
| `roll` | `翻滾.blend` | 16 tick | 前滚翻（右肩着地），结束站立 |
| `run` | `奔跑.blend` | 16 tick | 0–4 起跑，4–16 循环（标记 `loop_start` / `loop_end`） |
| `prone_down` | `臥倒.blend` | 10 tick | 扑倒成趴姿，头部留在实体原点上方，腿向后 |
| `prone_crawl` | `匍匐.blend` | 20 tick | 匍匐循环，0–20 |
| `prone_up` | `起身.blend` | 12 tick | 撑起、收腿、站起，结束站立 |

趴姿躯干约 84°，不是 90°：完全水平时胸口朝向垂直向下，导出器无法确定结束朝向。

| 用途 | 路径，相对 `src/main/resources/` |
| --- | --- |
| 服务端播放资源 | `data/fiercerbattles/fiercerbattles_animations/locomotion/*.json` |
| 导出验证资源与报告 | `assets/fiercerbattles/locomotion/*.json`、`*_metadata.json` |

在仓库根目录用 `tools/export_parry.py` 导出（同一导出器，按 `--name` 写入 `parry/` 或 `locomotion/`），追加 `--verify` 只读复算：

```powershell
blender --background --disable-autoexec "D:/code/mcmod/资源包/blender动画/新建文件夹/翻滾.blend" --python-exit-code 1 --python tools/export_parry.py -- --name roll
blender --background --disable-autoexec "D:/code/mcmod/资源包/blender动画/新建文件夹/奔跑.blend" --python-exit-code 1 --python tools/export_parry.py -- --name run
blender --background --disable-autoexec "D:/code/mcmod/资源包/blender动画/新建文件夹/臥倒.blend" --python-exit-code 1 --python tools/export_parry.py -- --name prone_down
blender --background --disable-autoexec "D:/code/mcmod/资源包/blender动画/新建文件夹/匍匐.blend" --python-exit-code 1 --python tools/export_parry.py -- --name prone_crawl
blender --background --disable-autoexec "D:/code/mcmod/资源包/blender动画/新建文件夹/起身.blend" --python-exit-code 1 --python tools/export_parry.py -- --name prone_up
```

源文件的 `loop_start` / `loop_end` 时间轴标记会导出为 `loop_start_tick` / `loop_end_tick`。clip 只覆盖标准人形六通道，`_bend` 半段必须保持 0（`humanoid_v1` 不支持肢体弯曲）。

## 安全修改边界

1. clip 保持原地：不要把翻滚位移烘焙进 clip，也不要让渲染端推动实体。玩家是客户端权威移动者，翻滚位移在本地 `PlayerTickEvent.Pre` 施加，服务器只管时间、冷却、无敌帧和互斥。
2. 强制姿势只在 `PRONE` 和翻滚期间生效；起身开始即释放，翻滚结束或提前结束即释放，中止、死亡、换维度、登出都要清理。
3. 奔跑是纯客户端表现，依赖同步的疾跑标志；不要为它新增服务器会话。
4. 新增动作时保留渲染优先级，并在 `ExecutionRenderContext` 的委托链里按“激活与否”而不是“返回值是否为 null”选择来源。

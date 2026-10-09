# Fiercer Battles 配置详解

## 1. 功能与按键

Fiercer Battles 新增 **战技** 按键。默认按键是 `G`，可在 Minecraft 的“选项 → 控制 → Fiercer Battles → 战技”中重新绑定。

战技不会替换普通左键攻击。玩家按下战技键时，模组会读取当前武器对应的战技 JSON，并根据**当前普通连击段**选择应当播放和结算的战技攻击。动画、命中框、伤害倍率、声音、粒子、突进等都使用所选战技段的数据。

- 武器与战技数据必须同时安装在**客户端和服务器端**可读取的资源中。将数据作为 Mod 自带数据包或双方相同的数据包安装最稳妥。
- 普通战技需要手持已配置战技的 Better Combat 武器、且当前连击段有可用战技；处决使用独立按键（默认 `V`），不需要配置武器战技。
- 战技本身算作一次攻击：成功结算后，Better Combat 连击照常推进一段。

## 2. 文件位置

假设命名空间为 `example`，武器 ID 为 `example:scythe`：

```text
data/example/weapon_attributes/scythe.json
data/example/fiercerbattles/combat_skills/debug.json
```

第一个文件是 Better Combat 的武器属性文件，同时写入 Fiercer Battles 的战技开关。第二个文件是独立战技文件；`json` 只写文件名时，默认从同命名空间的 `fiercerbattles/combat_skills` 目录读取。

也支持把战技文件放在与武器属性文件相同的 `weapon_attributes` 目录中作为后备查找位置，但不推荐这种方式：稀疏连击所需的 `null` 占位会使 Better Combat 将该文件视为无效武器配置。请优先使用 `fiercerbattles/combat_skills` 目录。

## 3. 在武器上启用战技

`data/example/weapon_attributes/scythe.json`：

```json
{
  "Combatskill": true,
  "json": "debug.json",
  "attributes": {
    "attack_range": 3.2,
    "two_handed": true,
    "pose": "bettercombat:pose_two_handed_scythe",
    "category": "sword",
    "attacks": [
      {
        "hitbox": "FORWARD_BOX",
        "angle": 180,
        "damage_multiplier": 1.0,
        "upswing": 0.6,
        "animation": "bettercombat:two_handed_slash_vertical_left",
        "swing_sound": {
          "id": "bettercombat:axe_slash"
        }
      },
      {
        "hitbox": "FORWARD_BOX",
        "angle": 180,
        "damage_multiplier": 1.0,
        "upswing": 0.6,
        "animation": "bettercombat:two_handed_slash_vertical_right"
      },
      {
        "hitbox": "FORWARD_BOX",
        "angle": 180,
        "damage_multiplier": 1.1,
        "upswing": 0.65,
        "animation": "bettercombat:two_handed_slam"
      }
    ]
  }
}
```

### 武器标记参数

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `Combatskill` | 布尔值 | 是 | 设为 `true` 后，该武器启用战技。也兼容小写别名 `combat_skill`。|
| `json` | 字符串 | 否* | 战技文件名或资源位置。`"debug.json"` 表示 `data/<命名空间>/fiercerbattles/combat_skills/debug.json`。没有 `skills` 时仍为必填；有 `skills` 时作为默认回退战技。|
| `skills` | 数组 | 否 | 条件战技变体列表，让同一把武器按手持状态引用不同的战技文件，详见下文“按手持状态选择战技”。|
| `two_handed_attack` | 布尔值，默认 `true` | 否 | 是否让 `attributes.two_handed`（双手武器判定）影响**攻击动作**。填 `false` 时该武器仍按**单手武器**判定：副手槽不会被清空（可以拿盾），攻击动作按单手基准播放，同时保留你 JSON 里写的双手 `pose` 与双手动画资源。详见下文“攻击动作判定：two_handed_attack”。|
| `offhand_skill` | 布尔值，默认 `false` | 否 | 填 `true` 时，这把武器放在**副手**时按战技键会释放**这把武器**的战技，而不是主手武器的战技。详见下文“副手战技：offhand_skill”。|
| `attributes` | 对象 | 是 | 武器的常规 Better Combat 属性；其普通 `attacks` 仍由左键攻击使用。|
| `parent` | 字符串 | 否 | Better Combat 原有继承目标，例如 `"bettercombat:dagger"`。|

`two_handed_attack` 与 `offhand_skill` 也可以写在 `attributes` 对象内部（此时根级的值优先）；两个字段都支持别名 `two_handed_affects_attack` / `affect_attack_action` / `affect_attack_animation` 与 `use_offhand_skill` / `offhand_combat_skill`。

若 `json` 使用带命名空间的完整资源位置，必须写出资源路径和扩展名，例如：

```json
"json": "example:fiercerbattles/combat_skills/debug.json"
```

### 攻击动作判定：`two_handed_attack`

Better Combat 的 `two_handed: true` 一次性做两件事：把武器按双手武器处理（**副手槽直接被清空**，拿不了盾），以及让攻击动作按双手判定。这个选项把「攻击动作判定」单独拆出来：

| 取值 | 攻击动作判定 | 副手槽 | 说明 |
| --- | --- | --- | --- |
| `true`（默认，或不写） | 双手武器 | 被清空 | Better Combat 原生行为，双手武器无法持盾。|
| `false` | **单手武器** | 保留，可持盾、可与另一把单手武器双持 | 攻击动作按单手基准（`AnimatedHand` 用主手/副手，而不是双手），武器仍然使用 JSON 里写的 `pose`（双手站姿）和你写的动画资源。|

```json
{
  "Combatskill": true,
  "json": "greatsword_skill.json",
  "two_handed_attack": false,
  "attributes": {
    "attack_range": 3.2,
    "two_handed": true,
    "pose": "bettercombat:pose_two_handed_sword",
    "attacks": [
      { "conditions": ["OFF_HAND_SHIELD"], "hitbox": "FORWARD_BOX", "damage_multiplier": 1.0, "upswing": 0.6, "animation": "bettercombat:one_handed_slash_vertical_right" },
      { "conditions": ["NO_OFFHAND_ITEM"], "hitbox": "FORWARD_BOX", "damage_multiplier": 1.0, "upswing": 0.6, "animation": "bettercombat:two_handed_slash_vertical_left" }
    ]
  }
}
```

填 `false` 后的实际效果：

- **副手不妨碍装备存在**：副手槽正常返回物品，盾牌可见、可用，也可以放第二把武器。
- **主手按单手攻击动作作为基准**：Better Combat 会用「主手/副手」而不是「双手」来决定播放哪套动作基准，因此双手攻击动作与单手攻击动作同时存在时，主手武器以单手动作基准出招。
- 双手 `pose` 仍然生效（站姿不变），但走动/潜行时会像单手武器一样收起姿势（这是「判定为单手武器」的一部分）。
- 双持判定跟随判定结果：和另一把非双手武器同时持有时会左右手交替连击。
- 物品提示不再显示 `Two-Handed` 行。
- 常配合攻击段的 `conditions` 使用：`OFF_HAND_SHIELD`（持盾时用单手段）与 `NO_OFFHAND_ITEM`（空副手时用双手段）会自动互相排斥，从而做到「有盾单手、无盾双手」。

优先级：**战技 JSON 自己的写法 > 武器属性文件**。战技 JSON 可以在 `attributes` 内或根级直接写 `"two_handed_attack": false`，只让这一段战技按单手播放；不写则继承武器文件的设置。

### 副手战技：`offhand_skill`

普通的战技按键只认「当前连击段所在的那只手」。填了 `offhand_skill: true` 的武器反过来：只要它被拿在**副手**，按战技键就优先释放**它自己**的战技（覆盖主手武器的战技）。

```json
{
  "Combatskill": true,
  "offhand_skill": true,
  "json": "parry_skill.json",
  "attributes": {
    "attack_range": 2.6,
    "category": "dagger",
    "attacks": [
      { "hitbox": "FORWARD_BOX", "angle": 120, "damage_multiplier": 0.9, "upswing": 0.5, "animation": "bettercombat:one_handed_stab" }
    ]
  }
}
```

规则：

- 只有「副手持有该武器」+「该武器当前段位有可用战技」同时成立才会生效；否则按原逻辑使用当前手的战技。
- 副手战技**优先于**主手战技：主手武器也有战技时，按战技键仍然使用副手的战技；副手战技在冷却中时会提示冷却，而不会自动改用主手战技。
- 伤害、攻速、附魔、耐久、音效等按**副手武器**结算（走 Better Combat 的副手攻击流程），伤害倍率/固定伤害/击退/距离等仍使用战技 JSON 的数值。
- 段位下标取「当前连击数 ÷ 2」，与 Better Combat 的手部连击段一致；副手武器的 `attacks` 段数与 `null` 占位规则与普通战技完全相同（含连锁段、`Multiplehits` 多段命中、独立冷却与释放效果）。
- 主手**空手、持盾、持工具**时也能正常释放副手战技。
- 若武器文件没有显式写 `two_handed_attack`，`offhand_skill: true` 会**默认按单手判定**：Better Combat 会把「任一只手拿着双手武器」的副手槽清空，若还按双手判定，这把武器根本没法待在副手。
- 副手战技默认按单手/副手播放动画；确实想让某段战技用双手动画时，在该战技 JSON 里写 `"two_handed_attack": true`。


### 按手持状态选择战技（`skills` 变体）

`skills` 让同一把武器引用**多个相互独立的战技文件**，按玩家当前手持状态选择。每个变体可以有自己的 `skill` 效果（冷却、音效、药水、增伤等）和 `attributes`，与“一个文件内多个连击段共享同一份效果”完全不同。

```json
{
  "Combatskill": true,
  "json": "axe_combat_art.json",
  "skills": [
    { "when": ["NOT_DUAL_WIELDING"], "json": "axe_single_hand.json" },
    { "when": ["DUAL_WIELDING_ANY"], "json": "axe_dual_wield.json" }
  ],
  "attributes": {
    "attack_range": 3.2,
    "two_handed": true,
    "category": "sword",
    "attacks": [
      { "hitbox": "FORWARD_BOX", "angle": 180, "damage_multiplier": 1.0, "upswing": 0.6, "animation": "bettercombat:two_handed_slash_vertical_left" }
    ]
  }
}
```

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| `when` | 字符串或字符串数组 | 可选。所有条件必须同时满足才会选中该变体；条件取值与攻击段的 `conditions` 完全一致（如 `NOT_DUAL_WIELDING`、`DUAL_WIELDING_ANY`、`DUAL_WIELDING_SAME`、`MAIN_HAND_ONLY`、`OFF_HAND_ONLY`、`NO_OFFHAND_ITEM`、`OFF_HAND_SHIELD` 等）。不写或为空表示无条件，始终匹配。|
| `json` | 字符串，必填 | 该变体的战技文件名或资源位置，解析规则与 `json` 相同。|

选择规则：

- 按 `skills` 数组顺序逐个检查，**第一个 `when` 全部满足的变体**胜出。
- 所有变体都不满足时，回退到 `json` 指定的默认战技（若填写）。
- 只填 `skills` 不填 `json` 时，没有变体匹配就没有战技。
- 每个变体按普通战技文件解析：支持 `skill` 效果块、`action_json`、稀疏 `attacks`（`null` 占位）、攻击段 `conditions`。
- 变体选择与攻击段 `conditions` 可以叠加：先按手持状态选中战技文件，再按连击段和段内条件选出具体攻击。

## 4. 战技 JSON 与连击段选择

`data/example/fiercerbattles/combat_skills/debug.json`：

```json
{
  "attributes": {
    "attack_range": 3.2,
    "two_handed": true,
    "pose": "bettercombat:pose_two_handed_scythe",
    "category": "sword",
    "attacks": [
      {
        "hitbox": "FORWARD_BOX",
        "angle": 180,
        "damage_multiplier": 1.35,
        "upswing": 0.55,
        "animation": "bettercombat:two_handed_slash_vertical_left",
        "swing_sound": { "id": "bettercombat:axe_slash" }
      },
      null,
      {
        "hitbox": "HORIZONTAL_PLANE",
        "angle": 180,
        "damage_multiplier": 2.0,
        "upswing": 0.75,
        "animation": "bettercombat:two_handed_slam",
        "swing_sound": { "id": "bettercombat:axe_slash", "pitch": 0.8 }
      }
    ]
  }
}
```

战技文件的 `attacks` 数组位置和普通武器的连击段位置一一对应。`null` 表示该位置没有新战技，并且让前一个已定义的战技继续可用。

| 战技 `attacks` 写法 | 连击第 1 段 | 连击第 2 段 | 连击第 3 段 |
| --- | --- | --- | --- |
| `[战技1]` | 战技1 | 战技1 | 战技1 |
| `[null, null, 战技3]` | 无法发动 | 无法发动 | 战技3 |
| `[战技1, null, 战技3]` | 战技1 | 战技1 | 战技3 |

因此，用户示例中的需求应写成第三种形式：第一段和第三段各写一个战技，中间用 `null` 占位。不要删除中间元素，否则第三个对象会变成第二段战技。

一次成功释放后，战技会**连锁覆盖整个 `attacks` 数组对应的连击段**：数组有几个元素（含 `null` 占位），这次释放就会持续几段挥击，每一段都使用该段对应的战技攻击（含其 `hitbox`、`angle`、`Multiplehits` 等全部判定），伤害判定完全一致。例如 `[战技1, null]` 的第一次释放会覆盖连击第 1、2 段，两段都用战技1 的命中框；连锁段结束后恢复普通攻击，按战技键可再次释放。连锁段沿用同一次释放，不会重复结算冷却与释放效果。若普通武器只有两段攻击，就无法通过第三段触发战技；普通武器的连击段数必须覆盖要使用的战技下标。

战技攻击段也可以写 `conditions`。数组中的所有条件必须同时满足，否则该战技段不会触发。例如，下面的段只会在主手双持相同武器时触发：

```json
{
  "conditions": ["DUAL_WIELDING_SAME", "MAIN_HAND_ONLY"],
  "hitbox": "FORWARD_BOX",
  "animation": "bettercombat:two_handed_slash_vertical_left"
}
```

## 5. 战技效果、动作 JSON 与独立冷却

战技 JSON 可以在根对象中增加 `skill`，用于声明释放时的效果。百分比字段使用小数填写：`0.20` 表示增加 20%，`0.10` 表示恢复最大生命值的 10%。

```json
{
  "skill": {
    "duration_seconds": 5,
    "damage_bonus": 0.20,
    "cooldown": 45,
    "action_json": "",
    "sound": {
      "id": "minecraft:entity.player.attack.crit",
      "volume": 1.0,
      "pitch": 1.0
    }
  },
  "attributes": {
    "attack_range": 3.2,
    "category": "sword",
    "attacks": [
      {
        "hitbox": "FORWARD_BOX",
        "angle": 180,
        "damage_multiplier": 1.0,
        "upswing": 0.6,
        "animation": "bettercombat:one_handed_stab"
      }
    ]
  }
}
```

| 字段 | 类型/默认值 | 说明 |
| --- | --- | --- |
| `duration_seconds` | 数字，默认 `0` | 增伤、护甲和药水效果的持续时间，单位秒。 |
| `damage_bonus` | 数字，默认 `0` | 持续增加造成的伤害比例；`0.20` 为额外 20% 伤害。多个同时存在的增伤效果会相乘。 |
| `armor_bonus` | 数字，默认 `0` | 持续增加的护甲比例；`0.20` 为额外 20% 护甲。 |
| `potion_effect` | 对象，可选 | 药水效果，例如 `{"id":"minecraft:strength","amplifier":0}`；力量 I 使用 `amplifier: 0`。持续时间使用 `duration_seconds`。`target` 默认为 `self`（自己），写成 `hit` 时会在战技实际命中后施加给被命中的生物。也兼容字段名 `apply_to`。 |
| `heal_percent` | 数字，默认 `0` | 释放时恢复最大生命值比例；`0.10` 为恢复 10%。 |
| `cooldown` | 数字，默认 `0` | 该战技自己的释放冷却，单位秒。每次成功释放后开始计时，不与普通攻击冷却共用。服务端会强制校验。 |
| `action_json` | 字符串，可选 | 额外动作 JSON 的文件名或资源位置。填写空字符串或不填写时不播放额外动作。 |
| `sound` | 字符串/对象，可选 | 释放时播放的声音；省略时不播放。对象支持 `id`、`volume`、`pitch`。 |
| `interrupt_attack` | 布尔值或数字，默认 `true` | 战技打断进行中攻击动作的规则。`true`（默认）：按战技键立刻取消当前挥击/后摇并开始战技动画；`false`：进行中有攻击动画时按战技键无效，必须等当前攻击完全结束才能释放；写数字 `N`（如 `30`）：攻击动画**已播放到第 `N` 帧及以后**（按动画资源帧 0..stopTick 计算）才允许打断，之前按战技键无效。|

例如，下面的战技不会给释放者施加药水效果，而会给每个实际被命中的目标施加 5 秒缓慢 II：

```json
"skill": {
  "duration_seconds": 5,
  "potion_effect": {
    "id": "minecraft:slowness",
    "amplifier": 1,
    "target": "hit"
  }
}
```

也可以在 `potion_effect` 内写 `"duration_ticks": 100`（兼容 `duration`）单独指定药水持续 tick；它会优先于根级的 `duration_seconds`。`target: "hit"` 只有伤害判定成功进入目标时才施加，挥空不会触发，多目标和多段命中会分别对每个命中目标生效。

战技可以在普通攻击完成伤害判定后的后摇阶段抢断。抢断窗口必须写在**主武器属性 JSON** 的 `attributes.attacks` 普通攻击段中；它描述该主动画什么时候允许被任意战技打断，而不是战技自身的属性：

```json
{
  "hitbox": "FORWARD_BOX",
  "damage_multiplier": 1.0,
  "upswing": 0.55,
  "recovery_cancel_frames": [0, 20],
  "animation": "bettercombat:one_handed_stab"
}
```

| 字段 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `recovery_cancel_frames` | 字符串或数组 | 未设置 | 写在主武器普通攻击段中，按该主动画资源的**已播放帧位置**设置抢断区间，端点包含在内。`"50,60"` 或 `[50,60]` 都表示动画播放到第 `50~60` 帧时允许打断普通攻击后摇并强制开始任意可用战技。帧以动画 JSON 的 `emote.stopTick` 为总帧数计算，而不是直接使用游戏 tick。|
| `recovery_cancel_frames_unit` | 字符串 | `played` | 帧窗口的语义。默认 `played`：数值是动画**已播放到的帧位置**（0..stopTick），例如 `[50,60]` 覆盖长动画的后段；旧版按"剩余帧数"理解，写 `"remaining"` 可恢复（此时 `[0,20]` 表示剩余 `0~20` 帧）。|
| `interrupt_recovery_frames` | 字符串或数组 | 未设置 | `recovery_cancel_frames` 的兼容别名。|
| `interrupt_recovery_frames_unit` | 字符串 | `played` | `recovery_cancel_frames_unit` 的兼容别名。|
| `recovery_cancel_ticks` | 整数 | `0` | 旧版兼容字段，按游戏 tick 判断最大剩余后摇；新配置优先使用 `recovery_cancel_frames`。|
| `interrupt_recovery_ticks` | 整数 | `0` | `recovery_cancel_ticks` 的兼容别名。|
| `recovery_cancel_seconds` | 数字 | `0` | 用秒填写抢断额度，例如 `0.4` 等于 `8` tick。|
| `recovery_cancel` | 数字 | `0` | `recovery_cancel_seconds` 的兼容别名。|

注意：这些字段必须写在主武器属性 JSON 的具体普通攻击 `attacks` 段中，而不是战技 JSON 的 `skill` 对象或战技攻击段中。`recovery_cancel_frames` 是主动画自己的动画帧抢断窗口；它不会改变战技自身的 `cooldown`。普通攻击必须已经完成伤害判定，且当前主动画播放位置落在指定帧区间内，按战技键才会取消挥击并立即开始战技。战技仍可在普通攻击完全冷却后正常释放。

抢断成功时，模组会同时取消 Better Combat 的挥击状态并将旧攻击动画以 `0` 帧淡出，战技动画会立即接管，不会等待原攻击动作播放完毕。

动画资源的总帧数来自 Better Combat 动画 JSON 的 `emote.stopTick`。例如 `one_handed_stab` 的 `stopTick` 为 `30`，配置 `"0,20"` 表示动画播放到第 `0~20` 帧时允许抢断（整个动画大部分时间都满足）；想只在动画末尾后摇段抢断，用 `"20,30"`。数值写的是**已播放帧位置**，不会再出现"剩余帧数永远达不到 50"这类不生效的情况；确实需要旧版剩余帧语义时，加 `"recovery_cancel_frames_unit": "remaining"`。若动画资源没有 `stopTick`，模组会回退到 Better Combat 传入的动画播放长度进行估算。

### 强制按原始动画播放

如果 Better Combat 的攻击适配导致动画头部俯仰、播放速度或镜头补偿与资源预览不一致，可以在动画资源的 `emote` 对象中加入：

```json
{
  "emote": {
    "forceOriginalPlayback": true
  }
}
```

也兼容字段名 `force_original_playback`。设为 `true` 后，该动画会恢复资源中的头部俯仰，固定为 PlayerAnimator 的原始速度，并跳过 Better Combat 的攻击层镜头补偿；未设置或设为 `false` 时保持 Better Combat 默认行为。动画资源仍需包含 `rightItem` / `leftItem` 通道，才能让手持武器按动画脱离手部移动。

### `action_json` 的写法

`action_json` 默认从当前战技文件所在目录读取。例如：

```json
"action_json": "skill_action.json"
```

对应的 `skill_action.json` 可以只写一个动画：

```json
{
  "animation": "bettercombat:two_handed_slam"
}
```

也可以写 `attacks` 数组，按连击段下标覆盖动作：

```json
{
  "attacks": [
    { "animation": "bettercombat:two_handed_slash_vertical_left" },
    null,
    { "animation": "bettercombat:two_handed_slam" }
  ]
}
```

`action_json` 只负责覆盖战技段的 Better Combat `animation`；战技 `attributes.attacks` 中仍应保留可用的命中框和基础攻击字段。动作 JSON 不填写时，使用战技段自身的 `animation`。

仓库已提供四个可直接引用的示例：

```text
data/fiercerbattles/fiercerbattles/combat_skills/damage_boost_20.json
data/fiercerbattles/fiercerbattles/combat_skills/armor_boost_20.json
data/fiercerbattles/fiercerbattles/combat_skills/strength_one.json
data/fiercerbattles/fiercerbattles/combat_skills/heal_10.json
```

示例效果分别是：5 秒增加 20% 伤害、5 秒增加 20% 护甲、5 秒力量 I、释放时恢复 10% 最大生命值；冷却依次为 45 秒、30 秒、30 秒、20 秒。四个示例都没有额外动作 JSON，但都配置了释放音效。

## 6. `attributes` 顶层参数

这些参数可用于普通武器 JSON 和战技 JSON。战技触发后优先采用战技 JSON 中的值。

| 参数 | 类型/默认值 | 说明 |
| --- | --- | --- |
| `attack_range` | 数字，默认 `0` | 武器基础攻击距离（格）。攻击段的 `range_multiplier` 会在此基础上相乘。|
| `range_bonus` | 数字，默认 `0` | Better Combat 的固定额外距离（格）。与攻击段的 Fiercer Battles `range_bonus` 不同。|
| `two_handed` | 布尔值 | 是否双手武器；影响手部姿势与副手使用。配合武器属性文件/战技 JSON 的 `two_handed_attack: false` 可以只保留姿势与动画资源、改按单手判定攻击动作。|
| `two_handed_attack` | 布尔值，默认 `true` | 写在战技 JSON 的 `attributes` 或根级时，单独控制**这段战技**是否按双手判定攻击动作。`false` = 该战技按单手动作基准播放；不写 = 继承武器属性文件的设置。|
| `pose` | 动画 ID 字符串 | 主手姿势，例如 `bettercombat:pose_two_handed_scythe`。|
| `off_hand_pose` | 动画 ID 字符串 | 副手姿势；适用于双持配置。|
| `category` | 字符串 | 武器类别，供 Better Combat 的条件和兼容逻辑使用。|
| `attacks` | 数组 | 攻击/战技段列表。普通武器中每项必须是完整的有效攻击；战技文件允许用 `null` 做空位。|
| `trail_appearance` | 对象 | 武器拖影外观。结构见第 8 节。|
| `fixed_damage` | 数字，默认 `0` | 战技所有攻击段的默认固定伤害（点）；攻击段内直接写的值优先。|
| `knockback` | 数字，默认 `0` | 战技所有攻击段的默认额外击退（格）；攻击段内直接写的值优先。|

## 7. `attacks` 攻击段参数

### Better Combat 原生参数

| 参数 | 类型/默认值 | 说明 |
| --- | --- | --- |
| `conditions` | 字符串数组 | 所有条件都必须满足才会使用该攻击段；普通攻击和战技攻击段都支持。可用值：`NOT_DUAL_WIELDING`、`DUAL_WIELDING_ANY`、`DUAL_WIELDING_SAME`、`DUAL_WIELDING_SAME_CATEGORY`、`NO_OFFHAND_ITEM`、`OFF_HAND_SHIELD`、`MAIN_HAND_ONLY`、`OFF_HAND_ONLY`、`MOUNTED`、`NOT_MOUNTED`。|
| `hitbox` | 枚举，必填 | 命中框：`FORWARD_BOX`（前方盒形）、`VERTICAL_PLANE`（竖直面）、`HORIZONTAL_PLANE`（水平面）。|
| `damage_multiplier` | 数字，默认 `1.0` | 这一段的伤害倍率；`1.35` 为 135% 伤害。不得小于 `0`。|
| `movement_speed_multiplier` | 数字，默认 `1.0` | 攻击期间移动速度倍率。`0.8` 表示降低 20%。|
| `range_multiplier` | 数字，默认 `1.0` | 该段攻击距离倍率。|
| `angle` | 数字，默认 `0` | 以视线为中心的命中角度（度）。`0` 为不额外限制角度，`180` 是常见的大范围横扫。|
| `upswing` | 数字，默认 `0` | 前摇占攻击冷却的比例；例如 `0.6` 表示在本次攻击冷却的 60% 处命中。不得小于 `0`。|
| `animation` | 动画 ID，必填 | 要播放的 Better Combat 动画，例如 `bettercombat:two_handed_slam`。|
| `swing_sound` | 对象 | 命中挥击时的声音，结构见下表。|
| `impact_sound` | 对象 | Better Combat 的冲击声音字段；当前 Better Combat 版本通常不使用它。|
| `trail_particles` | 数组 | 每段攻击的拖影粒子挂点，结构见第 8 节。|

### `swing_sound` / `impact_sound` 参数

```json
"swing_sound": {
  "id": "bettercombat:axe_slash",
  "volume": 1.0,
  "pitch": 0.9,
  "randomness": 0.1
}
```

| 参数 | 类型/默认值 | 说明 |
| --- | --- | --- |
| `id` | 声音 ID，必填 | 声音资源 ID。|
| `volume` | 数字，默认 `1.0` | 音量倍率。|
| `pitch` | 数字，默认 `1.0` | 音调倍率。|
| `randomness` | 数字，默认 `0.1` | 随机音调变化幅度。|

### Fiercer Battles 扩展参数

下列字段可直接写在普通武器的任一攻击段，也可写在战技 JSON 的攻击段。

| 参数 | 类型/默认值 | 说明 |
| --- | --- | --- |
| `attack_speed_multiplier` | 数字，默认 `1.0` | 实际攻击冷却倍率。`1.2` 更快，`0.8` 更慢；不单独改变动画资源。|
| `range_bonus` | 数字，默认 `0` | 这一段额外增加的攻击距离（格）。|
| `after_cooldown` | 数字，默认 `0` | 本段攻击结束后的额外等待时间，单位秒。|
| `attack_displacement` | 字符串 | 攻击时向视线方向突进，格式为 `"持续秒数,位移格数"`，如 `"0.2,1.0"` 表示 0.2 秒内前移 1 格。两个数都必须大于 0。|
| `Multiplehits` | 字符串 | 多段命中帧与每段独立伤害倍率，格式为 `"[帧1,帧2],[倍率1,倍率2]"`。完整规则见下一节。|
| `fixed_damage` | 数字，默认 `0` | 这一段攻击**每次命中**额外造成的固定伤害（点），在护甲等减伤结算后直接加算：最终伤害 = 面板伤害经过减伤后的部分 + 固定伤害。固定部分不会被暴击、附魔或战技增伤放大。只想用固定伤害、不要面板时，把该段的 `damage_multiplier` 写成 `0` 即可。也兼容别名 `flat_damage`；可写在武器/战技 JSON 的 `attributes` 根级（作用于全部段），或 `config/fiercerbattles.json` 按动画覆盖。|
| `knockback` | 数字，默认 `0` | 这一段攻击**每次命中**额外增加的击退距离（格），直接叠加在 Better Combat 的普通击退之上，与攻击是否满蓄力无关。`1` 大约额外击退 1 格。也兼容别名 `knockback_bonus`；同样支持根级默认值与 `config/fiercerbattles.json` 按动画覆盖。|

`upswing`、`movement_speed_multiplier`、`range_multiplier` 既是 Better Combat 原生参数，也会被 Fiercer Battles 的现有配置覆盖逻辑读取；直接写在战技段中即可。

### `Multiplehits` 多段伤害

```json
{
  "hitbox": "FORWARD_BOX",
  "angle": 180,
  "damage_multiplier": 1.0,
  "upswing": 0.6,
  "animation": "bettercombat:two_handed_slam",
  "Multiplehits": "[10,20,30],[1.0,1.0,0.5]"
}
```

`Multiplehits` 使用一个字符串，包含两个数组：前一组是动画开始后的命中帧，后一组是对应帧的独立武器伤害倍率。

- `"[10,20,30],[1.0,1.0,0.5]"` 表示动画第 10、20、30 帧各结算一次命中，伤害依次为武器基础攻击伤害的 100%、100%、50%。
- 帧以游戏逻辑 tick 计数，`20` 帧约等于 1 秒；数值必须是从小到大排列的非负整数。
- 倍率必须为非负数；`0.5` 即 50%，`2.0` 即 200%。
- **倍率数组可以比帧数组短**：缺省的后段自动沿用最后一个倍率。例如 `"[10,12,15],[0.5,1.25]"` 等价于 `"[10,12,15],[0.5,1.25,1.25]"`，第 15 帧仍按 125% 结算；不会因为少写一个倍率而整条失效。
- 配置 `Multiplehits` 后，Better Combat 原本由 `upswing` 触发的单次命中会被替换，不会额外造成一次普通伤害。
- 每一次多段命中都使用该攻击段的 `hitbox`、`angle`、`range_multiplier`、音效、粒子和目标筛选；`fixed_damage` 与 `knockback` 也会按每一次多段命中分别结算。
- **多段伤害倍率完全忽略该攻击段的 `damage_multiplier`。** 例如 `damage_multiplier: 3.0` 和 `Multiplehits: "[10],[1.0]"` 仍只造成武器基础伤害的 100%。
- 多段命中整体只推进一次普通连击段，不会每一段都让连击额外前进。
- 所有命中帧必须落在当前攻击动画/冷却尚未结束的时间内；超出挥击结束后的帧不会命中。
- **帧单位**：默认按游戏逻辑 tick 计数（20 tick/秒）。在同一攻击段写 `"MultiplehitsUnit": "animation"`（也兼容小写 `multiplehits_unit`）可改为按**动画资源帧**计数——帧值对应 PlayerAnimator 动画资源的 `stopTick` 帧，客户端按实际播放进度映射，写 `"Multiplehits":"[7,11,14,15,17],..."` 就精确在第 7、11、14、15、17 资源帧各命中一次，不受挥击时长拉伸影响（帧值需小于动画的 `stopTick`）。
- **战技的多段帧同理**：默认 tick 单位下，挥击长度等于攻击冷却（由武器攻速决定），要让靠后的帧生效（如第 17 tick），在战技攻击段写 `"attack_speed_multiplier": 0.6` 把挥击拉长到约 1.7 倍即可（`0.6` ≈ 冷却 ÷ 0.6）。使用 `MultiplehitsUnit: "animation"` 后无需再拉长挥击。

## 8. `config/fiercerbattles.json` 全局覆盖参数

此文件的 `entries` 按动画 ID 覆盖对应攻击或战技段。普通武器战技仍必须通过武器 JSON 中的 `Combatskill` 和 `json` 启用；同一文件的 `execution` 对象配置处决（见第 10 节）。

```json
{
  "entries": [
    {
      "animation": "bettercombat:two_handed_slam",
      "attack_speed_multiplier": 0.85,
      "upswing": 0.75,
      "movement_speed_multiplier": 0.7,
      "range_multiplier": 1.1,
      "range_bonus": 0.4,
      "after_cooldown": 0.25,
      "attack_displacement": "0.2,1.0",
      "Multiplehits": "[10,20,30],[1.0,1.0,0.5]"
    }
  ]
}
```

| 参数 | 说明 |
| --- | --- |
| `animation` | 要覆盖的动画 ID，必填。|
| `attack_speed_multiplier` | 攻击冷却倍率。|
| `upswing` | 前摇比例。|
| `movement_speed_multiplier` | 攻击期间移速倍率。|
| `range_multiplier` | 本段距离倍率。|
| `range_bonus` | 本段额外距离（格）。|
| `after_cooldown` | 额外后摇（秒）。|
| `attack_displacement` | 突进，格式 `"持续秒数,位移格数"`。|
| `Multiplehits` | 多段命中覆盖，格式 `"[帧列表],[伤害倍率列表]"`；行为与攻击段中的同名字段相同。|
| `fixed_damage` | 固定伤害覆盖（点）；行为与攻击段中的同名字段相同。兼容别名 `flat_damage`。|
| `knockback` | 额外击退覆盖（格）；行为与攻击段中的同名字段相同。兼容别名 `knockback_bonus`。|

攻击段内直接写的同名 Fiercer Battles 参数优先于全局 `config/fiercerbattles.json`。


## 9. 常见问题

- **按战技键没有反应**：确认武器属性文件有 `"Combatskill": true`，`json` 路径正确，当前连击段之前存在至少一个非 `null` 的战技攻击。标记必须写在**实际被加载**的那份 `weapon_attributes` 里：世界数据包中的同名文件会覆盖模组自带的那份，而 `kubejs/data/` 目录只有在安装了 KubeJS 时才会作为数据包加载。按键被忽略时日志会打印一条 `no combat skill resolved for '...'`。
- **双手武器想同时拿盾**：在武器属性文件写 `"two_handed_attack": false`。它让这把武器按单手判定（副手槽不被清空、攻击动作按单手基准），同时保留 JSON 里的双手 `pose` 与双手动画资源；配合攻击段 `conditions`（`OFF_HAND_SHIELD` / `NO_OFFHAND_ITEM`）可以让持盾与空副手走不同动画。
- **副手武器看不见 / 无法拿在副手**：该武器仍是 `two_handed: true`，Better Combat 会把任意一只手拿双手武器时的副手槽返回为空。写 `"two_handed_attack": false` 即可（`offhand_skill: true` 的武器在没写 `two_handed_attack` 时会自动按单手判定）。
- **副手武器战技按了没反应**：确认副手武器文件写了 `"offhand_skill": true`、`Combatskill` 与 `json` 都正确，且副手武器的 `attributes.attacks` 在当前段位（连击数 ÷ 2）之前有非 `null` 战技；另外主手/副手数据包必须一致。
- **按战技键用的是主手战技而不是副手战技**：`offhand_skill` 只对**写着它的那把武器**生效，且必须是「它在副手」；没有别的武器抢优先级。
- **副手战技动画是双手动作**：副手战技默认按单手/副手播放；如果该战技 JSON 显式写了 `"two_handed_attack": true`（或武器文件写了 `true` 而战技没覆盖），它就会按双手播放。
- **联机（专用服务器）里战技键没反应 / 副手战技无效**：客户端会读取**自己安装的数据包**来解析战技与武器标记（Better Combat 只在服务端加载武器属性注册表）。请确保客户端与服务端装有相同的 `weapon_attributes` 与 `fiercerbattles/combat_skills` 文件；若两端不一致，客户端发出的战技请求会被服务端按普通攻击结算。
- **同一武器想按手持状态用不同战技（如单持/双持）**：在武器属性文件用 `skills` 变体列表，每个变体写 `when` 条件和独立的 `json` 战技文件；`when` 条件与攻击段 `conditions` 相同。注意 `DUAL_WIELDING_*` 系列条件要求两只手都持非双手武器。
- **第三段战技提前释放**：检查数组中是否保留了 `null` 占位；数组元素会按下标对应连击段，不能省略中间段。
- **第三段按键无反应**：确认普通武器的 `attributes.attacks` 至少有三段，而且没有被 `conditions` 过滤掉第三段。
- **多段伤害只命中一次、只命中前几段或完全没有多段命中**：确认 `Multiplehits` 的帧数组递增、帧数不为 0。倍率数组可以比帧数组短（缺省的后段沿用最后一个倍率）；但帧数组必须完整。若整条 `Multiplehits` 无效（例如帧数组为空），模组会回退为普通的单次上挥命中。旧版本会在挥击窗口结束时直接丢弃尚未结算的多段状态，导致排在挥击末尾（帧值恰好等于或超过挥击长度）的那一段永远不触发——例如双持武器攻速加成下挥击被 `attack_speed_multiplier: 0.75` 拉长到约 15.2 tick（窗口取整为 15），第 15 tick 的第三段会在窗口判定处被吞掉，只结算前两段。最新版本保证已配置的每一帧都会触发，之后再清除多段状态。
- **多段伤害偶发各段倍率错位、多段打完后的普通攻击伤害不对（偏高/偏低/为零）**：旧版本把每段的伤害倍率、固定伤害与击退记录在处理攻击封包的线程里，而真正结算伤害的任务在服务器主线程执行——当 NeoForge 在网络线程派发包处理器时（专用服务器或部分联机场景），结算时读到的是**上一次请求残留的倍率**：多段各段依次错位一帧，且最后一段的倍率（例如 `0` 或 `0.5`）会一直泄漏到之后的普通攻击和其他由该玩家造成的伤害事件上，直到被下个请求覆盖。最新版本改为在服务器线程攻击任务开始时，按该请求自己的封包与攻击数据重建全部命中状态，任务结束时立即清理；任何派发模式下各段倍率都严格对应、不再残留。确认使用最新版本。
- **多段后几段偶发无伤害、或战技放完后几秒内普通攻击变回战技挥击**：旧版本里 Better Combat 的挥击对象自然到期时直接清空字段、不经过取消回调，排在挥击末端的帧仍会照常"触发"，却打进一个已不存在的挥击——攻击封包没有发出，该段伤害与连击推进全部静默丢失；对战技多段，最后一段的连锁段位也因此永不消耗，随后的普通挥击会持续按战技挥击结算（最长连锁窗口约 5 秒）。最新版本会跟踪多段帧所属的挥击对象，挥击一结束就显式丢弃剩余帧（日志可见 `Dropping pending multiple-hit frame ...: the swing that owns them has already ended`），并同步消耗战技连锁段位，普通攻击立即恢复。若日志频繁出现该警告，说明帧值配在了挥击寿命之外：tick 单位下用 `attack_speed_multiplier` 拉长挥击，或改用 `MultiplehitsUnit: "animation"`。确认使用最新版本。
- **战技+`Multiplehits` 只结算第一段**：战技的多段命中由客户端按帧逐个发送攻击请求，服务端只对第一段请求校验战技冷却与效果，后续各段沿用同一次激活（旧版本会把后续段误判为冷却未到而拒绝）。确认使用最新版本。
- **多段倍率不生效（每段都造成 100% 伤害）**：旧版本把伤害倍率注入点放在了仅服务器环境生效的 Mixin 中，单人游戏（客户端环境）永远不会加载它；最新版本已将该逻辑移入通用 Mixin，并跟随 Better Combat 2.4.0 的实际调用位置（`handleAttackRequest` 的服务器线程任务）注入。确认使用最新版本。
- **战技第二段命中框和第一段不一样大**：确认战技 `attacks` 数组中第二段写了 `null` 占位（表示沿用第一段战技）。一次释放会连锁覆盖整个数组对应的连击段，每一段都使用对应战技攻击的命中框；若数组只有一段而连击进入第二段，第二段会恢复为普通攻击。
- **战技多段命中后面几段伤害偏低**：旧版本只有第一段命中会把攻击强度条强制拉满，后续各段会因普通攻击重置强度条而只造成部分伤害；最新版本对同一释放的每一段命中都强制满蓄力。确认使用最新版本。
- **战技挥击莫名没有伤害，重进游戏才能恢复**：该问题由**战技挥击进行中切换武器或死亡**触发。旧版本在战技无法被服务端接受时（冷却未到、连锁窗口过期、武器已切换、客户端/服务端数据包不同步等）会直接丢弃整次攻击请求，导致挥击动画正常播放但没有伤害；死亡/换武器会让客户端仍按旧战技发包而服务端持续拒绝，于是所有后续挥击都无伤害，直到重进游戏重置状态。最新版本即使战技被拒绝也始终按**战技自身的数据**结算这一击（倍率、固定伤害、击退、距离），只是跳过激活效果与冷却计时；多段命中状态不再能越过所属挥击存活，玩家实体被替换（死亡重生导致 tick 计数归零）或多段帧已全部结算时自动清除，武器切换后也会丢弃已失效的战技选择。确认使用最新版本。
- **使用战技时角色/游戏卡住**：旧版本在战技处于待发状态时会把攻击强度门槛一并放开，按住攻击键时 Better Combat 会在每个 tick 重新开始战技挥击并重播动画，角色卡在前摇姿势并持续重复发包。最新版本只在没有进行中的挥击时（例如刚按下战技键）放开起手门槛，进行中挥击仍按真实攻击强度自然衔接；同时死亡重生后不再沿用旧实体的后摇窗口、位移和冷却截止时间，角色不会在重生后被旧状态锁死。确认使用最新版本。
- **战技处于冷却时仍能发送请求**：客户端会提前拦截，服务端也会按战技 JSON 的 `cooldown` 再次校验；确认客户端和服务器使用相同的战技 JSON。
- **增伤或护甲没有持续到结束**：确认 `duration_seconds` 大于 0；治疗是释放时一次性生效，不需要持续时间。
- **动作没有播放但音效正常**：`action_json` 为空时不会加载额外动作；同时检查战技 `attributes.attacks` 中是否存在 Better Combat 的 `animation`。
- **联机只有动画没有正确伤害或完全无效**：确保客户端和服务器都装有 Fiercer Battles、Better Combat，以及相同的战技数据资源。
- **战技文件改完没生效**：重新加载数据包或重启游戏/服务器；资源加载时会重新读取战技定义。

## 10. 双人处决动画

主手持原版或匠魂的剑/斧，准星指向血量**严格低于最大生命值 25%** 的成年僵尸或骷髅，在三格内按独立处决键（默认 `V`）启动。可在“选项 → 控制 → Fiercer Battles → 处决”重新绑定；战技仍使用 `G`。两套动画中的 `player` 是处决者，`player_B` 是被处决者。默认最大生命值 20 的僵尸/骷髅必须低于 5 点生命值；恰好 5 点不能处决。

第一套剑动作来自 `execution.blend`，56 tick（2.8 秒），仅允许原版六种剑和匠魂 `sword` / `cleaver`（剑 / 劈刀）。第二套斧动作来自 `axe_execution.blend`，30 tick（1.5 秒），仅允许原版六种斧和匠魂 `hand_axe` / `broad_axe` / `minotaur_axe`（手斧 / 板斧 / 米诺陶战斧）。按主手注册 ID 分类，客户端和服务器共用检查；空手、只在副手持武器、匕首、鹤嘴锄、喷流刃、投斧弹药及其他模组武器不触发处决。

服务器批准后，双方先用 5 tick（0.25 秒）快速移到摆位锚点，并从普通姿势平滑衔接到原动画首帧；然后完整播放原有的 56 / 30 tick 动画，结束后再用 5 tick 视觉复位。正常完整流程为剑 66 tick（3.3 秒）、斧 40 tick（2 秒）。开场和复位由共同的 `execution/ExecutionTimeline.java` 管理，两端共用 `approachPosition` 沿经过安全检查的先升高、再平移、后下降路径摆位，保留台阶与半砖的碰撞规则。动画资源、原始帧号和现有 `AnimationClip` / `AnimationPlayer` 保持不变。同步消息携带双方开场起点和动画锚点，原动画的 `startGameTime` 为请求成功的世界 tick 加 5。

服务器负责验证主手武器、血量、目标类型、距离、视线、姿态和摆位空间，再向客户端同步所选动作、两个角色的位置、朝向和共同的世界时间。双方沿所选动作的同一时间线播放，原动画结束时结算致命伤害并归属处决玩家。开场和原动画阶段玩家无敌，同时接管移动、角色朝向、AI、普通攻击、其他战技、交互、击退和外来伤害；鼠标可以左右环绕、上下调整观看角度；断线、切换维度、目标死亡或场地变化会中断并恢复控制。原动画结束时服务端恢复 AI、重力和无敌状态，客户端仍锁定输入和镜头 5 tick 完成姿势复位，视觉复位不延长服务端无敌；中止时及时恢复状态和控制。

`config/fiercerbattles.json` 的顶层增加：

```json
{
  "execution": {
    "enabled": true,
    "action_id": "fiercerbattles:execution",
    "axe_action_id": "fiercerbattles:axe_execution",
    "health_threshold": 0.25,
    "range": 3.0,
    "humanoid_entities": [
      "minecraft:zombie",
      "minecraft:skeleton"
    ],
    "hurt_sound_frames": [12, 20, 24, 28, 32, 36, 40, 48],
    "swing_sound_frames": [12, 24]
  },
  "entries": []
}
```

| 参数 | 说明 |
| --- | --- |
| `enabled` | 是否启用处决。 |
| `action_id` | 剑用的数据包成对动作 ID，例如 `my_pack:finisher`；默认 `fiercerbattles:execution`，兼容旧配置。 |
| `axe_action_id` | 斧用的数据包成对动作 ID；默认 `fiercerbattles:axe_execution`，旧配置缺少此字段时自动使用默认值。 |
| `health_threshold` | 血量比例上限，必须严格低于此值；有效范围大于 0 且不超过 1，默认 `0.25`。 |
| `range` | 玩家眼睛到目标可见身体的启动距离，单位为格；有效范围大于 0 且不超过 6，默认 `3.0`。按身体边界计算，贴身重叠也能选中。 |
| `humanoid_entities` | 可处决实体 ID 白名单。只添加使用成人标准人形骨架的生物，例如 `minecraft:husk`、`minecraft:stray` 或 `minecraft:wither_skeleton`；非人形模型无法匹配本动画。 |
| `hurt_sound_frames` | 剑动作播放被处决生物自身受伤音效的帧列表，默认 `[12, 20, 24, 28, 32, 36, 40, 48]`。斧动作固定只在原动画第 13 帧播放一次，不使用此列表。仅播放声音，不额外扣血或改变动作。 |
| `swing_sound_frames` | 播放挥剑音效 `minecraft:entity.player.attack.sweep` 的帧列表，默认 `[12, 24]`。 |

音效跟随服务器的原动画时间线向附近玩家播放，每个帧只触发一次。开场 5 tick 不计入音效帧号；源动画为 20 帧/秒，原动画第 12 帧是原动画开始后 0.6 秒、请求成功后 0.85 秒。斧头受伤声固定在原动画第 13 帧，即原动画开始后 0.65 秒、请求成功后 0.9 秒；即使 `axe_action_id` 指向自定义动作，也按斧武器分类执行此规则，所选原动画短于 13 tick 时不播放。

剑的 `hurt_sound_frames` 和两套动作共用的 `swing_sound_frames` 可改为 `[]` 禁用，重复帧去重，空值及超出所选原动画时长的帧忽略（剑 `0–56`，斧 `0–30`）；缺少字段或字段为 `null` 时使用默认列表。没有额外的斧受伤声多帧配置。完成或中止后不再触发后续音效。

配置由服务器裁定；客户端不需要复制服务器的白名单。修改后重启游戏/服务器生效，旧配置未写 `execution` 时自动使用默认值。按键时客户端优先使用画面准星已经选中的活体目标，没有时再做六格扩展拾取，避免渲染插值与 tick 射线不一致导致目标漏选。服务器仍验证身体距离与方块遮挡，不用可能尚未收到转头消息的旧朝向重复校验。实际启动范围由服务器决定；草、花等无碰撞植物不遮挡扩展拾取。主手武器不支持、无目标或服务端拒绝时会在快捷栏上方提示原因，并记录 INFO 日志；拒绝不会自动释放战技。

幼年生物、乘骑或载人状态、睡眠、游泳、鞘翅飞行、悬空及尺寸不适合的实体不能启动。服务器优先沿接近方向摆位；原位置被墙或地形挡住时，会尝试围绕目标的其他方向，以及最多一格的双人整体挪位。台阶或半砖导致脚底高度不同时，允许总共两格高低差，尝试双方已有高度和中间可落脚的表面。场地检查沿两人之间的通道进行，移动按实际路径分段连续检查，附近未挡住通道或移动的方块不再因落在大矩形中而阻止处决。双方仍需稳定落脚，且移动路径和结束位置安全。播放时双方脚底统一为同一高度，原动画的横向偏移、间距及朝向关系保持不变；隔墙、无落脚处或全部候选摆位被阻挡时仍拒绝。处决被拒绝时只清除请求等待状态，不会自动释放战技。

动画坐标按 Blender 原始骨架烘焙，1 Blender 单位为 0.25 格。剑动作中被处决者静态根骨 `Bone_B` 相对 `Bone_A` 为 `(-0.654533446, -5.532946110, 0)`，对应处决者朝向为 Minecraft `yaw=0` 时的世界偏移 `X=-0.163633361`、`Z=+1.383236527` 格；斧动作对应偏移为 `X=-0.163633361`、`Z=+0.979328990` 格。两套动作双方根朝向均相差约 180°。各自偏移随处决者朝向一起旋转，保留原动画的侧向偏移；动态躯干位移、根骨倾斜及肢体动作烘焙到模型部件，不能再次叠加到实体位置。渲染同时覆盖原模型动作、头身朝向插值、装备和盔甲，减少玩家与僵尸/骷髅的渲染差异造成的错位。

正常完成后，处决者接到当前动作末帧躯干髋部对应的水平站位。剑动作第 56 帧沿初始朝向前进 `0.4798867106` 格、朝向增加 `86.124866°`；斧动作第 30 帧不改变水平站位、朝向变化 `-144.676503°`。脚底均保持原地面高度。服务器在启动前和结束前都检查该站位、移动路径与落脚空间，完成消息同步最终位置和朝向，避免模型退回普通姿态时被拉回动画起点。中断或末尾空间被阻挡时不移动角色，也不结算处决伤害。

处决时统一使用 1.0 渲染比例，避免原版玩家的 0.9375 比例与怪物的比例不同导致接触位置偏移。服装、护甲、手持物品、披风和鞘翅随身体变换。第一人称镜头在 0.2 秒内平滑拉到第三人称，处决中可用鼠标环绕观看，沿用鼠标灵敏度、平滑和反转设置；镜头角度独立于双方固定朝向，并保留原版镜头防穿墙检查。正常结束时，处决者用 5 tick（0.25 秒）从重基后的末帧平滑回到普通姿态和比例，复位结束后才恢复原视角模式、移动和攻击等输入；中止、切换维度或离线时及时恢复。被处决者在死亡消散期间保持动画末帧，避免倒地后突然站直。

### 注册新的双人动作

双人处决动作可以放进世界数据包，无需改 Java。按此结构创建资源：

```text
pack.mcmeta
data/my_pack/fiercerbattles_actions/finisher.json
data/my_pack/fiercerbattles_animations/finish/executor.json
data/my_pack/fiercerbattles_animations/finish/victim.json
```

`pack.mcmeta` 示例（Minecraft 1.21.1）：`{"pack":{"pack_format":48,"description":"Paired finisher animations"}}`。

动作文件名对应 ID `my_pack:finisher`，内容示例：

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

两个动画 JSON 沿用 Blender 导出格式（`format: 1`、`duration_ticks`、`sample_step`、`parts`），通道采样是 `[x,y,z,qx,qy,qz,qw]`。当前 `humanoid_v1` 需要双方都包含 `head`、`body`、`rightArm`、`leftArm`、`rightLeg`、`leftLeg` 六通道；采样总时长必须和动作定义相同。几何偏移单位为方块，单轴偏移绝对值最多 16 格；开场到达锚点后，双方根位置在原动画阶段由服务端固定，所以根位移要烘焙进模型采样，并且 `geometry` 必须按导出的骨骼坐标仔细校准。

设置服务器 `config/fiercerbattles.json` 中的 `execution.action_id`（剑）或 `execution.axe_action_id`（斧）为 `my_pack:finisher`，重启以读取该配置，然后进入世界执行 `/reload` 加载或替换数据包动作。处决按主手剑/斧选择对应动作。服务端校验定义、资源、格式、时长和骨骼通道；无效动作会写入日志并跳过。播放前服务端会压缩同步动作和双方动画，客户端通过 SHA-256 校验与缓存，不需要玩家另装动画资源包。新动作必须适配目前唯一注册的 `fiercerbattles:humanoid_v1` 标准人形骨架；注册非人形模型还需编写对应模型适配器。

默认 Blender 源动画资源保留在 `assets/fiercerbattles/execution/executioner.json`、`victim.json` 与 `metadata.json`，每 0.125 tick 采样一次；内置数据包播放版本位于 `data/fiercerbattles/fiercerbattles_animations/execution/`，对应动作定义在 `data/fiercerbattles/fiercerbattles_actions/execution.json`。`tools/export_execution.py` 从原始 Blender 文件只读导出，并同时更新验证资源与内置动作采样，不会执行内嵌脚本或保存源文件。使用 Blender 的 `--python-exit-code 1 --python tools/export_execution.py -- --verify` 可回算网格顶点验证导出。原文件中被处决者的左臂在第 0 帧已经偏离肩部，且后续有脱落动作；本实现保留该轨迹。

斧动画验证资源位于 `assets/fiercerbattles/axe_execution/`，数据包 clips 位于 `data/fiercerbattles/fiercerbattles_animations/axe_execution/`，定义为 `data/fiercerbattles/fiercerbattles_actions/axe_execution.json`。导出 `axe_execution.blend` 时追加 `-- --name axe_execution --duration 30 --step 0.0625`，验证时再加 `--verify`。两套动画共用现有 `AnimationClip` / `AnimationPlayer`、人形适配器、双人同步和收尾机制。

## 11. 近战弹反

主手持原版或匠魂的剑、斧，按独立弹反键（默认 `R`，可在控制设置重绑）启动。弹反者可以移动、转向，镜头不变；原动画第 **5–12 tick（含两端）** 取消符合条件的近战伤害，让攻击者进入被反弹动作，不造成反伤。同一窗口可挡多个攻击者，环境伤害、投射物及处决伤害不触发成功弹反。

弹反原动作 17 tick，最初 3 tick 普通姿势淡入已计入时钟，结束另用 5 tick 复位。攻击者先倒放最近 5 tick 的真实六部位姿势历史，再完整播放 15 tick 被反弹动作，最后 5 tick 复位；全程 25 tick 由服务器禁止动作、移位并接管 AI，结束恢复原 AI 和重力。历史不足连续 6 tick 时记录 INFO 的 `ordinary fallback`，使用普通姿势衔接。第一、第三人称均显示对应手部或模型动作，不切换镜头。

弹反与处决互斥，已有 busy 状态不能互相覆盖；旧排队攻击按 epoch 作废。资源独立重载、压缩同步和 hash 校验，原处决资源与通用播放器保持不变。源码入口、资源路径、安全修改边界和只读导出验证命令见 [弹反模块速查](docs/parry_module.md)。

# Fiercer Battles 配置详解

## 1. 功能与按键

Fiercer Battles 新增 **战技** 按键。默认按键是 `V`，可在 Minecraft 的“选项 → 控制 → Fiercer Battles → 战技”中重新绑定。

战技不会替换普通左键攻击。玩家按下战技键时，模组会读取当前武器对应的战技 JSON，并根据**当前普通连击段**选择应当播放和结算的战技攻击。动画、命中框、伤害倍率、声音、粒子、突进等都使用所选战技段的数据。

- 武器与战技数据必须同时安装在**客户端和服务器端**可读取的资源中。将数据作为 Mod 自带数据包或双方相同的数据包安装最稳妥。
- 战技按键只有在手持已配置战技的 Better Combat 武器、且当前连击段有可用战技时才会生效。
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
| `json` | 字符串 | 是 | 战技文件名或资源位置。`"debug.json"` 表示 `data/<命名空间>/fiercerbattles/combat_skills/debug.json`。|
| `attributes` | 对象 | 是 | 武器的常规 Better Combat 属性；其普通 `attacks` 仍由左键攻击使用。|
| `parent` | 字符串 | 否 | Better Combat 原有继承目标，例如 `"bettercombat:dagger"`。|

若 `json` 使用带命名空间的完整资源位置，必须写出资源路径和扩展名，例如：

```json
"json": "example:fiercerbattles/combat_skills/debug.json"
```

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

如果普通武器只有两段攻击，就无法通过第三段触发战技；普通武器的连击段数必须覆盖要使用的战技下标。

## 5. `attributes` 顶层参数

这些参数可用于普通武器 JSON 和战技 JSON。战技触发后优先采用战技 JSON 中的值。

| 参数 | 类型/默认值 | 说明 |
| --- | --- | --- |
| `attack_range` | 数字，默认 `0` | 武器基础攻击距离（格）。攻击段的 `range_multiplier` 会在此基础上相乘。|
| `range_bonus` | 数字，默认 `0` | Better Combat 的固定额外距离（格）。与攻击段的 Fiercer Battles `range_bonus` 不同。|
| `two_handed` | 布尔值 | 是否双手武器；影响手部姿势与副手使用。|
| `pose` | 动画 ID 字符串 | 主手姿势，例如 `bettercombat:pose_two_handed_scythe`。|
| `off_hand_pose` | 动画 ID 字符串 | 副手姿势；适用于双持配置。|
| `category` | 字符串 | 武器类别，供 Better Combat 的条件和兼容逻辑使用。|
| `attacks` | 数组 | 攻击/战技段列表。普通武器中每项必须是完整的有效攻击；战技文件允许用 `null` 做空位。|
| `trail_appearance` | 对象 | 武器拖影外观。结构见第 8 节。|

## 6. `attacks` 攻击段参数

### Better Combat 原生参数

| 参数 | 类型/默认值 | 说明 |
| --- | --- | --- |
| `conditions` | 字符串数组 | 所有条件都必须满足才会使用该普通攻击。可用值：`NOT_DUAL_WIELDING`、`DUAL_WIELDING_ANY`、`DUAL_WIELDING_SAME`、`DUAL_WIELDING_SAME_CATEGORY`、`NO_OFFHAND_ITEM`、`OFF_HAND_SHIELD`、`MAIN_HAND_ONLY`、`OFF_HAND_ONLY`、`MOUNTED`、`NOT_MOUNTED`。|
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

`Multiplehits` 使用一个字符串，包含两个等长数组：前一组是动画开始后的命中帧，后一组是对应帧的独立武器伤害倍率。

- `"[10,20,30],[1.0,1.0,0.5]"` 表示动画第 10、20、30 帧各结算一次命中，伤害依次为武器基础攻击伤害的 100%、100%、50%。
- 帧以游戏逻辑 tick 计数，`20` 帧约等于 1 秒；数值必须是从小到大排列的非负整数。
- 倍率必须为非负数；`0.5` 即 50%，`2.0` 即 200%。
- 配置 `Multiplehits` 后，Better Combat 原本由 `upswing` 触发的单次命中会被替换，不会额外造成一次普通伤害。
- 每一次多段命中都使用该攻击段的 `hitbox`、`angle`、`range_multiplier`、音效、粒子和目标筛选。
- **多段伤害倍率完全忽略该攻击段的 `damage_multiplier`。** 例如 `damage_multiplier: 3.0` 和 `Multiplehits: "[10],[1.0]"` 仍只造成武器基础伤害的 100%。
- 多段命中整体只推进一次普通连击段，不会每一段都让连击额外前进。
- 所有命中帧必须落在当前攻击动画/冷却尚未结束的时间内；超出挥击结束后的帧不会命中。

## 7. `config/fiercerbattles.json` 全局覆盖参数

此文件按动画 ID 覆盖对应攻击或战技段。它不能启用战技；战技必须通过武器 JSON 中的 `Combatskill` 和 `json` 启用。

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

攻击段内直接写的同名 Fiercer Battles 参数优先于全局 `config/fiercerbattles.json`。


## 7. 常见问题

- **按战技键没有反应**：确认武器属性文件有 `"Combatskill": true`，`json` 路径正确，当前连击段之前存在至少一个非 `null` 的战技攻击。
- **第三段战技提前释放**：检查数组中是否保留了 `null` 占位；数组元素会按下标对应连击段，不能省略中间段。
- **第三段按键无反应**：确认普通武器的 `attributes.attacks` 至少有三段，而且没有被 `conditions` 过滤掉第三段。
- **多段伤害只命中一次**：确认 `Multiplehits` 的两组数组长度相同、帧递增，且所有帧都在攻击动画结束前。
- **联机只有动画没有正确伤害或完全无效**：确保客户端和服务器都装有 Fiercer Battles、Better Combat，以及相同的战技数据资源。
- **战技文件改完没生效**：重新加载数据包或重启游戏/服务器；资源加载时会重新读取战技定义。

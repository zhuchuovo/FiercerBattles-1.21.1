# Fiercer Battles

Better Combat 1.21.1 扩展 Mod：允许你指定某个攻击动画段落“更慢/更重”。

## 原理

- 不修改 BetterCombat 本体。
- 通过字符串 Mixin 挂到 `net.bettercombat.logic.WeaponRegistry#resolveAttributes`。
- 在 BetterCombat 加载武器属性后，根据动画 ID 覆写：
  - `upswing`：前摇占比，越大越慢/越重
  - `movement_speed_multiplier`：攻击期间移动速度，越小越慢

## 配置文件

放到：

```
config/fiercerbattles.json
```

示例：

```json
{
  "entries": [
    {
      "animation": "bettercombat:one_handed_stab",
      "animation_speed": 0.8,
      "upswing": 0.7,
      "movement_speed_multiplier": 0.8,
      "range_multiplier": 1.1,
      "attack_displacement": "1.5,1,20"
    },
    {
      "animation": "bettercombat:two_handed_slam",
      "animation_speed": 0.75,
      "upswing": 0.75,
      "movement_speed_multiplier": 0.7,
      "range_multiplier": 0.9,
      "attack_displacement": "1,2,10"
    }
  ]
}
```

- `animation`：要调整的攻击动画 ID，对应 BetterCombat 武器 JSON 中某一段的 `animation`。
- `animation_speed`：可选，动画播放速度倍率，`1.0` 为原速，`0.8` 表示更慢。
- `attack_speed_multiplier`：可选，该段攻击的真实攻速/冷却倍率。`1.0` 为原速，`1.2` 表示冷却变短、攻速更快，`0.8` 表示更慢。不会改变动画播放速度。
- `upswing`：可选，推荐 `0.5~0.8`，越大该段前摇越长。
- `movement_speed_multiplier`：可选，推荐 `0.5~1.0`，越小攻击期间移动越慢。
- `range_multiplier`：可选，该段攻击范围倍率，`1.0` 为原范围，`1.1` 表示加长 10%，`0.9` 表示缩短 10%。
- `attack_displacement`：可选，突进位移，格式为 `"持续秒数,位移格数"`。
  - 例：`"0.2,1"` 表示在 0.2 秒内向前突进 1 格。
- `range_bonus`：可选，额外攻击范围（格），例如 `0.5` 表示该段攻击范围 +0.5 格。
- `after_cooldown`：可选，后摇/额外冷却时间（秒），例如 `0.2` 表示该轮攻击冷却结束后还要额外等待 0.2 秒才能进入下一轮。

这些字段也可以直接写在 BetterCombat 的 `weapon_attributes` JSON 的对应攻击段里，FiercerBattles 会自动读取。

> 注意：如果使用数据包方式，必须把数据包放入当前存档的 `datapacks` 文件夹并启用；否则 FiercerBattles 读不到这些字段。也可以直接写进 `config/fiercerbattles.json`，不依赖数据包。

## 依赖

- NeoForge 21.1.241
- Better Combat 1.21.1


## 战技

- 新增默认 V 键的战技按键，可在按键设置中修改。
- 完整的战技 JSON 写法、连击段规则与全部参数见 [详细.md](详细.md)。

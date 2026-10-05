# 代码战技钩子（Combat Skill Hooks）

数据包里的战技 JSON 只能描述「挥出去的形状」——动画、判定框、伤害倍率、多段帧、位移、声音、药水。
凡是需要**读取运行时状态**（目标血量、实际伤害、护甲值、周围有几个敌人）、
**持续作用**（逐 tick 牵引）、或**改变实体属性**（临时削甲）的效果，JSON 都表达不了。

这一层由 `com.fiercerbattles.combatskill` 包补上：**每个战技一个独立目录，一个文件统一注册，JSON 直接引用**。

## 目录结构

```
src/main/java/com/fiercerbattles/combatskill/
├── CombatSkillHooks.java        ← 统一注册表 + JSON 调用入口（加战技只改这里）
├── CombatSkillHook.java         ← 战技契约
├── CombatSkillContext.java      ← 回调上下文（伤害改写 / 参数 / 状态 / 世界交互）
├── CombatSkillTrigger.java      ← 触发时机
└── skills/
    ├── soulharvest/    SoulHarvestSkill.java     噬魂
    ├── thunderchain/   ThunderChainSkill.java    连锁雷霆
    ├── armorshred/     ArmorShredSkill.java      碎甲
    ├── crimsonring/    CrimsonRingSkill.java     血环
    └── gravitywell/    GravityWellSkill.java     引力井
```

## JSON 怎么调用

在战技文件的 `skill` 块里加两个字段即可，其余字段照旧：

```json
{
  "skill": {
    "duration_seconds": 6.0,
    "cooldown": 8,
    "hook": "soul_harvest",
    "hook_params": {
      "execute_threshold": 0.30,
      "execute_bonus": 0.60,
      "life_steal": 0.30,
      "kill_heal": 4.0
    },
    "sound": { "id": "minecraft:block.beacon.activate" }
  },
  "attributes": {
    "attack_range": 3.0,
    "attacks": [
      { "hitbox": "FORWARD_BOX", "angle": 90, "damage_multiplier": 1.4, "animation": "bettercombat:overhead_slash" }
    ]
  }
}
```

- `hook` 写短 id（`soul_harvest`）或 `fiercerbattles:` 全名；也接受本包内实现类的全限定名。
- `hook_params` 的内容原样交给钩子，同一个钩子可以被不同战技用不同数值复用。留空则全部走钩子默认值。
- 名字写错只会记一条日志，战技本身的 JSON 效果照常生效。

## 触发时机

| 时机 | 何时触发 | 能做什么 |
| --- | --- | --- |
| `CAST` | 服务端接受战技、攻击还没落下 | 起手 AOE、开增益、布置持续效果 |
| `HIT` | 挥击命中、伤害还能改 | 改伤害、取消这次命中 |
| `DAMAGE` | 目标真正吃下伤害之后 | 读**真实伤害**做吸血、触发连锁 |
| `TICK` | 战技生效期间每个 tick | 逐 tick 牵引、持续结算、到点还原 |

会话（一次战技激活）从 `CAST` 开始：命中窗口 20~100 tick，TICK 窗口由 `duration_seconds` 决定。
结束时会回调一次 `onExpire`，让钩子回收自己挂上去的东西（比如削甲用的属性修改器）。

## 内置战技

| id | 时机 | 参数 |
| --- | --- | --- |
| `soul_harvest` | HIT + DAMAGE | `execute_threshold` `execute_bonus` `life_steal` `kill_heal` |
| `thunder_chain` | DAMAGE | `jumps` `radius` `decay` `min_ratio` `particle` `sound` |
| `armor_shred` | HIT + TICK | `damage_per_armor` `armor_reduction` `shred_seconds` |
| `crimson_ring` | CAST | `radius` `damage` `knockback` `max_targets` `heal_per_hit` `heal_cap` |
| `gravity_well` | CAST + TICK | `duration_seconds` `radius` `pull_strength` `damage_per_second` `max_targets` |

`gravity_well` 靠 TICK 活着，所以战技自身的 `duration_seconds` 要盖过它的 `duration_seconds`。

## 加一个新战技

1. 在 `combatskill/skills/` 下建目录，包名与目录同名，实现 `CombatSkillHook`：

```java
package com.fiercerbattles.combatskill.skills.myskill;

public final class MySkill implements CombatSkillHook {
    @Override
    public String id() {
        return "my_skill";
    }

    @Override
    public Set<CombatSkillTrigger> triggers() {
        return EnumSet.of(CombatSkillTrigger.HIT);
    }

    @Override
    public void onHit(CombatSkillContext context) {
        context.addDamage(context.param("bonus", 2.0));
    }
}
```

2. 在 `CombatSkillHooks` 的 static 块（战技登记表）里加一行 `register(new MySkill());`。
3. 战技 JSON 里写 `"hook": "my_skill"`。

## 常用上下文接口

- 参数：`param(key, fallback)` / `intParam` / `longParam` / `flag` / `text`
- 伤害：`damage()` `setDamage` `addDamage` `multiplyDamage` `setCancelled`
- 世界：`enemiesIn(radius, limit)` `strike(victim, amount)` `applyMobEffect(...)` `sound(...)` `particles(...)` `trail(...)`
- 跨时机传值：`state(key)` / `putState(key, value)`

`strike()` 造成的伤害归属玩家（算击杀、算经验），但不会二次套用战技加成，也不会递归触发战技自己。

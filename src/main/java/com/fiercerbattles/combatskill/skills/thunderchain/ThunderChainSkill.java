package com.fiercerbattles.combatskill.skills.thunderchain;

import com.fiercerbattles.combatskill.CombatSkillContext;
import com.fiercerbattles.combatskill.CombatSkillHook;
import com.fiercerbattles.combatskill.CombatSkillTrigger;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 连锁雷霆 — 命中后向邻近目标跳跃，每跳衰减，直到跳数用尽或衰减到下限。
 *
 * <p>目标在运行时才存在，"跳几个、跳给谁、每跳打多少"是算出来的，
 * JSON 里没有能描述这条链的字段。
 *
 * <pre>
 * "hook": "thunder_chain",
 * "hook_params": {
 *   "jumps": 3,            // 最多再跳几个目标
 *   "radius": 4.0,         // 每跳的搜寻半径
 *   "decay": 0.65,         // 每跳伤害乘数
 *   "min_ratio": 0.15,     // 衰减到低于此比例就停
 *   "particle": "minecraft:electric_spark",
 *   "sound": "minecraft:entity.lightning_bolt.thunder"
 * }
 * </pre>
 */
public final class ThunderChainSkill implements CombatSkillHook {
    @Override
    public String id() {
        return "thunder_chain";
    }

    @Override
    public Set<CombatSkillTrigger> triggers() {
        return EnumSet.of(CombatSkillTrigger.DAMAGE);
    }

    @Override
    public void onDamage(CombatSkillContext context) {
        double damage = Math.max(0, context.damage());
        if (damage <= 0) {
            return;
        }
        int jumps = Math.max(1, context.intParam("jumps", 3));
        double radius = context.param("radius", 4.0);
        double decay = context.param("decay", 0.65);
        double floor = context.param("min_ratio", 0.15);
        String particle = context.text("particle", "minecraft:electric_spark");
        String sound = context.text("sound", "minecraft:entity.lightning_bolt.thunder");

        Set<UUID> visited = new HashSet<>();
        visited.add(context.target().getUUID());
        Vec3 from = arcPoint(context.target());

        double ratio = 1.0;
        for (int jump = 0; jump < jumps; jump++) {
            ratio *= decay;
            if (ratio < floor) {
                break;
            }
            LivingEntity next = nearestUnvisited(context, from, radius, visited);
            if (next == null) {
                break;
            }
            visited.add(next.getUUID());
            Vec3 to = arcPoint(next);
            context.trail(particle, from, to, 8);
            context.particles(particle, to, 10, 0.3, 0.05);
            context.sound(sound, 0.55F, 1.0F + jump * 0.15F);
            context.strike(next, damage * ratio);
            from = to;
        }
    }

    private static Vec3 arcPoint(LivingEntity entity) {
        return entity.position().add(0, entity.getBbHeight() * 0.55, 0);
    }

    private static LivingEntity nearestUnvisited(
            CombatSkillContext context,
            Vec3 from,
            double radius,
            Set<UUID> visited
    ) {
        // enemiesIn 已经按距离排好序，第一个没被跳过的就是最近的那个。
        for (LivingEntity candidate : context.enemiesIn(from, radius, 0)) {
            if (!visited.contains(candidate.getUUID())) {
                return candidate;
            }
        }
        return null;
    }
}

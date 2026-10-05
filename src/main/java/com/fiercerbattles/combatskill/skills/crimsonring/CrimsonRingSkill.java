package com.fiercerbattles.combatskill.skills.crimsonring;

import com.fiercerbattles.combatskill.CombatSkillContext;
import com.fiercerbattles.combatskill.CombatSkillHook;
import com.fiercerbattles.combatskill.CombatSkillTrigger;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 血环 — 起手瞬间以玩家为圆心扫一圈，命中几个就回多少血。
 *
 * <p>"周围有几个敌人、打中了几个、因此回多少血"全部是运行时才知道的数字，
 * JSON 只能描述一条向前的攻击判定框。
 *
 * <pre>
 * "hook": "crimson_ring",
 * "hook_params": {
 *   "radius": 4.5,          // 环形半径
 *   "damage": 6.0,          // 每个目标的伤害
 *   "knockback": 0.6,       // 向外击退强度
 *   "max_targets": 8,
 *   "heal_per_hit": 1.0,    // 每命中一个回复的生命
 *   "heal_cap": 6.0
 * }
 * </pre>
 */
public final class CrimsonRingSkill implements CombatSkillHook {
    @Override
    public String id() {
        return "crimson_ring";
    }

    @Override
    public Set<CombatSkillTrigger> triggers() {
        return EnumSet.of(CombatSkillTrigger.CAST);
    }

    @Override
    public void onCast(CombatSkillContext context) {
        double radius = context.param("radius", 4.5);
        double damage = context.param("damage", 6.0);
        double knockback = context.param("knockback", 0.6);
        int maxTargets = context.intParam("max_targets", 8);

        drawRing(context, radius);
        context.sound("minecraft:entity.player.attack.sweep", 1.0F, 0.7F);

        List<LivingEntity> victims = context.enemiesIn(radius, maxTargets);
        int hits = 0;
        for (LivingEntity victim : victims) {
            if (context.strike(victim, damage)) {
                hits++;
            }
            pushAway(context, victim, knockback);
        }

        double healed = Math.min(context.param("heal_cap", 6.0), context.param("heal_per_hit", 1.0) * hits);
        if (healed > 0) {
            context.player().heal((float) healed);
        }
    }

    private static void pushAway(CombatSkillContext context, LivingEntity victim, double strength) {
        if (strength <= 0 || !victim.isAlive()) {
            return;
        }
        // knockback 参数是"攻击源方向"，取反即把目标推离玩家。
        Vec3 away = victim.position().subtract(context.player().position());
        double length = Math.sqrt(away.x * away.x + away.z * away.z);
        if (length < 1.0E-3) {
            return;
        }
        victim.knockback(strength, -away.x / length, -away.z / length);
    }

    private static void drawRing(CombatSkillContext context, double radius) {
        Vec3 center = context.player().position();
        int points = Math.max(16, (int) Math.round(radius * 10));
        for (int index = 0; index < points; index++) {
            double angle = (Math.PI * 2 * index) / points;
            Vec3 point = center.add(Math.cos(angle) * radius, 0.2, Math.sin(angle) * radius);
            context.particles("minecraft:sweep_attack", point, 1, 0.0, 0.0);
        }
    }
}

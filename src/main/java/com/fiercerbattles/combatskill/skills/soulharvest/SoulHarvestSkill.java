package com.fiercerbattles.combatskill.skills.soulharvest;

import com.fiercerbattles.combatskill.CombatSkillContext;
import com.fiercerbattles.combatskill.CombatSkillHook;
import com.fiercerbattles.combatskill.CombatSkillTrigger;
import net.minecraft.world.entity.LivingEntity;

import java.util.EnumSet;
import java.util.Set;

/**
 * 噬魂 — 残血加重，命中后按真实伤害回血，击杀再补一口。
 *
 * <p>这两件事 JSON 都写不出来：一个要读目标"此刻"的血量比例，
 * 一个要读目标实际承受了多少伤害。
 *
 * <pre>
 * "hook": "soul_harvest",
 * "hook_params": {
 *   "execute_threshold": 0.30,   // 目标血量低于此比例时这一击加重
 *   "execute_bonus": 0.60,       // 加重幅度
 *   "life_steal": 0.30,          // 按实际伤害的比例回血
 *   "kill_heal": 4.0             // 这一击击杀目标时额外回血
 * }
 * </pre>
 */
public final class SoulHarvestSkill implements CombatSkillHook {
    private static final String LEECH_STATE = "life_steal";

    @Override
    public String id() {
        return "soul_harvest";
    }

    @Override
    public Set<CombatSkillTrigger> triggers() {
        return EnumSet.of(CombatSkillTrigger.HIT, CombatSkillTrigger.DAMAGE);
    }

    @Override
    public void onHit(CombatSkillContext context) {
        LivingEntity target = context.target();
        double threshold = context.param("execute_threshold", 0.30);
        double maxHealth = Math.max(1.0F, target.getMaxHealth());
        boolean wounded = target.getHealth() / maxHealth <= threshold;
        if (wounded) {
            context.multiplyDamage(1.0 + context.param("execute_bonus", 0.60));
            context.particles("minecraft:soul", target.position().add(0, target.getBbHeight() * 0.5, 0),
                    14, 0.35, 0.02);
        }
        // 把这次激活的吸血比例带进 DAMAGE 阶段。
        context.putState(LEECH_STATE, context.param("life_steal", 0.30));
    }

    @Override
    public void onDamage(CombatSkillContext context) {
        double dealt = Math.max(0, context.damage());
        if (dealt <= 0) {
            return;
        }
        Object stored = context.state(LEECH_STATE);
        double leech = stored instanceof Number number ? number.doubleValue() : 0.30;
        double healed = dealt * leech;
        if (context.target().isDeadOrDying()) {
            healed += context.param("kill_heal", 4.0);
        }
        if (healed <= 0) {
            return;
        }
        context.player().heal((float) healed);
        context.particles("minecraft:heart",
                context.player().position().add(0, context.player().getBbHeight() * 0.8, 0),
                4, 0.3, 0.01);
    }
}

package com.fiercerbattles.combatskill.skills.armorshred;

import com.fiercerbattles.FiercerBattles;
import com.fiercerbattles.combatskill.CombatSkillContext;
import com.fiercerbattles.combatskill.CombatSkillHook;
import com.fiercerbattles.combatskill.CombatSkillTrigger;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.EnumSet;
import java.util.Set;

/**
 * 碎甲 — 按目标"此刻"的护甲值加重伤害，并临时削掉一段护甲，到点还原。
 *
 * <p>护甲是运行时属性，削甲是往实体身上挂动态属性修改器、还要自己计时回收，
 * 都不是 JSON 能表达的东西。
 *
 * <pre>
 * "hook": "armor_shred",
 * "hook_params": {
 *   "damage_per_armor": 0.18,   // 每 1 点护甲额外造成的伤害
 *   "armor_reduction": 6.0,     // 削掉的护甲点数
 *   "shred_seconds": 4.0        // 削甲持续秒数
 * }
 * </pre>
 */
public final class ArmorShredSkill implements CombatSkillHook {
    private static final String VICTIM_STATE = "shred_victim";
    private static final String MODIFIER_STATE = "shred_modifier";
    private static final String UNTIL_STATE = "shred_until";

    @Override
    public String id() {
        return "armor_shred";
    }

    @Override
    public Set<CombatSkillTrigger> triggers() {
        return EnumSet.of(CombatSkillTrigger.HIT, CombatSkillTrigger.TICK);
    }

    @Override
    public void onHit(CombatSkillContext context) {
        LivingEntity target = context.target();
        AttributeInstance armor = target.getAttribute(Attributes.ARMOR);
        double armorValue = armor != null ? armor.getValue() : 0;

        double perPoint = context.param("damage_per_armor", 0.18);
        if (armorValue > 0 && perPoint > 0) {
            context.addDamage(armorValue * perPoint);
        }

        double reduction = context.param("armor_reduction", 6.0);
        double seconds = context.param("shred_seconds", 4.0);
        if (armor == null || reduction <= 0 || seconds <= 0) {
            return;
        }

        ResourceLocation modifierId = modifierId(target);
        armor.removeModifier(modifierId);
        armor.addOrUpdateTransientModifier(new AttributeModifier(
                modifierId,
                -reduction,
                AttributeModifier.Operation.ADD_VALUE
        ));

        context.putState(VICTIM_STATE, target);
        context.putState(MODIFIER_STATE, modifierId);
        context.putState(UNTIL_STATE, context.gameTime() + Math.round(seconds * 20.0));
        context.sound("minecraft:block.anvil.land", 0.7F, 1.6F);
        context.particles("minecraft:crit",
                target.position().add(0, target.getBbHeight() * 0.6, 0), 12, 0.3, 0.05);
    }

    @Override
    public void onTick(CombatSkillContext context) {
        if (!(context.state(VICTIM_STATE) instanceof LivingEntity victim)) {
            return;
        }
        Object stored = context.state(UNTIL_STATE);
        long until = stored instanceof Number number ? number.longValue() : Long.MAX_VALUE;
        if (context.gameTime() >= until) {
            restore(context, victim);
        }
    }

    @Override
    public void onExpire(CombatSkillContext context) {
        if (context.state(VICTIM_STATE) instanceof LivingEntity victim) {
            restore(context, victim);
        }
    }

    private static void restore(CombatSkillContext context, LivingEntity victim) {
        if (context.state(MODIFIER_STATE) instanceof ResourceLocation modifierId) {
            AttributeInstance armor = victim.getAttribute(Attributes.ARMOR);
            if (armor != null) {
                armor.removeModifier(modifierId);
            }
        }
        context.putState(VICTIM_STATE, null);
        context.putState(MODIFIER_STATE, null);
        context.putState(UNTIL_STATE, null);
    }

    private static ResourceLocation modifierId(LivingEntity victim) {
        return ResourceLocation.fromNamespaceAndPath(
                FiercerBattles.MOD_ID,
                "skill/armor_shred/" + Integer.toUnsignedString(victim.getUUID().hashCode())
        );
    }
}

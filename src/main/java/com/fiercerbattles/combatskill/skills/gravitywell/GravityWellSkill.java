package com.fiercerbattles.combatskill.skills.gravitywell;

import com.fiercerbattles.combatskill.CombatSkillContext;
import com.fiercerbattles.combatskill.CombatSkillHook;
import com.fiercerbattles.combatskill.CombatSkillTrigger;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 引力井 — 起手后持续若干秒，每 tick 把范围内的敌人往玩家身上拽，并每秒结算一次伤害。
 *
 * <p>逐 tick 改写实体速度、连续牵引位置，这类"持续作用"在 JSON 里没有对应的字段，
 * 只能靠代码驱动。
 *
 * <pre>
 * "hook": "gravity_well",
 * "hook_params": {
 *   "duration_seconds": 2.0,     // 牵引时长（需要战技自身的 duration 覆盖它）
 *   "radius": 6.0,
 *   "pull_strength": 0.35,       // 每 tick 施加的牵引速度
 *   "damage_per_second": 2.0,
 *   "max_targets": 12
 * }
 * </pre>
 */
public final class GravityWellSkill implements CombatSkillHook {
    private static final String UNTIL_STATE = "pull_until";
    private static final String LAST_DAMAGE_STATE = "last_damage_tick";

    @Override
    public String id() {
        return "gravity_well";
    }

    @Override
    public Set<CombatSkillTrigger> triggers() {
        return EnumSet.of(CombatSkillTrigger.CAST, CombatSkillTrigger.TICK);
    }

    @Override
    public void onCast(CombatSkillContext context) {
        long ticks = Math.max(20L, Math.round(context.param("duration_seconds", 2.0) * 20.0));
        context.putState(UNTIL_STATE, context.gameTime() + ticks);
        context.putState(LAST_DAMAGE_STATE, context.gameTime());
        context.sound("minecraft:block.beacon.ambient", 1.0F, 0.6F);
        drawWell(context, context.param("radius", 6.0));
    }

    @Override
    public void onTick(CombatSkillContext context) {
        if (!(context.state(UNTIL_STATE) instanceof Number stored)) {
            return;
        }
        long now = context.gameTime();
        if (now > stored.longValue()) {
            return;
        }

        double radius = context.param("radius", 6.0);
        double pull = context.param("pull_strength", 0.35);
        Vec3 center = context.player().position();
        List<LivingEntity> victims = context.enemiesIn(radius, context.intParam("max_targets", 12));

        for (LivingEntity victim : victims) {
            pullToward(victim, center, pull, radius);
        }

        // 每秒结算一次，避开原版 20 tick 的受击无敌帧。
        Object last = context.state(LAST_DAMAGE_STATE);
        long lastTick = last instanceof Number number ? number.longValue() : now;
        double perSecond = context.param("damage_per_second", 2.0);
        if (now - lastTick >= 20 && perSecond > 0) {
            context.putState(LAST_DAMAGE_STATE, now);
            for (LivingEntity victim : victims) {
                context.strike(victim, perSecond);
            }
        }
    }

    private static void pullToward(LivingEntity victim, Vec3 center, double pull, double radius) {
        if (pull <= 0 || !victim.isAlive()) {
            return;
        }
        Vec3 offset = center.subtract(victim.position());
        double distance = Math.sqrt(offset.x * offset.x + offset.z * offset.z);
        if (distance < 0.75 || distance > radius) {
            return;
        }
        // 贴近后逐渐松手，免得在玩家身上反复抽搐。
        double strength = pull * Math.min(1.0, distance / 1.5);
        Vec3 step = new Vec3(offset.x, 0, offset.z).normalize().scale(strength);
        victim.setDeltaMovement(victim.getDeltaMovement().add(step));
        // 让服务端把改写后的速度同步给客户端。
        victim.hurtMarked = true;
    }

    private static void drawWell(CombatSkillContext context, double radius) {
        Vec3 center = context.player().position();
        int points = Math.max(20, (int) Math.round(radius * 8));
        for (int index = 0; index < points; index++) {
            double angle = (Math.PI * 2 * index) / points;
            Vec3 point = center.add(Math.cos(angle) * radius, 0.25, Math.sin(angle) * radius);
            context.particles("minecraft:portal", point, 1, 0.05, 0.0);
        }
    }
}

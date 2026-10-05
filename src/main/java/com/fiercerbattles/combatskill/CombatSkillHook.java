package com.fiercerbattles.combatskill;

import java.util.EnumSet;
import java.util.Set;

/**
 * 一个代码战技的契约。
 *
 * <p>实现类放在 {@code com.fiercerbattles.combatskill.skills.<战技名>} 包下，
 * 由 {@link CombatSkillHooks} 统一注册；数据包里的战技 JSON 用
 * {@code "skill": { "hook": "<id>" }} 引用它。
 *
 * <p>所有回调都在服务端调用。回调里抛出的异常会被框架吞掉并记入日志，
 * 不会把一次攻击变成崩溃。
 */
public interface CombatSkillHook {
    /** 钩子 id，例如 {@code soul_harvest}；不带命名空间时默认补上 {@code fiercerbattles:}。 */
    String id();

    /** 该钩子需要的触发时机；未声明的时机不会被调度。 */
    default Set<CombatSkillTrigger> triggers() {
        return EnumSet.of(CombatSkillTrigger.CAST);
    }

    /** 战技被接受时调用一次。 */
    default void onCast(CombatSkillContext context) {
    }

    /** 命中目标时调用，{@link CombatSkillContext#damage()} 可读可写。 */
    default void onHit(CombatSkillContext context) {
    }

    /** 目标实际承受伤害后调用，{@link CombatSkillContext#damage()} 是真实伤害。 */
    default void onDamage(CombatSkillContext context) {
    }

    /** 战技生效期间每个 tick 调用一次。 */
    default void onTick(CombatSkillContext context) {
    }

    /**
     * 会话结束（持续时间内没有新的命中、玩家重生或下线）时调用一次，
     * 用于回收钩子自己挂上去的状态。
     */
    default void onExpire(CombatSkillContext context) {
    }
}

package com.fiercerbattles.combatskill;

/**
 * 战技钩子的触发时机。JSON 里的战技只能描述"挥出去是什么样"，
 * 由此处补充它在各个时机上"做什么事"。
 */
public enum CombatSkillTrigger {
    /** 服务端接受战技、攻击尚未落下时触发一次。 */
    CAST,
    /** 战技的挥击命中目标、伤害仍可改写时触发（多段命中的每一段各触发一次）。 */
    HIT,
    /** 目标实际承受本次伤害之后触发，此时可读到真实的伤害数值。 */
    DAMAGE,
    /** 战技生效期间每个玩家 tick 触发一次。 */
    TICK
}

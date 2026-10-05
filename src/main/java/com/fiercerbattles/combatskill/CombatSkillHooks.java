package com.fiercerbattles.combatskill;

import com.fiercerbattles.FiercerBattles;
import com.fiercerbattles.combatskill.skills.armorshred.ArmorShredSkill;
import com.fiercerbattles.combatskill.skills.crimsonring.CrimsonRingSkill;
import com.fiercerbattles.combatskill.skills.gravitywell.GravityWellSkill;
import com.fiercerbattles.combatskill.skills.soulharvest.SoulHarvestSkill;
import com.fiercerbattles.combatskill.skills.thunderchain.ThunderChainSkill;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.slf4j.Logger;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 代码战技的统一注册表，也是数据包 JSON 唯一的调用入口。
 *
 * <h2>加一个战技</h2>
 * <ol>
 *   <li>在 {@code combatskill/skills/<战技名>/} 目录（同名包）下实现 {@link CombatSkillHook}；</li>
 *   <li>在下面 static 块里加一行 {@code register(new XxxSkill())}；</li>
 *   <li>战技 JSON 里写 {@code "skill": { "hook": "<id>", "hook_params": { ... } }}。</li>
 * </ol>
 *
 * <p>框架本身不认识任何一个具体战技：{@link com.fiercerbattles.FiercerBattlesSkillEffects}
 * 只在四个时机回调本类，由本类把事件派发给当前生效的钩子。
 */
public final class CombatSkillHooks {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** 只允许按类名实例化这个包下的钩子。 */
    private static final String HOOK_PACKAGE = "com.fiercerbattles.combatskill.";
    /** 命中窗口上限（tick）：多段命中的整段动画都落在这个范围内。 */
    private static final long MAX_HIT_WINDOW_TICKS = 100L;
    /** 没有配 duration 时，战技至少有这么长的会话寿命。 */
    private static final long MIN_SESSION_TICKS = 20L;

    private static final Map<String, CombatSkillHook> HOOKS = new LinkedHashMap<>();
    private static final Map<String, Optional<CombatSkillHook>> CLASS_HOOKS = new ConcurrentHashMap<>();
    private static final Map<UUID, Session> ACTIVE = new ConcurrentHashMap<>();
    private static final ThreadLocal<Integer> HOOK_DAMAGE_DEPTH = ThreadLocal.withInitial(() -> 0);

    static {
        // ==================== 战技登记表 ====================
        // 新增战技：在 combatskill/skills/ 下建目录写实现，然后在这里加一行。
        register(new SoulHarvestSkill());     // 噬魂      — 斩杀加重 + 按真实伤害吸血
        register(new ThunderChainSkill());    // 连锁雷霆  — 命中后向邻近目标跳跃衰减伤害
        register(new ArmorShredSkill());      // 碎甲      — 按目标护甲加重并临时削甲
        register(new CrimsonRingSkill());     // 血环      — 以玩家为中心的环形斩击
        register(new GravityWellSkill());     // 引力井    — 持续把范围内敌人拉向玩家
        // ===================================================
    }

    private CombatSkillHooks() {
    }

    // ------------------------------------------------------------------
    // 注册与查表
    // ------------------------------------------------------------------

    /** 登记一个钩子。id 重复时后者覆盖前者并记一条警告。 */
    public static void register(CombatSkillHook hook) {
        if (hook == null) {
            return;
        }
        String id = normalize(hook.id());
        if (id.isEmpty()) {
            LOGGER.warn("Fiercer Battles: ignored a combat skill hook with a blank id ({})",
                    hook.getClass().getName());
            return;
        }
        CombatSkillHook previous = HOOKS.put(id, hook);
        if (previous != null && previous.getClass() != hook.getClass()) {
            LOGGER.warn("Fiercer Battles: combat skill hook '{}' replaced {} with {}",
                    id, previous.getClass().getName(), hook.getClass().getName());
        }
    }

    /**
     * 按 JSON 里写的 hook 值查钩子。除了注册的短 id，也接受本包内钩子类的全限定名，
     * 于是 {@code "hook": "com.fiercerbattles.combatskill.skills.soulharvest.SoulHarvestSkill"}
     * 同样有效。
     *
     * @return 找不到时返回 {@code null}
     */
    public static CombatSkillHook find(String rawId) {
        if (rawId == null || rawId.isBlank()) {
            return null;
        }
        String id = normalize(rawId);
        CombatSkillHook registered = HOOKS.get(id);
        if (registered != null) {
            return registered;
        }
        return CLASS_HOOKS.computeIfAbsent(id, CombatSkillHooks::instantiate).orElse(null);
    }

    /** 已登记的钩子 id 快照，供调试命令或日志使用。 */
    public static Set<String> registeredIds() {
        return Set.copyOf(HOOKS.keySet());
    }

    private static Optional<CombatSkillHook> instantiate(String id) {
        int colon = id.indexOf(':');
        if (colon < 0 || !FiercerBattles.MOD_ID.equals(id.substring(0, colon))) {
            return Optional.empty();
        }
        String className = id.substring(colon + 1);
        if (!className.startsWith(HOOK_PACKAGE) || !className.matches("[A-Za-z0-9_$.]+")) {
            return Optional.empty();
        }
        try {
            Class<?> type = Class.forName(className);
            if (!CombatSkillHook.class.isAssignableFrom(type)) {
                LOGGER.warn("Fiercer Battles: '{}' is not a CombatSkillHook", className);
                return Optional.empty();
            }
            CombatSkillHook hook = (CombatSkillHook) type.getDeclaredConstructor().newInstance();
            LOGGER.info("Fiercer Battles: combat skill hook '{}' resolved by class name", className);
            return Optional.of(hook);
        } catch (ReflectiveOperationException | LinkageError exception) {
            LOGGER.warn("Fiercer Battles: could not instantiate combat skill hook class '{}'", className, exception);
            return Optional.empty();
        }
    }

    private static String normalize(String rawId) {
        String trimmed = rawId == null ? "" : rawId.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        return trimmed.indexOf(':') >= 0 ? trimmed : FiercerBattles.MOD_ID + ":" + trimmed;
    }

    // ------------------------------------------------------------------
    // 战技系统调用入口
    // ------------------------------------------------------------------

    /**
     * 战技被服务端接受时调用，开启一个钩子会话。
     *
     * @param skillId       战技文件 id，仅用于日志与调试
     * @param hookId        战技 JSON 的 {@code skill.hook}，为空时不做任何事
     * @param params        战技 JSON 的 {@code skill.hook_params}
     * @param durationTicks 战技配置的持续时长，决定 TICK 时机的寿命
     */
    public static void onSkillActivated(
            ServerPlayer player,
            String skillId,
            String hookId,
            JsonObject params,
            long durationTicks
    ) {
        if (player == null || hookId == null || hookId.isBlank()) {
            return;
        }
        CombatSkillHook hook = find(hookId);
        if (hook == null) {
            // 数据包写错了钩子名：说清楚，但不要影响战技本身的效果。
            LOGGER.warn("Fiercer Battles: combat skill '{}' asked for unknown hook '{}'",
                    player.getName().getString(), hookId);
            return;
        }
        long now = player.level().getGameTime();
        Session session = new Session(
                hook,
                hookId.trim(),
                skillId,
                params != null ? params.deepCopy() : new JsonObject(),
                hook.triggers(),
                now + Math.min(Math.max(durationTicks, MIN_SESSION_TICKS), MAX_HIT_WINDOW_TICKS),
                now + Math.max(durationTicks, MIN_SESSION_TICKS)
        );
        Session previous = ACTIVE.put(player.getUUID(), session);
        if (previous != null) {
            // 上一个战技还没到期就被顶掉：先让它收尾，别把削甲一类的状态漏在场上。
            fireExpired(previous, player);
        }
        fire(hook, CombatSkillTrigger.CAST, context(CombatSkillTrigger.CAST, player, null, null, session, 0));
    }

    /**
     * 战技的挥击命中目标、伤害仍可改写时调用。
     *
     * @return 钩子改写过命中时返回结果，否则返回 {@code null}（调用方不应改动事件）
     */
    public static HitResult onSkillHit(
            ServerPlayer attacker,
            LivingEntity target,
            DamageSource source,
            double damage
    ) {
        if (attacker == null || target == null || isResolvingHookDamage()) {
            return null;
        }
        Session session = sessionFor(attacker, CombatSkillTrigger.HIT);
        if (session == null) {
            return null;
        }
        CombatSkillContext context = context(CombatSkillTrigger.HIT, attacker, target, source, session, damage);
        fire(session.hook, CombatSkillTrigger.HIT, context);
        return new HitResult(context.damage(), context.cancelled());
    }

    /** 目标实际承受伤害之后调用，此时可读到真实伤害。 */
    public static void onSkillDamageDealt(
            ServerPlayer attacker,
            LivingEntity target,
            DamageSource source,
            double damage
    ) {
        if (attacker == null || target == null || isResolvingHookDamage()) {
            return;
        }
        Session session = sessionFor(attacker, CombatSkillTrigger.DAMAGE);
        if (session == null) {
            return;
        }
        fire(session.hook, CombatSkillTrigger.DAMAGE,
                context(CombatSkillTrigger.DAMAGE, attacker, target, source, session, damage));
    }

    /** 玩家每个 tick 调用；同时负责会话到期回收。 */
    public static void onPlayerTick(ServerPlayer player) {
        if (player == null || ACTIVE.isEmpty()) {
            return;
        }
        Session session = ACTIVE.get(player.getUUID());
        if (session == null) {
            return;
        }
        long now = player.level().getGameTime();
        if (now > session.hitExpiresAt && now > session.tickExpiresAt) {
            expire(player, session);
            return;
        }
        if (!player.isAlive() || now > session.tickExpiresAt
                || !session.triggers.contains(CombatSkillTrigger.TICK)) {
            return;
        }
        fire(session.hook, CombatSkillTrigger.TICK,
                context(CombatSkillTrigger.TICK, player, null, null, session, 0));
    }

    /** 玩家重生时丢弃会话，并让钩子有机会回收自己挂上去的状态。 */
    public static void clearPlayer(ServerPlayer player) {
        if (player == null) {
            return;
        }
        Session session = ACTIVE.remove(player.getUUID());
        if (session != null) {
            LOGGER.debug("Fiercer Battles: dropped combat skill session '{}'", session.hookId);
            fireExpired(session, player);
        }
    }

    // ------------------------------------------------------------------
    // 钩子自伤的重入保护
    // ------------------------------------------------------------------

    /**
     * 钩子通过 {@link CombatSkillContext#strike} 造成的伤害会再次流经伤害事件，
     * 期间置位，避免战技加成叠加到自己的伤害上、或让战技自己触发自己。
     */
    public static boolean isResolvingHookDamage() {
        return HOOK_DAMAGE_DEPTH.get() > 0;
    }

    static void pushHookDamage() {
        HOOK_DAMAGE_DEPTH.set(HOOK_DAMAGE_DEPTH.get() + 1);
    }

    static void popHookDamage() {
        int depth = HOOK_DAMAGE_DEPTH.get() - 1;
        if (depth <= 0) {
            HOOK_DAMAGE_DEPTH.remove();
        } else {
            HOOK_DAMAGE_DEPTH.set(depth);
        }
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private static Session sessionFor(ServerPlayer player, CombatSkillTrigger trigger) {
        Session session = ACTIVE.get(player.getUUID());
        if (session == null) {
            return null;
        }
        if (player.level().getGameTime() > session.hitExpiresAt) {
            // 命中窗口关了：这次命中不算这个战技的。只有 TICK 窗口也结束了，
            // 整个会话才真正退场（长持续型战技的命中窗口比 TICK 窗口短）。
            if (player.level().getGameTime() > session.tickExpiresAt) {
                expire(player, session);
            }
            return null;
        }
        return session.triggers.contains(trigger) ? session : null;
    }

    private static void expire(ServerPlayer player, Session session) {
        if (!ACTIVE.remove(player.getUUID(), session)) {
            return;
        }
        fireExpired(session, player);
    }

    private static void fireExpired(Session session, ServerPlayer player) {
        fire(session.hook, null,
                new CombatSkillContext(null, player, null, null, session.params, session.state,
                        session.skillId, player.level().getGameTime(), 0));
    }

    private static CombatSkillContext context(
            CombatSkillTrigger trigger,
            ServerPlayer player,
            LivingEntity target,
            DamageSource source,
            Session session,
            double damage
    ) {
        return new CombatSkillContext(
                trigger,
                player,
                target,
                source,
                session.params,
                session.state,
                session.skillId,
                player.level().getGameTime(),
                damage
        );
    }

    private static void fire(CombatSkillHook hook, CombatSkillTrigger trigger, CombatSkillContext context) {
        try {
            if (trigger == null) {
                hook.onExpire(context);
                return;
            }
            switch (trigger) {
                case CAST -> hook.onCast(context);
                case HIT -> hook.onHit(context);
                case DAMAGE -> hook.onDamage(context);
                case TICK -> hook.onTick(context);
            }
        } catch (RuntimeException exception) {
            // 一次战技写错不该把整场战斗带崩。
            LOGGER.warn("Fiercer Battles: combat skill hook '{}' failed on {}",
                    hook.id(), trigger == null ? "expire" : trigger, exception);
        }
    }

    /**
     * HIT 阶段的改写结果。
     *
     * @param damage    改写后的伤害
     * @param cancelled 是否取消这次命中
     */
    public record HitResult(double damage, boolean cancelled) {
    }

    /** 一个玩家当前生效的钩子会话。 */
    private static final class Session {
        private final CombatSkillHook hook;
        private final String hookId;
        private final String skillId;
        private final JsonObject params;
        private final Map<String, Object> state = new ConcurrentHashMap<>();
        private final Set<CombatSkillTrigger> triggers;
        private final long hitExpiresAt;
        private final long tickExpiresAt;

        private Session(
                CombatSkillHook hook,
                String hookId,
                String skillId,
                JsonObject params,
                Set<CombatSkillTrigger> triggers,
                long hitExpiresAt,
                long tickExpiresAt
        ) {
            this.hook = hook;
            this.hookId = hookId;
            this.skillId = skillId != null ? skillId : "";
            this.params = params;
            this.triggers = triggers == null || triggers.isEmpty()
                    ? EnumSet.noneOf(CombatSkillTrigger.class)
                    : EnumSet.copyOf(triggers);
            this.hitExpiresAt = hitExpiresAt;
            this.tickExpiresAt = tickExpiresAt;
        }
    }
}

package com.fiercerbattles.combatskill;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.logging.LogUtils;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 一次钩子回调的全部上下文：谁、打谁、打多少、参数是什么、能改什么。
 *
 * <p>JSON 里 {@code skill.hook_params} 的内容原样送到 {@link #param} 系列方法，
 * 所以同一个钩子可以被不同战技用不同数值复用。
 */
public final class CombatSkillContext {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final CombatSkillTrigger trigger;
    private final ServerPlayer player;
    private final LivingEntity target;
    private final DamageSource source;
    private final JsonObject params;
    private final Map<String, Object> state;
    private final String skillId;
    private final long gameTime;
    private double damage;
    private boolean cancelled;

    CombatSkillContext(
            CombatSkillTrigger trigger,
            ServerPlayer player,
            LivingEntity target,
            DamageSource source,
            JsonObject params,
            Map<String, Object> state,
            String skillId,
            long gameTime,
            double damage
    ) {
        this.trigger = trigger;
        this.player = player;
        this.target = target;
        this.source = source;
        this.params = params;
        this.state = state;
        this.skillId = skillId;
        this.gameTime = gameTime;
        this.damage = damage;
    }

    /** 当前时机；会话结束回调（{@link CombatSkillHook#onExpire}）时为 {@code null}。 */
    public CombatSkillTrigger trigger() {
        return trigger;
    }

    public ServerPlayer player() {
        return player;
    }

    /** 本次命中的目标；{@code CAST}/{@code TICK} 阶段为 {@code null}。 */
    public LivingEntity target() {
        return target;
    }

    /** 本次命中的伤害来源；{@code CAST}/{@code TICK} 阶段为 {@code null}。 */
    public DamageSource source() {
        return source;
    }

    /** 触发这个钩子的战技文件 id，仅用于日志与调试。 */
    public String skillId() {
        return skillId;
    }

    /** 所在世界的游戏刻。 */
    public long gameTime() {
        return gameTime;
    }

    // ------------------------------------------------------------------
    // 伤害改写（HIT 阶段）
    // ------------------------------------------------------------------

    public double damage() {
        return damage;
    }

    public void setDamage(double value) {
        this.damage = Double.isFinite(value) ? Math.max(0, value) : 0;
    }

    public void addDamage(double amount) {
        setDamage(this.damage + amount);
    }

    public void multiplyDamage(double factor) {
        setDamage(this.damage * factor);
    }

    public boolean cancelled() {
        return cancelled;
    }

    public void setCancelled(boolean value) {
        this.cancelled = value;
    }

    // ------------------------------------------------------------------
    // JSON 参数
    // ------------------------------------------------------------------

    private JsonElement raw(String key) {
        return params != null && params.has(key) ? params.get(key) : null;
    }

    public double param(String key, double fallback) {
        JsonElement value = raw(key);
        if (value == null || !value.isJsonPrimitive()) {
            return fallback;
        }
        try {
            double parsed = value.getAsDouble();
            return Double.isFinite(parsed) ? parsed : fallback;
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    public double param(String key, String alias, double fallback) {
        JsonElement value = raw(key);
        if (value == null && alias != null) {
            value = raw(alias);
        }
        if (value == null || !value.isJsonPrimitive()) {
            return fallback;
        }
        try {
            double parsed = value.getAsDouble();
            return Double.isFinite(parsed) ? parsed : fallback;
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    public int intParam(String key, int fallback) {
        return (int) Math.round(param(key, fallback));
    }

    public long longParam(String key, long fallback) {
        return Math.round(param(key, fallback));
    }

    public boolean flag(String key, boolean fallback) {
        JsonElement value = raw(key);
        if (value == null || !value.isJsonPrimitive()) {
            return fallback;
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return primitive.getAsBoolean();
        }
        if (primitive.isNumber()) {
            return primitive.getAsDouble() != 0;
        }
        String text = primitive.getAsString().trim().toLowerCase(Locale.ROOT);
        return switch (text) {
            case "true", "yes", "on", "1" -> true;
            case "false", "no", "off", "0" -> false;
            default -> fallback;
        };
    }

    public String text(String key, String fallback) {
        JsonElement value = raw(key);
        if (value == null || !value.isJsonPrimitive()) {
            return fallback;
        }
        String text = value.getAsString().trim();
        return text.isEmpty() ? fallback : text;
    }

    // ------------------------------------------------------------------
    // 会话状态：在同一个战技的 CAST / HIT / DAMAGE / TICK 之间共享
    // ------------------------------------------------------------------

    public Object state(String key) {
        return state.get(key);
    }

    public void putState(String key, Object value) {
        if (value == null) {
            state.remove(key);
        } else {
            state.put(key, value);
        }
    }

    // ------------------------------------------------------------------
    // 世界交互
    // ------------------------------------------------------------------

    public ServerLevel level() {
        return player.level() instanceof ServerLevel serverLevel ? serverLevel : null;
    }

    /** 命中目标的位置，没有目标时是玩家的位置。 */
    public Vec3 origin() {
        return target != null ? target.position() : player.position();
    }

    /**
     * 以 {@link #origin()} 为中心找出半径内的可攻击目标，按距离由近到远排序。
     *
     * @param limit 最多返回几个；{@code <= 0} 表示不限
     */
    public List<LivingEntity> enemiesIn(double radius, int limit) {
        return enemiesIn(origin(), radius, limit);
    }

    public List<LivingEntity> enemiesIn(Vec3 center, double radius, int limit) {
        ServerLevel level = level();
        if (level == null || radius <= 0) {
            return List.of();
        }
        double squared = radius * radius;
        AABB box = new AABB(center, center).inflate(radius);
        List<LivingEntity> found = level.getEntitiesOfClass(
                LivingEntity.class,
                box,
                entity -> isValidTarget(entity) && entity.position().distanceToSqr(center) <= squared
        );
        found.sort(Comparator.comparingDouble(entity -> entity.distanceToSqr(center)));
        if (limit > 0 && found.size() > limit) {
            return new ArrayList<>(found.subList(0, limit));
        }
        return found;
    }

    /** 默认把玩家自己、本次命中目标、旁观者排除在外；玩家需要 {@code allow_players} 才纳入。 */
    private boolean isValidTarget(LivingEntity entity) {
        if (entity == player || entity == target || !entity.isAlive() || entity.isSpectator()) {
            return false;
        }
        return !(entity instanceof Player) || flag("allow_players", false);
    }

    /**
     * 对目标造成一次战技伤害。伤害归属玩家（计入击杀、经验与掉落），
     * 但不会再套用战技自身的加成，也不会再触发一次战技钩子。
     *
     * @return 目标是否真的受到了伤害
     */
    public boolean strike(LivingEntity victim, double amount) {
        if (victim == null || victim == player || amount <= 0 || !victim.isAlive()) {
            return false;
        }
        DamageSource damageSource = player.damageSources().playerAttack(player);
        CombatSkillHooks.pushHookDamage();
        try {
            return victim.hurt(damageSource, (float) amount);
        } finally {
            CombatSkillHooks.popHookDamage();
        }
    }

    /** 施加药水效果，id 与 JSON 里的写法一致，例如 {@code minecraft:slowness}。 */
    public boolean applyMobEffect(LivingEntity victim, String effectId, int durationTicks, int amplifier) {
        if (victim == null || effectId == null || effectId.isBlank() || durationTicks <= 0) {
            return false;
        }
        ResourceLocation id = ResourceLocation.tryParse(effectId);
        if (id == null) {
            return false;
        }
        Optional<Holder.Reference<MobEffect>> holder = BuiltInRegistries.MOB_EFFECT.getHolder(id);
        if (holder.isEmpty()) {
            LOGGER.warn("Fiercer Battles: combat skill hook asked for unknown potion effect '{}'", effectId);
            return false;
        }
        victim.addEffect(new MobEffectInstance(holder.get(), durationTicks, Math.max(0, amplifier)), player);
        return true;
    }

    public void sound(String soundId, float volume, float pitch) {
        if (soundId == null || soundId.isBlank()) {
            return;
        }
        ServerLevel level = level();
        ResourceLocation id = ResourceLocation.tryParse(soundId);
        if (level == null || id == null) {
            return;
        }
        SoundEvent sound = BuiltInRegistries.SOUND_EVENT.getOptional(id).orElse(null);
        if (sound == null) {
            return;
        }
        Vec3 at = origin();
        level.playSound(null, at.x, at.y, at.z, sound, SoundSource.PLAYERS, volume, pitch);
    }

    public void particles(String particleId, Vec3 position, int count, double spread, double speed) {
        if (particleId == null || particleId.isBlank() || count <= 0 || position == null) {
            return;
        }
        ServerLevel level = level();
        ResourceLocation id = ResourceLocation.tryParse(particleId);
        if (level == null || id == null) {
            return;
        }
        ParticleType<?> type = BuiltInRegistries.PARTICLE_TYPE.get(id);
        if (!(type instanceof ParticleOptions options)) {
            return;
        }
        level.sendParticles(options, position.x, position.y, position.z, count, spread, spread, spread, speed);
    }

    /** 沿两点之间撒一串粒子，用来画连锁、光束一类的轨迹。 */
    public void trail(String particleId, Vec3 from, Vec3 to, int points) {
        if (from == null || to == null || points <= 0) {
            return;
        }
        for (int step = 0; step <= points; step++) {
            Vec3 point = from.lerp(to, (double) step / points);
            particles(particleId, point, 1, 0.02, 0.0);
        }
    }
}

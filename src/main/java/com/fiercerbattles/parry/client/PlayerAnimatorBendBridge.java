package com.fiercerbattles.parry.client;

import com.mojang.logging.LogUtils;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.LivingEntity;
import org.slf4j.Logger;

import java.lang.reflect.Method;

/** Optional PlayerAnimator 2.x mesh deformation; no compile-time PlayerAnimator/BendyLib dependency. */
public final class PlayerAnimatorBendBridge {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Api API = new Api();
    private static boolean warned;

    private PlayerAnimatorBendBridge() {}

    public static ParryPoseHistory.Bend capture(LivingEntity entity, String channel) {
        if (API.getBend == null || "head".equals(channel)) return ParryPoseHistory.Bend.ZERO;
        try {
            Object animation = entity.getClass().getMethod("playerAnimator_getAnimation").invoke(entity);
            if (animation == null) return ParryPoseHistory.Bend.ZERO;
            if ("body".equals(channel)) {
                // AnimationApplier.updatePart("torso") combines both bends for the torso cuboid.
                ParryPoseHistory.Bend torso = read(animation, "torso"), body = read(animation, "body");
                return new ParryPoseHistory.Bend(torso.axis() + body.axis(), torso.angle() + body.angle());
            }
            return read(animation, channel);
        } catch (NoSuchMethodException ignored) {
            return ParryPoseHistory.Bend.ZERO;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            warn(exception);
            return ParryPoseHistory.Bend.ZERO;
        }
    }

    private static ParryPoseHistory.Bend read(Object animation, String channel) throws ReflectiveOperationException {
        Object pair = API.getBend.invoke(animation, channel);
        if (pair == null) return ParryPoseHistory.Bend.ZERO;
        float axis = ((Number) API.left.invoke(pair)).floatValue();
        float angle = ((Number) API.right.invoke(pair)).floatValue();
        return Float.isFinite(axis) && Float.isFinite(angle) ? new ParryPoseHistory.Bend(axis, angle) : ParryPoseHistory.Bend.ZERO;
    }

    public static void apply(ModelPart part, ParryPoseHistory.Bend bend) {
        if (API.applyBend == null || !API.meshEnabled) return;
        try {
            API.applyBend.invoke(API.helper, part, bend.axis(), bend.angle());
        } catch (ReflectiveOperationException | RuntimeException exception) {
            warn(exception);
        }
    }

    private static void warn(Exception exception) {
        if (warned) return;
        warned = true;
        LOGGER.warn("Optional PlayerAnimator bend bridge could not access its backend", exception);
    }

    private static final class Api {
        Method getBend, left, right, applyBend;
        Object helper;
        boolean meshEnabled;

        Api() {
            try {
                Class<?> processor = Class.forName("dev.kosmx.playerAnim.core.impl.AnimationProcessor");
                Class<?> pair = Class.forName("dev.kosmx.playerAnim.core.util.Pair");
                Class<?> bendHelper = Class.forName("dev.kosmx.playerAnim.impl.animation.IBendHelper");
                getBend = processor.getMethod("getBend", String.class);
                left = pair.getMethod("getLeft"); right = pair.getMethod("getRight");
                helper = bendHelper.getField("INSTANCE").get(null);
                applyBend = bendHelper.getMethod("bend", ModelPart.class, float.class, float.class);
                meshEnabled = !helper.getClass().getName().endsWith("$DummyBendable");
                LOGGER.info("Parry PlayerAnimator bend bridge: {}", meshEnabled ? "mesh backend available" : "BendyLib absent; matrix replay only");
            } catch (ReflectiveOperationException | LinkageError exception) {
                LOGGER.debug("PlayerAnimator mesh bend backend is unavailable", exception);
            }
        }
    }
}

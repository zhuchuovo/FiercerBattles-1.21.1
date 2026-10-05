package com.fiercerbattles.animation.humanoid;

import com.fiercerbattles.animation.core.AnimationClip;
import com.fiercerbattles.animation.core.AnimationPoseContext;
import com.fiercerbattles.animation.rig.HumanoidRig;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import org.joml.Quaternionf;


/** Maps the humanoid_v1 channels to vanilla player/zombie/skeleton model parts. */
public final class HumanoidAnimationAdapter {
    public static final String RIG = HumanoidRig.ID;
    private HumanoidAnimationAdapter() {}

    public static void identify(HumanoidModel<?> model) {
        identify(model.head, "head"); identify(model.hat, "head"); identify(model.body, "body");
        identify(model.rightArm, "rightArm"); identify(model.leftArm, "leftArm");
        identify(model.rightLeg, "rightLeg"); identify(model.leftLeg, "leftLeg");
    }

    public static void identifyPlayerClothing(PlayerModel<?> model) {
        identify(model.jacket, "body"); identify(model.rightSleeve, "rightArm"); identify(model.leftSleeve, "leftArm");
        identify(model.rightPants, "rightLeg"); identify(model.leftPants, "leftLeg");
    }

    private static void identify(ModelPart part, String channel) {
        ((AnimationModelPart) (Object) part).fiercerbattles$setAnimationChannel(channel);
    }

    public static boolean supports(AnimationClip clip) { return HumanoidRig.supports(clip); }

    /** Apply one absolute sampled pose, replacing vanilla pivots exactly once. */
    public static boolean apply(ModelPart part, PoseStack poses) {
        String channel = ((AnimationModelPart) (Object) part).fiercerbattles$getAnimationChannel();
        if (channel == null) return false;
        AnimationClip.Transform rest = new AnimationClip.Transform(part.x, part.y, part.z,
                new Quaternionf().rotationZYX(part.zRot, part.yRot, part.xRot));
        AnimationClip.Transform transform = AnimationPoseContext.sample(channel, rest);
        if (transform == null) return false;
        poses.translate(transform.x() / 16.0F, transform.y() / 16.0F, transform.z() / 16.0F);
        poses.mulPose(transform.rotation());
        float blend = AnimationPoseContext.blendProgress();
        poses.scale(transform.scale() * Mth.lerp(blend, 1, part.xScale),
                transform.scale() * Mth.lerp(blend, 1, part.yScale),
                transform.scale() * Mth.lerp(blend, 1, part.zScale));
        return true;
    }
}

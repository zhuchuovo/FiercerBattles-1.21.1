package com.fiercerbattles.mixin;

import com.fiercerbattles.FiercerBattlesExecutionClient;
import com.fiercerbattles.parry.client.ParryClient;
import com.fiercerbattles.parry.client.ParryRenderContext;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;

/** Apply authored arm deltas to the existing first-person hand/item renderer without moving the camera. */
@Mixin(ItemInHandRenderer.class)
public abstract class ParryFirstPersonMixin {
    @WrapMethod(method = "renderArmWithItem")
    private void fiercerbattles$visibleParryHand(AbstractClientPlayer player, float partial, float pitch,
                                              InteractionHand hand, float swing, ItemStack stack, float equipped,
                                              PoseStack poses, MultiBufferSource buffers, int light, Operation<Void> original) {
        ParryClient.Actor actor = ParryClient.actor(player);
        if (actor == null || FiercerBattlesExecutionClient.isExecuting(player)) {
            original.call(player, partial, pitch, hand, swing, stack, equipped, poses, buffers, light);
            return;
        }
        HumanoidArm arm = hand == InteractionHand.MAIN_HAND ? player.getMainArm() : player.getMainArm().getOpposite();
        String channel = arm == HumanoidArm.RIGHT ? "rightArm" : "leftArm";
        var first = actor.animation().sample(channel, 0);
        Matrix4f sampled = actor.firstPersonPose(channel, partial);
        if (first == null || sampled == null) {
            original.call(player, partial, pitch, hand, swing, stack, equipped, poses, buffers, light);
            return;
        }
        Matrix4f delta = new Matrix4f(sampled).mul(ParryRenderContext.matrix(first).invert());
        Matrix4f basis = new Matrix4f().rotationZ((float) Math.PI);
        Matrix4f cameraDelta = new Matrix4f(basis).mul(delta).mul(new Matrix4f(basis).invert());
        poses.pushPose();
        ParryRenderContext.beginFirstPerson(actor, partial);
        try {
            if (cameraDelta.isFinite() && Math.abs(cameraDelta.determinant3x3()) > 1.0E-8F) poses.mulPose(cameraDelta);
            original.call(player, partial, pitch, hand, swing, stack, equipped, poses, buffers, light);
        } finally {
            ParryRenderContext.endFirstPerson();
            poses.popPose();
        }
    }
}

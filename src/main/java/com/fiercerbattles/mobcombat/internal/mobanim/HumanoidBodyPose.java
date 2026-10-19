package com.fiercerbattles.mobcombat.internal.mobanim;

import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;


public final class HumanoidBodyPose {
    private final PartPose headPose;
    private final PartPose bodyPose;
    private final PartPose leftArmPose;
    private final PartPose rightArmPose;
    private final PartPose leftLegPose;
    private final PartPose rightLegPose;

    private final Scale headScale;
    private final Scale bodyScale;
    private final Scale leftArmScale;
    private final Scale rightArmScale;
    private final Scale leftLegScale;
    private final Scale rightLegScale;

    public HumanoidBodyPose(
            ModelPart head,
            ModelPart body,
            ModelPart leftArm,
            ModelPart rightArm,
            ModelPart leftLeg,
            ModelPart rightLeg
    ) {
        this.headPose = head.storePose();
        this.bodyPose = body.storePose();
        this.leftArmPose = leftArm.storePose();
        this.rightArmPose = rightArm.storePose();
        this.leftLegPose = leftLeg.storePose();
        this.rightLegPose = rightLeg.storePose();

        this.headScale = Scale.of(head);
        this.bodyScale = Scale.of(body);
        this.leftArmScale = Scale.of(leftArm);
        this.rightArmScale = Scale.of(rightArm);
        this.leftLegScale = Scale.of(leftLeg);
        this.rightLegScale = Scale.of(rightLeg);
    }

    public void apply(HumanoidModelAccess model) {
        applyHead(model);
        applyBody(model);
        applyArms(model);
        applyLegs(model);
    }

    public void applyHead(HumanoidModelAccess model) {
        model.bmc$getHead().loadPose(this.headPose);
        this.headScale.applyTo(model.bmc$getHead());
    }

    public void applyBody(HumanoidModelAccess model) {
        model.bmc$getBody().loadPose(this.bodyPose);
        this.bodyScale.applyTo(model.bmc$getBody());
    }

    public void applyArms(HumanoidModelAccess model) {
        model.bmc$getLeftArm().loadPose(this.leftArmPose);
        model.bmc$getRightArm().loadPose(this.rightArmPose);
        this.leftArmScale.applyTo(model.bmc$getLeftArm());
        this.rightArmScale.applyTo(model.bmc$getRightArm());
    }

    public void applyLegs(HumanoidModelAccess model) {
        model.bmc$getLeftLeg().loadPose(this.leftLegPose);
        model.bmc$getRightLeg().loadPose(this.rightLegPose);
        this.leftLegScale.applyTo(model.bmc$getLeftLeg());
        this.rightLegScale.applyTo(model.bmc$getRightLeg());
    }

    private record Scale(float x, float y, float z) {
        static Scale of(ModelPart part) {
            return new Scale(part.xScale, part.yScale, part.zScale);
        }

        void applyTo(ModelPart part) {
            part.xScale = this.x;
            part.yScale = this.y;
            part.zScale = this.z;
        }
    }
}
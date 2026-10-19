package com.fiercerbattles.bettercombat.client.particle;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.fiercerbattles.bettercombat.api.fx.Color;
import com.fiercerbattles.bettercombat.particle.BetterCombatParticles;
import com.fiercerbattles.bettercombat.particle.SlashParticleEffect;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.*;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

@OnlyIn(Dist.CLIENT)
public class SlashParticle extends TextureSheetParticle {
    private final SpriteSet spriteProvider;
    public final float modelOffset;
    private final float pitch;
    private final float yaw;
    private final float localYaw;
    private final float roll;
    private final boolean light;

    public SlashParticle(ClientLevel world, double x, double y, double z, float scale, float pitch, float yaw, float localYaw, float roll, boolean light, long color_rgba, SpriteSet spriteProvider) {
        super(world, x, y, z, 0.0, 0.0, 0.0);
        this.spriteProvider = spriteProvider;
        this.light = light;
        this.pitch = pitch;
        this.yaw = yaw;
        this.roll = roll;
        this.localYaw = localYaw;

        // Set color from RGBA long
        var color = Color.fromRGBA(color_rgba);
        this.setColor(color.red(), color.green(), color.blue());
        this.alpha = color.alpha();

        this.lifetime = 6;
        this.modelOffset = this.setModelOffset();
        this.quadSize = scale;
        this.setSpriteFromAge(spriteProvider);
    }

    public void tick() {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;
        if (this.age++ >= this.lifetime) {
            this.remove();
        } else {
            this.setSpriteFromAge(this.spriteProvider);
        }
    }

    protected int getLightColor(float tint) {
        BlockPos blockPos = BlockPos.containing(this.x, this.y, this.z);
        if (this.light) {
            return 15728880;
        } else {
            return this.level.hasChunkAt(blockPos) ? LevelRenderer.getLightColor(this.level, blockPos) : 0;
        }
    }

    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }

    public Particle scale(float scale) {
        this.quadSize = scale;
        return super.scale(scale);
    }

    public float setModelOffset() {
        return 0.0F;
    }

    public void render(VertexConsumer vertexConsumer, Camera camera, float tickDelta) {
        Vec3 cameraPos = camera.getPosition();
        float x = (float)(this.xo - cameraPos.x());
        float y = (float)(this.yo - cameraPos.y());
        float z = (float)(this.zo - cameraPos.z());
        float size = this.getQuadSize(tickDelta);
        float minU = this.getU0();
        float maxU = this.getU1();
        float minV = this.getV0();
        float maxV = this.getV1();
        Matrix4f rotationMatrix = new Matrix4f();
        rotationMatrix.identity();
        rotationMatrix.rotate((float)Math.toRadians((double)(-this.yaw)), new Vector3f(0.0F, 1.0F, 0.0F));
        rotationMatrix.rotate((float)Math.toRadians((double)this.pitch), new Vector3f(1.0F, 0.0F, 0.0F));
        rotationMatrix.rotate((float)Math.toRadians((double)this.roll), new Vector3f(0.0F, 0.0F, 1.0F));
        rotationMatrix.rotate((float)Math.toRadians((double)(-this.localYaw)), new Vector3f(0.0F, 1.0F, 0.0F));
        Vector4f[] corners = new Vector4f[]{new Vector4f(-size, this.modelOffset, -size, 1.0F), new Vector4f(-size, this.modelOffset, size, 1.0F), new Vector4f(size, this.modelOffset, size, 1.0F), new Vector4f(size, this.modelOffset, -size, 1.0F)};
        Vector4f[] var15 = corners;
        int var16 = corners.length;

        for(int var17 = 0; var17 < var16; ++var17) {
            Vector4f corner = var15[var17];
            rotationMatrix.transform(corner);
            corner.add(x, y, z, 0.0F);
        }

        vertexConsumer.addVertex(corners[0].x(), corners[0].y(), corners[0].z()).setUv(maxU, maxV).setColor(this.rCol, this.gCol, this.bCol, this.alpha).setLight(this.getLightColor(tickDelta));
        vertexConsumer.addVertex(corners[1].x(), corners[1].y(), corners[1].z()).setUv(maxU, minV).setColor(this.rCol, this.gCol, this.bCol, this.alpha).setLight(this.getLightColor(tickDelta));
        vertexConsumer.addVertex(corners[2].x(), corners[2].y(), corners[2].z()).setUv(minU, minV).setColor(this.rCol, this.gCol, this.bCol, this.alpha).setLight(this.getLightColor(tickDelta));
        vertexConsumer.addVertex(corners[3].x(), corners[3].y(), corners[3].z()).setUv(minU, maxV).setColor(this.rCol, this.gCol, this.bCol, this.alpha).setLight(this.getLightColor(tickDelta));
        vertexConsumer.addVertex(corners[3].x(), corners[3].y(), corners[3].z()).setUv(minU, maxV).setColor(this.rCol, this.gCol, this.bCol, this.alpha).setLight(this.getLightColor(tickDelta));
        vertexConsumer.addVertex(corners[2].x(), corners[2].y(), corners[2].z()).setUv(minU, minV).setColor(this.rCol, this.gCol, this.bCol, this.alpha).setLight(this.getLightColor(tickDelta));
        vertexConsumer.addVertex(corners[1].x(), corners[1].y(), corners[1].z()).setUv(maxU, minV).setColor(this.rCol, this.gCol, this.bCol, this.alpha).setLight(this.getLightColor(tickDelta));
        vertexConsumer.addVertex(corners[0].x(), corners[0].y(), corners[0].z()).setUv(maxU, maxV).setColor(this.rCol, this.gCol, this.bCol, this.alpha).setLight(this.getLightColor(tickDelta));
    }

    @OnlyIn(Dist.CLIENT)
    public static class Provider implements ParticleProvider<SlashParticleEffect> {
        private final SpriteSet spriteProvider;
        private final BetterCombatParticles.StaticParams params;

        public Provider(SpriteSet spriteProvider, BetterCombatParticles.StaticParams params) {
            this.spriteProvider = spriteProvider;
            this.params = params;
        }

        public Particle createParticle(SlashParticleEffect settings, ClientLevel clientWorld, double d, double e, double f, double g, double h, double i) {
            return new SlashParticle(clientWorld, d, e, f, settings.getScale(), settings.getPitch(), settings.getYaw(), settings.getLocalYaw(), settings.getRoll(), settings.getLight(), settings.getColorRGBA(), this.spriteProvider);
        }
    }
}
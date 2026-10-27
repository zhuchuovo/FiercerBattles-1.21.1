package com.fiercerbattles.boss.client;

import com.fiercerbattles.FiercerBattles;
import com.fiercerbattles.animation.core.AnimationClip;
import com.fiercerbattles.boss.IronSentinelAction;
import com.mojang.logging.LogUtils;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Client resources of the Iron Sentinel, exported by tools/export_iron_sentinel.py: the Blender meshes
 * ({@code iron_sentinel/model.json}, read like the Zombie Lord's) and one clip per action
 * ({@code iron_sentinel/clips/<clip>.json}).
 */
public final class IronSentinelAssets implements ResourceManagerReloadListener {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static volatile List<ZombieLordAssets.MeshObject> mesh = List.of();
    private static volatile Map<String, AnimationClip> clips = Map.of();

    public static List<ZombieLordAssets.MeshObject> mesh() { return mesh; }

    @Nullable
    public static AnimationClip clip(String name) { return clips.get(name); }

    @Override
    public void onResourceManagerReload(ResourceManager manager) {
        try {
            mesh = ZombieLordAssets.readMesh(ZombieLordAssets.read(manager, "iron_sentinel/model.json"));
        } catch (IOException | RuntimeException exception) {
            mesh = List.of();
            LOGGER.error("Cannot load the Iron Sentinel model", exception);
        }
        Map<String, AnimationClip> loaded = new HashMap<>();
        for (IronSentinelAction action : IronSentinelAction.values()) {
            if (loaded.containsKey(action.clip)) continue;
            String path = "iron_sentinel/clips/" + action.clip + ".json";
            try {
                loaded.put(action.clip, AnimationClip.decode(FiercerBattles.MOD_ID + ":" + path,
                        ZombieLordAssets.read(manager, path)));
            } catch (IOException | RuntimeException exception) {
                LOGGER.error("Cannot load Iron Sentinel clip {}", path, exception);
            }
        }
        clips = Map.copyOf(loaded);
    }
}

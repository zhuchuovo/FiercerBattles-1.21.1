package com.fiercerbattles.boss.client;

import com.fiercerbattles.FiercerBattles;
import com.fiercerbattles.animation.core.AnimationClip;
import com.fiercerbattles.boss.ZombieLordAction;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Client resources of the Zombie Lord, exported by tools/export_zombie_lord.py: the Blender meshes
 * ({@code zombie_lord/model.json}) and one clip per action ({@code zombie_lord/clips/<clip>.json}).
 */
public final class ZombieLordAssets implements ResourceManagerReloadListener {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Floats per exported face: normal xyz, then four corners of x, y, z, u, v. */
    public static final int FACE_STRIDE = 23;

    /**
     * One rigid Blender object. It follows the clip channel {@code channel} (its own name unless model.json says
     * otherwise) and is drawn in boss phase {@code phase} only, or always when that is 0.
     */
    public record MeshObject(String name, String channel, int phase, float[] faces) {}

    private static volatile List<MeshObject> mesh = List.of();
    private static volatile Map<String, AnimationClip> clips = Map.of();

    public static List<MeshObject> mesh() { return mesh; }

    @Nullable
    public static AnimationClip clip(String name) { return clips.get(name); }

    @Override
    public void onResourceManagerReload(ResourceManager manager) {
        try {
            mesh = readMesh(read(manager, "zombie_lord/model.json"));
        } catch (IOException | RuntimeException exception) {
            mesh = List.of();
            LOGGER.error("Cannot load the Zombie Lord model", exception);
        }
        Map<String, AnimationClip> loaded = new HashMap<>();
        for (ZombieLordAction action : ZombieLordAction.values()) {
            if (loaded.containsKey(action.clip)) continue;
            String path = "zombie_lord/clips/" + action.clip + ".json";
            try {
                loaded.put(action.clip, AnimationClip.decode(FiercerBattles.MOD_ID + ":" + path, read(manager, path)));
            } catch (IOException | RuntimeException exception) {
                LOGGER.error("Cannot load Zombie Lord clip {}", path, exception);
            }
        }
        clips = Map.copyOf(loaded);
    }

    private static JsonObject read(ResourceManager manager, String path) throws IOException {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(FiercerBattles.MOD_ID, path);
        Optional<Resource> resource = manager.getResource(id);
        if (resource.isEmpty()) throw new IOException("Missing resource " + id);
        try (Reader reader = resource.get().openAsReader()) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static List<MeshObject> readMesh(JsonObject root) throws IOException {
        if (root.get("format").getAsInt() != 1) throw new IOException("Unsupported Zombie Lord model format");
        List<MeshObject> objects = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("objects").entrySet()) {
            JsonObject object = entry.getValue().getAsJsonObject();
            JsonArray faces = object.getAsJsonArray("faces");
            float[] data = new float[faces.size() * FACE_STRIDE];
            for (int i = 0; i < faces.size(); i++) {
                JsonArray face = faces.get(i).getAsJsonArray();
                if (face.size() != FACE_STRIDE) throw new IOException("Malformed face in " + entry.getKey());
                for (int j = 0; j < FACE_STRIDE; j++) data[i * FACE_STRIDE + j] = face.get(j).getAsFloat();
            }
            String channel = object.has("channel") ? object.get("channel").getAsString() : entry.getKey();
            int phase = object.has("phase") ? object.get("phase").getAsInt() : 0;
            objects.add(new MeshObject(entry.getKey(), channel, phase, data));
        }
        return List.copyOf(objects);
    }
}

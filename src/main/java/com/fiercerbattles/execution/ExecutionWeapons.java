package com.fiercerbattles.execution;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.Set;

/** Shared weapon eligibility; combat categories and broad tool tags include unrelated weapons. */
public final class ExecutionWeapons {
    public static final String SWORD_ACTION = "fiercerbattles:execution";
    public static final String AXE_ACTION = "fiercerbattles:axe_execution";
    private static final Set<String> VANILLA_SWORDS = Set.of(
            "wooden_sword", "stone_sword", "iron_sword", "golden_sword", "diamond_sword", "netherite_sword");
    private static final Set<String> VANILLA_AXES = Set.of(
            "wooden_axe", "stone_axe", "iron_axe", "golden_axe", "diamond_axe", "netherite_axe");
    private static final Set<String> TINKERS_SWORDS = Set.of("sword", "cleaver");
    private static final Set<String> TINKERS_AXES = Set.of("hand_axe", "broad_axe", "minotaur_axe");

    private ExecutionWeapons() {}

    /** The main-hand item determines the paired action; unsupported or empty stacks cannot execute. */
    public static String actionId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return actionId(id);
    }

    public static String actionId(ResourceLocation id) {
        if (id == null) return null;
        String path = id.getPath();
        return switch (id.getNamespace()) {
            case "minecraft" -> VANILLA_SWORDS.contains(path) ? SWORD_ACTION
                    : VANILLA_AXES.contains(path) ? AXE_ACTION : null;
            case "tconstruct" -> TINKERS_SWORDS.contains(path) ? SWORD_ACTION
                    : TINKERS_AXES.contains(path) ? AXE_ACTION : null;
            default -> null;
        };
    }

    public static boolean canExecute(ItemStack stack) { return actionId(stack) != null; }
}

package space.retri.minecraft.crumblestone;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

public class CrumblestonePlugin extends JavaPlugin {

    public static final String META_KEY = "crumblestone_placed";
    public static final String PDC_KEY = "crumblestone_item";
    private static long DECAY_TICKS = 5L * 60L * 20L;
    private static Material BLOCK_MATERIAL;

    private static NamespacedKey itemKey;
    private static Plugin plugin;
    private static CrumblestoneBlockListener listener;

    public static Plugin        getPlugin()     { return plugin; }
    public static long          getDecayTicks() { return DECAY_TICKS; }
    public static Material      getMaterial()   { return BLOCK_MATERIAL; }
    public static NamespacedKey getItemKey()    { return itemKey; }

    @Override
    public void onEnable() {
        plugin = this;
        CrumblestonePlugin.itemKey = new NamespacedKey(this, PDC_KEY);

        // Save default config if it doesn't exist
        saveDefaultConfig();

        // Read decay time from config (in seconds)
        int decaySeconds = getConfig().getInt("decay-time-seconds", 300); // fallback 5 min
        DECAY_TICKS = decaySeconds * 20L;

        String materialName = getConfig().getString("material");
        try {
            BLOCK_MATERIAL = Material.valueOf(materialName.toUpperCase());
        } catch (IllegalArgumentException ex) {
            getLogger().warning("Invalid material in config: " + materialName + ". Using default material (ROOTED_DIRT).");
            BLOCK_MATERIAL = Material.ROOTED_DIRT;
        }

        listener = new CrumblestoneBlockListener();

        registerRecipe();
        getServer().getPluginManager().registerEvents(listener, this);

        getLogger().info("Plugin enabled. Decay time: " + decaySeconds + "sec. Material: " + BLOCK_MATERIAL.toString() + "."); 
    }

    @Override
    public void onDisable() {
        listener.clearScheduledRemovals();

        getLogger().info("Crumblestone disabled.");
    }

    private void registerRecipe() {
        var recipes = CrumblestoneRecipe.createRecipes(
            getConfig().getStringList("ingredients"), 
            createCrumblestoneItem()
        );

        int failCount = 0;
        // Register each recipe combination
        for (int i = 0; i < recipes.size(); i++) {
            try {
                Bukkit.addRecipe(recipes.get(i));
            } catch (Exception ex) {
                failCount++;
            }
        }
        if (failCount > 0) 
            getLogger().warning("Failed to add " + failCount + " recipes.");
        else
            getLogger().info("Successfully added " + recipes.size() + " recipes.");
    }

    private ItemStack createCrumblestoneItem() {
        ItemStack item = new ItemStack(BLOCK_MATERIAL, 1);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Crumblestone").decoration(TextDecoration.ITALIC, false));
        meta.lore(java.util.List.of(
            Component.text("Decays after 5 minutes", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
        ));
        // mark thisd so we can identify it anywhere
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte)1);
        item.setItemMeta(meta);
        return item;
    }
}

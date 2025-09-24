package space.retri.minecraft.crumblestone;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.scheduler.BukkitTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.event.block.BlockDamageEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Objects;

public class CrumblestonePlugin extends JavaPlugin implements Listener {

    public static final String META_KEY = "crumblestone_placed";
    public static final String PDC_KEY = "crumblestone_item";
    private static long LIFETIME_TICKS = 5L * 60L * 20L;
    private static Material crumblestoneMaterial = Material.COARSE_DIRT;

    private NamespacedKey itemKey;
    // locationKey -> task
    private final Map<String, BukkitTask> scheduledRemoval = new ConcurrentHashMap<>();

    // For generating unique fake entity IDs 
    private int nextFakeEntityId = -1;
    // Map each block location to its fake entity ID
    private final Map<String, Integer> blockFakeEntityIds = new ConcurrentHashMap<>();
    

    @Override
    public void onEnable() {
        this.itemKey = new NamespacedKey(this, PDC_KEY);

        // Save default config if it doesn't exist
        saveDefaultConfig();

        // Read decay time from config (in seconds)
        int decaySeconds = getConfig().getInt("decay-time-seconds", 300); // fallback 5 min
        LIFETIME_TICKS = decaySeconds * 20L;

        String materialName = getConfig().getString("material", "COARSE_DIRT");
        try {
            crumblestoneMaterial = Material.valueOf(materialName.toUpperCase());
        } catch (IllegalArgumentException ex) {
            getLogger().warning("Invalid material in config: " + materialName + ". Falling back to COARSE_DIRT.");
            crumblestoneMaterial = Material.COARSE_DIRT;
        }

        getServer().getPluginManager().registerEvents(this, this);
        registerRecipe();

        getLogger().info("Crumblestone enabled. Decay time: " + decaySeconds + "sec / " + decaySeconds / 60.0 + " min."); 
    }

    @Override
    public void onDisable() {
        // Cancel all scheduled tasks we created
        for (BukkitTask task : scheduledRemoval.values()) {
            if (task != null) task.cancel();
        }
        scheduledRemoval.clear();

        getLogger().info("Crumblestone disabled.");
    }

    private void registerRecipe() {
        ItemStack result = createCrumblestoneItem();
        result.setAmount(8);

        // all earth block combinations
        Material[][] combinations = {
            // Original: dirt + cobblestone
            {Material.DIRT, Material.COBBLESTONE},
            // Dirt combinations
            {Material.DIRT, Material.GRAVEL},
            {Material.DIRT, Material.SAND},
            {Material.DIRT, Material.MUD},
            {Material.DIRT, Material.CLAY},
            // Clay combinations
            {Material.CLAY, Material.COBBLESTONE},
            {Material.CLAY, Material.SAND},
            {Material.CLAY, Material.MUD},
            // Gravel combinations
            {Material.GRAVEL, Material.COBBLESTONE},
            {Material.GRAVEL, Material.MUD},
            {Material.GRAVEL, Material.SAND},
            // Sand combinations
            {Material.SAND, Material.COBBLESTONE},
            {Material.SAND, Material.MUD},
            // Mud combinations
            {Material.MUD, Material.COBBLESTONE},
            // Coarse dirt combinations
            {Material.COARSE_DIRT, Material.COBBLESTONE},
            {Material.COARSE_DIRT, Material.GRAVEL},
            {Material.COARSE_DIRT, Material.SAND},
            {Material.COARSE_DIRT, Material.MUD},
            // Rooted dirt combinations
            {Material.ROOTED_DIRT, Material.DIRT},
            {Material.ROOTED_DIRT, Material.COBBLESTONE},
            {Material.ROOTED_DIRT, Material.GRAVEL},
            {Material.ROOTED_DIRT, Material.SAND},
            // Cobbled deepslate combinations
            {Material.COBBLED_DEEPSLATE, Material.DIRT},
            {Material.COBBLED_DEEPSLATE, Material.COBBLESTONE},
            {Material.COBBLED_DEEPSLATE, Material.GRAVEL},
            {Material.COBBLED_DEEPSLATE, Material.SAND},
            {Material.COBBLED_DEEPSLATE, Material.MUD},
            // Blackstone combinations
            {Material.BLACKSTONE, Material.DIRT},
            {Material.BLACKSTONE, Material.NETHERRACK},
            {Material.BLACKSTONE, Material.SOUL_SAND},
            {Material.BLACKSTONE, Material.SOUL_SOIL},
            // Netherrack combinations
            {Material.NETHERRACK, Material.DIRT},
            {Material.NETHERRACK, Material.SOUL_SAND},
            {Material.NETHERRACK, Material.SOUL_SOIL},
            // Soul sand combinations
            {Material.SOUL_SAND, Material.DIRT},
            {Material.SOUL_SAND, Material.SOUL_SOIL},
            // Soul soil combinations
            {Material.SOUL_SOIL, Material.DIRT},
        };

        // Register each recipe combination
        for (int i = 0; i < combinations.length; i++) {
            Material[] combo = combinations[i];
            NamespacedKey recipeKey = new NamespacedKey(this, "crumblestone_" + i);
            ShapelessRecipe recipe = new ShapelessRecipe(recipeKey, result);
            
            // Add 2 of each ingredient (shapeless)
            recipe.addIngredient(2, combo[0]);
            recipe.addIngredient(2, combo[1]);

            try {
                Bukkit.addRecipe(recipe);
            } catch (IllegalStateException ex) {
                // ignore if server already has a recipe with same key or other issue
                getLogger().warning("Failed to add recipe " + i + ": " + ex.getMessage());
            }
        }
    }

    private ItemStack createCrumblestoneItem() {
        ItemStack item = new ItemStack(crumblestoneMaterial, 1);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Crumblestone", NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
        meta.lore(java.util.List.of(
            Component.text("Decays after 5 minutes", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
        ));
        // mark thisd so we can identify it anywhere
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte)1);
        item.setItemMeta(meta);
        return item;
    }

    private String locKey(Location loc) {
        return loc.getWorld().getName() + ":" + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }

    private boolean isCrumblestoneItem(ItemStack item) {
        if (item == null) return false;
        if (item.getType() != crumblestoneMaterial) return false;
        if (!item.hasItemMeta()) return false;
        Byte flag = item.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.BYTE);
        return flag != null && flag == (byte)1;
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent e) {
        ItemStack used = e.getItemInHand();
        if (!isCrumblestoneItem(used)) return;

        Block placed = e.getBlockPlaced();
        Location loc = placed.getLocation();
        String key = locKey(loc);

        // Cancel any leftover task for this location
        BukkitTask oldTask = scheduledRemoval.remove(key);
        if (oldTask != null) oldTask.cancel();

        // Reset leftover overlay if needed
        resetCrackOverlay(placed);

        placed.setMetadata(META_KEY, new FixedMetadataValue(this, true));

        long interval = LIFETIME_TICKS / 10; // ticks between stages for smooth animation

        final int[] stage = {0};

        final BukkitTask[] taskHolder = new BukkitTask[1];
        taskHolder[0] = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (placed.getType() != crumblestoneMaterial || !hasPluginMetadata(placed)) {
                return;
            }

            if (stage[0] < 10) {
                float progress = stage[0] / 10.0f;
                sendCrackProgress(placed, progress);
                stage[0]++;
            } else {
                // Final stage; decay the block
                playBlockBreakEffect(placed);
                placed.setType(Material.AIR, false);
                resetCrackOverlay(placed);
                scheduledRemoval.remove(key);
                placed.removeMetadata(META_KEY, this);
                // Cancel repeating task
                taskHolder[0].cancel();
            }
        }, interval, interval);

        scheduledRemoval.put(key, taskHolder[0]);
    }

   @EventHandler
    public void onBlockBreak(BlockBreakEvent e) {
        Block block = e.getBlock();
        String key = locKey(block.getLocation());

        if (scheduledRemoval.containsKey(key) || hasPluginMetadata(block)) {
            BukkitTask task = scheduledRemoval.remove(key);
            if (task != null) task.cancel();

            // no item drops
            e.setDropItems(false);
            e.setExpToDrop(0);
            
            // reset the breaking animation for this tile
            resetCrackOverlay(block);

            playBlockBreakEffect(block);

            block.removeMetadata(META_KEY, this);
        }
    }

    @EventHandler
    public void onBlockDamage(BlockDamageEvent e) {
        Block block = e.getBlock();
        if (block.getType() == crumblestoneMaterial && hasPluginMetadata(block)) {
            // force instant break
            e.setInstaBreak(true);
        }
    }

    // entity explosions
    @EventHandler
    public void onEntityExplode(EntityExplodeEvent e) {
        for (Block b : e.blockList()) {
            handleExternalBlockDestroy(b);
        }
    }

    // block explosions 
    @EventHandler
    public void onBlockExplode(BlockExplodeEvent e) {
        for (Block b : e.blockList()) {
            handleExternalBlockDestroy(b);
        }
    }

    private void handleExternalBlockDestroy(Block b) {
        String key = locKey(b.getLocation());
        if (scheduledRemoval.containsKey(key) || hasPluginMetadata(b)) {
            BukkitTask t = scheduledRemoval.remove(key);
            if (t != null) t.cancel();
            resetCrackOverlay(b);
            try {
                b.removeMetadata(META_KEY, this);
            } catch (Throwable ignored) {}
        }
    }

    private boolean hasPluginMetadata(Block block) {
        try {
            for (MetadataValue mv : block.getMetadata(META_KEY)) {
                if (Objects.equals(mv.getOwningPlugin(), this) && Boolean.TRUE.equals(mv.value())) {
                    return true;
                }
            }
        } catch (Exception idc) {}
        return false;
    }

    private void playBlockBreakEffect(Block block) {
        block.getWorld().playEffect(block.getLocation(), org.bukkit.Effect.STEP_SOUND, block.getType());
    }
    
    private int getOrCreateFakeEntityId(Location loc) {
        String key = locKey(loc);
        return blockFakeEntityIds.computeIfAbsent(key, k -> nextFakeEntityId--);
    }

    private void removeFakeEntityId(Location loc) {
        String key = locKey(loc);
        blockFakeEntityIds.remove(key);
    }

    // Send crack overlay using fake entity ID
    private void sendCrackProgress(Block block, float progress) {
        Location loc = block.getLocation();
        int fakeId = getOrCreateFakeEntityId(loc);

        int viewDist = Bukkit.getViewDistance() * 16;
        int viewDistSq = viewDist * viewDist;

        for (var player : block.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(loc) <= viewDistSq) {
                player.sendBlockDamage(loc, progress, fakeId);
            }
        }
    }

    private void resetCrackOverlay(Block block) {
        Location loc = block.getLocation();
        String key = locKey(loc);
        Integer fakeId = blockFakeEntityIds.get(key);
        if (fakeId == null) return;

        int viewDist = Bukkit.getViewDistance() * 16;
        int viewDistSq = viewDist * viewDist;

        for (var player : block.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(loc) <= viewDistSq) {
                player.sendBlockDamage(loc, 0f, fakeId); // reset
            }
        }

        removeFakeEntityId(loc);
    }


}

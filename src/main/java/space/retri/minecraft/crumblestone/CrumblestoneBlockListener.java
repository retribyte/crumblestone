package space.retri.minecraft.crumblestone;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

public class CrumblestoneBlockListener implements Listener {
    // For generating unique fake entity IDs 
    private int nextFakeEntityId = -1;
    // Map each block location to its fake entity ID
    private final Map<String, Integer> blockFakeEntityIds = new ConcurrentHashMap<>();
    // locationKey -> task
    private final Map<String, BukkitTask> scheduledRemoval = new ConcurrentHashMap<>();
    
    private String locKey(Location loc) {
        return loc.getWorld().getName() + ":" + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }

    private boolean isCrumblestoneItem(ItemStack item) {
        if (item == null) return false;
        if (item.getType() != CrumblestonePlugin.getMaterial()) return false;
        if (!item.hasItemMeta()) return false;
        Byte flag = item.getItemMeta().getPersistentDataContainer().get(CrumblestonePlugin.getItemKey(), PersistentDataType.BYTE);
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

        placed.setMetadata(CrumblestonePlugin.META_KEY, new FixedMetadataValue(CrumblestonePlugin.getPlugin(), true));

        long interval = CrumblestonePlugin.getDecayTicks() / 10; // ticks between stages for smooth animation

        final int[] stage = {0};

        final BukkitTask[] taskHolder = new BukkitTask[1];
        taskHolder[0] = Bukkit.getScheduler().runTaskTimer(CrumblestonePlugin.getPlugin(), () -> {
            if (placed.getType() != CrumblestonePlugin.getMaterial() || !hasPluginMetadata(placed)) {
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
                placed.removeMetadata(CrumblestonePlugin.META_KEY, CrumblestonePlugin.getPlugin());
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

            block.removeMetadata(CrumblestonePlugin.META_KEY, CrumblestonePlugin.getPlugin());
        }
    }

    @EventHandler
    public void onBlockDamage(BlockDamageEvent e) {
        Block block = e.getBlock();
        if (block.getType() == CrumblestonePlugin.getMaterial() && hasPluginMetadata(block)) {
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
                b.removeMetadata(CrumblestonePlugin.META_KEY, CrumblestonePlugin.getPlugin());
            } catch (Throwable ignored) {}
        }
    }

    private boolean hasPluginMetadata(Block block) {
        try {
            for (MetadataValue mv : block.getMetadata(CrumblestonePlugin.META_KEY)) {
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

    public void clearScheduledRemovals() {
        // Cancel all scheduled tasks we created
        for (BukkitTask task : scheduledRemoval.values()) {
            if (task != null) task.cancel();
        }
        scheduledRemoval.clear();
    }
}

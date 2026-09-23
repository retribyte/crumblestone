package space.retri.crumblestone;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

public class CrumblestoneBlockListener implements Listener {
    // For generating unique fake entity IDs 
    private int nextFakeEntityId = -1;
    // Map each block to its fake entity ID
    private final Map<Block, Integer> blockFakeEntityIds = new ConcurrentHashMap<>();
    // Block -> task
    private final Map<Block, BukkitTask> scheduledRemoval = new ConcurrentHashMap<>();

    private boolean isCrumblestoneItem(ItemStack item) {
        if (item == null) return false;
        if (item.getType() != CrumblestonePlugin.getMaterial()) return false;
        if (!item.hasItemMeta()) return false;
        Byte flag = item.getItemMeta().getPersistentDataContainer().get(CrumblestonePlugin.getItemKey(), PersistentDataType.BYTE);
        return flag != null && flag == (byte)1;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBlockPlace(BlockPlaceEvent e) {
        ItemStack used = e.getItemInHand();
        if (!isCrumblestoneItem(used)) return;

        Block placed = e.getBlockPlaced();
        int placedTick = Bukkit.getCurrentTick();

        // Cancel any leftover task for this location
        BukkitTask oldTask = scheduledRemoval.remove(placed);
        if (oldTask != null) oldTask.cancel();

        // Reset leftover overlay if needed
        resetCrackOverlay(placed);

        placed.setMetadata(CrumblestonePlugin.META_KEY, new FixedMetadataValue(CrumblestonePlugin.getPlugin(), true));

        // must refresh often enough so client doesn't remove cracks
        long interval = Math.max(1, Math.min(100, CrumblestonePlugin.getDecayTicks()));

        final BukkitTask[] taskHolder = new BukkitTask[1];
        taskHolder[0] = Bukkit.getScheduler().runTaskTimer(CrumblestonePlugin.getPlugin(), () -> {
            if (!isCrumblestone(placed)) {
                placed.removeMetadata(CrumblestonePlugin.META_KEY, CrumblestonePlugin.getPlugin());
                resetCrackOverlay(placed);
                scheduledRemoval.remove(placed);
                taskHolder[0].cancel();
                return;
            }

            int elapsedTicks = Bukkit.getCurrentTick() - placedTick;
            if (elapsedTicks >= CrumblestonePlugin.getDecayTicks()) {
                // Final stage; decay the block
                playBlockBreakEffect(placed);
                placed.setType(Material.AIR, false);
                resetCrackOverlay(placed);
                scheduledRemoval.remove(placed);
                placed.removeMetadata(CrumblestonePlugin.META_KEY, CrumblestonePlugin.getPlugin());
                // Cancel repeating task
                taskHolder[0].cancel();
            } else {
                sendCrackProgress(placed, (elapsedTicks / (float)CrumblestonePlugin.getDecayTicks()));
            }
        }, 0, interval);

        scheduledRemoval.put(placed, taskHolder[0]);
    }

   @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onBlockBreak(BlockBreakEvent e) {
        Block block = e.getBlock();

        if (isCrumblestone(block)) {
            // CrumblestonePlugin.getPlugin().getLogger().info("A crumblestone block was broken.");
            BukkitTask task = scheduledRemoval.remove(block);
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
        if (isCrumblestone(block)) {
            // CrumblestonePlugin.getPlugin().getLogger().info("A crumblestone block is being damaged, preventing normal break.");
            // force instant break
            e.setInstaBreak(true);
        }
    }

    // entity explosions
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onEntityExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(this::destroyCrumblestoneInExplosion);
    }

    // block explosions (bed, respawn anchor)
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(this::destroyCrumblestoneInExplosion);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityUpdate(EntityChangeBlockEvent e) {
        Block b = e.getBlock();

        // handle if material is set to a falling block e.g. sand, gravel
        if (isCrumblestone(b) && e.getEntityType().equals(EntityType.FALLING_BLOCK)) {
            e.setCancelled(true);
            // CrumblestonePlugin.getPlugin().getLogger().warning("A crumblestone block is trying to fall!");
            return;
        }

        if (isCrumblestone(b)) {
            // CrumblestonePlugin.getPlugin().getLogger().info("A crumblestone block is being updated by an entity.");
            playBlockBreakEffect(b);
            b.setType(Material.AIR, false);

            handleExternalBlockDestroy(b);
            e.setCancelled(true);
        }
    }

    // piston push
    @EventHandler(ignoreCancelled = true)
    public void onBlockPistonPush(BlockPistonExtendEvent e) {
        onBlockPistonMove(e.getBlocks());
    }

    // piston pull
    @EventHandler(ignoreCancelled = true)
    public void onBlockPistonPull(BlockPistonRetractEvent e) {
        if (!e.isSticky()) return; // only care about sticky pistons
        onBlockPistonMove(e.getBlocks());
    }

    private boolean destroyCrumblestoneInExplosion(Block b) {
        // keep exploded blocks
        if (!isCrumblestone(b))
            return false;

        // CrumblestonePlugin.getPlugin().getLogger().info("A crumblestone block is being exploded.");
        playBlockBreakEffect(b);
        b.setType(Material.AIR, false);   // destroy it ourselves, no drop
        handleExternalBlockDestroy(b);    // cancels task, resets overlay, removes metadata
        return true;
    }

    // generic piston event
    public void onBlockPistonMove(List<Block> blocks) {
        for (Block block : blocks) {
            if (block.getType() == CrumblestonePlugin.getMaterial() && (scheduledRemoval.containsKey(block) || hasPluginMetadata(block))) {
                // CrumblestonePlugin.getPlugin().getLogger().info("A crumblestone block is being moved by a piston.");
                playBlockBreakEffect(block);
                block.setType(Material.AIR, false);
                handleExternalBlockDestroy(block);
            }
        }
    }

    private void handleExternalBlockDestroy(Block b) {
        if (isCrumblestone(b)) {
            BukkitTask t = scheduledRemoval.remove(b);
            if (t != null) t.cancel();
            resetCrackOverlay(b);
            b.removeMetadata(CrumblestonePlugin.META_KEY, CrumblestonePlugin.getPlugin());
        }
    }

    private boolean hasPluginMetadata(Block block) {
        try {
            for (MetadataValue mv : block.getMetadata(CrumblestonePlugin.META_KEY)) {
                if (Objects.equals(mv.getOwningPlugin(), CrumblestonePlugin.getPlugin()) && Boolean.TRUE.equals(mv.value())) {
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
        return blockFakeEntityIds.computeIfAbsent(loc.getBlock(), k -> nextFakeEntityId--);
    }

    private void removeFakeEntityId(Location loc) {
        blockFakeEntityIds.remove(loc.getBlock());
    }

    // Send crack overlay using fake entity ID
    private void sendCrackProgress(Block block, float progress) {
        Location loc = block.getLocation();
        int fakeId = getOrCreateFakeEntityId(loc);

        int viewDist = Bukkit.getSimulationDistance() * 16;
        int viewDistSq = viewDist * viewDist;

        for (var player : block.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(loc) <= viewDistSq) {
                player.sendBlockDamage(loc, progress, fakeId);
            }
        }
    }

    private void resetCrackOverlay(Block block) {
        Location loc = block.getLocation();
        Integer fakeId = blockFakeEntityIds.get(block);
        if (fakeId == null) return;

        int viewDist = Bukkit.getSimulationDistance() * 16;
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
        for (Map.Entry<Block, BukkitTask> pending : scheduledRemoval.entrySet()) {
            pending.getValue().cancel();
            // now we need to break all the blocks so they don't persist
            pending.getKey().setType(Material.AIR, false);
            resetCrackOverlay(pending.getKey());
        }
        scheduledRemoval.clear();
    }

    private boolean isCrumblestone(Block block) {
        return block.getType() == CrumblestonePlugin.getMaterial() && hasPluginMetadata(block);
    }
}

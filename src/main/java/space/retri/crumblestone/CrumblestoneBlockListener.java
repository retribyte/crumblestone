package space.retri.crumblestone;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
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
import org.bukkit.event.world.ChunkLoadEvent;
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

    // initial no-cracks + 10 crack textures
    private static final int VISUAL_STAGES = 11;

    private NamespacedKey chunkKey(Block b) {
        return new NamespacedKey(CrumblestonePlugin.getPlugin(), "block." + (b.getX() & 15) + "." + b.getY() + "." + (b.getZ() & 15));
    }

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

        // set block metadata and persistent chunk info for that block / location
        placed.setMetadata(CrumblestonePlugin.META_KEY, new FixedMetadataValue(CrumblestonePlugin.getPlugin(), true));
        placed.getChunk().getPersistentDataContainer()
            .set(
                chunkKey(placed), 
                PersistentDataType.INTEGER_ARRAY,
                new int[]{ placed.getX(), placed.getY(), placed.getZ() }
            );

        // must refresh often enough so client doesn't remove cracks
        long interval = Math.max(1, Math.min(100, CrumblestonePlugin.getDecayTicks()));

        // timer
        final BukkitTask[] taskHolder = new BukkitTask[1];
        taskHolder[0] = Bukkit.getScheduler().runTaskTimer(CrumblestonePlugin.getPlugin(), () -> {
            // skip if the chunk is unloaded
            if (!placed.getWorld().isChunkLoaded(placed.getX() >> 4, placed.getZ() >> 4)) {
                return;
            }

            if (!isCrumblestone(placed)) {
                removeMetadata(placed);
                resetCrackOverlay(placed);
                scheduledRemoval.remove(placed);
                taskHolder[0].cancel();
                return;
            }

            int elapsedTicks = Bukkit.getCurrentTick() - placedTick;
            if (elapsedTicks >= CrumblestonePlugin.getDecayTicks()) {
                // Final stage; decay the block
                playBlockBreakEffect(placed);
                placed.setType(Material.AIR, CrumblestonePlugin.getUpdateOnDecay());
                resetCrackOverlay(placed);
                scheduledRemoval.remove(placed);
                removeMetadata(placed);
                // Cancel repeating task
                taskHolder[0].cancel();
            } else {
                sendCrackProgress(placed, crackProgress(elapsedTicks));
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
            removeMetadata(block);
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

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        recoverChunk(e.getChunk());
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
        if (isCrumblestone(b)) { 
            if (e.getEntityType().equals(EntityType.FALLING_BLOCK)) {
                e.setCancelled(true);
                // CrumblestonePlugin.getPlugin().getLogger().warning("A crumblestone block is trying to fall!");
                return;
            }
            
            // CrumblestonePlugin.getPlugin().getLogger().info("A crumblestone block is being updated by an entity.");
            playBlockBreakEffect(b);
            b.setType(Material.AIR, true);

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
        b.setType(Material.AIR, true);   // destroy it ourselves, no drop
        handleExternalBlockDestroy(b);    // cancels task, resets overlay, removes metadata
        return true;
    }

    // generic piston event
    public void onBlockPistonMove(List<Block> blocks) {
        for (Block block : blocks) {
            if (isCrumblestone(block)) {
                // CrumblestonePlugin.getPlugin().getLogger().info("A crumblestone block is being moved by a piston.");
                playBlockBreakEffect(block);
                block.setType(Material.AIR, false);
                handleExternalBlockDestroy(block);
            }
        }
    }

    private void handleExternalBlockDestroy(Block b) {
        if (scheduledRemoval.containsKey(b) || hasPluginMetadata(b)) {
            BukkitTask t = scheduledRemoval.remove(b);
            if (t != null) t.cancel();
            resetCrackOverlay(b);
            removeMetadata(b);
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
    
    private float crackProgress(long elapsedTicks) {
        int stage = (int)(elapsedTicks * VISUAL_STAGES / CrumblestonePlugin.getDecayTicks()) - 1;
        if (stage < 0) {
            return 0;
        }
        // add 0.5 to avoid rounding down a stage
        return Math.min(1f, (stage + 0.5f) / 9f);
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
            Block block = pending.getKey();
            pending.getValue().cancel();

            // now we need to break all the blocks so they don't persist
            if (!block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)) {
                continue;
            }
            block.setType(Material.AIR, CrumblestonePlugin.getUpdateOnDecay());
            resetCrackOverlay(block);
            removeMetadata(block);
        }
        scheduledRemoval.clear();
    }

    private void removeMetadata(Block b) {
        b.removeMetadata(CrumblestonePlugin.META_KEY, CrumblestonePlugin.getPlugin());
        b.getChunk().getPersistentDataContainer().remove(chunkKey(b));
    }

    private boolean isCrumblestone(Block block) {
        return block.getType() == CrumblestonePlugin.getMaterial() && hasPluginMetadata(block);
    }

    public void recoverChunk(Chunk chunk) {
        for (NamespacedKey key : chunk.getPersistentDataContainer().getKeys()) {
            if (!key.getNamespace().equals(CrumblestonePlugin.NAMESPACE) 
                || !key.getKey().startsWith("block.")) {
                continue;
            }

            int[] coords = chunk.getPersistentDataContainer().get(key, PersistentDataType.INTEGER_ARRAY);
            Block block = chunk.getWorld().getBlockAt(coords[0], coords[1], coords[2]);
            if (block.getType() == CrumblestonePlugin.getMaterial()) {
                playBlockBreakEffect(block);
                block.setType(Material.AIR, false);
            }

            if (!scheduledRemoval.containsKey(block)) {
                chunk.getPersistentDataContainer().remove(key);
            }
        }
    }
}

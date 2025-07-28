package gg.hjk.domination

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import gg.hjk.secondwind.api.PlayerKnockDownEvent
import gg.hjk.secondwind.api.PlayerSecondWindEvent
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bstats.bukkit.Metrics
import org.bukkit.Bukkit
import org.bukkit.craftbukkit.entity.CraftPlayer
import org.bukkit.entity.Entity
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import org.jetbrains.annotations.ApiStatus.Internal
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Internal
class Domination : JavaPlugin(), Listener {

    companion object {
        const val BSTATS_ID = 26670
        const val DOMINATION_AT = 4
    }

    private lateinit var metrics: Metrics
    private lateinit var dataFile: File
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private var saveTask: BukkitTask? = null

    // Use ConcurrentHashMap for thread-safety with asynchronous saving.
    private val deathTracker = ConcurrentHashMap<UUID, HashMap<String, Int>>()
    private val cachedKillers = ConcurrentHashMap<UUID, Killer>()

    override fun onEnable() {
        metrics = Metrics(this, BSTATS_ID)
        dataFile = File(dataFolder, "data.json")

        loadData()
        Bukkit.getPluginManager().registerEvents(this, this)

        // Schedule an asynchronous task to save data every 5 minutes (6000 ticks).
        saveTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, Runnable { saveData() }, 0L, 6000L)

        this.logger.info("Enabled!")
    }

    override fun onDisable() {
        // Cancel the scheduled async task to prevent a race condition during shutdown.
        saveTask?.cancel()

        saveData() // Perform a final, synchronous save.
        if (this::metrics.isInitialized)
            metrics.shutdown()
    }

    private fun saveData() {
        try {
            dataFolder.mkdirs()
            dataFile.writer().use { writer ->
                gson.toJson(deathTracker, writer)
            }
        } catch (e: IOException) {
            logger.severe("Could not save death tracker data to data.json")
            e.printStackTrace()
        }
    }

    private fun loadData() {
        if (!dataFile.exists()) {
            return
        }
        try {
            dataFile.reader().use { reader ->
                val type = object : TypeToken<ConcurrentHashMap<UUID, HashMap<String, Int>>>() {}.type
                // Safely load data, handling cases where the file might be empty or corrupt.
                val loadedData: ConcurrentHashMap<UUID, HashMap<String, Int>>? = gson.fromJson(reader, type)
                if (loadedData != null) {
                    deathTracker.putAll(loadedData)
                }
            }
        } catch (e: Exception) {
            logger.severe("Could not load death tracker data from data.json")
            e.printStackTrace()
        }
    }

    inner class Killer(entity: Entity) {
        val isPlayer = entity is Player
        val id = if (isPlayer) entity.uniqueId.toString() else entity.type.toString()
        val name = if (isPlayer) entity.name else entity.type.translationKey()
    }

    // ------------------------

    @EventHandler(priority = EventPriority.HIGH)
    fun appendKillstreak(event: PlayerDeathEvent) {
        if (event.isCancelled)
            return
        val killer = cachedKillers[event.player.uniqueId] ?: return
        cachedKillers.remove(event.player.uniqueId)
        val displayName = if (killer.isPlayer) killer.name else "<lang:${killer.name}>"
        val playerName = event.player.name

        // computeIfAbsent ensures the inner map is created if it doesn't exist.
        val tracker = deathTracker.computeIfAbsent(event.player.uniqueId) { HashMap() }
        val timesKilled = tracker.getOrDefault(killer.id, 0) + 1
        tracker[killer.id] = timesKilled

        if (timesKilled < DOMINATION_AT)
            return
        else if (timesKilled == DOMINATION_AT) {
            Bukkit.getScheduler().runTaskLater(this, Runnable {
                val message = MiniMessage.miniMessage().deserialize(
                    "$displayName <bold><gradient:#ffaaaa:#ff1111>IS DOMINATING</gradient></bold> $playerName")
                Bukkit.getServer().broadcast(message)
            }, 1L)
            return
        }

        var component = event.deathMessage() ?: return
        component = component.append(MiniMessage.miniMessage().deserialize(
            " <hover:show_text:'<green>${timesKilled} <gray>consecutive deaths to ${displayName}'>" +
                    "<gray>(<green>◎${timesKilled}<gray>)</hover>"
        ))
        event.deathMessage(component)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun cacheKiller(event: PlayerKnockDownEvent) {
        val player = event.player
        cachedKillers.remove(player.uniqueId)

        var killer = event.damageSource.causingEntity
        if (killer == null) {
            // Attempt to get killer for falling death
            val mostSignificantFall = event.player.combatTracker.computeMostSignificantFall()
            if (mostSignificantFall != null)
                killer = mostSignificantFall.damageSource.causingEntity

            // Attempt to get killer via nms for indirect kills (e.g. lava)
            if (killer == null && player is CraftPlayer) {
                killer = player.handle.killCredit?.bukkitLivingEntity
            }
        }

        if (killer !is Mob && killer !is Player)
            return
        if (player.uniqueId == killer.uniqueId)
            return
        cachedKillers[event.player.uniqueId] = Killer(killer)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun removeKillerOnRevive(event: PlayerSecondWindEvent) {
        // TODO: if cancelled return
        cachedKillers.remove(event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun checkForRevenge(event: EntityDeathEvent) {
        val player = event.damageSource.causingEntity as? Player ?: return
        val key = if (event.entity is Player) event.entity.uniqueId.toString() else event.entity.type.toString()

        val tracker = deathTracker[player.uniqueId] ?: return
        val timesKilled = tracker[key] ?: 0

        if (timesKilled >= DOMINATION_AT) {
            tracker.remove(key) // Clear the domination streak
            val displayName = if (event.entity is Player) event.entity.name else "<lang:${event.entity.type.translationKey()}>"
            val playerName = player.name
            Bukkit.getScheduler().runTaskLater(this, Runnable {
                val message = MiniMessage.miniMessage().deserialize(
                    "$playerName <bold><gradient:#ffaaaa:#ff1111>GOT REVENGE ON</gradient></bold> $displayName")
                Bukkit.getServer().broadcast(message)
            }, 1L)
        }
    }
}

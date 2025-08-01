package gg.hjk.domination

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import gg.hjk.secondwind.api.PlayerDeathAfterKnockDownEvent
import gg.hjk.secondwind.api.PlayerKnockDownEvent
import gg.hjk.secondwind.api.PlayerSecondWindEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bstats.bukkit.Metrics
import org.bukkit.Bukkit
import org.bukkit.craftbukkit.entity.CraftLivingEntity
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
        val id = if (isPlayer) entity.uniqueId.toString() else entity.type.key.toString()
        val name = if (isPlayer) entity.name else entity.type.translationKey()
    }

    fun getKillstreakColorTag(kills: Int, killOffset: Int): Pair<String, String> {
        val killstreakTiers = listOf(
            "white",              // 0
            "green",              // 5
            "#ff9900",            // 10
            "dark_purple",        // 15
            "yellow",             // 20
            "blue",               // 25
            "red",                // 30
            "light_purple",       // 35
            "aqua",               // 40
            "#ffb0ff",            // 45
        )

        val k = kills + killOffset
        return if (kills >= 1000) {
            Pair("<rainbow><bold>◎$k</bold></rainbow>", "<rainbow><bold>$k</bold></rainbow>")
        } else if (kills >= 500) {
            val nonBoldTag = getKillstreakColorTag(kills - 500, 500)
            Pair("<bold>${nonBoldTag.first}</bold>", "<bold>${nonBoldTag.second}</bold>")
        } else if (kills >= 50) {
            val firstColor = killstreakTiers[kills / 50]
            var secondColor = killstreakTiers[(kills % 50) / 5]
            if (secondColor == firstColor)
                secondColor = "white"
            Pair("<gradient:$firstColor:$secondColor>◎$k</gradient>", "<gradient:$firstColor:$secondColor>$k</gradient>")
        } else {
            val color = killstreakTiers[kills / 5]
            Pair("<color:$color>◎$k</color>", "<color:$color>$k</color>")
        }
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

        val tracker = deathTracker.computeIfAbsent(event.player.uniqueId) { HashMap() }
        val timesKilled = tracker.getOrDefault(killer.id, 0) + 1
        tracker[killer.id] = timesKilled

        if (timesKilled < DOMINATION_AT)
            return
        else if (timesKilled == DOMINATION_AT) {
            Bukkit.getScheduler().runTaskLater(this, Runnable {
                val message = MiniMessage.miniMessage().deserialize(
                    "$displayName <bold><gradient:#ffaaaa:#ff5555>IS DOMINATING</gradient></bold> $playerName")
                Bukkit.getServer().broadcast(message)
            }, 1L)
            return
        }

        var component = event.deathMessage() ?: return
        val tag = getKillstreakColorTag(timesKilled, 0)
        component = component.append(MiniMessage.miniMessage().deserialize(
            " <hover:show_text:'${tag.second} <gray>consecutive deaths to ${displayName}'>" +
                    "<gray>(${tag.first}<gray>)</hover>"
        ))
        event.deathMessage(component)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun cacheKiller(event: PlayerKnockDownEvent) {
        val player = event.player
        cachedKillers.remove(player.uniqueId)

        var killer = event.damageSource.causingEntity
        if (killer == null) {
            // Try another, player-specific method
            killer = event.player.killer

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

        // If the killer isn't mentioned in the death message, we'll append it for clarity
        // TODO do the hover/insert components.
        val message = MiniMessage.miniMessage().serialize(event.deathMessage)
        val customName = MiniMessage.miniMessage().serialize(killer.customName() ?: Component.empty())
        if (killer !is Player && !message.contains(":${killer.uniqueId}")) {
            event.deathMessage = event.deathMessage.append(MiniMessage.miniMessage().deserialize(
                " while fighting ${customName.ifEmpty { "<lang:${killer.type.translationKey()}>" }}"
            ))
        } else if (killer is Player && !message.contains("player:${killer.uniqueId}")) {
            event.deathMessage = event.deathMessage.append(MiniMessage.miniMessage().deserialize(
                " while fighting ${killer.name}"
            ))
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun removeKillerOnRevive(event: PlayerSecondWindEvent) {
        // TODO: if cancelled return
        cachedKillers.remove(event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun checkForEntityRevenge(event: EntityDeathEvent) {
        if (event.entity is Player)
            return // revenge handled elsewhere when supporting secondwind

        var killer = event.damageSource.causingEntity
        if (killer == null) {
            // Try another, player-specific method
            killer = event.entity.killer

            // Attempt to get killer for falling death
            if (killer == null) {
                val mostSignificantFall = event.entity.combatTracker.computeMostSignificantFall()
                if (mostSignificantFall != null)
                    killer = mostSignificantFall.damageSource.causingEntity
            }

            // Attempt to get killer via nms for indirect kills (e.g. lava)
            if (killer == null && event.entity is CraftLivingEntity) {
                killer = (event.entity as CraftLivingEntity).handle.killCredit?.bukkitLivingEntity
            }
        }

        if (killer !is Player)
            return

        val key = event.entity.type.key.toString()

        val tracker = deathTracker[killer.uniqueId] ?: return
        val timesKilled = tracker[key] ?: 0
        tracker.remove(key) // Clear the domination streak

        if (timesKilled >= DOMINATION_AT) {
            val displayName = "<lang:${event.entity.type.translationKey()}>"
            val playerName = killer.name
            Bukkit.getScheduler().runTaskLater(this, Runnable {
                val message = MiniMessage.miniMessage().deserialize(
                    "$playerName <bold><gradient:#ffaaaa:#ff5555>GOT REVENGE ON</gradient></bold> $displayName")
                Bukkit.getServer().broadcast(message)
            }, 1L)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun checkForPlayerRevenge(event: PlayerDeathAfterKnockDownEvent) {
        val killer = cachedKillers[event.player.uniqueId]
        if (killer == null || !killer.isPlayer)
            return
        val killerUuid = UUID.fromString(killer.id)

        val tracker = deathTracker[killerUuid] ?: return
        val timesKilled = tracker[event.player.uniqueId.toString()] ?: 0
        tracker.remove(event.player.uniqueId.toString())
        deathTracker[killerUuid] = tracker

        if (timesKilled >= DOMINATION_AT) {
            Bukkit.getScheduler().runTaskLater(this, Runnable {
                val offlineKiller = Bukkit.getServer().getOfflinePlayer(killerUuid)
                val killerName = offlineKiller.name ?: return@Runnable
                val message = MiniMessage.miniMessage().deserialize(
                    "$killerName <bold><gradient:#ffaaaa:#ff5555>GOT REVENGE ON</gradient></bold> ${event.player.name}")
                Bukkit.getServer().broadcast(message)
            }, 1L)
        }
    }
}

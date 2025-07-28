package gg.hjk.domination

import gg.hjk.secondwind.api.PlayerKnockDownEvent
import gg.hjk.secondwind.api.PlayerSecondWindEvent
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bstats.bukkit.Metrics
import org.bukkit.Bukkit
import org.bukkit.entity.Entity
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.plugin.java.JavaPlugin
import org.jetbrains.annotations.ApiStatus.Internal
import java.util.UUID

@Internal
class Domination : JavaPlugin(), Listener {

    companion object {
        const val BSTATS_ID = 26670
        const val DOMINATION_AT = 4
    }

    private lateinit var metrics : Metrics

    override fun onEnable() {
        metrics = Metrics(this, BSTATS_ID)
        Bukkit.getPluginManager().registerEvents(this, this)
        this.logger.info("Enabled!")
    }

    override fun onDisable() {
        if (this::metrics.isInitialized)
            metrics.shutdown()
    }

    inner class Killer(entity: Entity) {
        val isPlayer = entity is Player
        val id = if (isPlayer) entity.uniqueId.toString() else entity.type.toString()
        val name = if (isPlayer) entity.name else entity.type.translationKey() // TODO handle cases without key
    }

    // ------------------------

    val cachedKillers = HashMap<UUID, Killer>()
    val deathTracker = HashMap<UUID, HashMap<String, Int>>()

    @EventHandler(priority = EventPriority.HIGHEST)
    fun appendKillstreak(event: PlayerDeathEvent) {
        if (event.isCancelled)
            return
        val killer = cachedKillers[event.player.uniqueId] ?: return
        cachedKillers.remove(event.player.uniqueId)
        val displayName = if (killer.isPlayer) killer.name else "<lang:${killer.name}>"
        val playerName = event.player.name

        val tracker = deathTracker[event.player.uniqueId] ?: HashMap()
        val timesKilled = (tracker[killer.id] ?: 0) + 1
        tracker[killer.id] = timesKilled
        deathTracker[event.player.uniqueId] = tracker

        if (timesKilled < DOMINATION_AT)
            return
        else if (timesKilled == DOMINATION_AT) {
            Bukkit.getScheduler().runTaskLater(this, Runnable {
                val message = MiniMessage.miniMessage().deserialize(
                    "$displayName <bold><gradient:#ffaaaa:#ff1111>IS DOMINATING</gradient></bold> $playerName")
                Bukkit.getServer().sendMessage(message)
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
        cachedKillers.remove(event.player.uniqueId)
        var killer = event.damageSource.causingEntity
        if (killer == null) {
            val mostSignificantFall = event.player.combatTracker.computeMostSignificantFall() ?: return
            killer = mostSignificantFall.damageSource.causingEntity ?: return
        }

        if (killer !is Mob && killer !is Player)
            return
        if (event.player.uniqueId == killer.uniqueId)
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
        val player = event.damageSource.causingEntity as? Player ?: return // TODO same combat tracker hack as above?
        val key = if (event.entity is Player) event.entity.uniqueId.toString() else event.entity.type.toString()

        val tracker = deathTracker[player.uniqueId] ?: HashMap()
        val timesKilled = tracker[key] ?: 0
        val displayName = if (event.entity is Player) event.entity.name else "<lang:${event.entity.type.translationKey()}>"
        val playerName = player.name
        tracker.remove(key)
        deathTracker[player.uniqueId] = tracker

        if (timesKilled >= DOMINATION_AT) {
            Bukkit.getScheduler().runTaskLater(this, Runnable {
                val message = MiniMessage.miniMessage().deserialize(
                    "$playerName <bold><gradient:#ffaaaa:#ff1111>GOT REVENGE ON</gradient></bold> $displayName")
                Bukkit.getServer().sendMessage(message)
            }, 1L)
        }
    }
}

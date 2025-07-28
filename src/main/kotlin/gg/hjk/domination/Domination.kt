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
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.plugin.java.JavaPlugin
import org.jetbrains.annotations.ApiStatus.Internal
import java.util.UUID

@Internal
class Domination : JavaPlugin(), Listener {

    companion object {
        const val BSTATS_ID = 26670
        const val DOMINATION_AT = 1 // FIXME 4
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
        val killer = cachedKillers[event.player.uniqueId] ?: return
        cachedKillers.remove(event.player.uniqueId)
        val displayName = if (killer.isPlayer) killer.name else "<lang:${killer.name}>"

        // FIXME debug print
        println("${event.player.name} $displayName ${killer.id}")

        val tracker = deathTracker[event.player.uniqueId] ?: HashMap()
        val timesKilled = (tracker[killer.id] ?: 0) + 1
        tracker[killer.id] = timesKilled
        deathTracker[event.player.uniqueId] = tracker

        if (timesKilled < DOMINATION_AT)
            return
        else if (timesKilled == DOMINATION_AT) {
            // TODO
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
    fun cacheDeathCause(event: PlayerKnockDownEvent) {
        val killer = event.damageSource.causingEntity ?: return
        if (killer !is Mob && killer !is Player)
            return
        cachedKillers[event.player.uniqueId] = Killer(killer)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun removeDeathCauseOnRevive(event: PlayerSecondWindEvent) {
        // TODO: if cancelled return
        cachedKillers.remove(event.player.uniqueId)
    }
}

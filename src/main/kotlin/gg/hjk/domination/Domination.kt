package gg.hjk.domination

import org.bstats.bukkit.Metrics
import org.bukkit.plugin.java.JavaPlugin
import org.jetbrains.annotations.ApiStatus.Internal

@Internal
class Domination : JavaPlugin() {

    companion object {
        const val BSTATS_ID = 26670
    }

    private lateinit var metrics : Metrics

    override fun onEnable() {
        metrics = Metrics(this, BSTATS_ID)
        this.logger.info("Enabled!")
    }

    override fun onDisable() {
        if (this::metrics.isInitialized)
            metrics.shutdown()
    }
}

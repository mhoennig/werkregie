package de.hoennig.werkregie.dashboard.sources

import com.fasterxml.jackson.databind.JsonNode
import de.hoennig.werkregie.dashboard.Costs
import de.hoennig.werkregie.dashboard.Item
import de.hoennig.werkregie.dashboard.JsonHttp
import de.hoennig.werkregie.dashboard.Source
import de.hoennig.werkregie.dashboard.WerkrevisorConfig
import java.time.Duration
import java.time.Instant

/** The review costs of werkrevisor's statistics service; it reports no items of its own. */
class WerkrevisorSource(private val config: WerkrevisorConfig, private val http: JsonHttp) : Source {
    override val id = "werkrevisor:${config.name}"
    override val label = "werkrevisor ${config.name}"
    override val url = config.url.trimEnd('/')
    // the sums change with each review, a few times an hour
    override val ttl: Duration = Duration.ofMinutes(2)

    @Volatile
    private var costs: Costs? = null

    override fun costs() = costs

    override fun fetch(now: Instant, since: Instant): List<Item> {
        costs = costs(http.get("$url/api/stats"))
        return emptyList()
    }

    internal fun costs(stats: JsonNode): Costs {
        val windows = stats["windows"]?.associate { it.text("name") to it["sums"] }.orEmpty()
        val day = windows["24h"]
        return Costs(
            source = id,
            label = label,
            url = url,
            day = day?.number("costUsd"),
            week = windows["7d"]?.number("costUsd"),
            reviews = day?.number("reviews")?.toInt(),
            pullRequests = day?.number("pullRequests")?.toInt(),
            // the service lists the days newest first
            perDay = stats["days"]?.take(DAYS)?.map { it["sums"]?.number("costUsd") ?: 0.0 }?.reversed().orEmpty(),
        )
    }

    companion object {
        private const val DAYS = 7
    }
}

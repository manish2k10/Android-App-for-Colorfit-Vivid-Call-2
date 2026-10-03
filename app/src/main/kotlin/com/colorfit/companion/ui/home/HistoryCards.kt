package com.colorfit.companion.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.colorfit.companion.vendor.HistoricalRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val DAYS_SHOWN = 7

private fun dayLabel(epochSeconds: Long): String =
    SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(epochSeconds * 1000L))

private fun hourMinute(epochSeconds: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochSeconds * 1000L))

/** 425 → "7h 05m"; 45 → "45m". */
private fun duration(minutes: Int): String =
    if (minutes >= 60) "%dh %02dm".format(minutes / 60, minutes % 60) else "${minutes}m"

@Composable
private fun CardTitle(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Last few nights of sleep, from the watch's `31 01` sync. The history list is
 * rebuilt on every connect and every "Sync history", so repeats are dropped.
 */
@Composable
fun SleepCard(records: List<HistoricalRecord>) {
    val nights = remember(records) {
        records.filterIsInstance<HistoricalRecord.Sleep>()
            .distinctBy { it.timestampUtcSeconds }
            .sortedByDescending { it.timestampUtcSeconds }
            .take(DAYS_SHOWN)
    }
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CardTitle(Icons.Default.Bedtime, "Sleep")
            if (nights.isEmpty()) {
                Text(
                    "No sleep recorded yet. The watch hasn't sent any sleep data — wear it overnight, " +
                        "then tap Sync history.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            nights.forEach { night ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            "${dayLabel(night.timestampUtcSeconds)} · from ${hourMinute(night.timestampUtcSeconds)}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            duration(night.durationMinutes),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    // Deep vs light as a proportional bar.
                    val total = (night.deepMinutes + night.lightMinutes).coerceAtLeast(1)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                    ) {
                        if (night.deepMinutes > 0) {
                            Box(
                                Modifier
                                    .weight(night.deepMinutes.toFloat() / total)
                                    .fillMaxHeight()
                                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)),
                            )
                        }
                        if (night.lightMinutes > 0) {
                            Box(
                                Modifier
                                    .weight(night.lightMinutes.toFloat() / total)
                                    .fillMaxHeight()
                                    .background(MaterialTheme.colorScheme.tertiary, RoundedCornerShape(4.dp)),
                            )
                        }
                    }
                    Text(
                        "Deep ${duration(night.deepMinutes)} · Light ${duration(night.lightMinutes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private data class HrDay(
    val label: String,
    val min: Int,
    val max: Int,
    val avg: Int,
    /** Window averages in time order, for the chart. */
    val windows: List<Int>,
)

/**
 * Heart-rate history from the watch's 24-hour log (`F7 FA`): one row per day
 * (lowest / average / highest) and a bar chart for the most recent day.
 */
@Composable
fun HeartRateHistoryCard(records: List<HistoricalRecord>) {
    val days = remember(records) {
        records.filterIsInstance<HistoricalRecord.HeartRate>()
            .distinctBy { it.timestampUtcSeconds }
            .sortedBy { it.timestampUtcSeconds }
            .groupBy { dayLabel(it.timestampUtcSeconds) }
            .map { (label, rs) ->
                HrDay(
                    label = label,
                    min = rs.minOf { it.minBpm },
                    max = rs.maxOf { it.maxBpm },
                    avg = rs.map { it.avgBpm }.average().toInt(),
                    windows = rs.map { it.avgBpm },
                )
            }
            .takeLast(DAYS_SHOWN)
            .reversed()
    }
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CardTitle(Icons.Default.Favorite, "Heart rate history")
            if (days.isEmpty()) {
                Text(
                    "No heart-rate history yet. It arrives a few seconds after connecting — " +
                        "or tap Sync history.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            val latest = days.first()
            Text(
                "${latest.label} — each bar is a 2-hour average",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HrBars(latest.windows)

            days.forEach { d ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(d.label, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${d.min}–${d.max} bpm · avg ${d.avg}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun HrBars(values: List<Int>) {
    if (values.isEmpty()) return
    // Scale from just below the lowest value so differences are visible.
    val floor = (values.min() - 10).coerceAtLeast(0)
    val span = (values.max() - floor).coerceAtLeast(1)
    Row(
        Modifier
            .fillMaxWidth()
            .height(64.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        values.forEach { v ->
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight((v - floor).toFloat() / span)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)),
            )
        }
    }
}

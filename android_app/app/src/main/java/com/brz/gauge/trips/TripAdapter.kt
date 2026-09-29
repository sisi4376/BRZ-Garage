package com.brz.gauge.trips

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.TextView
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class TripAdapter(
    private val context: Context,
    private val onReviseTime: (TripRecord) -> Unit,
    private val onDelete: (TripRecord) -> Unit,
    private val onOpenDetails: (TripRecord) -> Unit,
) : BaseAdapter() {
    private val trips = ArrayList<TripRecord>()
    private var revisionMode = false
    private val dateFormat = DateTimeFormatter.ofPattern("yyyy.MM.dd EEEE", Locale.getDefault())
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
    private val dateTimeFormat = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.getDefault())

    fun submit(items: List<TripRecord>) {
        trips.clear()
        trips.addAll(items)
        notifyDataSetChanged()
    }

    fun setRevisionMode(enabled: Boolean) {
        revisionMode = enabled
        notifyDataSetChanged()
    }

    override fun getCount() = trips.size
    override fun getItem(position: Int) = trips[position]
    override fun getItemId(position: Int) = trips[position].tripId

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.trip_row, parent, false)
        val trip = getItem(position)
        val monthDivider = view.findViewById<TextView>(R.id.tripMonthDivider)
        val title = view.findViewById<TextView>(R.id.tripTitle)
        val status = view.findViewById<TextView>(R.id.tripStatus)
        val time = view.findViewById<TextView>(R.id.tripTime)
        val month = view.findViewById<TextView>(R.id.tripMonth)
        val day = view.findViewById<TextView>(R.id.tripDay)
        val dateBadge = view.findViewById<LinearLayout>(R.id.tripDateBadge)
        val metricStrip = view.findViewById<LinearLayout>(R.id.tripMetricStrip)
        val distance = view.findViewById<TextView>(R.id.tripDistance)
        val tripDuration = view.findViewById<TextView>(R.id.tripDuration)
        val economy = view.findViewById<TextView>(R.id.tripEconomy)
        val fuel = view.findViewById<TextView>(R.id.tripFuel)
        val actions = view.findViewById<View>(R.id.tripRevisionActions)
        val revise = view.findViewById<TextView>(R.id.tripReviseTime)
        val delete = view.findViewById<TextView>(R.id.tripDelete)

        dateBadge.background = rounded(SOFT_BLUE, 14)
        metricStrip.background = rounded(METRIC_BACKGROUND, 15)
        revise.background = rounded(SOFT_BLUE, 13)
        delete.background = rounded(SOFT_RED, 13)

        val monthKey = tripMonth(trip)
        val previousMonth = trips.getOrNull(position - 1)?.let(::tripMonth)
        if (position == 0 || monthKey != previousMonth) {
            val count = trips.count { tripMonth(it) == monthKey }
            monthDivider.text = if (monthKey == null) "时间未知  ·  $count 次行程"
                else "${monthKey.year}年${monthKey.monthValue}月  ·  $count 次行程"
            monthDivider.visibility = View.VISIBLE
        } else {
            monthDivider.visibility = View.GONE
        }

        if (trip.hasValidTime) {
            val zone = ZoneId.systemDefault()
            val start = Instant.ofEpochSecond(trip.startEpochS).atZone(zone)
            val end = Instant.ofEpochSecond(trip.endEpochS).atZone(zone)
            month.text = "${start.monthValue}月"
            day.text = start.dayOfMonth.toString()
            title.text = "行程 #${trip.displayId}"
            val endText = if (start.toLocalDate() == end.toLocalDate()) {
                timeFormat.format(end)
            } else {
                dateTimeFormat.format(end)
            }
            time.text = "${dateFormat.format(start)}  ·  ${timeFormat.format(start)} → $endText"
        } else {
            month.text = "时间"
            day.text = "?"
            title.text = "行程 #${trip.displayId}"
            time.text = "升级前记录或本次行程未完成手机授时"
        }
        val statusStyle = when {
            trip.isLocalSplit -> Triple("手动拆分", BLUE, SOFT_BLUE)
            trip.timeInconsistent -> Triple("待核实", WARNING, SOFT_WARNING)
            trip.dataRevised -> Triple("测试数据", WARNING, SOFT_WARNING)
            trip.timeRevised -> Triple("已修订", MAINTENANCE, SOFT_RED)
            !trip.hasValidTime -> Triple("时间未知", WARNING, SOFT_WARNING)
            else -> Triple("已同步", BLUE, SOFT_BLUE)
        }
        status.text = statusStyle.first
        status.setTextColor(statusStyle.second)
        status.background = rounded(statusStyle.third, 12)
        distance.text = String.format(Locale.getDefault(), "%.1f km", trip.distanceM / 1000.0)
        tripDuration.text = duration(trip.durationS)
        economy.text = String.format(Locale.getDefault(), "%.1f L/100km", trip.avgL100X100 / 100.0)
        fuel.text = buildString {
            append(String.format(Locale.getDefault(), "消耗燃油 %.2f L", trip.fuelMl / 1000.0))
            trip.averageSpeedKmh?.let {
                append(String.format(Locale.getDefault(), "  ·  平均 %.1f km/h", it))
            }
        }
        actions.visibility = if (revisionMode) View.VISIBLE else View.GONE
        revise.setOnClickListener(if (revisionMode) View.OnClickListener { onReviseTime(trip) } else null)
        delete.setOnClickListener(if (revisionMode) View.OnClickListener { onDelete(trip) } else null)
        view.setOnClickListener { onOpenDetails(trip) }
        return view
    }

    private fun tripMonth(trip: TripRecord): YearMonth? = if (trip.hasValidTime) {
        YearMonth.from(Instant.ofEpochSecond(trip.startEpochS).atZone(ZoneId.systemDefault()))
    } else null

    private fun duration(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = seconds / 60 % 60
        return String.format(Locale.getDefault(), "%02d:%02d", hours, minutes)
    }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusDp * context.resources.displayMetrics.density
    }

    private companion object {
        val BLUE = Color.rgb(54, 143, 181)
        val MAINTENANCE = Color.rgb(218, 70, 111)
        val WARNING = Color.rgb(181, 111, 35)
        val SOFT_BLUE = Color.rgb(233, 244, 248)
        val SOFT_RED = Color.rgb(252, 235, 240)
        val SOFT_WARNING = Color.rgb(251, 241, 226)
        val METRIC_BACKGROUND = Color.rgb(247, 248, 251)
    }
}

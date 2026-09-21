package com.brz.gauge.trips

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.TextView
import java.time.Instant
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
    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.getDefault())
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
        val title = view.findViewById<TextView>(R.id.tripTitle)
        val time = view.findViewById<TextView>(R.id.tripTime)
        val stats = view.findViewById<TextView>(R.id.tripStats)
        val actions = view.findViewById<View>(R.id.tripRevisionActions)
        val revise = view.findViewById<Button>(R.id.tripReviseTime)
        val delete = view.findViewById<Button>(R.id.tripDelete)

        if (trip.hasValidTime) {
            val zone = ZoneId.systemDefault()
            val start = Instant.ofEpochSecond(trip.startEpochS).atZone(zone)
            val end = Instant.ofEpochSecond(trip.endEpochS).atZone(zone)
            title.text = "${dateFormat.format(start)}   #${trip.tripId}"
            val endText = if (start.toLocalDate() == end.toLocalDate()) {
                timeFormat.format(end)
            } else {
                dateTimeFormat.format(end)
            }
            time.text = "${timeFormat.format(start)} → $endText  ·  ${duration(trip.durationS)}" +
                if (trip.timeRevised) "\n时间由用户手动修订" else if (trip.timeInconsistent) "\n时间与驾驶时长不一致 · 待核实" else ""
        } else {
            title.text = "时间未知   #${trip.tripId}"
            time.text = "用时 ${duration(trip.durationS)}（升级前或本次行程未完成手机授时）"
        }
        stats.text = String.format(
            Locale.getDefault(), "%.1f km    %.1f L/100km    %.2f L",
            trip.distanceM / 1000.0,
            trip.avgL100X100 / 100.0,
            trip.fuelMl / 1000.0,
        )
        actions.visibility = if (revisionMode) View.VISIBLE else View.GONE
        revise.visibility = if (revisionMode) View.VISIBLE else View.GONE
        revise.setOnClickListener(if (revisionMode) View.OnClickListener { onReviseTime(trip) } else null)
        delete.setOnClickListener(if (revisionMode) View.OnClickListener { onDelete(trip) } else null)
        view.setOnClickListener { onOpenDetails(trip) }
        return view
    }

    private fun duration(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = seconds / 60 % 60
        return String.format(Locale.getDefault(), "%02d:%02d", hours, minutes)
    }
}

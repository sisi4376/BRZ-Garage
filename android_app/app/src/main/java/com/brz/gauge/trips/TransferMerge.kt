package com.brz.gauge.trips

internal data class TransferMergeResult(val snapshot: TransferSnapshot, val added: Int,
    val updated: Int, val removed: Int, val unchanged: Int, val conflicts: Int) {
    fun summary() = "新增 $added 条\n更新 $updated 条\n删除 $removed 条\n重复或未变化 $unchanged 条\n冲突 $conflicts 组（含配置与仪表缓存）"
}

internal object TransferMerge {
    fun merge(local: TransferSnapshot, incoming: TransferSnapshot, preferIncoming: Boolean): TransferMergeResult {
        TransferSchema.validate(local)
        TransferSchema.validate(incoming)
        val output = local.tables.mapValues { (_, rows) -> rows.toMutableList() }.toMutableMap()
        var conflicts = 0
        val handled = HashSet<String>()
        fun choose(a: Map<String, List<TransferRow>>, b: Map<String, List<TransferRow>>): Map<String, List<TransferRow>> {
            fun normalized(v: Map<String, List<TransferRow>>) = v.mapValues { (_, rows) -> rows.toSet() }
            if (normalized(a) == normalized(b)) return a
            conflicts++
            return if (preferIncoming) b else a
        }
        TransferSchema.tables.forEach { table ->
            if (!handled.add(table.name)) return@forEach
            val names = listOfNotNull(table.name, table.deletedTable)
            handled.addAll(names)
            // A split parent, its children and their tombstones form one indivisible edit.
            fun group(row: TransferRow): String {
                if (table.name != "trips") return table.key(row)
                val id = row.getValue("trip_id") as Long
                val parent = if (id < 0) (-id - 1L) / 2L else id
                return "${row["device_id"]}|$parent"
            }
            fun groups(snapshot: TransferSnapshot): Map<String, Map<String, List<TransferRow>>> {
                val keys = names.flatMap { name -> snapshot.tables.getValue(name).map(::group) }.toSet()
                val indexed = names.associateWith { snapshot.tables.getValue(it).groupBy(::group) }
                return keys.associateWith { key -> names.associateWith { indexed.getValue(it)[key].orEmpty() } }
            }
            val a = groups(local)
            val b = groups(incoming)
            val merged = a.toMutableMap()
            b.forEach { (key, rows) -> merged[key] = a[key]?.let { choose(it, rows) } ?: rows }
            names.forEach { name -> output[name] = merged.values.flatMap { it.getValue(name) }.toMutableList() }
        }
        // Refuel node deletion can merge adjacent rows without changing their IDs.
        // When generations differ, resolve that vehicle's whole refuel history together.
        val revisions = (local.preferences.keys + incoming.preferences.keys).filter { it.startsWith("refuel_history_revision_") }
        revisions.forEach { key ->
            val a = local.preferences[key]?.get("value")
            val b = incoming.preferences[key]?.get("value")
            if (a != null && b != null && a != b) {
                val gauge = key.removePrefix("refuel_history_revision_")
                conflicts++
                output.getValue("refuel").removeAll { it["device_id"] == gauge }
                output.getValue("refuel").addAll((if (preferIncoming) incoming else local).tables.getValue("refuel")
                    .filter { it["device_id"] == gauge })
            }
        }
        val prefs = local.preferences.toMutableMap()
        incoming.preferences.forEach { (key, value) ->
            val old = prefs[key]
            if (old != null && old["value"] != null && old != value) {
                conflicts++
                if (preferIncoming) prefs[key] = value
            } else prefs[key] = value
        }
        val devices = local.devices.toMutableMap()
        val groups = listOf("vehicle" to "vehicle_at", "last_fuel" to "last_fuel_at",
            "refuel" to "refuel_meta_at", "gauge_settings" to "gauge_settings_at",
            "firmware" to "firmware_at", "time" to "time_at")
        incoming.devices.forEach { (gauge, value) ->
            val old = devices[gauge]
            if (old == null) devices[gauge] = value else {
                val merged = old.toMutableMap()
                groups.forEach { (prefix, stamp) ->
                    val oldAt = old[stamp] as? Long ?: 0L
                    val incomingAt = value[stamp] as? Long ?: 0L
                    val keys = value.keys.filter { it == stamp || it.startsWith("${prefix}_") }
                    val differs = keys.any { old[it] != value[it] }
                    if (differs && oldAt == incomingAt) conflicts++
                    if (incomingAt > oldAt || incomingAt == oldAt && preferIncoming)
                        keys.forEach { merged[it] = value[it] }
                }
                devices[gauge] = merged
            }
        }
        val knownGauges = (devices.keys + output.values.flatten().mapNotNull { it["device_id"] as? String } +
            prefs.keys.mapNotNull { Regex("([0-9A-F]{2}(?::[0-9A-F]{2}){5})$").find(it)?.value })
            .filter { TransferSchema.gaugePattern.matches(it) }.toSet()
        val gauge = local.gauge.ifEmpty { incoming.gauge }.ifEmpty { knownGauges.singleOrNull().orEmpty() }
        val result = TransferSnapshot(output, prefs, gauge, devices = devices)
        TransferSchema.validate(result)
        var added = 0; var updated = 0; var removed = 0; var unchanged = 0
        TransferSchema.tables.filter { it.name != "deleted_trips" && !it.name.endsWith("_deleted") }.forEach { table ->
            val a = local.tables.getValue(table.name).associateBy(table::key)
            val b = result.tables.getValue(table.name).associateBy(table::key)
            b.forEach { (id, row) -> when {
                id !in a -> added++
                a[id] != row -> updated++
                else -> unchanged++
            } }
            removed += a.keys.count { it !in b }
        }
        return TransferMergeResult(result, added, updated, removed, unchanged, conflicts)
    }
}

package com.brz.gauge.trips

import java.util.Locale

/** Companion MacAddress strings and BluetoothDevice addresses use different casing. */
internal object GaugeBluetoothAddress {
    fun normalize(address: String): String = address.trim().uppercase(Locale.ROOT)
}

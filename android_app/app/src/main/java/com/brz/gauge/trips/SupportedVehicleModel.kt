package com.brz.gauge.trips

/** BRZ profiles intentionally exposed by the companion app. */
enum class SupportedVehicleModel(
    val profileIndex: Int,
    val title: String,
    val homeSubtitle: String,
    val heroDescription: String,
) {
    ZD8(
        profileIndex = 4,
        title = "Subaru BRZ ZD8 6MT",
        homeSubtitle = "SUBARU  /  ZD8 · 6MT",
        heroDescription = "第二代 Subaru BRZ ZD8 三维车辆模型",
    ),
    ZC6(
        profileIndex = 1,
        title = "Subaru BRZ ZC6 6MT",
        homeSubtitle = "SUBARU  /  ZC6 · 6MT",
        heroDescription = "第一代 Subaru BRZ ZC6 三维车辆模型",
    );

    companion object {
        const val DEFAULT_PROFILE_INDEX = 4
        fun fromProfileIndex(index: Int?): SupportedVehicleModel? =
            entries.firstOrNull { it.profileIndex == index }
    }
}

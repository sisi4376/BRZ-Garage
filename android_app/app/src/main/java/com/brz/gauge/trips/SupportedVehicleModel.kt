package com.brz.gauge.trips

/** BRZ profiles intentionally exposed by the companion app. */
enum class SupportedVehicleModel(
    val profileIndex: Int,
    val title: String,
    val homeSubtitle: String,
    val heroDescription: String,
    /** Normalized TL, TR, BR, BL corners of the front bumper's plate mounting plane. */
    val frontPlateQuad: FloatArray,
) {
    ZD8(
        profileIndex = 4,
        title = "Subaru BRZ ZD8 6MT",
        homeSubtitle = "SUBARU  /  ZD8 · 6MT",
        heroDescription = "第二代 Subaru BRZ ZD8 三维车辆模型",
        // Calibrated against real ZD8 front/three-quarter photos: the plate straddles the
        // grille's upper edge, remains centred below the emblem, and narrows at the far edge.
        frontPlateQuad = floatArrayOf(
            0.768657f, 0.587020f, // top-left
            0.890448f, 0.580752f, // top-right
            0.892239f, 0.649708f, // bottom-right
            0.770448f, 0.655976f, // bottom-left
        ),
    ),
    ZC6(
        profileIndex = 1,
        title = "Subaru BRZ ZC6 6MT",
        homeSubtitle = "SUBARU  /  ZC6 · 6MT",
        heroDescription = "第一代 Subaru BRZ ZC6 三维车辆模型",
        // The first-generation artwork has the same view direction but a higher grille plane.
        frontPlateQuad = floatArrayOf(
            0.763096f, 0.607513f, // top-left
            0.886630f, 0.596958f, // top-right
            0.888194f, 0.666154f, // bottom-right
            0.763878f, 0.676709f, // bottom-left
        ),
    );

    companion object {
        const val DEFAULT_PROFILE_INDEX = 4
        fun fromProfileIndex(index: Int?): SupportedVehicleModel? =
            entries.firstOrNull { it.profileIndex == index }
    }
}

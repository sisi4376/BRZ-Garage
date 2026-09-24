package com.brz.gauge.trips

import java.util.Locale

data class GeneratedLicensePlate(
    val province: Char,
    val authority: Char,
    val serial: String,
) {
    /** The decorative middle dot is not part of the registration number. */
    val compactText: String = "$province$authority$serial"
    val displayText: String = "$province$authority·$serial"
}

data class LicensePlateParseResult(
    val plate: GeneratedLicensePlate? = null,
    val error: String? = null,
)

/**
 * GA 36-2018 small conventional-car registration-number parser.
 *
 * It deliberately covers only the requested 440 mm x 140 mm blue plate. Decorative spaces and
 * the middle dot are accepted on input and removed before validation.
 */
object LicensePlateGenerator {
    const val WIDTH_MM = 440
    const val HEIGHT_MM = 140
    const val AUTHORITY_LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    const val SERIAL_LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ"
    private val provinceAbbreviations =
        "京津冀晋蒙辽吉黑沪苏浙皖闽赣鲁豫鄂湘粤桂琼渝川贵云藏陕甘青宁新".toSet()

    fun parse(input: String): LicensePlateParseResult {
        val normalized = normalize(input)
        if (normalized.length != 7) {
            return failure("请输入完整 7 位车牌号：省级简称、发牌机关代号和 5 位序号")
        }
        val province = normalized[0]
        if (province !in provinceAbbreviations) {
            return failure("首位应为 GA 36-2018 表 2 中的省、自治区或直辖市简称")
        }
        val authority = normalized[1]
        if (authority !in AUTHORITY_LETTERS) {
            return failure("发牌机关代号应为大写英文字母 A～Z")
        }
        val serial = normalized.drop(2)
        if (serial.any { it !in '0'..'9' && it !in SERIAL_LETTERS }) {
            return failure("序号只能使用数字和大写英文字母，且不使用 I、O")
        }
        if (serial.count { it in SERIAL_LETTERS } > 2) {
            return failure("5 位序号最多使用 2 个英文字母")
        }
        return LicensePlateParseResult(GeneratedLicensePlate(province, authority, serial))
    }

    fun normalize(input: String): String = input
        .uppercase(Locale.ROOT)
        .filterNot { it.isWhitespace() || it == '·' || it == '•' || it == '.' || it == '-' }

    private fun failure(message: String) = LicensePlateParseResult(error = message)
}

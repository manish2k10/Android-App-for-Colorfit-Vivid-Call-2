package com.colorfit.band.protocol

/**
 * The type byte at the front of a `0x41` message, which picks the icon on the
 * band (`CRPBleMessageType` in the SDK).
 *
 * NoiseFit Track (`d3/c.c`) assigns most app types only when the band has
 * reported support for them; this band answered none of those capability
 * queries in the capture, so like the original only the unconditional ones are
 * used and everything else goes as [OTHER].
 */
object BandMessageType {

    const val SMS = 1
    const val WHATSAPP = 4
    const val INSTAGRAM = 6
    const val SKYPE = 7
    const val OTHER = 128
    const val FACEBOOK = 130
    const val TWITTER = 131

    /** Firmware code the original assumes when the version string can't be read. */
    private const val DEFAULT_FIRMWARE_CODE = 172

    fun forPackage(packageName: String, defaultSmsPackage: String?): Int = when {
        defaultSmsPackage != null && packageName == defaultSmsPackage -> SMS
        packageName == "com.whatsapp" -> WHATSAPP
        packageName == "com.instagram.android" -> INSTAGRAM
        packageName.startsWith("com.skype") -> SKYPE
        packageName in setOf("com.facebook.katana", "com.facebook.lite", "com.facebook.orca", "com.facebook.mlite") -> FACEBOOK
        packageName == "com.twitter.android" -> TWITTER
        else -> OTHER
    }

    /** True when the band draws a dedicated icon (anything but [OTHER]). */
    fun hasOwnIcon(packageName: String, defaultSmsPackage: String?): Boolean =
        forPackage(packageName, defaultSmsPackage) != OTHER

    /**
     * `x8/b.a`: firmware from code 161 on numbers some apps differently
     * (this band's `MOY-CK4-1.6.5` is 165).
     */
    fun wireType(type: Int, firmwareCode: Int): Int =
        if (firmwareCode < 161) {
            when (type) {
                FACEBOOK -> 2
                TWITTER -> 3
                else -> type
            }
        } else {
            when (type) {
                WHATSAPP -> 8
                5 -> 2
                8 -> 10
                FACEBOOK -> 4
                TWITTER -> 5
                else -> type
            }
        }

    /**
     * `BandFirmwareUtils.getFirmwareVersionCode`: the digits of the third
     * dash-separated part, joined (`MOY-CK4-1.6.5` → 165); 172 when absent.
     */
    fun firmwareCode(firmware: String?): Int {
        val part = firmware?.split("-")?.getOrNull(2) ?: return DEFAULT_FIRMWARE_CODE
        var code = 0
        Regex("\\d+").findAll(part).forEach { code = code * 10 + it.value.toInt() }
        return if (code == 0) DEFAULT_FIRMWARE_CODE else code
    }
}

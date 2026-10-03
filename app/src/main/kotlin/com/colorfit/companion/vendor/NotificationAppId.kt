package com.colorfit.companion.vendor

/**
 * Maps Android package names (and well-known apps) to the watch-side
 * bitfield used by the Noise vendor protocol.
 *
 * ### These bits live in three separate namespaces
 *
 * The SDK keeps **three** 32-bit masks (`isPushMessageDisplay1/2/3`) and the
 * same numeric bit means a different app in each. For example bit `1` is
 * SMS in group 1 but **Telegram** in group 2, and bit `16` is "other" in
 * group 1 but WhatsApp Business in group 3:
 *
 * | App      | Group | Bit |
 * |----------|-------|-----|
 * | SMS      | 1     | 1   |
 * | WhatsApp | 1     | 128 |
 * | Telegram | 2     | 1   |
 * | Truecaller | 2   | 2   |
 *
 * So a bare bit value here is **not** a unique app id — it is only
 * meaningful together with its group. The constants below are flattened
 * into one namespace for convenience and therefore collide
 * (`BIT_TELEGRAM == BIT_SMS == 1`). Don't compare them to identify an app;
 * branch on the package name instead (see [alertTypeForPackage]).
 *
 * These masks only gate *whether* to push. The actual notification is sent
 * as chunked text (`0xC5`/`0xC6`, terminated by `0xC5 0xFD`) plus a short
 * vibration (`0xAB`), neither of which carries the app bit.
 *
 * Source: `com.yc.pedometer.utils.PushMessageUtil` and
 * `StatusbarMsgNotificationListener.isContinuePush()` in NoiseFit Prime 1.1.20.
 */
object NotificationAppId {

    const val BIT_SMS: Int = 1
    const val BIT_QQ: Int = 2
    const val BIT_WECHAT: Int = 4
    const val BIT_PHONE: Int = 8
    const val BIT_OTHER: Int = 16
    const val BIT_FACEBOOK: Int = 32
    const val BIT_TWITTER: Int = 64
    const val BIT_LINKEDIN: Int = 4096
    const val BIT_INSTAGRAM: Int = 8192
    const val BIT_HANGOUTS: Int = 2048
    const val BIT_GMAIL: Int = 0x80000              // 524288
    const val BIT_OUTLOOK: Int = 64
    const val BIT_SNAPCHAT: Int = 0x20000           // 131072
    const val BIT_TELEGRAM: Int = 1
    const val BIT_WHATSAPP: Int = 128
    const val BIT_WHATSAPP_BUSINESS: Int = 16
    const val BIT_SKYPE: Int = 1024
    const val BIT_LINE: Int = 512
    const val BIT_FACEBOOK_MESSENGER: Int = 256
    const val BIT_VIBER: Int = 16384
    const val BIT_KAKAOTALK: Int = 32768
    const val BIT_PAYTM: Int = 4
    const val BIT_PHONEPE: Int = 1024
    const val BIT_GPAY: Int = 512
    const val BIT_ZALO: Int = 8
    const val BIT_INSHORTS: Int = 0x40000           // 262144
    const val BIT_DAILYHUNT: Int = 0x20000          // 131072
    const val BIT_FLIPKART: Int = 0x2000            // 8192
    const val BIT_MYNTRA: Int = 0x8000              // 32768
    const val BIT_AMAZON: Int = 0x4000              // 16384
    const val BIT_HOTSTAR: Int = 0x800               // 2048
    const val BIT_PRIMEVIDEO: Int = 0x1000           // 4096
    const val BIT_BOOKMYSHOW: Int = 0x80000          // 524288
    const val BIT_SWIGGY: Int = 128
    const val BIT_ZOMATO: Int = 256
    const val BIT_MICROSOFT_TEAMS: Int = 32
    const val BIT_TWITTER_X: Int = 64
    const val BIT_FLICKR: Int = 0x100000            // 1048576
    const val BIT_TUMBLR: Int = 0x200000            // 2097152
    const val BIT_PINTEREST: Int = 0x400000         // 4194304
    const val BIT_YOUTUBE: Int = 0x800000           // 8388608
    const val BIT_NOISEAPP: Int = 0x10000            // 65536

    /**
     * Best-effort mapping from an Android package name to the bit the
     * watch uses for that app family. Returns [BIT_OTHER] if unknown —
     * the watch still receives a generic alert.
     */
    fun bitForPackage(packageName: String?): Int = when (packageName) {
        "com.android.phone", "com.android.dialer", "com.google.android.dialer",
        "com.samsung.android.dialer", "com.oneplus.dialer" -> BIT_PHONE

        "com.android.mms", "com.google.android.apps.messaging",
        "com.samsung.android.messaging", "com.oneplus.mms" -> BIT_SMS

        "com.whatsapp", "com.whatsapp.w4b" -> BIT_WHATSAPP
        "com.tencent.mm" -> BIT_WECHAT
        "com.tencent.mobileqq" -> BIT_QQ
        "org.telegram.messenger", "org.telegram.messenger.web",
        "org.telegram.tgnet", "org.thunderdog.challegram",
        "org.telegram.plus", "com.telegram",
        "ir.android.telegram", "me.telegram" -> BIT_TELEGRAM
        "com.facebook.orca", "com.facebook.mlite" -> BIT_FACEBOOK_MESSENGER
        "com.facebook.katana" -> BIT_FACEBOOK
        "com.instagram.android" -> BIT_INSTAGRAM
        "com.twitter.android", "com.twitter.android.lite" -> BIT_TWITTER
        "com.snapchat.android" -> BIT_SNAPCHAT
        "com.linkedin.android" -> BIT_LINKEDIN
        "com.viber.voip" -> BIT_VIBER
        "com.skype.raider", "com.skype.m2" -> BIT_SKYPE
        "jp.naver.line.android" -> BIT_LINE
        "com.kakao.talk" -> BIT_KAKAOTALK
        "com.zhiliaoapp.musically" -> BIT_OTHER       // TikTok
        "com.google.android.apps.youtube.music",
        "com.google.android.youtube" -> BIT_YOUTUBE
        "com.pinterest" -> BIT_PINTEREST

        "com.netflix.partner.activation",
        "com.google.android.gm" -> BIT_GMAIL
        "com.microsoft.office.outlook" -> BIT_OUTLOOK
        "com.microsoft.teams" -> BIT_MICROSOFT_TEAMS

        "com.amazon.mShop.android.shopping" -> BIT_AMAZON
        "in.amazon.mShop.android.shopping" -> BIT_AMAZON
        "com.flipkart.android" -> BIT_FLIPKART
        "com.myntra.android" -> BIT_MYNTRA
        "net.one97.paytm" -> BIT_PAYTM
        "com.phonepe.app" -> BIT_PHONEPE
        "com.google.android.apps.nbu.paisa.user" -> BIT_GPAY

        else -> BIT_OTHER
    }

    /** Type byte for an incoming call / SMS / any app without its own id. */
    const val TYPE_CALL: Int = 0
    const val TYPE_SMS: Int = 3
    const val TYPE_OTHER: Int = 4

    /**
     * The app id that leads a pushed message (`[type, length] + text`) and
     * picks the icon on the watch. Unlike the mask bits above this *is* a
     * unique id per app.
     *
     * Source: `StatusbarMsgNotificationListener.getAppType()`; Gmail (19) and
     * Telegram (24) confirmed against captured traffic.
     */
    private val watchTypes: Map<String, Int> = mapOf(
        "com.android.mms" to TYPE_SMS,
        "com.google.android.apps.messaging" to TYPE_SMS,
        "com.samsung.android.messaging" to TYPE_SMS,
        "com.oneplus.mms" to TYPE_SMS,
        "com.tencent.mobileqq" to 1,
        "com.tencent.mm" to 2,
        "com.facebook.katana" to 5,
        "com.twitter.android" to 6,
        "com.whatsapp" to 7,
        "com.skype.raider" to 8,
        "com.skype.rover" to 8,
        "com.skype.insiders" to 8,
        "com.facebook.orca" to 9,
        "com.google.android.talk" to 10,
        "jp.naver.line.android" to 11,
        "com.linkedin.android" to 12,
        "com.instagram.android" to 13,
        "com.viber.voip" to 14,
        "com.kakao.talk" to 15,
        "com.vkontakte.android" to 16,
        "com.snapchat.android" to 17,
        "com.google.android.gm" to 19,
        "com.google.android.apps.plus" to 18,
        "com.yahoo.mobile.client.android.flickr" to 20,
        "com.tumblr" to 21,
        "com.pinterest" to 22,
        "com.google.android.youtube" to 23,
        "org.telegram.messenger" to 24,
        "com.truecaller" to 25,
        "net.one97.paytm" to 26,
        "com.zing.zalo" to 27,
        "com.imo.android.imoim" to 28,
        "com.microsoft.teams" to 29,
        "com.microsoft.office.outlook" to 30,
        "in.swiggy.android" to 31,
        "com.application.zomato" to 32,
        // The original only lists the Wallet package; the India GPay app
        // and the real Hotstar package are added so they get the same icon.
        "com.google.android.apps.walletnfcrel" to 33,
        "com.google.android.apps.nbu.paisa.user" to 33,
        "com.phonepe.app" to 34,
        "my.imback.hostar" to 35,
        "in.startv.hotstar" to 35,
        "com.amazon.avod.thirdpartyclient" to 36,
        "com.flipkart.android" to 37,
        "in.amazon.mShop.android.shopping" to 38,
        "com.amazon.mShop.android.shopping" to 38,
        "com.myntra.android" to 39,
        "com.noisefit" to 40,
        "com.eterno" to 41,
        "com.nis.app" to 42,
        "com.bt.bms" to 43,
        "com.whatsapp.w4b" to 52,
    )

    fun typeForPackage(packageName: String?): Int = watchTypes[packageName] ?: TYPE_OTHER

    /** Every app the watch has a dedicated icon for. */
    val packagesWithWatchIcon: Set<String> get() = watchTypes.keys

    /**
     * Which watch-side alert style to use for a package — `sendSmsCommand(5)`
     * for the messaging app, `sendQQWeChatVibrationCommand(1)` for everything
     * else. Cosmetic (it picks the vibration pattern), but it must branch on
     * the package: the flattened bit constants collide, so `bit == BIT_SMS`
     * would also match Telegram.
     */
    fun alertTypeForPackage(packageName: String?): Int = when (packageName) {
        "com.android.mms", "com.google.android.apps.messaging",
        "com.samsung.android.messaging", "com.oneplus.mms" -> VendorFrame.NOTIFY_TYPE_SMS
        else -> VendorFrame.NOTIFY_TYPE_APP
    }

    /** Stable app family name for the bit — used as a short header in push payloads. */
    fun nameForPackage(packageName: String?): String = when (packageName) {
        "com.whatsapp", "com.whatsapp.w4b" -> "WhatsApp"
        "com.tencent.mm" -> "WeChat"
        "com.tencent.mobileqq" -> "QQ"
        "org.telegram.messenger", "org.telegram.messenger.web",
        "org.telegram.tgnet", "org.thunderdog.challegram",
        "org.telegram.plus", "com.telegram",
        "ir.android.telegram", "me.telegram" -> "Telegram"
        "com.facebook.orca", "com.facebook.mlite" -> "Messenger"
        "com.instagram.android" -> "Instagram"
        "com.twitter.android", "com.twitter.android.lite" -> "X"
        "com.snapchat.android" -> "Snapchat"
        "com.android.mms", "com.google.android.apps.messaging",
        "com.samsung.android.messaging", "com.oneplus.mms" -> "SMS"
        "com.android.phone", "com.android.dialer" -> "Phone"
        else -> packageName?.substringAfterLast('.') ?: "Notification"
    }
}

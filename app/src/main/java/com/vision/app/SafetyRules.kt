package com.vision.app

/**
 * Hard-coded limits. These run in code, before any text is typed into another app,
 * so no prompt or message can switch them off.
 *
 * Vision may still READ OTPs and bank messages on screen. It is never allowed to
 * TYPE or SEND them anywhere.
 */
object SafetyRules {

    private val OTP_WORDS = listOf(
        "otp", "one time password", "one-time password", "verification code",
        "security code", "passcode", "pin is", "ओटीपी", "वेरिफिकेशन कोड"
    )

    private val MONEY_WORDS = listOf(
        "bank", "statement", "account number", "a/c", "ifsc", "upi", "paisa", "paise",
        "rupee", "₹", "inr", "transaction", "debit", "credit card", "debit card",
        "payment", "money", "loan", "emi", "neft", "imps", "rtgs",
        "बैंक", "स्टेटमेंट", "खाता", "खाते", "पैसा", "पैसे", "रुपये", "रुपए", "लोन", "पेमेंट"
    )

    private val LOCATION_WORDS = listOf(
        "location", "where is", "where are you", "address", "कहाँ है", "कहां है", "लोकेशन", "पता बताओ"
    )

    /** Returns a refusal sentence if the text must not be sent, otherwise null. */
    fun refusal(text: String): String? {
        val t = text.lowercase()
        if (OTP_WORDS.any { t.contains(it) } || OTP_REGEX.containsMatchIn(t) && t.contains("code")) {
            return "Blocked: Vision never sends one-time codes or PINs."
        }
        if (MONEY_WORDS.any { t.contains(it) }) {
            return "Blocked: Vision never sends bank, payment or money messages."
        }
        return null
    }

    /** Questions asking where the owner is. Vision never answers these for anyone. */
    fun isLocationRequest(text: String): Boolean {
        val t = text.lowercase()
        return LOCATION_WORDS.any { t.contains(it) }
    }

    private val OTP_REGEX = Regex("""\b\d{4,8}\b""")
}

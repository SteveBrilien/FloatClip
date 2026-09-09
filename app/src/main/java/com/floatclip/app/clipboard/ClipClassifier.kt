package com.floatclip.app.clipboard

object ClipClassifier {
    private val url = Regex("(?i)^(https?://|www\\.)\\S+")
    private val email = Regex("^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$", RegexOption.IGNORE_CASE)
    private val phone = Regex("^(?:\\+?\\d[\\d -]{6,18}\\d)$")
    private val otp = Regex("(?<!\\d)\\d{4,8}(?!\\d)")
    private val addressHints = listOf("路", "街", "道", "巷", "号", "区", "县", "市", "省", "road", "street", "avenue", "address")

    fun classify(text: String, available: Collection<String>): String? {
        fun pick(name: String): String? = name.takeIf { it in available }
        val trimmed = text.trim()
        if (url.containsMatchIn(trimmed)) return pick("网址")
        if (email.matches(trimmed)) return pick("邮箱")
        if (phone.matches(trimmed)) return pick("电话")
        if (trimmed.length <= 80 && otp.containsMatchIn(trimmed) && listOf("验证码", "code", "otp", "验证").any { trimmed.contains(it, true) }) return pick("验证码")
        if (trimmed.length <= 160 && addressHints.any { trimmed.contains(it, true) }) return pick("地址")
        return null
    }
}

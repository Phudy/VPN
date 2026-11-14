package com.phudy.redline.explore

/**
 * Hard filter: refuses searches and hides results that point to minors or non-consensual material.
 * This cannot be turned off.
 */
object SafetyFilter {
    private val words = listOf(
        "child", "children", "kid", "kids", "underage", "under age", "minor", "minors", "preteen", "pre-teen",
        "loli", "lolita", "shota", "shotacon", "lolicon", "jailbait", "pthc", "pedo", "paedo", "toddler", "infant",
        "elementary", "middle school", "junior high", "young girl", "little girl", "little boy", "schoolkid",
        "rape", "raped", "non-consensual", "nonconsensual", "unconscious", "drugged", "sleeping girl", "hidden cam", "spycam", "upskirt",
        "เด็ก", "ผู้เยาว์", "อายุไม่ถึง", "ประถม", "ม.ต้น", "มัธยมต้น", "ข่มขืน", "แอบถ่าย", "โลลิ",
    )
    private val ageRe = Regex("""(?i)\b(1[0-7]|[1-9])\s*(yo|y/o|y\.o\.|yrs?|years?\s*old|ขวบ|ปี)\b""")

    fun allowed(text: String): Boolean {
        val t = text.lowercase()
        if (ageRe.containsMatchIn(t)) return false
        return words.none { w ->
            if (w.any { it.code > 0x0E00 }) t.contains(w)
            else Regex("""(?<![a-z])${Regex.escape(w)}(?![a-z])""").containsMatchIn(t)
        }
    }
}

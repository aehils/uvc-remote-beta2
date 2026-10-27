package com.expiation.reemanremote

/**
 * What staff call a robot. Each robot's navigation computer has a hostname unique to it
 * (/reeman/hostname), like `wbot11u-250101-003-007`: model, a six-digit date, then two numbers,
 * the last one the unit. That is how the app recognises its robot anywhere, but it is too long to
 * read out, so the app shows it short: the last two numbers without their leading zeros, the unit
 * as at least two digits, as `UV 3-07`.
 */
object RobotName {

    private val PATTERN = Regex("""^[A-Za-z0-9]+-\d{6}-(\d{1,4})-(\d{1,4})$""")

    fun short(hostname: String): String {
        val m = PATTERN.matchEntire(hostname.trim()) ?: return fallback(hostname)
        val (batch, unit) = m.destructured
        return "UV ${batch.toInt()}-${unit.toInt().toString().padStart(2, '0')}"
    }

    /** Another kind of hostname: its last part if that is a number, otherwise the whole name. */
    private fun fallback(hostname: String): String {
        val last = hostname.trim().substringAfterLast('-')
        return if (last.isNotEmpty() && last.all(Char::isDigit)) "UV ${last.toInt().toString().padStart(2, '0')}" else hostname.trim()
    }
}

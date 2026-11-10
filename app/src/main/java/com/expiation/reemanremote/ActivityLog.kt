package com.expiation.reemanremote

import java.io.File

/**
 * The Activity log: what the app did and why, kept for diagnosing problems after the fact. Each
 * line is written to [file] as it happens, so the log outlives the app closing, a crash or a new
 * install, and a day of runs fits ([KEEP] lines). It used to be 14 lines in memory, sized for a
 * panel on the old main screen, and lost a failed run's whole story within a minute (2026-11-10).
 *
 * Plain Kotlin and java.io: [file] null keeps it in memory only (tests, previews).
 */
class ActivityLog(private val file: File? = null, private val keep: Int = KEEP) {
    private val lines = ArrayDeque<String>() // newest first
    private var sinceCompact = 0

    init {
        file?.let { f -> readNewestFirst(f, keep).asReversed().forEach { lines.addFirst(it) } }
    }

    /** Adds [line] as the newest, and appends it to the file. The file is cut back to [keep] lines now and then. */
    @Synchronized
    fun add(line: String) {
        lines.addFirst(line)
        while (lines.size > keep) lines.removeLast()
        val f = file ?: return
        runCatching {
            f.appendText(line + "\n")
            if (++sinceCompact >= COMPACT_EVERY) {
                sinceCompact = 0
                f.writeText(lines.asReversed().joinToString("\n", postfix = "\n"))
            }
        }
    }

    /** The newest [n] lines, newest first: what the Activity log page shows. */
    @Synchronized
    fun newest(n: Int): List<String> = lines.take(n)

    /** Everything kept, newest first: what Copy and Export send. */
    @Synchronized
    fun all(): List<String> = lines.toList()

    companion object {
        /** About a day of runs: a 3-point routine writes a few dozen lines. */
        const val KEEP = 2_000

        /** Lines on the Activity log page; Copy and Export send all [KEEP]. */
        const val SHOWN = 200

        private const val COMPACT_EVERY = 200

        /** In the app's own files: private to it, kept across updates, gone on uninstall. */
        const val FILE_NAME = "activity_log.txt"

        /** The saved log, newest first, at most [keep] lines; empty if there is none or it can't be read. */
        fun readNewestFirst(f: File, keep: Int = KEEP): List<String> =
            runCatching { if (f.exists()) f.readLines().filter { it.isNotBlank() }.takeLast(keep).asReversed() else emptyList() }
                .getOrDefault(emptyList())
    }
}

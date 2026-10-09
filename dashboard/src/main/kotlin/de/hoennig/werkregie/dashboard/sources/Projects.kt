package de.hoennig.werkregie.dashboard.sources

import java.nio.file.Files
import java.nio.file.Path

/** Derives a short project name from a working directory, worktrees included. */
object Projects {
    private val home = System.getProperty("user.home")
    private val WORKTREE = Regex("^(.*)/\\.(claude|codex)/worktrees/([^/]+)")

    fun of(cwd: String?): String? {
        if (cwd.isNullOrBlank()) return null
        if ("/scratch-workspaces/" in cwd) return "Scratch"
        val worktree = WORKTREE.find(cwd)
        return Path.of(worktree?.groupValues?.get(1) ?: cwd).fileName?.toString()
    }

    fun shortPath(cwd: String?): String? = when {
        cwd == null -> null
        "/scratch-workspaces/" in cwd -> "Scratch-Workspace"
        WORKTREE.containsMatchIn(cwd) -> "worktree " + WORKTREE.find(cwd)!!.groupValues[3]
        cwd.startsWith(home) -> "~" + cwd.substring(home.length)
        else -> cwd
    }
}

/** Liveness of a process; the start time guards against a reused PID. */
fun interface ProcessTable {
    fun isAlive(pid: Long, startTime: String?): Boolean

    companion object {
        val linux = ProcessTable { pid, startTime ->
            val stat = Path.of("/proc/$pid/stat")
            if (!Files.exists(stat)) return@ProcessTable false
            if (startTime == null) return@ProcessTable true
            // field 22 (starttime); the command name in field 2 may contain blanks, so split after its ')'
            val fields = runCatching { Files.readString(stat) }.getOrNull()
                ?.substringAfterLast(')')?.trim()?.split(' ')
                ?: return@ProcessTable false
            fields.getOrNull(19) == startTime
        }
    }
}

fun String.firstLine(maxLength: Int = 140): String {
    val line = lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: ""
    return if (line.length > maxLength) line.take(maxLength - 1) + "…" else line
}

package de.hoennig.werkregie.dashboard.sources

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

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

    private val repositories = ConcurrentHashMap<String, Result<String?>>()

    /**
     * The repository a working directory is checked out from: the last path segment of its
     * `origin` URL without `.git`, e.g. `hs.hsadmin.ng` for `…/hostsharing/hs.hsadmin.ng.git`.
     * Read from the git configuration itself, worktrees included; cached, since a checkout's
     * origin does not change while the dashboard runs.
     */
    fun repository(cwd: String?): String? {
        if (cwd.isNullOrBlank()) return null
        return repositories.computeIfAbsent(cwd) { runCatching { originOf(Path.of(it)) } }.getOrNull()
    }

    fun repositoryName(url: String?): String? =
        url?.trim()?.trimEnd('/')?.removeSuffix(".git")?.substringAfterLast('/')?.substringAfterLast(':')?.takeIf { it.isNotBlank() }

    private fun originOf(start: Path): String? {
        var dir: Path? = start
        while (dir != null) {
            val git = dir.resolve(".git")
            if (Files.exists(git)) return repositoryName(originUrl(configOf(git)))
            dir = dir.parent
        }
        return null
    }

    /** `.git` is a directory, or in a worktree a file `gitdir: …` whose `commondir` leads to the shared config. */
    private fun configOf(git: Path): Path {
        if (Files.isDirectory(git)) return git.resolve("config")
        val gitDir = git.parent.resolve(Files.readString(git).trim().removePrefix("gitdir:").trim()).normalize()
        val common = gitDir.resolve("commondir").takeIf { Files.exists(it) }
            ?.let { gitDir.resolve(Files.readString(it).trim()).normalize() }
            ?: gitDir
        return common.resolve("config")
    }

    private fun originUrl(config: Path): String? {
        var inOrigin = false
        for (line in Files.readAllLines(config).map { it.trim() }) {
            if (line.startsWith("[")) inOrigin = line.replace(" ", "") == "[remote\"origin\"]"
            else if (inOrigin && line.startsWith("url")) return line.substringAfter('=').trim()
        }
        return null
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

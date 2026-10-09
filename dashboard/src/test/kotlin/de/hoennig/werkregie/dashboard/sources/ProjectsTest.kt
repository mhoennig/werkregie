package de.hoennig.werkregie.dashboard.sources

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class ProjectsTest : FunSpec({
    test("the repository name is the origin's last segment without owner and .git") {
        Projects.repositoryName("https://user@dev.example.net/hostsharing/hs.hsadmin.ng.git") shouldBe "hs.hsadmin.ng"
        Projects.repositoryName("git.example.de:mi/notes.git") shouldBe "notes"
        Projects.repositoryName("mih09-git@git.example.de:werkcrappit") shouldBe "werkcrappit"
        Projects.repositoryName(null) shouldBe null
    }

    test("reads the origin of a checkout, from a subdirectory and from a worktree") {
        val checkout = Files.createTempDirectory("hsadmin-ng")
        val git = Files.createDirectories(checkout.resolve(".git"))
        Files.writeString(
            git.resolve("config"),
            """
            [core]
            	bare = false
            [remote "upstream"]
            	url = https://example.net/other/fork.git
            [remote "origin"]
            	url = https://dev.example.net/hostsharing/hs.hsadmin.ng.git
            """.trimIndent(),
        )
        val sub = Files.createDirectories(checkout.resolve("src/main"))
        val worktreeGit = Files.createDirectories(git.resolve("worktrees/fix-1"))
        Files.writeString(worktreeGit.resolve("commondir"), "../..\n")
        val worktree = Files.createDirectories(checkout.resolve(".claude/worktrees/fix-1"))
        Files.writeString(worktree.resolve(".git"), "gitdir: $worktreeGit\n")

        Projects.repository(checkout.toString()) shouldBe "hs.hsadmin.ng"
        Projects.repository(sub.toString()) shouldBe "hs.hsadmin.ng"
        Projects.repository(worktree.toString()) shouldBe "hs.hsadmin.ng"
    }

    test("a directory outside any checkout has no repository") {
        Projects.repository(Files.createTempDirectory("plain").toString()) shouldBe null
    }
})

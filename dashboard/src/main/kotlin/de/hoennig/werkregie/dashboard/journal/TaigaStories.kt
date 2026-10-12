package de.hoennig.werkregie.dashboard.journal

/** A Taiga story, or an issue or a task without story: what one entry of the journal is booked for. */
data class Story(
    val slug: String,
    val ref: Long,
    // US, Issue or Task, as Taiga's pages label them
    val kind: String,
    val subject: String,
    val url: String? = null,
)

/** Resolves a ref of a Taiga project, e.g. 545 of hsadmin-ng, to its story; null when it is unknown. */
fun interface TaigaStories {
    fun story(slug: String, ref: Long): Story?
}

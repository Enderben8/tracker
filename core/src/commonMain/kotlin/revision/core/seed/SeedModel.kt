package revision.core.seed

/**
 * Plain data describing the starting topic tree. It is turned into database rows
 * by [Seeder] on first launch.
 */
data class SeedSubject(
    val key: String,            // stable short name used to build stable row ids, e.g. "biology"
    val name: String,
    val colour: String,         // hex
    val examBoard: String?,
    val topics: List<SeedTopic>,
)

data class SeedTopic(
    val title: String,
    val code: String? = null,
    val page: Int? = null,      // null when the source book is unreliable or there is no book
    val children: List<SeedTopic> = emptyList(),
)

fun leaf(title: String, code: String? = null, page: Int? = null) = SeedTopic(title, code, page)

/** A topic that contains other topics. Children come first; `code` and `page` are named. */
fun group(
    title: String,
    vararg children: SeedTopic,
    code: String? = null,
    page: Int? = null,
) = SeedTopic(title, code, page, children.toList())

/** Shorthand for a list of page-less leaf topics. */
fun leaves(vararg titles: String): List<SeedTopic> = titles.map { leaf(it) }

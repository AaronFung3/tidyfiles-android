package eu.kanade.tachiyomi.source.tidyfiles

/**
 * PC 書庫 tag 嘅分類前綴。
 *
 * 整理 app 寫 `ComicInfo.xml` 嘅 `<Tags>` 時會加前綴（`作者：珈琲貴族`、
 * `品質：無修正`），手機端靠前綴砌出兩層：分類 tab（作者／品質／其他）
 * ＋ tab 入面嘅值 chips。
 */
object TidyFilesTagKind {

    const val SEP = "："

    const val AUTHOR = "作者"
    const val QUALITY = "品質"
    const val OTHER = "其他"

    /** 會變成書架分類 tab 嘅名（順序 = tab 順序）。 */
    val ALL = listOf(AUTHOR, QUALITY, OTHER)

    /** 一粒 tag → 佢屬於邊個分類；認唔到就當「其他」，空字串就 null。 */
    fun kindOf(tag: String): String? {
        val t = tag.trim()
        if (t.isEmpty()) return null
        return when {
            t.startsWith("$AUTHOR$SEP") -> AUTHOR
            t.startsWith("$QUALITY$SEP") -> QUALITY
            SEP in t -> null
            else -> OTHER
        }
    }

    /** 一本書喺呢個分類入面有咩值（`genre` 係 `", "` 分隔嘅字串）。 */
    fun valuesOf(genre: String?, kind: String): List<String> {
        val g = genre ?: return emptyList()
        val prefix = if (kind == OTHER) null else "$kind$SEP"
        return g.split(", ")
            .mapNotNull { raw ->
                val t = raw.trim()
                when {
                    prefix == null -> t.takeIf { it.isNotEmpty() && SEP !in it }
                    t.startsWith(prefix) -> t.removePrefix(prefix).trim().takeIf { it.isNotEmpty() }
                    else -> null
                }
            }
    }

    /** 呢本書喺呢個分類入面有冇 `value`。 */
    fun matches(genre: String?, kind: String, value: String): Boolean =
        valuesOf(genre, kind).any { it == value }
}

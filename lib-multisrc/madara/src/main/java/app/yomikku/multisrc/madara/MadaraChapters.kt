package app.yomikku.multisrc.madara

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

internal object MadaraChapters {
    /**
     * A chapter list's `.wp-manga-chapter` rows, newest first. A list grouped into volumes
     * (`li.parent.has-child`) puts the volumes oldest first but each volume's chapters newest first, so the
     * volumes are flipped and their chapters kept; chapters outside any volume come after the last one, so they
     * lead.
     */
    fun newestFirst(document: Document): List<Element> {
        val volumes = document.select("li.parent.has-child")
        if (volumes.isEmpty()) return document.select(".wp-manga-chapter")
        val loose = document.select(".wp-manga-chapter").filter { row -> row.parents().none { it in volumes } }
        return loose + volumes.reversed().flatMap { it.select(".wp-manga-chapter") }
    }
}

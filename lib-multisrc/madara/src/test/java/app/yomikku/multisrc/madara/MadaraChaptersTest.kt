package app.yomikku.multisrc.madara

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Test

class MadaraChaptersTest {
    private fun order(html: String) = MadaraChapters.newestFirst(Jsoup.parse(html)).map { it.text() }

    @Test
    fun flatListKeepsItsOrder() {
        assertEquals(listOf("2", "1"), order("""<li class="wp-manga-chapter">2</li><li class="wp-manga-chapter">1</li>"""))
    }

    @Test
    fun volumesGoNewestFirstWithLooseChaptersLeading() {
        val html = """
            <ul>
              <li class="wp-manga-chapter">loose 2</li>
              <li class="wp-manga-chapter">loose 1</li>
              <li class="parent has-child"><a>Volume 0</a><ul class="sub-chap-list">
                <li class="wp-manga-chapter">v0 c2</li><li class="wp-manga-chapter">v0 c1</li>
              </ul></li>
              <li class="parent has-child"><a>Volume 1</a><ul class="sub-chap-list">
                <li class="wp-manga-chapter">v1 c2</li><li class="wp-manga-chapter">v1 c1</li>
              </ul></li>
            </ul>
        """
        assertEquals(listOf("loose 2", "loose 1", "v1 c2", "v1 c1", "v0 c2", "v0 c1"), order(html))
    }
}

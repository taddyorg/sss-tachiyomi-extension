package eu.kanade.tachiyomi.extension.all.taddyink

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

open class TaddyInk(
    override val lang: String,
    private val taddyLang: String,
) : ConfigurableSource, HttpSource() {

    final override val baseUrl = "https://taddy.org"
    override val name = "Taddy INK"
    override val supportsLatest = false
    private val popularManagaLimit = 25
    private val searchManagaLimit = 25

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override val client: OkHttpClient by lazy {
        network.cloudflareClient.newBuilder()
            .rateLimit(4)
            .build()
    }

    private var displayFullTitle: Boolean = when (preferences.getString(TITLE_PREF, "full")) {
        "full" -> true
        else -> false
    }

    private val shortenTitleRegex = Regex("""(\[[^]]*]|[({][^)}]*[)}])""")
    private fun String.shortenTitle() = this.replace(shortenTitleRegex, "").trim()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = TITLE_PREF
            title = TITLE_PREF
            entries = arrayOf("Full Title", "Short Title")
            entryValues = arrayOf("full", "short")
            summary = "%s"

            setDefaultValue("full")

            setOnPreferenceChangeListener { _, newValue ->
                displayFullTitle = when (newValue) {
                    "full" -> true
                    else -> false
                }
                true
            }
        }.also(screen::addPreference)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val langParam = taddyLang.let { "&lang=$it" } ?: ""
        return GET("$baseUrl/feeds/directory/list?taddyType=comicseries&sort=latest$langParam&page=$page&limit=$popularManagaLimit", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        TODO("Not yet implemented")
    }

    override fun popularMangaRequest(page: Int): Request {
        val langParam = taddyLang.let { "&lang=$it" } ?: ""
        return GET("$baseUrl/feeds/directory/list?taddyType=comicseries&sort=popular$langParam&page=$page&limit=$popularManagaLimit", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        return parseManga(response)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val shouldFilterByGenre = filterList.findInstance<GenreFilter>()?.state != 0
        val shouldFilterByCreator = filterList.findInstance<CreatorFilter>()?.state?.isNotBlank() ?: false
        val shouldFilterForTags = filterList.findInstance<TagFilter>()?.state?.isNotBlank() ?: false

        val url = "$baseUrl/feeds/directory/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("lang", taddyLang)
            .addQueryParameter("taddyType", "comicseries")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", searchManagaLimit.toString())

        if (shouldFilterByGenre) {
            filterList.findInstance<GenreFilter>()?.let { f ->
                url.addQueryParameter("genre", f.toUriPart())
            }
        }

        if (shouldFilterByCreator) {
            filterList.findInstance<CreatorFilter>()?.let { name ->
                url.addQueryParameter("creator", name.state)
            }
        }

        if (shouldFilterForTags) {
            filterList.findInstance<TagFilter>()?.let { tags ->
                url.addQueryParameter("tags", tags.state)
            }
        }

        return GET(url.toString(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        return parseManga(response)
    }

    private fun parseManga(response: Response): MangasPage {
        val jsonObject = JSONObject(response.body.string())
        val comicsArray = jsonObject.getJSONArray("comicseries")

        val mangas = List(comicsArray.length()) { i ->
            val comic = comicsArray.getJSONObject(i)
            TaddyUtils.getManga(comic)
        }

        val hasNextPage = comicsArray.length() == popularManagaLimit

        return MangasPage(mangas, hasNextPage)
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        return GET(manga.url, headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val jsonObject = JSONObject(response.body.string())

        return TaddyUtils.getManga(jsonObject)
    }

    override fun chapterListRequest(manga: SManga): Request {
        return GET(manga.url, headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val jsonObject = JSONObject(response.body.string())
        val issuesArray = jsonObject.getJSONArray("issues")
        val sssUrl = jsonObject.optString("url", "")

        val chapters = List(issuesArray.length()) { i ->
            val chapter = issuesArray.getJSONObject(i)

            val chapterUuid = chapter.optString("identifier", "")
            val chapterName = chapter.optString("name", "Unknown")
            val datPublished = chapter.optString("datePublished", "")

            SChapter.create().apply {
                url = "$sssUrl#$chapterUuid"
                name = chapterName
                date_upload = TaddyUtils.getTime(datPublished)
                chapter_number = (issuesArray.length() - i).toFloat()
            }
        }

        return chapters.reversed()
    }

    override fun pageListRequest(chapter: SChapter): Request {
        return GET(chapter.url, headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val requestUrl = response.request.url.toString()

        // Extracting the issueUuid from the requestUrl
        val issueUuid = requestUrl.substringAfterLast("#")

        val jsonObject = JSONObject(response.body.string())
        val issuesArray = jsonObject.getJSONArray("issues")

        // Convert the issuesArray into a Map with identifier as the key
        val issuesMap = mutableMapOf<String, JSONObject>()
        for (i in 0 until issuesArray.length()) {
            val issue = issuesArray.getJSONObject(i)
            issuesMap[issue.getString("identifier")] = issue
        }

        val pages = mutableListOf<Page>()

        issuesMap[issueUuid]?.let { matchingIssue ->
            val stories = matchingIssue.getJSONArray("stories")
            for (j in 0 until stories.length()) {
                val story = stories.getJSONObject(j)
                val imageUrl = story.getJSONObject("storyImage").let {
                    it.getString("base_url") + it.getString("story")
                }
                pages.add(Page(j, "", imageUrl))
            }
        }

        return pages
    }

    override fun imageUrlParse(response: Response): String {
        return ""
    }

    override fun getFilterList(): FilterList = FilterList(
        GenreFilter(),
        Filter.Separator(),
        Filter.Header("Filter by the creator or tags:"),
        CreatorFilter(),
        TagFilter(),
    )

    class CreatorFilter : AdvSearchEntryFilter("Creator")
    class TagFilter : AdvSearchEntryFilter("Tags")
    open class AdvSearchEntryFilter(name: String) : Filter.Text(name)

    private class GenreFilter : UriPartFilter(
        "Filter By Genre",
        TaddyUtils.getGenrePairs(),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    private inline fun <reified T> Iterable<*>.findInstance() = find { it is T } as? T

    companion object {
        const val PREFIX_ID_SEARCH = "id:"
        private const val TITLE_PREF = "Display manga title as:"
    }
}

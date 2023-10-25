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
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

open class TaddyInk(
    override val lang: String,
    private val taddyLang: String,
) : ConfigurableSource, HttpSource() {

    final override val baseUrl = "https://taddy.org"
    override val name = "Taddy INK"
    override val supportsLatest = false

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

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException("Not used!")

    override fun latestUpdatesParse(response: Response): MangasPage = throw UnsupportedOperationException("Not used!")

    override fun popularMangaRequest(page: Int): Request {
        val langParam = taddyLang.let { "&lang=$it" } ?: ""
        return GET("$baseUrl/feeds/directory/list?taddyType=comicseries&sort=popular$langParam&page=$page&limit=$popularMangaLimit", headers)
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
            .addQueryParameter("limit", searchMangaLimit.toString())

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
        val comicSeries = json.decodeFromString<ComicResults>(response.body.string())
        val mangas = comicSeries.comicseries.map { TaddyUtils.getManga(it) }
        val hasNextPage = comicSeries.comicseries.size == popularMangaLimit
        return MangasPage(mangas, hasNextPage)
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        return GET(manga.url, headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val comicObj = json.decodeFromString<Comic>(response.body.string())
        return TaddyUtils.getManga(comicObj)
    }

    override fun chapterListRequest(manga: SManga): Request {
        return GET(manga.url, headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val comic = json.decodeFromString<Comic>(response.body.string())
        val sssUrl = comic.url

        val chapters = comic.issues?.mapIndexed { i, chapter ->
            SChapter.create().apply {
                url = "$sssUrl#${chapter.identifier}"
                name = chapter.name
                date_upload = TaddyUtils.getTime(chapter.datePublished)
                chapter_number = (comic.issues.size - i).toFloat()
            }
        }

        return chapters?.reversed() ?: emptyList()
    }

    override fun pageListRequest(chapter: SChapter): Request {
        return GET(chapter.url, headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val requestUrl = response.request.url.toString()
        val issueUuid = requestUrl.substringAfterLast("#")
        val comic = json.decodeFromString<Comic>(response.body.string())

        return comic.issues?.firstOrNull { it.identifier == issueUuid }?.stories?.mapIndexed { index, storyObj ->
            Page(index, "", "${storyObj.storyImage?.base_url}${storyObj.storyImage?.story}")
        }.orEmpty()
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
        TaddyUtils.genrePairs,
    )

    private open class UriPartFilter(displayName: String, val vals: List<Pair<String, String>>) :
        Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    private inline fun <reified T> Iterable<*>.findInstance() = find { it is T } as? T

    companion object {
        private const val TITLE_PREF = "Display manga title as:"
        private const val popularMangaLimit = 25
        private const val searchMangaLimit = 25
        private val json = Json { ignoreUnknownKeys = true }
    }
}

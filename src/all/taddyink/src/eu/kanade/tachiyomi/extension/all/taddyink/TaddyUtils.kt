package eu.kanade.tachiyomi.extension.all.taddyink

import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

object TaddyUtils {
    fun getManga(comicObj: JSONObject): SManga {
        val name = comicObj.optString("name", "Unknown")
        val sssUrl = comicObj.optString("sssUrl", "")
        val sssDescription = comicObj.optString("description", "")
        val genres = comicObj.optJSONArray("genres")?.let { jsonArray ->
            List(jsonArray.length()) { index ->
                getPrettyGenre(jsonArray[index] as String)
            }
        }?.joinToString(", ")

        val creators = comicObj.optJSONArray("creators")?.let { jsonArray ->
            List(jsonArray.length()) { index ->
                jsonArray.opt(index) as? String
            }.filterNotNull()
        }.orEmpty().joinToString(", ")

        val coverImage = comicObj.optJSONObject("coverImage")
        val thumbnailBaseUrl = coverImage?.optString("base_url", "") ?: ""
        val thumbnail = coverImage?.optString("cover_sm", "") ?: ""
        val thumbnailUrl = if (thumbnailBaseUrl.isNotEmpty() && thumbnail.isNotEmpty()) "$thumbnailBaseUrl$thumbnail" else ""

        return SManga.create().apply {
            url = sssUrl
            title = name
            creators?.let { if (it.isNotEmpty() && it.isNotBlank()) author = it }
            description = sssDescription
            thumbnail_url = thumbnailUrl
            status = SManga.ONGOING
            update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
            genre = genres
            initialized = true
        }
    }

    fun getTime(timeString: String): Long {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).parse(timeString)?.time ?: 0L
    }

    private fun getPrettyGenre(genre: String): String {
        return when (genre) {
            "COMICSERIES_ACTION" -> "Action"
            "COMICSERIES_COMEDY" -> "Comedy"
            "COMICSERIES_DRAMA" -> "Drama"
            "COMICSERIES_EDUCATIONAL" -> "Educational"
            "COMICSERIES_FANTASY" -> "Fantasy"
            "COMICSERIES_HISTORICAL" -> "Historical"
            "COMICSERIES_HORROR" -> "Horror"
            "COMICSERIES_INSPIRATIONAL" -> "Inspirational"
            "COMICSERIES_MYSTERY" -> "Mystery"
            "COMICSERIES_ROMANCE" -> "Romance"
            "COMICSERIES_SCI_FI" -> "Sci-Fi"
            "COMICSERIES_SLICE_OF_LIFE" -> "Slice Of Life"
            "COMICSERIES_SUPERHERO" -> "Superhero"
            "COMICSERIES_SUPERNATURAL" -> "Supernatural"
            "COMICSERIES_WHOLESOME" -> "Wholesome"
            "COMICSERIES_BL" -> "BL (Boy Love)"
            "COMICSERIES_GL" -> "GL (Girl Love)"
            "COMICSERIES_LGBTQ" -> "LGBTQ+"
            "COMICSERIES_THRILLER" -> "Thriller"
            "COMICSERIES_ZOMBIES" -> "Zombies"
            "COMICSERIES_POST_APOCALYPTIC" -> "Post Apocalyptic"
            "COMICSERIES_SCHOOL" -> "School"
            "COMICSERIES_SPORTS" -> "Sports"
            "COMICSERIES_ANIMALS" -> "Animals"
            "COMICSERIES_GAMING" -> "Gaming"
            else -> ""
        }
    }

    fun getGenrePairs(): Array<Pair<String, String>> {
        return arrayOf(
            Pair("", ""),
            Pair("Action", "COMICSERIES_ACTION"),
            Pair("Comedy", "COMICSERIES_COMEDY"),
            Pair("Drama", "COMICSERIES_DRAMA"),
            Pair("Educational", "COMICSERIES_EDUCATIONAL"),
            Pair("Fantasy", "COMICSERIES_FANTASY"),
            Pair("Historical", "COMICSERIES_HISTORICAL"),
            Pair("Horror", "COMICSERIES_HORROR"),
            Pair("Inspirational", "COMICSERIES_INSPIRATIONAL"),
            Pair("Mystery", "COMICSERIES_MYSTERY"),
            Pair("Romance", "COMICSERIES_ROMANCE"),
            Pair("Sci-Fi", "COMICSERIES_SCI_FI"),
            Pair("Slice Of Life", "COMICSERIES_SLICE_OF_LIFE"),
            Pair("Superhero", "COMICSERIES_SUPERHERO"),
            Pair("Supernatural", "COMICSERIES_SUPERNATURAL"),
            Pair("Wholesome", "COMICSERIES_WHOLESOME"),
            Pair("BL (Boy Love)", "COMICSERIES_BL"),
            Pair("GL (Girl Love)", "COMICSERIES_GL"),
            Pair("LGBTQ+", "COMICSERIES_LGBTQ"),
            Pair("Thriller", "COMICSERIES_THRILLER"),
            Pair("Zombies", "COMICSERIES_ZOMBIES"),
            Pair("Post Apocalyptic", "COMICSERIES_POST_APOCALYPTIC"),
            Pair("School", "COMICSERIES_SCHOOL"),
            Pair("Sports", "COMICSERIES_SPORTS"),
            Pair("Animals", "COMICSERIES_ANIMALS"),
            Pair("Gaming", "COMICSERIES_GAMING"),
        )
    }

    private fun Element.cleanTag(): String = text().replace(Regex("\\(.*\\)"), "").trim()
}

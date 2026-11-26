package io.github.velcheor137.shiori.data.libsocial

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** Пункт справочника фильтра: жанр, тег, тип, статус… */
data class FilterOption(val id: Long, val name: String, val adult: Boolean = false, val altNames: List<String> = emptyList())

/** Справочники фильтров каталога из `GET /api/constants`, уже отобранные по сайту (`site_ids`). */
data class FilterConstants(
    val genres: List<FilterOption>,
    val tags: List<FilterOption>,
    val types: List<FilterOption>,
    val formats: List<FilterOption>,
    val statuses: List<FilterOption>,
    val scanlate: List<FilterOption>,
    val ages: List<FilterOption>,
) {
    companion object {
        val FIELDS = listOf("genres", "tags", "types", "format", "status", "scanlateStatus", "ageRestriction")

        /** Разбор ответа; `site_ids` бывает и массивом, и объектом `{"0":0,"1":1}`. */
        fun parse(body: String, siteId: Int = 3): FilterConstants {
            val data = (Json.parseToJsonElement(body) as? JsonObject)?.get("data") as? JsonObject ?: JsonObject(emptyMap())
            fun list(key: String): List<FilterOption> = ((data[key] as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val sites = when (val s = o["site_ids"]) {
                    is JsonArray -> s.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
                    is JsonObject -> s.values.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
                    else -> emptyList()
                }
                if (siteId !in sites) return@mapNotNull null
                val id = (o["id"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
                val name = ((o["name"] ?: o["label"]) as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val alt = (o["alt_name"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
                FilterOption(id, name, (o["adult"] as? JsonPrimitive)?.booleanOrNull == true, alt)
            }
            return FilterConstants(
                genres = list("genres").sortedBy { it.name.lowercase() },
                tags = list("tags").sortedBy { it.name.lowercase() },
                types = list("types"),
                formats = list("format"),
                statuses = list("status"),
                scanlate = list("scanlateStatus"),
                ages = list("ageRestriction"),
            )
        }
    }
}

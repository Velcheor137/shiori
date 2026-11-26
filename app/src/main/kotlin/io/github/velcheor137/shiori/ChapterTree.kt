package io.github.velcheor137.shiori

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.velcheor137.shiori.data.libsocial.ChapterEntry
import io.github.velcheor137.shiori.data.libsocial.TitleDetails

/** Переводчик (линия перевода) для подписи в списках: имя команды(команд) и аватар. */
data class BranchInfo(val branchId: Long, val name: String, val cover: String?, val chapterCount: Int)

/**
 * Подписи переводов берутся из самих глав (`branches[].teams`), поэтому работают и когда читалку открыли,
 * минуя карточку тайтла. Если у ветки нет команды — «Загрузил <ник>».
 */
fun branchInfos(chapters: List<ChapterEntry>, title: TitleDetails? = null): List<BranchInfo> {
    val counts = HashMap<Long, Int>()
    val names = HashMap<Long, String>()
    val covers = HashMap<Long, String?>()
    chapters.forEach { c ->
        c.versions.forEach { v ->
            counts[v.branchId] = (counts[v.branchId] ?: 0) + 1
            if (names[v.branchId] == null && v.teamNames.isNotEmpty()) {
                names[v.branchId] = v.teamNames.joinToString(", ")
                covers[v.branchId] = v.teamCovers.firstOrNull()
            }
        }
    }
    chapters.forEach { c ->
        c.versions.forEach { v -> if (names[v.branchId] == null && v.uploader != null) names[v.branchId] = "Загрузил ${v.uploader}" }
    }
    title?.teams?.forEach { t -> t.branchId?.let { id -> if (names[id] == null) names[id] = t.name } }
    return counts.map { (id, n) -> BranchInfo(id, names[id] ?: "Перевод без подписи", covers[id], n) }.sortedByDescending { it.chapterCount }
}

/** Плашки команд с аватарами (как на RanobeLib): выбор показывает только главы этого переводчика. */
@Composable
fun TeamChips(infos: List<BranchInfo>, pinned: Long, filter: Long?, onFilter: (Long?) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (infos.size > 1) {
            TeamChip(null, "Все", null, selected = filter == null, pinned = false) { onFilter(null) }
        }
        infos.forEach { b ->
            TeamChip(b.cover, b.name, "${b.chapterCount}", selected = filter == b.branchId, pinned = b.branchId == pinned) {
                onFilter(if (filter == b.branchId) null else b.branchId)
            }
        }
    }
}

@Composable
private fun TeamChip(cover: String?, name: String, count: String?, selected: Boolean, pinned: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(if (pinned) 1.5.dp else 0.dp, if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (cover != null) {
            AsyncImage(cover, null, Modifier.size(32.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
            Spacer(Modifier.width(8.dp))
        }
        Column {
            Text(name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (count != null) Text(if (pinned) "$count гл. · читаю" else "$count гл.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Список глав как на RanobeLib: строка главы, под ней — переводы этой главы (команда, дата, загрузка).
 * Закреплённый перевод подсвечен. Открытие другого перевода — явный выбор пользователя.
 */
fun LazyListScope.chapterTree(
    chapters: List<ChapterEntry>,
    infos: Map<Long, BranchInfo>,
    pinned: Long,
    filter: Long?,
    current: Pair<String, String>?,
    downloaded: Set<Long>,
    onOpen: (ChapterEntry, Long) -> Unit,
    onDownload: ((ChapterEntry, Long) -> Unit)?,
) {
    items(chapters, key = { it.apiId }) { c ->
        val versions = c.versions.filter { filter == null || it.branchId == filter }
            .sortedWith(compareByDescending<io.github.velcheor137.shiori.data.libsocial.ChapterVersion> { it.branchId == pinned }.thenByDescending { it.createdAt })
        val isCurrent = current != null && c.volume == current.first && c.number == current.second
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(c, (versions.firstOrNull { it.branchId == pinned } ?: versions.firstOrNull())?.branchId ?: pinned) }
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Том ${c.volume} Глава ${c.number}",
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                c.name?.takeIf { it.isNotBlank() }?.let {
                    Text(" - $it", color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            versions.forEachIndexed { i, v ->
                val isPinned = v.branchId == pinned
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(c, v.branchId) }.padding(start = 24.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // «Дерево»: вертикальная линия и отвод к строке перевода.
                    Box(Modifier.width(18.dp).height(40.dp)) {
                        Box(Modifier.width(1.dp).height(if (i == versions.lastIndex) 20.dp else 40.dp).background(MaterialTheme.colorScheme.outline))
                        Box(Modifier.padding(top = 20.dp).width(12.dp).height(1.dp).background(MaterialTheme.colorScheme.outline))
                    }
                    Text(
                        infos[v.branchId]?.name ?: v.teamNames.joinToString().ifBlank { v.uploader ?: "Перевод" },
                        Modifier.weight(1f),
                        color = when {
                            isPinned && isCurrent -> MaterialTheme.colorScheme.primary
                            isPinned -> MaterialTheme.colorScheme.onSurface
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontWeight = if (isPinned) FontWeight.Medium else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!v.isOpen) Text("платная  ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    v.createdAt?.let { Text(formatDate(it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    when {
                        // Скачанная версия — всегда со значком «скачано» (и в карточке тайтла, и в читалке).
                        v.versionId in downloaded -> Icon(
                            AppIcons.DownloadDone,
                            "Скачано",
                            Modifier.padding(horizontal = 12.dp).size(20.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        onDownload != null -> IconButton(onClick = { onDownload(c, v.branchId) }, enabled = v.isOpen) {
                            Icon(AppIcons.Download, "Скачать", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        else -> Spacer(Modifier.width(12.dp))
                    }
                }
            }
            HorizontalDivider(Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.outline)
        }
    }
}

/** «2026-09-26T12:00:00Z» → «26.09.2026». */
fun formatDate(iso: String): String {
    val d = iso.take(10).split('-')
    return if (d.size == 3) "${d[2]}.${d[1]}.${d[0]}" else iso.take(10)
}

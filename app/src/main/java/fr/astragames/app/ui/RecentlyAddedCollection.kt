package fr.astragames.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import fr.astragames.app.core.model.LibraryViewMode
import fr.astragames.app.data.local.GameEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

internal enum class AddedPeriod(val label: String) {
    DAY("24 heures"), WEEK("7 jours"), FORTNIGHT("14 jours"), MONTH("1 mois"),
    THREE_MONTHS("3 mois"), OLDER("Plus de 3 mois"), ALL("Tout"), CUSTOM("Période personnalisée")
}

internal data class AddedDateRange(val fromInclusive: Long, val untilExclusive: Long) {
    fun contains(timestamp: Long) = timestamp >= fromInclusive && timestamp < untilExclusive
}

internal fun addedDateRange(period: AddedPeriod, now: Long, zone: ZoneId = ZoneId.systemDefault(), start: LocalDate? = null, end: LocalDate? = null): AddedDateRange {
    val instant = Instant.ofEpochMilli(now)
    val localNow = instant.atZone(zone)
    val startTime = when (period) {
        AddedPeriod.DAY -> now - 86_400_000L
        AddedPeriod.WEEK -> localNow.minusDays(7).toInstant().toEpochMilli()
        AddedPeriod.FORTNIGHT -> localNow.minusDays(14).toInstant().toEpochMilli()
        AddedPeriod.MONTH -> localNow.minusMonths(1).toInstant().toEpochMilli()
        AddedPeriod.THREE_MONTHS -> localNow.minusMonths(3).toInstant().toEpochMilli()
        AddedPeriod.OLDER, AddedPeriod.ALL -> Long.MIN_VALUE
        AddedPeriod.CUSTOM -> start?.atStartOfDay(zone)?.toInstant()?.toEpochMilli() ?: Long.MIN_VALUE
    }
    val endTime = when (period) {
        AddedPeriod.OLDER -> localNow.minusMonths(3).toInstant().toEpochMilli()
        AddedPeriod.CUSTOM -> end?.plusDays(1)?.atStartOfDay(zone)?.toInstant()?.toEpochMilli() ?: now + 1
        else -> now + 1
    }
    return AddedDateRange(startTime, endTime)
}

@Composable
internal fun RecentlyAddedCollection(games: List<GameEntity>, viewMode: LibraryViewMode, columns: Int, onGame: (String) -> Unit, onFavorite: (String) -> Unit) {
    var period by rememberSaveable { mutableStateOf(AddedPeriod.WEEK) }
    var startDay by rememberSaveable { mutableStateOf<Long?>(null) }
    var endDay by rememberSaveable { mutableStateOf<Long?>(null) }
    var choosingDates by rememberSaveable { mutableStateOf(false) }
    val now by produceState(System.currentTimeMillis()) {
        while (true) { delay(60_000); value = System.currentTimeMillis() }
    }
    val range = addedDateRange(period, now, start = startDay?.let(LocalDate::ofEpochDay), end = endDay?.let(LocalDate::ofEpochDay))
    val matching = remember(games, range) { games.filter { range.contains(it.dateAdded) }.sortedWith(compareByDescending<GameEntity> { it.dateAdded }.thenBy { it.id }) }
    Column(Modifier.fillMaxSize()) {
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("added-date-filters")) {
            items(AddedPeriod.entries) { choice ->
                FilterChip(period == choice, { if (choice == AddedPeriod.CUSTOM) choosingDates = true else period = choice },
                    label = { Text(choice.label) })
            }
        }
        val description = when (period) {
            AddedPeriod.CUSTOM -> {
                val format = DateTimeFormatter.ofPattern("dd/MM/yyyy")
                "Du ${startDay?.let(LocalDate::ofEpochDay)?.format(format)} au ${endDay?.let(LocalDate::ofEpochDay)?.format(format)} inclus"
            }
            AddedPeriod.ALL -> "Toutes les dates d’ajout"
            AddedPeriod.OLDER -> "Ajoutés il y a plus de 3 mois"
            else -> "Ajoutés depuis ${period.label}"
        }
        Text("$description · ${matching.size} jeu(x)", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
        Box(Modifier.weight(1f)) {
            if (matching.isEmpty()) CenterMessage("Aucun jeu ajouté pendant cette période", Modifier.fillMaxSize())
            else GameCollection(matching, viewMode, columns, onGame, onFavorite)
        }
    }
    if (choosingDates) AddedDatePicker(startDay, endDay, onConfirm = { start, end ->
        startDay = start; endDay = end; period = AddedPeriod.CUSTOM; choosingDates = false
    }, onDismiss = { choosingDates = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddedDatePicker(startDay: Long?, endDay: Long?, onConfirm: (Long, Long) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    val currentConfiguration = LocalConfiguration.current
    val localized = remember(context, language, currentConfiguration) {
        val configuration = android.content.res.Configuration(currentConfiguration)
        configuration.setLocale(java.util.Locale.forLanguageTag(language.code))
        context.createConfigurationContext(configuration)
    }
    CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides localized.resources.configuration, LocalResources provides localized.resources) {
        LocalizedAddedDatePicker(startDay, endDay, onConfirm, onDismiss)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocalizedAddedDatePicker(startDay: Long?, endDay: Long?, onConfirm: (Long, Long) -> Unit, onDismiss: () -> Unit) {
    fun utcMillis(day: Long?) = day?.let { LocalDate.ofEpochDay(it).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }
    val picker = rememberDateRangePickerState(initialSelectedStartDateMillis = utcMillis(startDay), initialSelectedEndDateMillis = utcMillis(endDay),
        initialDisplayMode = if (LandscapeLayout) DisplayMode.Input else DisplayMode.Picker)
    val pickerContext = LocalContext.current
    val pickerConfiguration = LocalConfiguration.current
    val pickerResources = LocalResources.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // Dialog creates its own Android composition locals; retain the app's chosen language.
        CompositionLocalProvider(LocalContext provides pickerContext, LocalConfiguration provides pickerConfiguration, LocalResources provides pickerResources) {
        AstraDialogSystemBars()
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
                CompactHeader("Dates d’ajout", "Le premier et le dernier jour sont inclus", onBack = onDismiss)
                DateRangePicker(picker, Modifier.weight(1f), title = null, showModeToggle = true)
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Annuler") }
                    Button(onClick = {
                        val start = picker.selectedStartDateMillis ?: return@Button
                        val end = picker.selectedEndDateMillis ?: return@Button
                        onConfirm(Instant.ofEpochMilli(start).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay(), Instant.ofEpochMilli(end).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay())
                    }, enabled = picker.selectedStartDateMillis != null && picker.selectedEndDateMillis != null) { Text("Appliquer") }
                }
            }
        }
        }
    }
}

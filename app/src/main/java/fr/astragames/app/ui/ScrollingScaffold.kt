package fr.astragames.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import kotlin.math.roundToInt

/** The header exits before the list scrolls; it returns only once the list reaches its start. */
@Composable
internal fun ScrollingScaffold(
    modifier: Modifier = Modifier,
    contentWindowInsets: WindowInsets = WindowInsets(0, 0, 0, 0),
    topBar: @Composable () -> Unit,
    content: @Composable (PaddingValues) -> Unit
) {
    var headerHeight by remember { mutableFloatStateOf(0f) }
    var offset by remember { mutableFloatStateOf(0f) }
    val scroll = remember {
        object : NestedScrollConnection {
            fun consume(amount: Float): Offset {
                val previous = offset
                offset = (offset + amount).coerceIn(-headerHeight, 0f)
                return Offset(0f, offset - previous)
            }
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                if (available.y < 0) consume(available.y) else Offset.Zero
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                if (available.y > 0) consume(available.y) else Offset.Zero
        }
    }
    Scaffold(
        modifier = modifier.statusBarsPadding().nestedScroll(scroll),
        contentWindowInsets = contentWindowInsets,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Layout(
                modifier = Modifier.clipToBounds().testTag("scrolling-header"),
                content = { Box(Modifier.onSizeChanged { headerHeight = it.height.toFloat(); offset = offset.coerceIn(-headerHeight, 0f) }) { topBar() } }
            ) { measurables, constraints ->
                val header = measurables.single().measure(constraints.copy(minHeight = 0))
                layout(header.width, (header.height + offset).roundToInt().coerceAtLeast(0)) {
                    header.placeRelative(0, offset.roundToInt())
                }
            }
        },
        content = content
    )
}

@Composable
internal fun ScrollingColumn(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    ScrollingScaffold(modifier = modifier, topBar = topBar) { padding ->
        Column(Modifier.fillMaxSize().padding(padding), content = content)
    }
}

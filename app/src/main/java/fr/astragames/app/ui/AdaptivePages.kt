package fr.astragames.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

internal val LandscapeLayout: Boolean
    @Composable get() {
        val size = LocalWindowInfo.current.containerSize
        return with(LocalDensity.current) { size.width.toDp() >= 600.dp } && size.width > size.height
    }

@Composable
internal fun AdaptiveLists(
    modifier: Modifier = Modifier,
    split: Boolean = LandscapeLayout,
    horizontalPadding: androidx.compose.ui.unit.Dp = 20.dp,
    first: LazyListScope.() -> Unit,
    second: LazyListScope.() -> Unit
) {
    val padding = PaddingValues(start = horizontalPadding, top = 12.dp, end = horizontalPadding, bottom = PageBottomPadding)
    if (split) Row(modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxHeight(), contentPadding = padding, content = first)
        LazyColumn(Modifier.weight(1f).fillMaxHeight(), contentPadding = padding, content = second)
    } else LazyColumn(modifier.fillMaxSize(), contentPadding = padding) { first(); second() }
}

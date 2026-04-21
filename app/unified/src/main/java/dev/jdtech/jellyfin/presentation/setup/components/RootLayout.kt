package dev.jdtech.jellyfin.presentation.setup.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.presentation.utils.plus
import dev.jdtech.jellyfin.presentation.utils.rememberSafePadding

@Composable
fun RootLayout(padding: PaddingValues = PaddingValues(), content: @Composable BoxScope.() -> Unit) {
    val safePadding = rememberSafePadding()

    val safePaddingValues =
        PaddingValues(
            start = safePadding.start + 24.dp,
            top = safePadding.top + 16.dp,
            end = safePadding.end + 24.dp,
            bottom = safePadding.bottom + 16.dp,
        )

    Box(modifier = Modifier.fillMaxSize().padding(safePaddingValues + padding), content = content)
}

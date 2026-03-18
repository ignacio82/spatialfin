package dev.jdtech.jellyfin.presentation.settings

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import dev.spatialfin.BuildConfig
import dev.spatialfin.R
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.presentation.film.components.XrBrowseHeader
import dev.jdtech.jellyfin.presentation.utils.rememberSafePadding
import dev.spatialfin.presentation.theme.SpatialFinTheme
import dev.spatialfin.presentation.theme.spacings
import dev.jdtech.jellyfin.settings.R as SettingsR

@Composable
fun AboutScreen(navigateBack: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val safePadding = rememberSafePadding()

    val libraries by produceLibraries(R.raw.aboutlibraries)

    Box(modifier = Modifier.fillMaxSize()) {
        LibrariesContainer(
            libraries = libraries,
            modifier = Modifier.fillMaxSize(),
            contentPadding =
                PaddingValues(
                    start = safePadding.start + MaterialTheme.spacings.default,
                    top = safePadding.top + 96.dp,
                    end = safePadding.end + MaterialTheme.spacings.default,
                    bottom = safePadding.bottom + MaterialTheme.spacings.large,
                ),
            header = {
                item {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(
                            modifier =
                                Modifier.padding(horizontal = MaterialTheme.spacings.large),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Spacer(Modifier.height(MaterialTheme.spacings.medium))
                            Image(
                                painter = painterResource(CoreR.drawable.ic_banner),
                                contentDescription = null,
                                modifier = Modifier.width(320.dp),
                            )
                            Spacer(Modifier.height(MaterialTheme.spacings.large))
                            Text(
                                text = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Spacer(Modifier.height(MaterialTheme.spacings.small))
                            Text(
                                text = stringResource(CoreR.string.app_description),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Spacer(Modifier.height(MaterialTheme.spacings.large))
                            HorizontalDivider()
                            Spacer(Modifier.height(MaterialTheme.spacings.large))
                            Row(
                                horizontalArrangement =
                                    Arrangement.spacedBy(MaterialTheme.spacings.medium)
                            ) {
                                FilledTonalIconButton(
                                    onClick = {
                                        try {
                                            uriHandler.openUri(
                                                "https://github.com/ignacio82/SpatialFin"
                                            )
                                        } catch (e: IllegalArgumentException) {
                                            Toast.makeText(
                                                    context,
                                                    e.localizedMessage,
                                                    Toast.LENGTH_SHORT,
                                                )
                                                .show()
                                        }
                                    }
                                ) {
                                    Icon(
                                        painter = painterResource(CoreR.drawable.ic_github),
                                        contentDescription = null,
                                    )
                                }
                                FilledTonalIconButton(
                                    onClick = {
                                        try {
                                            uriHandler.openUri(
                                                "https://spatialfin.martinez.fyi"
                                            )
                                        } catch (e: IllegalArgumentException) {
                                            Toast.makeText(
                                                    context,
                                                    e.localizedMessage,
                                                    Toast.LENGTH_SHORT,
                                                )
                                                .show()
                                        }
                                    }
                                ) {
                                    Icon(
                                        painter = painterResource(CoreR.drawable.ic_coffee),
                                        contentDescription = null,
                                    )
                                }
                            }
                            Spacer(Modifier.height(MaterialTheme.spacings.medium))
                        }
                    }
                }
            },
        )
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .zIndex(1f)
                    .padding(
                        start = safePadding.start + MaterialTheme.spacings.default,
                        top = safePadding.top + MaterialTheme.spacings.default,
                        end = safePadding.end + MaterialTheme.spacings.default,
                    ),
        ) {
            XrBrowseHeader(
                title = stringResource(SettingsR.string.about),
                onBackClick = navigateBack,
            )
        }
    }
}

@PreviewScreenSizes
@Composable
private fun AboutScreenPreview() {
    SpatialFinTheme { AboutScreen(navigateBack = {}) }
}

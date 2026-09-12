package dev.spatialfin.unified.screenshots

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jdtech.jellyfin.player.beam.BeamWidth
import dev.jdtech.jellyfin.player.beam.LocalBeamWidth
import dev.spatialfin.beam.BeamTheme
import dev.spatialfin.beam.BeamTokens

/**
 * Renders representative widescreen tablet screens with Blender open movie assets
 * for Google Play Store tablet listings (7-inch and 10-inch).
 *
 * Debug-only — not in the release manifest.
 */
class TabletScreenshotActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val screen = intent.getStringExtra("screen") ?: "home"

        if (screen == "player") {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }

        setContent {
            BeamTheme {
                CompositionLocalProvider(
                    LocalBeamWidth provides BeamWidth.Expanded
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = Color(0xFF0F1218)
                    ) {
                        when (screen) {
                            "home" -> TabletHomeScreen()
                            "detail" -> TabletDetailScreen()
                            "player" -> TabletPlayerScreen()
                            "remote" -> TabletRemoteScreen()
                            "downloads" -> TabletDownloadsScreen()
                            "music" -> TabletMusicScreen()
                            else -> TabletHomeScreen()
                        }
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Tablet Navigation Shell
    // -------------------------------------------------------------------------
    @Composable
    private fun TabletScaffold(
        selectedRoute: String,
        content: @Composable () -> Unit
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            // Tablet Sidebar Navigation
            Column(
                modifier = Modifier
                    .width(96.dp)
                    .fillMaxHeight()
                    .background(Color(0xFF131720))
                    .padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top Logo / Brand
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(Color(0xFF388E3C), Color(0xFF0288D1))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Movie,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(32.dp))

                    // Nav Items
                    SidebarItem(
                        icon = Icons.Rounded.Home,
                        label = "Home",
                        selected = selectedRoute == "home"
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    SidebarItem(
                        icon = Icons.Rounded.Movie,
                        label = "Movies",
                        selected = selectedRoute == "detail"
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    SidebarItem(
                        icon = Icons.Rounded.MusicNote,
                        label = "Music",
                        selected = selectedRoute == "music"
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    SidebarItem(
                        icon = Icons.Rounded.CloudDownload,
                        label = "Downloads",
                        selected = selectedRoute == "downloads"
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    SidebarItem(
                        icon = Icons.Rounded.Cast,
                        label = "Remote",
                        selected = selectedRoute == "remote"
                    )
                }

                // Bottom Utilities
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // Voice Mic FAB
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Mic,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Icon(
                        imageVector = Icons.Rounded.Settings,
                        contentDescription = null,
                        tint = Color(0xFF8D9199),
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            // Main Content
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(Color(0xFF0C0E14))
            ) {
                content()
            }
        }
    }

    @Composable
    private fun SidebarItem(
        icon: ImageVector,
        label: String,
        selected: Boolean
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(if (selected) Color(0xFF1E2638) else Color.Transparent)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (selected) MaterialTheme.colorScheme.primary else Color(0xFF8D9199),
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) MaterialTheme.colorScheme.primary else Color(0xFF8D9199),
                fontSize = 11.sp
            )
        }
    }

    // -------------------------------------------------------------------------
    // 1. Tablet Home Screen
    // -------------------------------------------------------------------------
    @Composable
    private fun TabletHomeScreen() {
        TabletScaffold(selectedRoute = "home") {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(32.dp)
            ) {
                // Header Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "SpatialFin",
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "Connected to Studio Media Server · 4K HDR Library",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF8D9199)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilledTonalButton(onClick = {}) {
                            Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Search")
                        }
                        FilledTonalButton(onClick = {}) {
                            Icon(Icons.Rounded.Lan, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Network Shares")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))

                // Hero Spotlight Banner
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFF1E2836), Color(0xFF131824), Color(0xFF0F1A1C))
                            )
                        )
                        .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(24.dp))
                        .padding(36.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(0.65f),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TagBadge(text = "FEATURED MOVIE", color = Color(0xFFA4C9FE), textColor = Color(0xFF00315C))
                                TagBadge(text = "4K HDR", color = Color(0x33FFFFFF), textColor = Color.White)
                                TagBadge(text = "5.1 SURROUND", color = Color(0x33FFFFFF), textColor = Color.White)
                                TagBadge(text = "★ 7.8", color = Color(0x33F2C94C), textColor = Color(0xFFF2C94C))
                            }
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = "Sprite Fright",
                                style = MaterialTheme.typography.displaySmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "When a lively group of rowdy teenagers head into the woods, they encounter creatures unlike anything they expected. A hilarious comedy horror produced by Blender Studio.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color(0xFFC3C6CF),
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Button(
                                onClick = {},
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Color(0xFF00315C))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Play Now", color = Color(0xFF00315C), fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(
                                onClick = {},
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Rounded.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Movie Details")
                            }
                            OutlinedButton(
                                onClick = {},
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Rounded.Cast, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Cast to Headset")
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(36.dp))

                // Continue Watching Shelf
                Text(
                    text = "Continue Watching",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    ContinueCard("Spring", "08:14 left", 0.72f, Color(0xFF2E3E34))
                    ContinueCard("Sintel", "03:20 left", 0.85f, Color(0xFF3E2E3C))
                    ContinueCard("Big Buck Bunny", "04:12 left", 0.40f, Color(0xFF2E363E))
                    ContinueCard("Cosmos Laundromat", "09:00 left", 0.15f, Color(0xFF3E362E))
                }

                Spacer(modifier = Modifier.height(36.dp))

                // Recently Added Shelf
                Text(
                    text = "Recently Added Movies",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    PosterCard("Sprite Fright", "2021", "4K HDR", Color(0xFF1E2836))
                    PosterCard("Tears of Steel", "2012", "Sci-Fi", Color(0xFF2C2436))
                    PosterCard("Caminandes", "2013", "Animation", Color(0xFF363224))
                    PosterCard("Charge", "2022", "3D Spatial", Color(0xFF243632))
                    PosterCard("Elephants Dream", "2006", "Sci-Fi", Color(0xFF362428))
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // 2. Tablet Detail Screen
    // -------------------------------------------------------------------------
    @Composable
    private fun TabletDetailScreen() {
        TabletScaffold(selectedRoute = "detail") {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(36.dp),
                horizontalArrangement = Arrangement.spacedBy(36.dp)
            ) {
                // Left Column: Poster & Actions (340dp)
                Column(
                    modifier = Modifier
                        .width(340.dp)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(440.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color(0xFF223145), Color(0xFF141C28))
                                )
                            )
                            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(20.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Rounded.Movie,
                                contentDescription = null,
                                tint = Color(0xFFA4C9FE),
                                modifier = Modifier.size(64.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                "Sprite Fright",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                "Blender Open Movie · 4K",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF8D9199)
                            )
                        }
                    }

                    // Action Buttons
                    Button(
                        onClick = {},
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Color(0xFF00315C))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Play Movie", color = Color(0xFF00315C), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        FilledTonalButton(
                            onClick = {},
                            modifier = Modifier.weight(1f).height(46.dp),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Icon(Icons.Rounded.Cast, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Cast", maxLines = 1)
                        }
                        FilledTonalButton(
                            onClick = {},
                            modifier = Modifier.weight(1.2f).height(46.dp),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Icon(Icons.Rounded.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Download", maxLines = 1)
                        }
                        FilledTonalButton(
                            onClick = {},
                            modifier = Modifier.width(52.dp).height(46.dp),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Icon(Icons.Rounded.Favorite, contentDescription = null, tint = Color(0xFFFF8B94), modifier = Modifier.size(20.dp))
                        }
                    }
                }

                // Right Column: Metadata & Details
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    Column {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            TagBadge("2021", Color(0x33FFFFFF), Color.White)
                            TagBadge("10 MIN", Color(0x33FFFFFF), Color.White)
                            TagBadge("PG-13", Color(0x33FFFFFF), Color.White)
                            TagBadge("4K HDR", Color(0xFF1E3A5F), Color(0xFFA4C9FE))
                            TagBadge("★ 7.8 IMDb", Color(0x33F2C94C), Color(0xFFF2C94C))
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Sprite Fright",
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "Animation · Comedy · Horror · Short",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color(0xFFA4C9FE)
                        )
                    }

                    // Smart Track Selection Chips
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF151922))
                            .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(16.dp))
                            .padding(20.dp)
                    ) {
                        Text(
                            text = "Smart Audio & Subtitles",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TrackChip("♪ Audio: English · EAC3 5.1", isSelected = true)
                            TrackChip("CC Subtitles: English · ASS (Full)", isSelected = true)
                            TrackChip("HD Video: Direct Play 4K", isSelected = false)
                        }
                    }

                    // Synopsis
                    Column {
                        Text(
                            text = "Overview",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "When a lively group of rowdy teenagers venture into a remote forest for a weekend of camping and loud music, they discover that the seemingly peaceful woodland is inhabited by bloodthirsty sprite creatures with a gruesome appetite for intruders.\n\nAn affectionate tribute to 1980s horror comedies, Sprite Fright is an open-source animation masterpiece written and directed by former Pixar story supervisor Matthew Luhn.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color(0xFFC3C6CF),
                            lineHeight = 26.sp
                        )
                    }

                    // Cast & Crew
                    Column {
                        Text(
                            text = "Cast & Crew",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                            CastAvatar("Matthew Luhn", "Director")
                            CastAvatar("Colin Levy", "Co-Director")
                            CastAvatar("Dirk", "Woodsman")
                            CastAvatar("Astrid", "Lead Hiker")
                            CastAvatar("Victoria", "Camper")
                        }
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // 3. Tablet Player Screen (Cinematic Video Player)
    // -------------------------------------------------------------------------
    @Composable
    private fun TabletPlayerScreen() {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            // Simulated Video Frame
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            listOf(Color(0xFF1B263B), Color(0xFF0D1B2A), Color.Black)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                // Video Content Representation
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Rounded.Movie,
                        contentDescription = null,
                        tint = Color(0x33A4C9FE),
                        modifier = Modifier.size(160.dp)
                    )
                }

                // Subtitle Display
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 120.dp)
                        .background(Color(0x99000000), RoundedCornerShape(8.dp))
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "Stay quiet... they can hear every step we take!",
                        color = Color(0xFFFFF275),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Top Player Controls HUD
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xCC000000), Color.Transparent)
                        )
                    )
                    .padding(horizontal = 36.dp, vertical = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    IconButton(onClick = {}) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = null, tint = Color.White)
                    }
                    Column {
                        Text(
                            text = "Sprite Fright (2021)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "4K HDR · 60fps · EAC3 5.1 Surround · Spatial Direct Play",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFA4C9FE)
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    TagBadge("3D SBS", Color(0x33FFFFFF), Color.White)
                    IconButton(onClick = {}) {
                        Icon(Icons.Rounded.Subtitles, contentDescription = null, tint = Color.White)
                    }
                    IconButton(onClick = {}) {
                        Icon(Icons.Rounded.Audiotrack, contentDescription = null, tint = Color.White)
                    }
                    IconButton(onClick = {}) {
                        Icon(Icons.Rounded.Cast, contentDescription = null, tint = Color.White)
                    }
                    IconButton(onClick = {}) {
                        Icon(Icons.Rounded.AspectRatio, contentDescription = null, tint = Color.White)
                    }
                }
            }

            // Center Playback Buttons
            Row(
                modifier = Modifier.align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(48.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {}, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.Replay10, contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp))
                }
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Pause, contentDescription = null, tint = Color(0xFF00315C), modifier = Modifier.size(44.dp))
                }
                IconButton(onClick = {}, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.Forward10, contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp))
                }
            }

            // Bottom Player Controls HUD
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color(0xCC000000))
                        )
                    )
                    .padding(horizontal = 36.dp, vertical = 24.dp)
            ) {
                // Chapter Marker
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Chapter 2: The Forest (01:48 - 04:36)",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFFA4C9FE)
                    )
                    Text(
                        text = "Auto-play Next: Sintel",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFF8D9199)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Seek Progress Bar
                LinearProgressIndicator(
                    progress = { 0.59f },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color(0x44FFFFFF),
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Time and Controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "06:12 / 10:30",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.VolumeUp, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                        Box(
                            modifier = Modifier
                                .width(120.dp)
                                .height(4.dp)
                                .clip(CircleShape)
                                .background(Color(0x44FFFFFF))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(0.7f)
                                    .background(Color.White)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(Icons.Rounded.Fullscreen, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // 4. Tablet Remote Control Screen
    // -------------------------------------------------------------------------
    @Composable
    private fun TabletRemoteScreen() {
        TabletScaffold(selectedRoute = "remote") {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(36.dp),
                horizontalArrangement = Arrangement.spacedBy(36.dp)
            ) {
                // Left Pane: Hero Remote (Active Session)
                Column(
                    modifier = Modifier
                        .weight(1.2f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xFF141A24))
                        .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(24.dp))
                        .padding(32.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // Target Device Status Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(Color(0xFF4CAF50)))
                            Text("Controlling Galaxy XR Headset", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        TagBadge("Battery: 84%", Color(0x33FFFFFF), Color.White)
                    }

                    // Media Artwork & Info
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(220.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color(0xFF1E2838)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.Movie, contentDescription = null, tint = Color(0xFFA4C9FE), modifier = Modifier.size(64.dp))
                        }
                        Spacer(modifier = Modifier.height(20.dp))
                        Text("Sprite Fright", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("Blender Studio · 4K HDR · 5.1 Surround", style = MaterialTheme.typography.bodyMedium, color = Color(0xFFA4C9FE))
                    }

                    // Scrub Bar & Controls
                    Column {
                        LinearProgressIndicator(
                            progress = { 0.59f },
                            modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = Color(0x33FFFFFF)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("06:12", style = MaterialTheme.typography.labelMedium, color = Color.White)
                            Text("-04:18", style = MaterialTheme.typography.labelMedium, color = Color(0xFF8D9199))
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        // Controls
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = {}) {
                                Icon(Icons.Rounded.SkipPrevious, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
                            }
                            IconButton(onClick = {}) {
                                Icon(Icons.Rounded.Replay10, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
                            }
                            Box(
                                modifier = Modifier
                                    .size(68.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Rounded.Pause, contentDescription = null, tint = Color(0xFF00315C), modifier = Modifier.size(38.dp))
                            }
                            IconButton(onClick = {}) {
                                Icon(Icons.Rounded.Forward10, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
                            }
                            IconButton(onClick = {}) {
                                Icon(Icons.Rounded.SkipNext, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
                            }
                        }
                    }
                }

                // Right Pane: Chapters & Headset Calibration (1f)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    // Chapters List Card
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1.2f)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFF141A24))
                            .padding(24.dp)
                    ) {
                        Text("Chapters", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
                        Spacer(modifier = Modifier.height(16.dp))
                        ChapterItem("1. Opening", "00:00", isCurrent = false)
                        ChapterItem("2. The Forest", "01:48", isCurrent = true)
                        ChapterItem("3. Campfire Song", "04:36", isCurrent = false)
                        ChapterItem("4. Sprites Attack", "07:02", isCurrent = false)
                    }

                    // Spatial Headset Calibration Card
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(0.8f)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFF141A24))
                            .padding(24.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Spatial Headset Quick Actions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            FilledTonalButton(onClick = {}, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Rounded.FilterCenterFocus, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Recenter")
                            }
                            FilledTonalButton(onClick = {}, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Rounded.ViewInAr, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Theater Mode")
                            }
                        }
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // 5. Tablet Downloads & Offline Media Screen
    // -------------------------------------------------------------------------
    @Composable
    private fun TabletDownloadsScreen() {
        TabletScaffold(selectedRoute = "downloads") {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Downloads & Offline Media", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("Enjoy your favorite cinema offline anywhere on your tablet", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF8D9199))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TagBadge("OFFLINE READY", Color(0xFF1B382B), Color(0xFF81C784))
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Storage Meter Card
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF151922))
                        .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(16.dp))
                        .padding(20.dp)
                ) {
                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Tablet Storage: 14.8 GB Used by SpatialFin", style = MaterialTheme.typography.bodyMedium, color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text("113.2 GB Available", style = MaterialTheme.typography.bodyMedium, color = Color(0xFFA4C9FE))
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { 0.12f },
                            modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = Color(0x33FFFFFF)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))

                // Download Grid
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    DownloadGridCard("Sprite Fright", "4K HDR · 1.4 GB", "Downloaded · Ready", Color(0xFF1E2836), modifier = Modifier.weight(1f))
                    DownloadGridCard("Spring", "1080p · 820 MB", "Downloaded · Ready", Color(0xFF2E3E34), modifier = Modifier.weight(1f))
                    DownloadGridCard("Sintel", "4K · 2.1 GB", "Downloaded · Ready", Color(0xFF3E2E3C), modifier = Modifier.weight(1f))
                    DownloadGridCard("Big Buck Bunny", "1080p · 650 MB", "Downloaded · Ready", Color(0xFF2E363E), modifier = Modifier.weight(1f))
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // 6. Tablet Music Screen
    // -------------------------------------------------------------------------
    @Composable
    private fun TabletMusicScreen() {
        TabletScaffold(selectedRoute = "music") {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(36.dp),
                horizontalArrangement = Arrangement.spacedBy(36.dp)
            ) {
                // Left: Large Now Playing Vinyl
                Column(
                    modifier = Modifier
                        .weight(1.1f)
                        .fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Box(
                        modifier = Modifier
                            .size(360.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(Color(0xFF2C1E38), Color(0xFF161024))
                                )
                            )
                            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(24.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = Color(0xFFD9BDE3), modifier = Modifier.size(100.dp))
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        TagBadge("FLAC 96kHz / 24-bit Lossless", Color(0x33D9BDE3), Color(0xFFD9BDE3))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Echoes of the Forest", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("Blender Studio Soundtracks · Sprite Fright OST", style = MaterialTheme.typography.bodyLarge, color = Color(0xFFC3C6CF))
                    }

                    // Audio Controls
                    Column(modifier = Modifier.fillMaxWidth()) {
                        LinearProgressIndicator(
                            progress = { 0.42f },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                            color = Color(0xFFD9BDE3),
                            trackColor = Color(0x33FFFFFF)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("01:34", style = MaterialTheme.typography.labelMedium, color = Color.White)
                            Text("-02:11", style = MaterialTheme.typography.labelMedium, color = Color(0xFF8D9199))
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = {}) { Icon(Icons.Rounded.Shuffle, contentDescription = null, tint = Color(0xFF8D9199)) }
                            IconButton(onClick = {}) { Icon(Icons.Rounded.SkipPrevious, contentDescription = null, tint = Color.White, modifier = Modifier.size(36.dp)) }
                            Box(
                                modifier = Modifier.size(64.dp).clip(CircleShape).background(Color(0xFFD9BDE3)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Rounded.Pause, contentDescription = null, tint = Color(0xFF3C2947), modifier = Modifier.size(36.dp))
                            }
                            IconButton(onClick = {}) { Icon(Icons.Rounded.SkipNext, contentDescription = null, tint = Color.White, modifier = Modifier.size(36.dp)) }
                            IconButton(onClick = {}) { Icon(Icons.Rounded.Repeat, contentDescription = null, tint = Color(0xFF8D9199)) }
                        }
                    }
                }

                // Right: Queue
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xFF151922))
                        .padding(28.dp)
                ) {
                    Text("Up Next Queue", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(modifier = Modifier.height(20.dp))
                    QueueItem("1", "Echoes of the Forest", "Blender Studio", "03:45", isPlaying = true)
                    QueueItem("2", "Campfire Gathering", "Blender Studio", "02:18", isPlaying = false)
                    QueueItem("3", "The Chase Begins", "Blender Studio", "04:12", isPlaying = false)
                    QueueItem("4", "Dawn at the Woods", "Blender Studio", "05:02", isPlaying = false)
                    QueueItem("5", "End Credits Suite", "Blender Studio", "03:30", isPlaying = false)
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Reusable UI Helpers
    // -------------------------------------------------------------------------
    @Composable
    private fun TagBadge(text: String, color: Color, textColor: Color) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(color)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = textColor,
                fontSize = 11.sp
            )
        }
    }

    @Composable
    private fun ContinueCard(title: String, remaining: String, progress: Float, bg: Color) {
        Column(
            modifier = Modifier
                .width(280.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF161B24))
                .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(16.dp))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .background(bg),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(48.dp))
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(4.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color(0x33FFFFFF)
            )
            Column(modifier = Modifier.padding(14.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color.White)
                Text(remaining, style = MaterialTheme.typography.bodySmall, color = Color(0xFF8D9199))
            }
        }
    }

    @Composable
    private fun PosterCard(title: String, year: String, badge: String, bg: Color) {
        Column(
            modifier = Modifier
                .width(180.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF161B24))
                .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(16.dp))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .background(bg),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.Movie, contentDescription = null, tint = Color(0x55FFFFFF), modifier = Modifier.size(40.dp))
                }
            }
            Column(modifier = Modifier.padding(12.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(year, style = MaterialTheme.typography.bodySmall, color = Color(0xFF8D9199))
                    Text(badge, style = MaterialTheme.typography.labelSmall, color = Color(0xFFA4C9FE))
                }
            }
        }
    }

    @Composable
    private fun TrackChip(label: String, isSelected: Boolean) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color(0xFF1E2430))
                .border(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else Color(0x22FFFFFF), RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else Color(0xFFC3C6CF)
            )
        }
    }

    @Composable
    private fun CastAvatar(name: String, role: String) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF263245)),
                contentAlignment = Alignment.Center
            ) {
                Text(name.take(1), fontWeight = FontWeight.Bold, color = Color.White, fontSize = 20.sp)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(name, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = Color.White)
            Text(role, style = MaterialTheme.typography.bodySmall, color = Color(0xFF8D9199), fontSize = 11.sp)
        }
    }

    @Composable
    private fun ChapterItem(title: String, time: String, isCurrent: Boolean) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(if (isCurrent) Color(0xFF1E2D45) else Color.Transparent)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                color = if (isCurrent) Color(0xFFA4C9FE) else Color(0xFFC3C6CF)
            )
            Text(text = time, style = MaterialTheme.typography.labelSmall, color = Color(0xFF8D9199))
        }
    }

    @Composable
    private fun DownloadGridCard(title: String, size: String, status: String, bg: Color, modifier: Modifier = Modifier) {
        Column(
            modifier = modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF151922))
                .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(16.dp))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .background(bg),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = Color(0xFF81C784), modifier = Modifier.size(40.dp))
            }
            Column(modifier = Modifier.padding(14.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color.White)
                Text(size, style = MaterialTheme.typography.bodySmall, color = Color(0xFFA4C9FE))
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {},
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Play Offline", fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
    }

    @Composable
    private fun QueueItem(index: String, title: String, artist: String, duration: String, isPlaying: Boolean) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(if (isPlaying) Color(0xFF281E34) else Color.Transparent)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(index, style = MaterialTheme.typography.bodyMedium, color = if (isPlaying) Color(0xFFD9BDE3) else Color(0xFF8D9199))
                Column {
                    Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.SemiBold, color = if (isPlaying) Color(0xFFD9BDE3) else Color.White)
                    Text(artist, style = MaterialTheme.typography.bodySmall, color = Color(0xFF8D9199))
                }
            }
            Text(duration, style = MaterialTheme.typography.labelSmall, color = Color(0xFF8D9199))
        }
    }
}

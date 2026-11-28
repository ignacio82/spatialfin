package dev.spatialfin.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.SpeakerGroup
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.components.userPrimaryImageUri

@Composable
internal fun TvTopBar(
    currentRouteName: String,
    navItems: List<Pair<String, Pair<String, ImageVector>>>,
    onNavigate: (String) -> Unit,
    user: dev.jdtech.jellyfin.models.User?,
    serverName: String?,
    serverAddress: String?,
    showUserSwitcher: Boolean = true,
    showSignInAction: Boolean = false,
    onUserClick: () -> Unit,
    onSignInClick: () -> Unit = onUserClick,
    theaterRoomLabel: String? = null,
    onTheaterAudioClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(104.dp)
            .padding(start = 36.dp, end = 36.dp, top = 24.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            androidx.compose.foundation.Image(
                painter = painterResource(id = CoreR.drawable.ic_launcher_foreground),
                contentDescription = "SpatialFin",
                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)),
            )
        }

        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            navItems.forEach { (route, info) ->
                val (label, icon) = info
                val selected = currentRouteName == route
                var isFocused by remember { mutableStateOf(false) }
                Box(
                    modifier = Modifier
                        .onFocusChanged { isFocused = it.isFocused }
                        .tvFocus(isFocused, RoundedCornerShape(20.dp))
                        .clickable { onNavigate(route) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = if (selected || isFocused) MaterialTheme.colorScheme.primary else Color.White.copy(0.7f),
                        )
                        Text(
                            label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected || isFocused) Color.White else Color.White.copy(0.7f),
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }

        if (onTheaterAudioClick != null) {
            var isTheaterFocused by remember { mutableStateOf(false) }
            val theaterShape = RoundedCornerShape(20.dp)
            val isRoomPicked = !theaterRoomLabel.isNullOrBlank()
            Row(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .onFocusChanged { isTheaterFocused = it.isFocused }
                    .tvFocus(isTheaterFocused, theaterShape)
                    .clickable(onClick = onTheaterAudioClick)
                    .background(
                        if (isRoomPicked) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                        } else {
                            Color.White.copy(alpha = 0.06f)
                        },
                        theaterShape,
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.SpeakerGroup,
                    contentDescription = "Theater Audio",
                    modifier = Modifier.size(20.dp),
                    tint = if (isRoomPicked || isTheaterFocused) Color(0xFF4FC3F7) else Color.White.copy(alpha = 0.85f),
                )
                Text(
                    text = theaterRoomLabel ?: "Theater Audio",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isRoomPicked) Color(0xFF4FC3F7) else Color.White,
                    fontWeight = if (isRoomPicked) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }

        if (showSignInAction) {
            var isSignInFocused by remember { mutableStateOf(false) }
            val shape = RoundedCornerShape(20.dp)
            Row(
                modifier = Modifier
                    .padding(start = 12.dp)
                    .onFocusChanged { isSignInFocused = it.isFocused }
                    .tvFocus(isSignInFocused, shape)
                    .clickable(onClick = onSignInClick)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f), shape)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Rounded.Login, contentDescription = null, modifier = Modifier.size(20.dp), tint = Color.White)
                Text("Sign in", style = MaterialTheme.typography.labelLarge, color = Color.White)
            }
        } else if (showUserSwitcher) {
            var isUserFocused by remember { mutableStateOf(false) }
            val userShape = RoundedCornerShape(20.dp)
            Row(
                modifier = Modifier
                    .padding(start = 12.dp)
                    .onFocusChanged { isUserFocused = it.isFocused }
                    .tvFocus(isUserFocused, userShape)
                    .clickable(onClick = onUserClick)
                    .background(Color.White.copy(0.05f), userShape)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (user != null) {
                    val avatarUri = serverAddress?.let { userPrimaryImageUri(it, user.id) }
                    if (avatarUri != null) {
                        AsyncImage(
                            model = avatarUri,
                            contentDescription = "Profile",
                            modifier = Modifier.size(28.dp).clip(RoundedCornerShape(14.dp)),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        val initial = user.name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.secondaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(initial, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                    Text(user.name, style = MaterialTheme.typography.labelLarge, color = Color.White)
                } else {
                    Icon(Icons.Rounded.People, contentDescription = null, modifier = Modifier.size(24.dp), tint = Color.White)
                    Text("Sign in", style = MaterialTheme.typography.labelLarge, color = Color.White)
                }
            }
        }
    }
}

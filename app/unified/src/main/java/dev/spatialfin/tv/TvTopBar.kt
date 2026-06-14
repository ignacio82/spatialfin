package dev.spatialfin.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.People
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
import dev.jdtech.jellyfin.core.presentation.components.userPrimaryImageUri
import dev.spatialfin.R

@Composable
internal fun TvTopBar(
    currentRouteName: String,
    navItems: List<Pair<String, Pair<String, ImageVector>>>,
    onNavigate: (String) -> Unit,
    user: dev.jdtech.jellyfin.models.User?,
    serverName: String?,
    serverAddress: String?,
    onUserClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(104.dp)
            .padding(horizontal = 48.dp, vertical = 24.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Logo / Server Name
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            androidx.compose.foundation.Image(
                painter = painterResource(id = R.drawable.ic_launcher_foreground),
                contentDescription = "SpatialFin",
                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)),
            )
        }
        
        // Tabs
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            navItems.forEach { (route, info) ->
                val (label, icon) = info
                val selected = currentRouteName == route
                var isFocused by remember { mutableStateOf(false) }
                Box(
                    modifier = Modifier
                        .onFocusChanged { isFocused = it.isFocused }
                        .tvFocus(isFocused, RoundedCornerShape(20.dp))
                        .clickable { onNavigate(route) }
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = if (selected || isFocused) MaterialTheme.colorScheme.primary else Color.White.copy(0.7f))
                        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected || isFocused) Color.White else Color.White.copy(0.7f), fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        }
        
        // User Chip
        var isUserFocused by remember { mutableStateOf(false) }
        val userShape = RoundedCornerShape(20.dp)
        Row(
            modifier = Modifier
                .onFocusChanged { isUserFocused = it.isFocused }
                .tvFocus(isUserFocused, userShape)
                .clickable(onClick = onUserClick)
                .background(Color.White.copy(0.05f), userShape)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (user != null) {
                val avatarUri = serverAddress?.let { userPrimaryImageUri(it, user.id) }
                if (avatarUri != null) {
                    AsyncImage(
                        model = avatarUri,
                        contentDescription = "Profile",
                        modifier = Modifier.size(28.dp).clip(RoundedCornerShape(14.dp)),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    val initial = user.name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
                    Box(
                        modifier = Modifier.size(28.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center
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

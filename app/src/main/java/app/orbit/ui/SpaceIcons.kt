package app.orbit.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LocalCafe
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Newspaper
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.WorkOutline
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.orbit.core.Space

/** Icons a space can wear. Keys are persisted, so only ever append. */
val SpaceIconSet: List<Pair<String, ImageVector>> = listOf(
    "person" to Icons.Outlined.Person,
    "work" to Icons.Outlined.WorkOutline,
    "code" to Icons.Outlined.Code,
    "school" to Icons.Outlined.School,
    "book" to Icons.AutoMirrored.Outlined.MenuBook,
    "home" to Icons.Outlined.Home,
    "shopping" to Icons.Outlined.ShoppingBag,
    "travel" to Icons.Outlined.Flight,
    "games" to Icons.Outlined.SportsEsports,
    "music" to Icons.Outlined.MusicNote,
    "art" to Icons.Outlined.Palette,
    "science" to Icons.Outlined.Science,
    "news" to Icons.Outlined.Newspaper,
    "money" to Icons.Outlined.Savings,
    "coffee" to Icons.Outlined.LocalCafe,
    "pets" to Icons.Outlined.Pets,
    "heart" to Icons.Outlined.Favorite,
    "folder" to Icons.Outlined.Folder,
)

private val byKey = SpaceIconSet.toMap()

fun spaceIcon(key: String): ImageVector = byKey[key] ?: Icons.Outlined.Folder

@Composable
fun SpaceGlyph(space: Space?, size: Dp = 20.dp, tint: Color = Orb.Text, modifier: Modifier = Modifier) {
    Icon(spaceIcon(space?.icon ?: "folder"), space?.name, tint = tint, modifier = modifier.size(size))
}

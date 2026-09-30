package dev.digitalducktape.openrun

import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

internal data class RunPalette(val id:String,val name:String,val accent:Long,val background:Long,val surface:Long,val selected:Long)
internal val runPalettes=listOf(
    RunPalette("charcoal","Charcoal & lime",0xFFB7EF79,0xFF121416,0xFF202326,0xFF34393E),
    RunPalette("ocean","Midnight blue",0xFF8CCFFF,0xFF101722,0xFF1C2939,0xFF30455B),
    RunPalette("forest","Forest",0xFF94E3B0,0xFF101A15,0xFF1D2C23,0xFF344A3B),
    RunPalette("plum","Plum",0xFFD6B4FF,0xFF1A1420,0xFF2B2233,0xFF473851)
)
internal fun runPalette(id:String?)=runPalettes.firstOrNull { it.id==id } ?: runPalettes.first()

@Composable internal fun openRunColors(): androidx.compose.material3.ColorScheme {
    val app=LocalContext.current.applicationContext as OpenRunApplication
    val state=app.store.state.collectAsState().value
    return paletteColors(runPalette(state.profiles.firstOrNull { it.id==state.selectedId }?.colorScheme))
}
internal fun paletteColors(p:RunPalette)=darkColorScheme(
    primary=Color(p.accent), onPrimary=Color(p.background),
    background=Color(p.background), onBackground=Color(0xFFF5F6F7),
    surface=Color(p.surface), onSurface=Color(0xFFF5F6F7),
    surfaceVariant=Color(p.selected), onSurfaceVariant=Color(0xFFB9C0C5),
    secondary=Color(0xFFB9C0C5), onSecondary=Color(p.background),
    secondaryContainer=Color(p.selected), onSecondaryContainer=Color(0xFFF5F6F7),
    surfaceTint=Color(p.accent), outline=Color(0xFF7F898F)
)

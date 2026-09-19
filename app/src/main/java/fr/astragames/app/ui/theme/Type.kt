package fr.astragames.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.sp
import fr.astragames.app.R

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun family(resource: Int) = FontFamily(
    Font(resource, weight = FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(resource, weight = FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(resource, weight = FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(resource, weight = FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700)))
)
private val heading = family(R.font.geist)
private val body = family(R.font.inter)
private fun heading(size: Int, height: Int) = TextStyle(fontFamily = heading, fontWeight = FontWeight.Bold, fontSize = size.sp, lineHeight = height.sp, letterSpacing = (-.7).sp)
private fun body(size: Int, height: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(fontFamily = body, fontWeight = weight, fontSize = size.sp, lineHeight = height.sp)

val AstraTypography = Typography(
    displayLarge = heading(52, 58), displayMedium = heading(46, 50), displaySmall = heading(40, 46),
    headlineLarge = heading(34, 40), headlineMedium = heading(30, 36), headlineSmall = heading(24, 30),
    titleLarge = heading(19, 25), titleMedium = body(15, 21, FontWeight.SemiBold), titleSmall = body(13, 19, FontWeight.SemiBold),
    bodyLarge = body(15, 23), bodyMedium = body(13, 20), bodySmall = body(11, 17),
    labelLarge = body(13, 19, FontWeight.SemiBold), labelMedium = body(11, 16, FontWeight.Medium), labelSmall = body(10, 14, FontWeight.Medium)
)

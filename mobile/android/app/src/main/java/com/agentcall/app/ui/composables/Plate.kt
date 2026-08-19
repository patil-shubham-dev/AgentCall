package com.agentcall.app.ui.composables

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.agentcall.app.ui.theme.Radii
import com.agentcall.app.ui.theme.Slate700

/**
 * The standard card recipe: plate surface at ~0.9 alpha with a 1dp hairline
 * border and no heavy shadow. Shadows are reserved for sheets and dialogs.
 * Rendered as a button when [onClick] is provided.
 *
 * Parameter names deliberately avoid colliding with material3 `Surface`
 * parameter names (`shape`, `color`, `border`, `content`): the K2 compiler
 * misresolves same-named argument forwards against Surface's overload set
 * ("No value passed for parameter 'p1'").
 */
@Composable
fun Plate(
    modifier: Modifier = Modifier,
    containerShape: RoundedCornerShape = RoundedCornerShape(Radii.Panel),
    onClick: (() -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
    interactionSource: MutableInteractionSource? = null,
    plateContent: @Composable ColumnScope.() -> Unit,
) {
    val hairline = BorderStroke(1.dp, Slate700)
    if (onClick != null) {
        val src = interactionSource ?: remember { MutableInteractionSource() }
        val isPressed by src.collectIsPressedAsState()
        val pressScale by animateFloatAsState(
            targetValue = if (isPressed) 0.96f else 1f,
            animationSpec = spring(dampingRatio = 0.5f, stiffness = 800f),
            label = "platePress",
        )
        Surface(
            onClick = { onClick?.invoke() },
            modifier = modifier.scale(pressScale),
            shape = containerShape,
            color = containerColor,
            border = hairline,
            tonalElevation = 0.dp,
            interactionSource = src,
        ) {
            Column {
                plateContent()
            }
        }
    } else {
        Surface(
            modifier = modifier,
            shape = containerShape,
            color = containerColor,
            border = hairline,
            tonalElevation = 0.dp,
        ) {
            Column {
                plateContent()
            }
        }
    }
}

package com.agency.stickerjet.ui.common

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/**
 * Paints a transparency checkerboard behind content so that cut-out edges are visible. Cells are
 * [cell] wide; colours alternate between [colorA] and [colorB].
 */
fun Modifier.checkerboard(colorA: Color, colorB: Color, cell: Dp): Modifier = drawBehind {
    val cellPx = cell.toPx()
    drawRect(colorA)
    var row = 0
    var y = 0f
    while (y < size.height) {
        var column = 0
        var x = 0f
        while (x < size.width) {
            if ((row + column) % 2 == 1) {
                drawRect(colorB, topLeft = Offset(x, y), size = Size(cellPx, cellPx))
            }
            x += cellPx
            column++
        }
        y += cellPx
        row++
    }
}

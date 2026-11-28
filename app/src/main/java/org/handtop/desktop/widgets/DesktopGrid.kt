package org.handtop.desktop.widgets

import org.handtop.desktop.shell.Cell

/** How many cells something takes on the desktop's grid. */
data class CellSize(val columns: Int, val rows: Int)

/** Where everything on the desktop goes: the icons' cells and the widgets' top left cells, in their order. */
data class Layout(val icons: List<Cell>, val widgets: List<Cell>)

/**
 * The desktop's grid, shared by icons and widgets. Nothing here knows Android: the desktop
 * view asks where things go and draws them there.
 *
 * Order: icons the user put somewhere keep their cells; then the widgets, each where the user
 * put it or, when that does not fit this screen or is taken, the nearest place that fits;
 * then the icons without a place, column after column as always, around everything else. So a
 * widget does not jump when an icon is added, and never pushes an icon away.
 */
object DesktopGrid {

    /**
     * Lays out [iconCells] (each icon's saved cell, null for none) and [widgets] on a grid of
     * [columns] by [rows]. A saved place that does not fit is not forgotten: the inputs are not
     * changed, so on a larger screen things go back where they were. A widget with no room left
     * (counting the icons still to place) is put over the top left corner.
     */
    fun layOut(columns: Int, rows: Int, iconCells: List<Cell?>, widgets: List<PlacedWidget>): Layout {
        val taken = HashSet<Cell>()
        val icons = arrayOfNulls<Cell>(iconCells.size)
        iconCells.forEachIndexed { i, cell ->
            if (cell != null && cell.column in 0 until columns && cell.row in 0 until rows && taken.add(cell)) icons[i] = cell
        }
        val unplacedIcons = icons.count { it == null }
        val placed = widgets.map { w ->
            val saved = w.cell
            val at = when {
                saved != null && fits(columns, rows, w.kind.size, saved, taken) -> saved
                saved != null -> nearest(columns, rows, w.kind.size, saved, taken)
                else -> firstFree(columns, rows, w.kind.size, taken)
            } ?: firstFree(columns, rows, w.kind.size, taken)
            // The icons still to place need cells too: a widget that would leave them too few
            // goes over the top left corner instead, taking none, rather than push icons off.
            val left = columns * rows - taken.size - w.kind.size.columns * w.kind.size.rows
            if (at == null || left < unplacedIcons) return@map Cell(0, 0)
            taken += cellsOf(at, w.kind.size)
            at
        }
        var next = 0
        val result = icons.map { cell ->
            cell ?: run {
                while (Cell(next / rows, next % rows) in taken) next++
                Cell(next / rows, next % rows).also { taken += it }
            }
        }
        return Layout(result, placed)
    }

    /** The first place where [size] fits: rows from the top, in each row from the right. */
    fun firstFree(columns: Int, rows: Int, size: CellSize, taken: Set<Cell>): Cell? {
        for (row in 0..rows - size.rows) {
            for (column in columns - size.columns downTo 0) {
                val at = Cell(column, row)
                if (fits(columns, rows, size, at, taken)) return at
            }
        }
        return null
    }

    /** Whether [size] at [at] is inside the grid and on no [taken] cell. */
    fun fits(columns: Int, rows: Int, size: CellSize, at: Cell, taken: Set<Cell>): Boolean {
        if (at.column < 0 || at.row < 0 || at.column + size.columns > columns || at.row + size.rows > rows) return false
        return cellsOf(at, size).none { it in taken }
    }

    /** The cells [size] covers with its top left at [at]. */
    fun cellsOf(at: Cell, size: CellSize): Set<Cell> {
        val cells = HashSet<Cell>(size.columns * size.rows)
        for (c in 0 until size.columns) for (r in 0 until size.rows) cells += Cell(at.column + c, at.row + r)
        return cells
    }

    /** The place where [size] fits whose top left is closest to [to], or null. */
    private fun nearest(columns: Int, rows: Int, size: CellSize, to: Cell, taken: Set<Cell>): Cell? {
        var best: Cell? = null
        var bestDistance = Int.MAX_VALUE
        for (row in 0..rows - size.rows) {
            for (column in 0..columns - size.columns) {
                val at = Cell(column, row)
                if (!fits(columns, rows, size, at, taken)) continue
                val dc = column - to.column
                val dr = row - to.row
                val distance = dc * dc + dr * dr
                if (distance < bestDistance) {
                    best = at
                    bestDistance = distance
                }
            }
        }
        return best
    }
}

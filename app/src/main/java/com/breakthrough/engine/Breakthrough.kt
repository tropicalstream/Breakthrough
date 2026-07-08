package com.breakthrough.engine

import kotlin.math.abs

/**
 * Breakthrough (Dan Troyka, 2000) — winner of the 2001 8x8 Game Design
 * Competition. One kind of piece per side, on an 8x8 board:
 *  - move one square straight or diagonally FORWARD onto an empty square,
 *  - capture one square DIAGONALLY forward only (never straight),
 *  - win by landing any piece on the opponent's home row (a "breakthrough"),
 *    or by leaving the opponent with no move (all captured / fully blocked).
 * There are no draws.
 */
object P {
    const val EMPTY = 0
    const val WHITE = 1   // moves up the board (toward rank 7)
    const val BLACK = -1  // moves down the board (toward rank 0)
}

data class Move(val from: Int, val to: Int)

fun file(sq: Int) = sq and 7
fun rank(sq: Int) = sq shr 3
fun sqOf(f: Int, r: Int) = r * 8 + f
fun onBoard(f: Int, r: Int) = f in 0..7 && r in 0..7

sealed class MoveOutcome {
    class Legal(val move: Move) : MoveOutcome()
    class Illegal(val reason: String) : MoveOutcome()
}

class Board {
    val sq = IntArray(64)
    var side = P.WHITE

    fun clone(): Board {
        val b = Board()
        System.arraycopy(sq, 0, b.sq, 0, 64)
        b.side = side
        return b
    }

    fun whiteToMove() = side == P.WHITE

    private fun own(p: Int, white: Boolean) = p != 0 && (p > 0) == white

    /** All legal moves for the side to move (no self-check concept exists here). */
    fun generate(): MutableList<Move> {
        val moves = ArrayList<Move>(48)
        val white = whiteToMove()
        val dir = side
        for (from in 0 until 64) {
            val p = sq[from]
            if (p == 0 || (p > 0) != white) continue
            val f0 = file(from); val r0 = rank(from)
            val r1 = r0 + dir
            if (r1 !in 0..7) continue
            // Straight forward: only onto an empty square (never a capture).
            if (sq[sqOf(f0, r1)] == 0) moves.add(Move(from, sqOf(f0, r1)))
            // Diagonals: onto an empty square (move) or an enemy (capture).
            for (df in intArrayOf(-1, 1)) {
                val f1 = f0 + df
                if (f1 !in 0..7) continue
                val t = sq[sqOf(f1, r1)]
                if (t == 0 || (t > 0) != (p > 0)) moves.add(Move(from, sqOf(f1, r1)))
            }
        }
        return moves
    }

    fun legalMovesFrom(from: Int): List<Move> = generate().filter { it.from == from }

    /** Apply a move to a fresh board (captures overwrite; side flips). */
    fun applied(m: Move): Board {
        val b = clone()
        b.sq[m.to] = b.sq[m.from]
        b.sq[m.from] = 0
        b.side = -b.side
        return b
    }

    /** Has `white` landed a piece on the opponent's home row? */
    fun hasReachedGoal(white: Boolean): Boolean {
        val goalRank = if (white) 7 else 0
        val piece = if (white) P.WHITE else P.BLACK
        for (f in 0 until 8) if (sq[sqOf(f, goalRank)] == piece) return true
        return false
    }

    fun count(white: Boolean): Int {
        var n = 0
        for (i in 0 until 64) if (own(sq[i], white)) n++
        return n
    }

    /** Most-advanced piece for `white` (nearest its goal); -1 if none. */
    fun spearheadSquare(white: Boolean): Int {
        var best = -1
        var bestAdv = -1
        for (i in 0 until 64) {
            if (!own(sq[i], white)) continue
            val adv = if (white) rank(i) else 7 - rank(i)
            if (adv > bestAdv) { bestAdv = adv; best = i }
        }
        return best
    }

    /** Does `white` have a piece one step from breaking through? (danger flag) */
    fun aboutToBreakThrough(white: Boolean): Boolean {
        val nearRank = if (white) 6 else 1
        val piece = if (white) P.WHITE else P.BLACK
        for (f in 0 until 8) if (sq[sqOf(f, nearRank)] == piece) return true
        return false
    }

    // ------------------------------------------------ explain a user move

    fun classify(from: Int, to: Int): MoveOutcome {
        val piece = sq[from]
        if (piece == 0) return MoveOutcome.Illegal("There's no piece on that square.")
        if ((piece > 0) != whiteToMove()) return MoveOutcome.Illegal("That's not your piece to move.")
        val legal = generate().firstOrNull { it.from == from && it.to == to }
        if (legal != null) return MoveOutcome.Legal(legal)
        return MoveOutcome.Illegal(reason(from, to, piece))
    }

    private fun reason(from: Int, to: Int, piece: Int): String {
        val dir = if (piece > 0) 1 else -1
        val df = file(to) - file(from)
        val dr = rank(to) - rank(from)
        val ownThere = sq[to] != 0 && (sq[to] > 0) == (piece > 0)
        return when {
            dr == 0 -> "Pieces move forward, not sideways."
            (dr > 0) != (dir > 0) -> "Pieces only move forward, never backward."
            abs(dr) > 1 -> "Pieces move just one square at a time."
            df == 0 && ownThere -> "Your own piece blocks the square ahead."
            df == 0 && sq[to] != 0 -> "You can't capture straight ahead — captures are diagonal."
            abs(df) > 1 -> "That's too far sideways — one diagonal step only."
            abs(df) == 1 && ownThere -> "Your own piece is on that square."
            else -> "That move isn't allowed."
        }
    }

    companion object {
        fun initial(): Board {
            val b = Board()
            for (f in 0 until 8) {
                b.sq[sqOf(f, 0)] = P.WHITE
                b.sq[sqOf(f, 1)] = P.WHITE
                b.sq[sqOf(f, 6)] = P.BLACK
                b.sq[sqOf(f, 7)] = P.BLACK
            }
            return b
        }
    }
}

package com.breakthrough.engine

import kotlin.math.abs
import kotlin.random.Random

/**
 * Difficulty tiers. Depth sets strength; `blunder` lets the easy tiers pick a
 * near-best (or random) move so a new player can win.
 */
enum class Difficulty(
    val label: String, val depth: Int, val blunderChance: Float,
    val poolMargin: Int, val budgetMs: Long,
) {
    RECRUIT("1 · Recruit", 2, 0.55f, 240, 800),
    SCOUT("2 · Scout", 3, 0.28f, 130, 1200),
    SOLDIER("3 · Soldier", 4, 0.10f, 70, 1700),
    CAPTAIN("4 · Captain", 5, 0.0f, 0, 2300),
    GENERAL("5 · General", 6, 0.0f, 0, 2800);

    companion object {
        fun from(i: Int) = entries[i.coerceIn(0, entries.size - 1)]
    }
}

class AI {
    @Volatile private var deadline = 0L
    @Volatile private var aborted = false
    private val rng = Random(System.nanoTime())

    fun bestMove(board: Board, diff: Difficulty): Move? {
        val legal = board.generate()
        if (legal.isEmpty()) return null
        if (legal.size == 1) return legal[0]
        // Take an immediate winning move without deliberating.
        for (m in legal) if (rank(m.to) == goalRank(board.whiteToMove())) return m
        deadline = System.currentTimeMillis() + diff.budgetMs
        aborted = false

        var scored = legal.map { it to 0 }
        for (d in 1..diff.depth) {
            val ordered = scored.sortedByDescending { it.second }.map { it.first }
            val res = ArrayList<Pair<Move, Int>>(ordered.size)
            for (m in ordered) {
                if (System.currentTimeMillis() > deadline) { aborted = true; break }
                res.add(m to -search(board.applied(m), d - 1, -INF, INF, 1))
            }
            if (!aborted && res.isNotEmpty()) scored = res
            if (aborted) break
            if (scored.any { abs(it.second) > MATE - 100 }) break
        }

        val best = scored.maxByOrNull { it.second } ?: return legal.random(rng)
        if (diff.blunderChance > 0f && rng.nextFloat() < diff.blunderChance) {
            val pool = scored.filter { best.second - it.second <= diff.poolMargin }
            return (if (pool.isNotEmpty()) pool else scored).random(rng).first
        }
        return scored.filter { it.second == best.second }.random(rng).first
    }

    private fun goalRank(white: Boolean) = if (white) 7 else 0

    private fun search(board: Board, depth: Int, a0: Int, beta: Int, ply: Int): Int {
        if (aborted || System.currentTimeMillis() > deadline) { aborted = true; return 0 }
        // If the opponent already broke through on the previous move, we've lost.
        if (board.hasReachedGoal(!board.whiteToMove())) return -(MATE - ply)
        val moves = board.generate()
        if (moves.isEmpty()) return -(MATE - ply) // no move = loss (blocked / wiped out)
        if (depth == 0) return evalNega(board)

        order(board, moves)
        var alpha = a0
        var best = -INF
        for (m in moves) {
            val s = -search(board.applied(m), depth - 1, -beta, -alpha, ply + 1)
            if (s > best) best = s
            if (best > alpha) alpha = best
            if (alpha >= beta) break
        }
        return best
    }

    /** Winning moves and captures first — cheap, big pruning win. */
    private fun order(board: Board, moves: List<Move>) {
        val white = board.whiteToMove()
        val goal = goalRank(white)
        (moves as? ArrayList<Move>)?.sortByDescending { m ->
            var s = 0
            if (rank(m.to) == goal) s += 10000
            if (board.sq[m.to] != 0) s += 500
            s + if (white) rank(m.to) else 7 - rank(m.to)
        }
    }

    /** Static eval from the side-to-move's perspective (negamax). */
    private fun evalNega(board: Board): Int {
        var white = 0
        var black = 0
        val s = board.sq
        for (i in 0 until 64) {
            val p = s[i]
            if (p == 0) continue
            val f = file(i); val r = rank(i)
            if (p > 0) {
                white += BASE + ADV * r + urgency(r) + defenders(s, f, r, true)
            } else {
                val adv = 7 - r
                black += BASE + ADV * adv + urgency(adv) + defenders(s, f, r, false)
            }
        }
        return (white - black) * board.side
    }

    // Steeply rising value as a piece nears the goal row.
    private fun urgency(adv: Int) = when (adv) { 6 -> 140; 5 -> 45; 4 -> 14; else -> 0 }

    // Friendly pieces diagonally behind that could recapture — structure matters.
    private fun defenders(s: IntArray, f: Int, r: Int, white: Boolean): Int {
        val back = if (white) r - 1 else r + 1
        if (back !in 0..7) return 0
        val friend = if (white) P.WHITE else P.BLACK
        var n = 0
        if (f - 1 >= 0 && s[sqOf(f - 1, back)] == friend) n++
        if (f + 1 <= 7 && s[sqOf(f + 1, back)] == friend) n++
        return n * DEF
    }

    companion object {
        private const val INF = 1_000_000
        private const val MATE = 30_000
        private const val BASE = 100
        private const val ADV = 8
        private const val DEF = 10
    }
}

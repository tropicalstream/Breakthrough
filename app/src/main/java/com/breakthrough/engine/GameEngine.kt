package com.breakthrough.engine

import com.breakthrough.SettingsStore
import com.breakthrough.audio.Audio
import kotlin.math.max

enum class GameState { MENU, PLAYING, THINKING, OVER }

interface GameHost {
    fun applySettings()
    fun sound(id: Int, pitch: Float = 1f, vol: Float = 1f)
}

/**
 * Drives a game of Breakthrough against [AI]. Human moves come from a
 * swipe-driven cursor + click; illegal attempts are explained rather than
 * silently rejected, and a legal move only commits after the cursor has rested
 * on the target (dwell-to-commit) so a finicky X3 swipe can't misfire. The AI
 * runs on a background thread, its move consumed on the main thread under a
 * generation guard.
 */
class GameEngine(val store: SettingsStore, val host: GameHost) {

    var state = GameState.MENU
        private set
    var settingsOpen = false
        private set
    val settingsMenu = SettingsMenu(this, store)

    val particles = ParticleSystem()

    var board = Board.initial()
        private set
    var humanWhite = true
        private set
    var difficulty = Difficulty.SCOUT
        private set

    var cursor = 8
    var selected = -1
        private set
    var targets = HashSet<Int>()
        private set
    var lastFrom = -1
        private set
    var lastTo = -1
        private set
    var oppFrom = -1
        private set
    var oppTo = -1
        private set

    var cursorSince = 0f
        private set
    val dwellProgress get() = ((time - cursorSince) / DWELL).coerceIn(0f, 1f)
    val moveArmed get() = time - cursorSince >= DWELL

    var invalidMsg: String? = null
        private set
    var invalidIsHint = false
        private set
    var invalidT = 0f
    var statusMsg = ""
        private set
    var resultMsg = ""
        private set

    var speedOn = false
        private set
    var whiteMs = 0L
        private set
    var blackMs = 0L
        private set

    var time = 0f
    var menuDiff = 1
    var thinkPulse = 0f

    private val history = ArrayList<Board>()
    private val ai = AI()
    private var aiGen = 0
    @Volatile private var pendingMove: Move? = null
    @Volatile private var pendingGen = -1
    private var wasDanger = false

    val flip get() = !humanWhite

    fun boot() {
        menuDiff = store.difficulty
        val saved = store.savedGame
        if (saved != null && runCatching { resume(saved) }.getOrDefault(false)) return
        toMenu()
    }

    // ------------------------------------------------------------- loop

    fun update(dt: Float) {
        time += dt
        thinkPulse = (thinkPulse + dt) % 1f
        particles.update(dt)
        if (invalidT > 0f) { invalidT -= dt; if (invalidT <= 0f) invalidMsg = null }
        if (settingsOpen) return

        val pm = pendingMove
        if (pm != null && pendingGen == aiGen && state == GameState.THINKING) {
            pendingMove = null
            history.add(board.clone())
            doMove(pm)
            oppFrom = pm.from; oppTo = pm.to
            if (state == GameState.THINKING) {
                state = GameState.PLAYING
                // Center on your most-advanced piece — the spearhead of the race.
                cursor = board.spearheadSquare(humanWhite).let { if (it >= 0) it else cursor }
                cursorSince = time
            }
            if (state != GameState.OVER) persist()
        }

        if (speedOn && (state == GameState.PLAYING || state == GameState.THINKING)) {
            val white = board.whiteToMove()
            if (white) whiteMs = max(0L, whiteMs - (dt * 1000).toLong())
            else blackMs = max(0L, blackMs - (dt * 1000).toLong())
            val low = if (white) whiteMs else blackMs
            if (low in 1..10000 && time % 1f < dt) host.sound(Audio.LOWTIME, 1.2f, 0.6f)
            if ((white && whiteMs <= 0L) || (!white && blackMs <= 0L)) flagFall(white)
        }
    }

    private fun flagFall(whiteFlagged: Boolean) {
        val humanFlagged = whiteFlagged == humanWhite
        endGame(if (humanFlagged) "Out of time — you lose." else "Your opponent flagged. You win!", !humanFlagged)
    }

    // ------------------------------------------------------------- input

    fun cursorMove(dir: Int) {
        if (state != GameState.PLAYING) return
        var f = file(cursor); var r = rank(cursor)
        when (dir) {
            0 -> if (!flip) r++ else r--
            1 -> if (!flip) r-- else r++
            2 -> if (!flip) f-- else f++
            3 -> if (!flip) f++ else f--
        }
        if (onBoard(f, r)) {
            cursor = sqOf(f, r)
            cursorSince = time
            host.sound(Audio.TICK, 1.4f, 0.5f)
        }
    }

    fun click() {
        when {
            settingsOpen -> settingsMenu.activate()
            state == GameState.MENU -> startGame()
            state == GameState.PLAYING -> boardClick()
            state == GameState.OVER -> toMenu()
            else -> {}
        }
    }

    private fun boardClick() {
        if (board.whiteToMove() != humanWhite) return
        val p = board.sq[cursor]
        if (selected == -1) {
            when {
                p != 0 && (p > 0) == humanWhite -> selectSquare(cursor)
                p != 0 -> setInvalid("That's your opponent's piece — tap one of yours.")
                else -> setInvalid("No piece there. Tap one of your own pieces first.")
            }
            return
        }
        if (cursor == selected) { deselect(); return }
        if (p != 0 && (p > 0) == humanWhite) { selectSquare(cursor); return }
        when (val out = board.classify(selected, cursor)) {
            is MoveOutcome.Legal -> {
                if (!moveArmed) {
                    setHint("Rest on the square a moment, then tap to move.")
                    host.sound(Audio.TICK, 0.9f, 0.6f)
                    return
                }
                applyHuman(out.move)
            }
            is MoveOutcome.Illegal -> {
                setInvalid(out.reason)
                host.sound(Audio.ILLEGAL)
            }
        }
    }

    private fun selectSquare(sq: Int) {
        selected = sq
        targets = HashSet(board.legalMovesFrom(sq).map { it.to })
        invalidMsg = null
        cursorSince = time
        host.sound(Audio.SELECT)
    }

    private fun deselect() {
        selected = -1
        targets = HashSet()
    }

    private fun applyHuman(m: Move) {
        history.add(board.clone())
        oppFrom = -1; oppTo = -1
        deselect()
        doMove(m)
        if (state == GameState.PLAYING) { state = GameState.THINKING; requestAi() }
        if (state != GameState.OVER) persist()
    }

    // --------------------------------------------------------------- move

    private fun doMove(m: Move) {
        val capture = board.sq[m.to] != 0
        board = board.applied(m)
        lastFrom = m.from; lastTo = m.to
        cursor = m.to
        cursorSince = time
        if (capture) {
            host.sound(Audio.CAPTURE)
            particles.burst(sqCenterX(m.to), sqCenterY(m.to), 0xFFFFC060.toInt(), 1f)
        } else host.sound(Audio.MOVE)
        evaluatePosition(m)
    }

    private fun evaluatePosition(m: Move) {
        val stmWhite = board.whiteToMove()   // side to move now
        val moverWhite = !stmWhite            // side that just moved
        val humanWon = moverWhite == humanWhite

        val reachedGoal = board.hasReachedGoal(moverWhite)
        val stmStuck = board.count(stmWhite) == 0 || board.generate().isEmpty()

        when {
            reachedGoal -> {
                particles.burst(sqCenterX(m.to), sqCenterY(m.to), 0xFF9CFFB0.toInt(), 1.6f)
                endGame(
                    if (humanWon) "You broke through — you win!" else "Your opponent broke through. You lose.",
                    humanWon
                )
            }
            stmStuck -> endGame(
                if (humanWon) "Your opponent is trapped. You win!" else "You're out of moves. You lose.",
                humanWon
            )
            else -> {
                val danger = board.aboutToBreakThrough(!humanWhite)
                statusMsg = when {
                    danger -> "Danger — block the breakthrough!"
                    board.aboutToBreakThrough(humanWhite) -> "You're one step from breaking through!"
                    else -> ""
                }
                // Alert on the rising edge of danger (enemy just got a piece in).
                if (danger && !wasDanger) host.sound(Audio.CHECK, 1f, 0.85f)
                wasDanger = danger
            }
        }
    }

    private fun endGame(msg: String, humanWon: Boolean) {
        resultMsg = msg
        statusMsg = ""
        state = GameState.OVER
        aiGen++
        pendingMove = null
        store.clearSavedGame()
        if (humanWon) { store.wins++; host.sound(Audio.WIN) }
        else { store.losses++; host.sound(Audio.LOSE) }
    }

    // ----------------------------------------------------------- AI hook

    private fun requestAi() {
        aiGen++
        val myGen = aiGen
        val snapshot = board.clone()
        val diff = difficulty
        Thread {
            val mv = ai.bestMove(snapshot, diff)
            if (mv != null) { pendingMove = mv; pendingGen = myGen }
        }.start()
    }

    // -------------------------------------------------------- navigation

    fun startGame() {
        humanWhite = store.playerWhite
        difficulty = Difficulty.from(store.difficulty)
        board = Board.initial()
        history.clear()
        deselect()
        lastFrom = -1; lastTo = -1
        oppFrom = -1; oppTo = -1
        statusMsg = ""; resultMsg = ""; invalidMsg = null
        wasDanger = false
        cursor = board.spearheadSquare(humanWhite).let { if (it >= 0) it else if (humanWhite) 8 else 55 }
        cursorSince = time
        store.clearSavedGame()
        particles.clear()
        speedOn = store.speedSeconds > 0
        whiteMs = store.speedSeconds * 1000L
        blackMs = store.speedSeconds * 1000L
        settingsOpen = false
        aiGen++
        pendingMove = null
        state = GameState.PLAYING
        host.sound(Audio.SELECT)
        if (board.whiteToMove() != humanWhite) { state = GameState.THINKING; requestAi() }
    }

    fun toMenu() {
        state = GameState.MENU
        settingsOpen = false
        aiGen++
        pendingMove = null
        particles.clear()
        menuDiff = store.difficulty
    }

    fun newGame() = startGame()
    fun restart() = startGame()

    fun undo() {
        if (history.isEmpty() || state == GameState.MENU) return
        aiGen++
        pendingMove = null
        var pops = 0
        while (history.isNotEmpty() && pops < 2) {
            board = history.removeAt(history.size - 1)
            pops++
            if (board.whiteToMove() == humanWhite) break
        }
        deselect()
        lastFrom = -1; lastTo = -1
        oppFrom = -1; oppTo = -1
        cursorSince = time
        resultMsg = ""
        statusMsg = ""
        state = GameState.PLAYING
        host.sound(Audio.TICK)
    }

    fun resign() {
        if (state == GameState.PLAYING || state == GameState.THINKING) endGame("You resigned.", false)
    }

    // -------------------------------------------------------- settings/UI

    fun doubleTap() {
        settingsOpen = !settingsOpen
        if (settingsOpen) settingsMenu.onOpen()
        host.sound(if (settingsOpen) Audio.SELECT else Audio.TICK)
    }

    fun onBack(): Boolean {
        if (settingsOpen) { doubleTap(); return true }
        return when (state) {
            GameState.PLAYING, GameState.THINKING -> { doubleTap(); true }
            GameState.OVER -> { toMenu(); true }
            else -> false
        }
    }

    /** One discrete swipe gesture, classified on finger-up (dir 0 up,1 down,2 left,3 right). */
    fun swipeDir(dir: Int) {
        if (settingsOpen) { settingsMenu.onDir(dir); return }
        when (state) {
            GameState.PLAYING -> cursorMove(dir)
            GameState.MENU -> if (dir == 2 || dir == 3) {
                menuDiff = (menuDiff + (if (dir == 3) 1 else -1)).coerceIn(0, 4)
                store.difficulty = menuDiff
                host.sound(Audio.TICK)
            }
            else -> {}
        }
    }

    fun onAppPause() {
        persist()
        if ((state == GameState.PLAYING || state == GameState.THINKING) && !settingsOpen) {
            settingsOpen = true
            settingsMenu.onOpen()
        }
    }

    private fun setInvalid(msg: String) { invalidMsg = msg; invalidIsHint = false; invalidT = 2.6f }
    private fun setHint(msg: String) { invalidMsg = msg; invalidIsHint = true; invalidT = 1.6f }

    // ------------------------------------------------------- persistence

    private fun serialize(): String {
        val sb = StringBuilder("1|")
        sb.append(board.sq.joinToString(",")).append("|")
        sb.append(board.side).append("|")
        sb.append(if (humanWhite) 1 else 0).append(",").append(difficulty.ordinal).append(",")
            .append(if (speedOn) 1 else 0).append(",").append(whiteMs).append(",").append(blackMs).append("|")
        sb.append(oppFrom).append(",").append(oppTo)
        return sb.toString()
    }

    private fun resume(s: String): Boolean {
        val parts = s.split("|")
        if (parts.size < 5 || parts[0] != "1") return false
        val sqs = parts[1].split(",").map { it.toInt() }
        if (sqs.size != 64) return false
        val b = Board()
        for (i in 0 until 64) b.sq[i] = sqs[i]
        b.side = parts[2].toInt()
        val cfg = parts[3].split(",")
        humanWhite = cfg[0] == "1"
        difficulty = Difficulty.from(cfg[1].toInt())
        speedOn = cfg[2] == "1"
        whiteMs = cfg[3].toLong(); blackMs = cfg[4].toLong()
        val opp = parts[4].split(",")
        oppFrom = opp[0].toInt(); oppTo = opp[1].toInt()

        board = b
        history.clear()
        deselect()
        lastFrom = -1; lastTo = -1
        invalidMsg = null; resultMsg = ""; statusMsg = ""
        wasDanger = false
        cursor = board.spearheadSquare(humanWhite).let { if (it >= 0) it else if (humanWhite) 8 else 55 }
        cursorSince = time
        aiGen++
        pendingMove = null
        state = if (board.whiteToMove() == humanWhite) GameState.PLAYING else GameState.THINKING
        if (state == GameState.THINKING) requestAi()
        return true
    }

    private fun persist() {
        if (state == GameState.PLAYING || state == GameState.THINKING) store.savedGame = serialize()
    }

    // --------------------------------------------------- board geometry

    companion object {
        const val BOARD_X = 144f
        const val BOARD_Y = 64f
        const val SQ = 44f
        const val DWELL = 2f
    }

    fun col(sq: Int) = if (!flip) file(sq) else 7 - file(sq)
    fun row(sq: Int) = if (!flip) 7 - rank(sq) else rank(sq)
    fun sqCenterX(sq: Int) = BOARD_X + col(sq) * SQ + SQ / 2
    fun sqCenterY(sq: Int) = BOARD_Y + row(sq) * SQ + SQ / 2
}

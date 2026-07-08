package com.breakthrough

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.breakthrough.engine.Difficulty
import com.breakthrough.engine.GameEngine
import com.breakthrough.engine.GameState
import com.breakthrough.engine.file
import com.breakthrough.engine.rank
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** All drawing in 640x480 logical space on pure black (waveguide = black transparent). */
class Renderer(private val engine: GameEngine, private val store: SettingsStore) {

    private val W = 640f
    private val H = 480f
    private val BX = GameEngine.BOARD_X
    private val BY = GameEngine.BOARD_Y
    private val SQ = GameEngine.SQ

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textP = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val rf = RectF()
    private val chevron = Path()

    private val lightSq = 0xFF33456A.toInt()
    private val darkSq = 0xFF1B2740.toInt()
    private val whiteFill = 0xFFEDEFF6.toInt()
    private val whiteRim = 0xFF2C3E5E.toInt()
    private val blackFill = 0xFF2A3247.toInt()
    private val blackRim = 0xFFBBC6DA.toInt()
    private val goalWhite = 0xFF9CFFB0.toInt()   // tint on the row each side is racing to

    fun draw(c: Canvas, w: Int, h: Int) {
        c.drawColor(Color.BLACK)
        if (w <= 0 || h <= 0) return
        val s = min(w / W, h / H)
        c.save()
        c.translate((w - W * s) / 2f, (h - H * s) / 2f)
        c.scale(s, s)

        if (engine.state == GameState.MENU) drawMenu(c)
        else {
            drawBoard(c)
            drawPieces(c)
            drawOppTrail(c)
            drawHud(c)
            drawParticles(c)
            if (engine.state == GameState.OVER) drawOver(c)
            drawInvalid(c)
        }
        if (engine.settingsOpen) drawSettings(c)
        c.restore()
    }

    // ------------------------------------------------------------- board

    private fun drawBoard(c: Canvas) {
        rf.set(BX - 8f, BY - 8f, BX + 8 * SQ + 8f, BY + 8 * SQ + 8f)
        stroke.strokeWidth = 6f; stroke.color = Color.argb(60, 100, 150, 220)
        c.drawRoundRect(rf, 8f, 8f, stroke)
        stroke.strokeWidth = 2f; stroke.color = Color.argb(200, 140, 190, 245)
        c.drawRoundRect(rf, 8f, 8f, stroke)

        for (sqi in 0 until 64) {
            val col = engine.col(sqi); val row = engine.row(sqi)
            val x = BX + col * SQ; val y = BY + row * SQ
            val light = (file(sqi) + rank(sqi)) % 2 == 1
            fill.shader = null
            fill.color = if (light) lightSq else darkSq
            c.drawRect(x, y, x + SQ, y + SQ, fill)
        }
        // Goal rows: the two home rows each side is trying to reach (rank 0 & 7).
        tintRow(c, 7, engine.humanWhite)   // white's goal
        tintRow(c, 0, !engine.humanWhite)   // black's goal

        if (engine.lastFrom >= 0) {
            highlightSquare(c, engine.lastFrom, Color.argb(70, 255, 220, 90))
            highlightSquare(c, engine.lastTo, Color.argb(90, 255, 220, 90))
        }
        if (engine.selected >= 0) {
            highlightSquare(c, engine.selected, Color.argb(110, 90, 230, 120))
            if (store.showLegal) {
                for (t in engine.targets) {
                    val cx = engine.sqCenterX(t); val cy = engine.sqCenterY(t)
                    fill.shader = null
                    if (engine.board.sq[t] != 0) {
                        stroke.strokeWidth = 3f; stroke.color = Color.argb(180, 255, 150, 90)
                        c.drawCircle(cx, cy, SQ * 0.42f, stroke)
                    } else {
                        fill.color = Color.argb(150, 90, 230, 120)
                        c.drawCircle(cx, cy, 6f, fill)
                    }
                }
            }
        }
        if (store.showCoords) {
            for (i in 0 until 8) {
                val fileChar = ('a' + if (!engine.flip) i else 7 - i)
                text(c, fileChar.toString(), BX + i * SQ + SQ - 7f, BY + 8 * SQ + 11f, 9f, Color.argb(150, 150, 180, 220))
                val rankChar = (if (!engine.flip) 8 - i else i + 1).toString()
                text(c, rankChar, BX - 9f, BY + i * SQ + 13f, 9f, Color.argb(150, 150, 180, 220))
            }
        }
        // Cursor + dwell ring.
        val cx = BX + engine.col(engine.cursor) * SQ
        val cy = BY + engine.row(engine.cursor) * SQ
        val pulse = (180 + 75 * sin(engine.time * 5f)).toInt().coerceIn(80, 255)
        rf.set(cx + 2f, cy + 2f, cx + SQ - 2f, cy + SQ - 2f)
        stroke.strokeWidth = 3f; stroke.color = Color.argb(pulse, 255, 255, 255)
        c.drawRoundRect(rf, 6f, 6f, stroke)
        if (engine.selected >= 0 && engine.cursor in engine.targets) {
            val ccx = cx + SQ / 2; val ccy = cy + SQ / 2
            rf.set(ccx - SQ * 0.46f, ccy - SQ * 0.46f, ccx + SQ * 0.46f, ccy + SQ * 0.46f)
            stroke.strokeWidth = 4f
            if (engine.moveArmed) {
                val pz = (200 + 55 * sin(engine.time * 6f)).toInt().coerceIn(120, 255)
                stroke.color = Color.argb(pz, 120, 240, 150)
                c.drawArc(rf, -90f, 360f, false, stroke)
            } else {
                stroke.color = Color.argb(90, 255, 220, 140)
                c.drawArc(rf, -90f, 360f, false, stroke)
                stroke.color = Color.argb(230, 255, 210, 120)
                c.drawArc(rf, -90f, 360f * engine.dwellProgress, false, stroke)
            }
        }
    }

    private fun tintRow(c: Canvas, boardRank: Int, forWhite: Boolean) {
        fill.shader = null
        val col = if (forWhite) goalWhite else 0xFFFF6A6A.toInt()
        fill.color = Color.argb(28, Color.red(col), Color.green(col), Color.blue(col))
        for (f in 0 until 8) {
            val sqi = boardRank * 8 + f
            val x = BX + engine.col(sqi) * SQ; val y = BY + engine.row(sqi) * SQ
            c.drawRect(x, y, x + SQ, y + SQ, fill)
        }
    }

    private fun highlightSquare(c: Canvas, sqi: Int, color: Int) {
        val x = BX + engine.col(sqi) * SQ; val y = BY + engine.row(sqi) * SQ
        fill.shader = null; fill.color = color
        c.drawRect(x, y, x + SQ, y + SQ, fill)
    }

    private fun drawPieces(c: Canvas) {
        for (sqi in 0 until 64) {
            val p = engine.board.sq[sqi]
            if (p == 0) continue
            val white = p > 0
            drawPiece(c, engine.sqCenterX(sqi), engine.sqCenterY(sqi), SQ * 0.8f, white, pointUp = white != engine.flip)
        }
    }

    /** A glossy stone with a chevron showing the direction it advances. */
    private fun drawPiece(c: Canvas, cx: Float, cy: Float, size: Float, white: Boolean, pointUp: Boolean) {
        val r = size * 0.42f
        val body = if (white) whiteFill else blackFill
        val rim = if (white) whiteRim else blackRim
        fill.shader = null
        fill.color = rim; c.drawCircle(cx, cy, r, fill)
        fill.color = body; c.drawCircle(cx, cy, r * 0.84f, fill)
        fill.color = Color.argb(70, 255, 255, 255)
        c.drawCircle(cx - r * 0.28f, cy - r * 0.30f, r * 0.30f, fill)
        // Direction chevron.
        val cw = r * 0.44f; val ch = r * 0.42f
        val ay = if (pointUp) cy - ch else cy + ch
        val by = if (pointUp) cy + ch * 0.35f else cy - ch * 0.35f
        chevron.reset()
        chevron.moveTo(cx - cw, by); chevron.lineTo(cx, ay); chevron.lineTo(cx + cw, by)
        stroke.color = rim; stroke.strokeWidth = max(2f, r * 0.17f)
        stroke.strokeCap = Paint.Cap.ROUND; stroke.strokeJoin = Paint.Join.ROUND
        c.drawPath(chevron, stroke)
    }

    private fun drawOppTrail(c: Canvas) {
        if (engine.oppFrom < 0 || engine.oppTo < 0) return
        val x1 = engine.sqCenterX(engine.oppFrom); val y1 = engine.sqCenterY(engine.oppFrom)
        val x2 = engine.sqCenterX(engine.oppTo); val y2 = engine.sqCenterY(engine.oppTo)
        val dx = x2 - x1; val dy = y2 - y1
        val len = hypot(dx, dy)
        val n = (len / 13f).toInt().coerceAtLeast(2)
        fill.shader = null
        for (i in 1 until n) {
            val t = i.toFloat() / n
            val tw = sin(engine.time * 4f - i * 0.5f) * 0.5f + 0.5f
            fill.color = Color.argb((110 + 120 * tw).toInt().coerceIn(60, 255), 255, 158, 96)
            c.drawCircle(x1 + dx * t, y1 + dy * t, 2.4f + tw * 1.4f, fill)
        }
        val ang = atan2(dy, dx); val ah = 9f
        chevron.reset()
        chevron.moveTo(x2, y2)
        chevron.lineTo(x2 - ah * cos(ang - 0.42f), y2 - ah * sin(ang - 0.42f))
        chevron.lineTo(x2 - ah * cos(ang + 0.42f), y2 - ah * sin(ang + 0.42f))
        chevron.close()
        fill.color = Color.argb(235, 255, 172, 104)
        c.drawPath(chevron, fill)
    }

    // --------------------------------------------------------------- HUD

    private fun drawHud(c: Canvas) {
        val turnText: String; val turnColor: Int
        when {
            engine.state == GameState.THINKING -> {
                val dots = ".".repeat(1 + ((engine.time * 2).toInt() % 3))
                turnText = "${engine.difficulty.label} thinking$dots"
                turnColor = Color.argb(255, 255, 200, 100)
            }
            engine.statusMsg.startsWith("Danger") -> { turnText = "DANGER — BLOCK IT!"; turnColor = Color.argb(255, 255, 90, 90) }
            engine.statusMsg.startsWith("You're one") -> { turnText = "ONE STEP TO WIN!"; turnColor = Color.argb(255, 156, 255, 176) }
            else -> { turnText = "Your move"; turnColor = Color.argb(255, 150, 235, 170) }
        }
        if (engine.state != GameState.OVER) text(c, turnText, W / 2f, 34f, 16f, turnColor, glow = Color.argb(90, 60, 120, 200))

        val you = if (engine.humanWhite) "White" else "Black"
        text(c, "CPU", 570f, 78f, 13f, Color.argb(255, 255, 150, 150))
        text(c, engine.difficulty.label.substringAfter("· ").ifEmpty { engine.difficulty.label }, 570f, 96f, 11f, Color.argb(220, 220, 200, 200))
        if (engine.speedOn) drawClock(c, 570f, 122f, if (engine.humanWhite) engine.blackMs else engine.whiteMs, engine.state == GameState.THINKING)
        drawPiece(c, 548f, 150f, 22f, !engine.humanWhite, pointUp = false)
        text(c, "×${engine.board.count(!engine.humanWhite)}", 576f, 155f, 15f, Color.WHITE, Paint.Align.LEFT)

        text(c, "YOU", 570f, 336f, 13f, Color.argb(255, 150, 235, 170))
        text(c, you, 570f, 354f, 11f, Color.argb(220, 200, 224, 235))
        if (engine.speedOn) drawClock(c, 570f, 380f, if (engine.humanWhite) engine.whiteMs else engine.blackMs, engine.state == GameState.PLAYING)
        drawPiece(c, 548f, 408f, 22f, engine.humanWhite, pointUp = true)
        text(c, "×${engine.board.count(engine.humanWhite)}", 576f, 413f, 15f, Color.WHITE, Paint.Align.LEFT)

        // Left panel: the race — how far each spearhead has advanced (0..6).
        drawRace(c)
        text(c, "W ${store.wins}  L ${store.losses}", 72f, 432f, 10f, Color.argb(200, 160, 185, 220))
    }

    private fun advance(white: Boolean): Int {
        val sq = engine.board.spearheadSquare(white)
        if (sq < 0) return 0
        return if (white) rank(sq) else 7 - rank(sq)
    }

    private fun drawRace(c: Canvas) {
        val top = 120f; val bot = 360f
        text(c, "RACE", 72f, 104f, 11f, Color.argb(210, 160, 190, 225))
        val you = advance(engine.humanWhite) / 7f
        val cpu = advance(!engine.humanWhite) / 7f
        drawRaceBar(c, 54f, top, bot, you, 0xFF7CFF9C.toInt(), "you")
        drawRaceBar(c, 92f, top, bot, cpu, 0xFFFF7A7A.toInt(), "cpu")
    }

    private fun drawRaceBar(c: Canvas, x: Float, top: Float, bot: Float, frac: Float, color: Int, label: String) {
        stroke.strokeWidth = 2f; stroke.color = Color.argb(120, 130, 160, 205)
        rf.set(x - 9f, top, x + 9f, bot)
        c.drawRoundRect(rf, 5f, 5f, stroke)
        val h = (bot - top - 4f) * frac.coerceIn(0f, 1f)
        if (h > 1f) {
            fill.shader = null; fill.color = color
            rf.set(x - 7f, bot - 2f - h, x + 7f, bot - 2f)
            c.drawRoundRect(rf, 4f, 4f, fill)
        }
        text(c, label, x, bot + 13f, 9f, Color.argb(200, 170, 195, 225))
    }

    private fun drawClock(c: Canvas, cx: Float, y: Float, ms: Long, active: Boolean) {
        val totalSec = (ms / 1000).toInt(); val mm = totalSec / 60; val ss = totalSec % 60
        val low = ms in 1..10000
        val col = when {
            low -> Color.argb((150 + 100 * sin(engine.time * 8f)).toInt().coerceIn(60, 255), 255, 80, 80)
            active -> Color.argb(255, 255, 240, 180)
            else -> Color.argb(200, 170, 195, 230)
        }
        rf.set(cx - 42f, y - 15f, cx + 42f, y + 9f)
        fill.shader = null
        fill.color = if (active) Color.argb(140, 40, 60, 100) else Color.argb(80, 24, 36, 60)
        c.drawRoundRect(rf, 6f, 6f, fill)
        text(c, "%d:%02d".format(mm, ss), cx, y + 2f, 16f, col)
    }

    // -------------------------------------------------------- overlays

    private fun drawInvalid(c: Canvas) {
        val msg = engine.invalidMsg ?: return
        val a = (engine.invalidT / 2.6f).coerceIn(0f, 1f)
        val alpha = (min(1f, a * 3f) * 255).toInt()
        val hint = engine.invalidIsHint
        rf.set(150f, 436f, 490f, 466f)
        fill.shader = null
        fill.color = if (hint) Color.argb((alpha * 0.85f).toInt(), 54, 44, 16) else Color.argb((alpha * 0.85f).toInt(), 60, 20, 24)
        c.drawRoundRect(rf, 12f, 12f, fill)
        stroke.strokeWidth = 2f
        stroke.color = if (hint) Color.argb((alpha * 0.9f).toInt(), 255, 200, 110) else Color.argb((alpha * 0.9f).toInt(), 255, 110, 110)
        c.drawRoundRect(rf, 12f, 12f, stroke)
        text(c, msg, 320f, 456f, 12.5f, if (hint) Color.argb(alpha, 255, 230, 180) else Color.argb(alpha, 255, 220, 210))
    }

    private fun drawOver(c: Canvas) {
        dim(c, 150)
        panel(c, 130f, 170f, 510f, 320f)
        val win = engine.resultMsg.contains("win", true)
        val col = if (win) Color.argb(255, 150, 235, 170) else Color.argb(255, 255, 120, 120)
        text(c, if (win) "BREAKTHROUGH!" else "DEFEAT", 320f, 218f, 30f, col, glow = Color.argb(140, 40, 90, 150))
        text(c, engine.resultMsg, 320f, 254f, 13f, Color.argb(255, 210, 224, 245))
        text(c, "TAP FOR MENU", 320f, 296f, 15f, Color.argb((170 + 85 * sin(engine.time * 4f)).toInt().coerceIn(60, 255), 200, 230, 255))
    }

    private fun drawMenu(c: Canvas) {
        // Motif: a defensive line with one piece breaking through it.
        for (i in 0 until 5) drawPiece(c, 220f + i * 50f, 150f, 30f, false, pointUp = false)
        drawPiece(c, 320f, 96f, 40f, true, pointUp = true)
        stroke.strokeWidth = 2f; stroke.color = Color.argb(120, 120, 160, 210)
        c.drawLine(196f, 172f, 296f, 172f, stroke)
        c.drawLine(344f, 172f, 444f, 172f, stroke)

        textP.setShadowLayer(16f, 0f, 0f, Color.argb(180, 60, 150, 255))
        text(c, "BREAKTHROUGH", 320f, 232f, 40f, 0xFFEAF3FF.toInt())
        textP.clearShadowLayer()
        text(c, "race to the far row · one piece is enough", 320f, 258f, 12f, Color.argb(220, 150, 190, 235))

        val d = Difficulty.from(engine.menuDiff)
        text(c, "‹  ${d.label}  ›", 320f, 306f, 22f, Color.WHITE, glow = Color.argb(140, 80, 150, 255))
        text(c, "playing as ${if (store.playerWhite) "White" else "Black"} · speed ${store.speedLabel}", 320f, 332f, 12f, Color.argb(255, 255, 224, 120))
        text(c, "record   W ${store.wins}   L ${store.losses}", 320f, 356f, 12f, Color.argb(220, 156, 255, 176))

        text(c, "swipe ↔ difficulty   •   tap to play", 320f, 400f, 13f, Color.argb((170 + 85 * sin(engine.time * 3f)).toInt().coerceIn(60, 255), 200, 230, 255))
        text(c, "double-tap for settings", 320f, 422f, 11f, Color.argb(160, 150, 175, 210))
    }

    // --------------------------------------------------------- settings

    private fun drawSettings(c: Canvas) {
        dim(c, 188)
        panel(c, 138f, 34f, 502f, 446f)
        text(c, "SETTINGS", 320f, 64f, 20f, Color.WHITE, glow = Color.argb(160, 80, 150, 255))
        val menu = engine.settingsMenu
        val visible = 10
        val start = (menu.selected - visible / 2).coerceIn(0, (menu.items.size - visible).coerceAtLeast(0))
        var y = 96f
        for (i in start until min(start + visible, menu.items.size)) {
            val item = menu.items[i]
            val sel = i == menu.selected
            if (sel) {
                fill.shader = null; fill.color = Color.argb(210, 36, 64, 106)
                rf.set(150f, y - 15f, 490f, y + 8f)
                c.drawRoundRect(rf, 8f, 8f, fill)
            }
            text(c, item.label, 166f, y, 13f, if (sel) Color.WHITE else Color.argb(255, 159, 180, 208), Paint.Align.LEFT)
            val v = item.value()
            if (v.isNotEmpty()) {
                val shown = if (sel && item.adjust != null) "‹ $v ›" else v
                text(c, shown, 474f, y, 13f, if (sel) Color.argb(255, 255, 224, 128) else Color.argb(255, 120, 144, 176), Paint.Align.RIGHT)
            }
            y += 33f
        }
        if (start > 0) text(c, "▲", 320f, 86f, 10f, Color.argb(180, 150, 180, 220))
        if (start + visible < menu.items.size) text(c, "▼", 320f, 430f, 10f, Color.argb(180, 150, 180, 220))
        text(c, "swipe ↕ select   ↔ adjust   tap OK   double-tap close", 320f, 462f, 10.5f, Color.argb(200, 150, 175, 210))
    }

    // ------------------------------------------------------ fx & helpers

    private fun drawParticles(c: Canvas) {
        for (pt in engine.particles.list) {
            val k = (pt.life / pt.maxLife).coerceIn(0f, 1f)
            val alpha = (k * 255).toInt()
            if (pt.ring) {
                stroke.strokeWidth = 1.5f + 3f * k; stroke.color = pt.color; stroke.alpha = alpha
                c.drawCircle(pt.x, pt.y, pt.size * (1f + (1f - k) * 2f), stroke)
            } else {
                fill.shader = null; fill.color = pt.color; fill.alpha = alpha
                c.drawCircle(pt.x, pt.y, pt.size * (0.4f + 0.6f * k), fill)
            }
        }
        stroke.alpha = 255; fill.alpha = 255
    }

    private fun dim(c: Canvas, a: Int) {
        fill.shader = null; fill.color = Color.argb(a, 0, 0, 0)
        c.drawRect(0f, 0f, W, H, fill)
    }

    private fun panel(c: Canvas, l: Float, t: Float, r: Float, b: Float) {
        rf.set(l, t, r, b)
        fill.shader = null; fill.color = Color.argb(236, 12, 22, 42)
        c.drawRoundRect(rf, 16f, 16f, fill)
        stroke.strokeWidth = 2f; stroke.color = Color.argb(200, 95, 134, 200)
        c.drawRoundRect(rf, 16f, 16f, stroke)
    }

    private fun text(
        c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int,
        align: Paint.Align = Paint.Align.CENTER, glow: Int = 0,
    ) {
        textP.textSize = size
        textP.textAlign = align
        textP.color = color
        if (glow != 0) textP.setShadowLayer(size * 0.4f, 0f, 0f, glow) else textP.clearShadowLayer()
        c.drawText(s, x, y, textP)
        textP.clearShadowLayer()
    }
}

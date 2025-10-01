package com.example.mywhiteboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Simple multi-touch draw view.
 */
class DrawView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    // Base paint settings used for new strokes
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 12f
    }

    // internal data class to keep path + paint together
    private data class Stroke(val path: Path, val paint: Paint)

    // finished strokes
    private val strokes = mutableListOf<Stroke>()

    // active pointerId -> Path
    private val activePaths = mutableMapOf<Int, Path>()

    // active pointerId -> Paint (clone of basePaint at touch start)
    private val activePaints = mutableMapOf<Int, Paint>()

    // temporary canvas background color
    private var backgroundColor = Color.WHITE

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // clear background
        canvas.drawColor(backgroundColor)

        // draw finished strokes
        for (s in strokes) {
            canvas.drawPath(s.path, s.paint)
        }

        // draw currently active paths
        for ((pid, path) in activePaths) {
            val p = activePaints[pid] ?: basePaint
            canvas.drawPath(path, p)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked

        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                val pointerId = event.getPointerId(index)
                val x = event.getX(index)
                val y = event.getY(index)

                // start a new path for this pointer
                val path = Path().apply { moveTo(x, y) }
                activePaths[pointerId] = path

                // clone the base paint so subsequent changes do not affect this stroke
                activePaints[pointerId] = Paint(basePaint)
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> {
                // update each active pointer path
                for (i in 0 until event.pointerCount) {
                    val pointerId = event.getPointerId(i)
                    val path = activePaths[pointerId]
                    if (path != null) {
                        val x = event.getX(i)
                        val y = event.getY(i)
                        path.lineTo(x, y)
                    }
                }
                invalidate()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val index = event.actionIndex
                val pointerId = event.getPointerId(index)
                val path = activePaths[pointerId]
                val paint = activePaints[pointerId]

                if (path != null && paint != null) {
                    // commit finished path to strokes list (store paint copy)
                    strokes.add(Stroke(Path(path), Paint(paint)))
                }

                // remove active mappings
                activePaths.remove(pointerId)
                activePaints.remove(pointerId)
                invalidate()
            }
        }
        return true
    }
}
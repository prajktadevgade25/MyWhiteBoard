package com.example.mywhiteboard

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Simple multi-touch draw view with PEN default and SHAPE mode support.
 */
class DrawView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    enum class Mode { PEN, ERASER, SHAPE, TEXT }
    enum class Shape { RECT, CIRCLE, LINE, POLYGON }

    // Base paint settings used for new strokes/shapes
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

    // For SHAPE mode: store start point per pointer (x,y)
    private val shapeStart = mutableMapOf<Int, Pair<Float, Float>>()

    // background
    private var backgroundColor = Color.WHITE

    var mode: Mode = Mode.PEN          // default = PEN
    var shape: Shape = Shape.RECT      // the current shape when mode == SHAPE

    // stroke properties that affect new strokes
    var strokeColor: Int
        get() = basePaint.color
        set(value) {
            basePaint.color = value
        }

    var strokeWidth: Float
        get() = basePaint.strokeWidth
        set(value) {
            basePaint.strokeWidth = value
        }

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

                when (mode) {
                    Mode.PEN, Mode.ERASER, Mode.TEXT -> {
                        // start a freehand path
                        val path = Path().apply { moveTo(x, y) }
                        activePaths[pointerId] = path
                        activePaints[pointerId] = Paint(basePaint)
                    }

                    Mode.SHAPE -> {
                        // record start point; create an empty preview path
                        shapeStart[pointerId] = Pair(x, y)
                        activePaints[pointerId] = Paint(basePaint)
                        activePaths[pointerId] = Path()
                    }
                }
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> {
                // update each active pointer path
                for (i in 0 until event.pointerCount) {
                    val pid = event.getPointerId(i)
                    val x = event.getX(i)
                    val y = event.getY(i)

                    when (mode) {
                        Mode.PEN, Mode.ERASER, Mode.TEXT -> {
                            val path = activePaths[pid]
                            if (path != null) {
                                path.lineTo(x, y)
                            }
                        }

                        Mode.SHAPE -> {
                            val start = shapeStart[pid]
                            if (start != null) {
                                val (sx, sy) = start
                                // build a preview path for current shape from (sx,sy) to (x,y)
                                val preview = Path()
                                when (shape) {
                                    Shape.RECT -> {
                                        preview.addRect(
                                            RectF(
                                                minOf(sx, x),
                                                minOf(sy, y),
                                                maxOf(sx, x),
                                                maxOf(sy, y)
                                            ), Path.Direction.CW
                                        )
                                    }

                                    Shape.CIRCLE -> {
                                        val left = minOf(sx, x)
                                        val top = minOf(sy, y)
                                        val right = maxOf(sx, x)
                                        val bottom = maxOf(sy, y)

                                        // center point of the rectangle
                                        val cx = (left + right) / 2f
                                        val cy = (top + bottom) / 2f

                                        // radius = half of the smaller side (so it fits inside the rectangle)
                                        val radius = minOf(right - left, bottom - top) / 2f

                                        preview.addCircle(cx, cy, radius, Path.Direction.CW)
                                    }

                                    Shape.LINE -> {
                                        preview.moveTo(sx, sy)
                                        preview.lineTo(x, y)
                                    }

                                    Shape.POLYGON -> {
                                        val left = minOf(sx, x)
                                        val top = minOf(sy, y)
                                        val right = maxOf(sx, x)
                                        val bottom = maxOf(sy, y)

                                        val cx = (left + right) / 2f
                                        val cy = (top + bottom) / 2f
                                        val radius = minOf(right - left, bottom - top) / 2f

                                        // Generate 5 vertices of a regular pentagon
                                        val path = Path()
                                        for (i in 0..4) {
                                            val angle =
                                                Math.toRadians((270 + i * 72).toDouble()) // start at top
                                            val px = (cx + radius * Math.cos(angle)).toFloat()
                                            val py = (cy + radius * Math.sin(angle)).toFloat()
                                            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                                        }
                                        path.close()
                                        preview.addPath(path)
                                    }
                                }
                                activePaths[pid] = preview
                            }
                        }
                    }
                }
                invalidate()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val index = event.actionIndex
                val pointerId = event.getPointerId(index)
                val x = event.getX(index)
                val y = event.getY(index)

                when (mode) {
                    Mode.PEN, Mode.ERASER, Mode.TEXT -> {
                        val path = activePaths[pointerId]
                        val paint = activePaints[pointerId]
                        if (path != null && paint != null) {
                            // commit freehand stroke
                            strokes.add(Stroke(Path(path), Paint(paint)))
                        }
                    }

                    Mode.SHAPE -> {
                        val start = shapeStart[pointerId]
                        val paint = activePaints[pointerId]
                        if (start != null && paint != null) {
                            val (sx, sy) = start
                            val committed = Path()
                            when (shape) {
                                Shape.RECT -> {
                                    committed.addRect(
                                        RectF(
                                            minOf(sx, x), minOf(sy, y), maxOf(sx, x), maxOf(sy, y)
                                        ), Path.Direction.CW
                                    )
                                }

                                Shape.CIRCLE -> {
                                    val left = minOf(sx, x)
                                    val top = minOf(sy, y)
                                    val right = maxOf(sx, x)
                                    val bottom = maxOf(sy, y)

                                    val cx = (left + right) / 2f
                                    val cy = (top + bottom) / 2f
                                    val radius = minOf(right - left, bottom - top) / 2f

                                    committed.addCircle(cx, cy, radius, Path.Direction.CW)
                                }

                                Shape.LINE -> {
                                    committed.moveTo(sx, sy)
                                    committed.lineTo(x, y)
                                }

                                Shape.POLYGON -> {
                                    val left = minOf(sx, x)
                                    val top = minOf(sy, y)
                                    val right = maxOf(sx, x)
                                    val bottom = maxOf(sy, y)

                                    val cx = (left + right) / 2f
                                    val cy = (top + bottom) / 2f
                                    val radius = minOf(right - left, bottom - top) / 2f

                                    // Generate 5 vertices of a regular pentagon
                                    val path = Path()
                                    for (i in 0..4) {
                                        val angle =
                                            Math.toRadians((270 + i * 72).toDouble()) // start at top
                                        val px = (cx + radius * Math.cos(angle)).toFloat()
                                        val py = (cy + radius * Math.sin(angle)).toFloat()
                                        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                                    }
                                    path.close()
                                    committed.addPath(path)
                                }
                            }
                            strokes.add(Stroke(committed, Paint(paint)))
                        }
                        // cleanup shape state
                        shapeStart.remove(pointerId)
                    }
                }

                // remove active mappings for this pointer
                activePaths.remove(pointerId)
                activePaints.remove(pointerId)
                invalidate()
            }
        }
        return true
    }
}
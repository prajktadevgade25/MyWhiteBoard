package com.example.mywhiteboard

import android.content.Context
import android.graphics.*
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.appcompat.content.res.AppCompatResources
import kotlin.math.*

/**
 * Model representing a single shape on the whiteboard.
 * 'color' is the stroke (border) color. 'fillEnabled' + 'fillColor' control filling.
 */
data class ShapeModel(
    var id: String,
    var kind: DrawView.Shape,
    var left: Float,
    var top: Float,
    var right: Float,
    var bottom: Float,
    var rotation: Float = 0f,
    var color: Int,
    var strokeWidth: Float = 6f,
    var fillEnabled: Boolean = false,
    var fillColor: Int = Color.TRANSPARENT
) {
    fun centerX() = (left + right) / 2f
    fun centerY() = (top + bottom) / 2f
    fun width() = right - left
    fun height() = bottom - top
}

/**
 * DrawView: freehand strokes, shapes (create/select/transform/delete) and eraser with per-shape properties.
 */
class DrawView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    // public enums
    enum class Mode { PEN, ERASER, SHAPE, TEXT }
    enum class Shape { RECT, CIRCLE, LINE, POLYGON }

    // dp helper
    private val Float.dp: Float get() = this * resources.displayMetrics.density

    // ---------- base paint for freehand strokes ----------
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 12f
    }

    // ---------- delete icon (vector or raster) ----------
    private var deleteBitmapScaled: Bitmap? = null
    private val deleteSizeDp: Float
        get() = 32f.dp
    private val deleteHitRadius: Float
        get() = (deleteSizeDp / 2f) * 1.15f

    // ---------- stroke / shape defaults ----------
    var mode: Mode = Mode.PEN
    var shape: Shape = Shape.RECT

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

    var shapeDefaultWidth: Float = 0f
    var shapeDefaultHeight: Float = 0f

    var shapeBorderColor: Int = Color.BLACK
    var shapeBorderWidth: Float = 8f

    var shapeFillColor: Int = Color.TRANSPARENT
    var shapeFillEnabled: Boolean = false

    // eraser settings
    var eraserRadiusDp: Float = 24f
    private val eraserRadiusPx: Float get() = eraserRadiusDp.dp

    // preview paints
    private val eraserPreviewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.BLACK; alpha = 120
    }
    private val eraserPreviewBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.BLACK; alpha = 120; strokeWidth = 1f.dp
    }

    // shadow/overlay paints for trace visualization
    // thick stroke along trace centerline (rounded)
    private val eraserShadowStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.GRAY
        alpha = 110
    }

    // dot paint for recorded points (smaller filled circles)
    private val eraserShadowDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.GRAY
        alpha = 110
    }

    // throttle for recording points (avoid too many points)
    private val eraseRecordDistancePx: Float
        get() = (eraserRadiusPx * 0.25f).coerceAtLeast(6f)

    // internal stroke bookkeeping (vector)
    private data class Stroke(var points: MutableList<PointF>, val paint: Paint)

    private val strokes = mutableListOf<Stroke>()
    private val activePaths = mutableMapOf<Int, Path>()
    private val activePaints = mutableMapOf<Int, Paint>()
    private val activePoints =
        mutableMapOf<Int, MutableList<PointF>>() // pointer -> stroke points (for pen)

    // eraser trace per pointer: list of recorded centers while moving
    private val eraserTrace = mutableMapOf<Int, MutableList<PointF>>()

    private val shapeStart = mutableMapOf<Int, Pair<Float, Float>>()

    // ---------- shapes and selection ----------
    private val shapes = mutableListOf<ShapeModel>()
    private var _selectedShape: ShapeModel? = null
    private fun setSelected(s: ShapeModel?) {
        _selectedShape = s
        selectionListener?.invoke(getSelectedShape())
        invalidate()
    }

    fun getSelectedShape(): ShapeModel? = _selectedShape?.copy()

    private var selectionListener: ((ShapeModel?) -> Unit)? = null
    fun setOnSelectionChangedListener(listener: (ShapeModel?) -> Unit) {
        selectionListener = listener
    }

    // ---------- paints for rendering shapes and selection ----------
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLUE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(16f, 10f), 0f)
    }
    private val handleFillPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.WHITE }
    private val handleBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.BLUE; strokeWidth = 2f
    }
    private val rotateHandlePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.YELLOW }

    private val HANDLE_SIZE = 14f.dp
    private val ROTATE_HANDLE_DISTANCE = 36f.dp

    // ---------- hit/transform tracking ----------
    private enum class HitType { NONE, INSIDE, ROTATE, HANDLE_LT, HANDLE_T, HANDLE_RT, HANDLE_R, HANDLE_RB, HANDLE_B, HANDLE_LB, HANDLE_L }

    private var activeHit = HitType.NONE
    private var activePointerId = -1
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    // ---------- helper: load drawable (vector supported) into bitmap of desired size ----------
    private fun drawableToBitmap(drawable: Drawable, size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        return bmp
    }

    private fun ensureDeleteBitmap() {
        val size = deleteSizeDp.toInt().coerceAtLeast(1)
        val cur = deleteBitmapScaled
        if (cur != null && cur.width == size && cur.height == size) return

        val drawable: Drawable? = try {
            AppCompatResources.getDrawable(context, R.drawable.ic_delete)
        } catch (e: Exception) {
            try {
                context.resources.getDrawable(R.drawable.ic_delete, context.theme)
            } catch (_: Exception) {
                null
            }
        }

        deleteBitmapScaled = drawable?.let { drawableToBitmap(it, size) }
    }

    // ---------- drawing ----------
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.WHITE)

        // draw strokes (rebuild path from points)
        for (s in strokes) {
            val p = buildPathFromPoints(s.points)
            canvas.drawPath(p, s.paint)
        }

        // draw shapes
        for (s in shapes) drawShapeModel(canvas, s)

        // draw previews (activePaths)
        for ((pid, path) in activePaths) {
            val p = activePaints[pid] ?: basePaint
            canvas.drawPath(path, p)
        }

        // draw eraser trace shadow overlay for each active trace
        // configure paints with proper width based on eraserRadius
        eraserShadowStrokePaint.strokeWidth = eraserRadiusPx * 2f
        eraserShadowDotPaint.strokeWidth = 1f

        for ((_, trace) in eraserTrace) {
            if (trace.size == 0) continue

            // draw a smooth thick stroke along the trace centerline
            if (trace.size >= 2) {
                val tracePath = Path()
                tracePath.moveTo(trace[0].x, trace[0].y)
                for (i in 1 until trace.size) tracePath.lineTo(trace[i].x, trace[i].y)
                canvas.drawPath(tracePath, eraserShadowStrokePaint)
            }

            // draw dots (extra visibility) — optional, keeps overlay visible when single point
            for (pt in trace) {
                canvas.drawCircle(pt.x, pt.y, eraserRadiusPx * 0.6f, eraserShadowDotPaint)
            }
        }

        // eraser preview: draw the translucent circle at the lastTouch for feedback
        if (mode == Mode.ERASER) {
            val centerX = lastTouchX
            val centerY = lastTouchY
            canvas.drawCircle(centerX, centerY, eraserRadiusPx, eraserPreviewPaint)
            canvas.drawCircle(centerX, centerY, eraserRadiusPx, eraserPreviewBorder)
        }

        // draw selection if any
        _selectedShape?.let { drawSelection(canvas, it) }
    }

    private fun buildPathFromPoints(points: MutableList<PointF>): Path {
        val path = Path()
        if (points.isEmpty()) return path
        path.moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) path.lineTo(points[i].x, points[i].y)
        return path
    }

    private fun drawShapeModel(canvas: Canvas, s: ShapeModel) {
        val save = canvas.save()
        val cx = s.centerX();
        val cy = s.centerY()
        canvas.rotate(s.rotation, cx, cy)

        if (s.fillEnabled) {
            fillPaint.color = s.fillColor
            canvas.drawPath(buildShapePath(s), fillPaint)
        }
        strokePaint.color = s.color
        strokePaint.strokeWidth = s.strokeWidth
        strokePaint.style = Paint.Style.STROKE
        canvas.drawPath(buildShapePath(s), strokePaint)

        canvas.restoreToCount(save)
    }

    private fun buildShapePath(s: ShapeModel): Path {
        val p = Path()
        when (s.kind) {
            Shape.RECT -> p.addRect(RectF(s.left, s.top, s.right, s.bottom), Path.Direction.CW)
            Shape.CIRCLE -> {
                val cx = s.centerX();
                val cy = s.centerY()
                val r = min(s.width(), s.height()) / 2f
                p.addCircle(cx, cy, r, Path.Direction.CW)
            }

            Shape.LINE -> {
                p.moveTo(s.left, s.top); p.lineTo(s.right, s.bottom)
            }

            Shape.POLYGON -> {
                val cx = s.centerX();
                val cy = s.centerY()
                val r = min(s.width(), s.height()) / 2f
                for (i in 0..4) {
                    val ang = Math.toRadians((270 + i * 72).toDouble())
                    val px = (cx + r * cos(ang)).toFloat()
                    val py = (cy + r * sin(ang)).toFloat()
                    if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
                }
                p.close()
            }
        }
        return p
    }

    private fun drawSelection(canvas: Canvas, s: ShapeModel) {
        val save = canvas.save()
        val cx = s.centerX();
        val cy = s.centerY()
        canvas.rotate(s.rotation, cx, cy)
        val rect = RectF(s.left, s.top, s.right, s.bottom)

        // dashed rect
        canvas.drawRect(rect, selectionPaint)

        // handles
        val handles = getHandlePoints(rect)
        for (pt in handles) {
            val left = pt.x - HANDLE_SIZE / 2f
            val top = pt.y - HANDLE_SIZE / 2f
            val r = RectF(left, top, left + HANDLE_SIZE, top + HANDLE_SIZE)
            canvas.drawRect(r, handleFillPaint)
            canvas.drawRect(r, handleBorderPaint)
        }

        // rotate handle (top center)
        val topCenter = PointF((rect.left + rect.right) / 2f, rect.top - ROTATE_HANDLE_DISTANCE)
        canvas.drawCircle(topCenter.x, topCenter.y, HANDLE_SIZE / 2f, rotateHandlePaint)
        canvas.drawCircle(topCenter.x, topCenter.y, HANDLE_SIZE / 2f, handleBorderPaint)

        // delete - draw red circular background then drawable on top
        val localDelX = rect.right + 8f.dp + (deleteSizeDp / 2f)
        val localDelY = rect.top - 8f.dp - (deleteSizeDp / 2f)

        val redBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.TRANSPARENT; style = Paint.Style.FILL
        }
        canvas.drawCircle(localDelX, localDelY, deleteSizeDp / 2f, redBgPaint)
        ensureDeleteBitmap()
        deleteBitmapScaled?.let { bmp ->
            val left = localDelX - bmp.width / 2f
            val top = localDelY - bmp.height / 2f
            canvas.drawBitmap(bmp, left, top, null)
        } ?: run {
            // fallback: white X
            val xPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; strokeWidth = 3f.dp; style = Paint.Style.STROKE
            }
            val cross = 8f.dp
            canvas.drawLine(
                localDelX - cross, localDelY - cross, localDelX + cross, localDelY + cross, xPaint
            )
            canvas.drawLine(
                localDelX - cross, localDelY + cross, localDelX + cross, localDelY - cross, xPaint
            )
        }

        canvas.restoreToCount(save)
    }

    private fun getHandlePoints(rect: RectF): List<PointF> = listOf(
        PointF(rect.left, rect.top),
        PointF((rect.left + rect.right) / 2f, rect.top),
        PointF(rect.right, rect.top),
        PointF(rect.right, (rect.top + rect.bottom) / 2f),
        PointF(rect.right, rect.bottom),
        PointF((rect.left + rect.right) / 2f, rect.bottom),
        PointF(rect.left, rect.bottom),
        PointF(rect.left, (rect.top + rect.bottom) / 2f)
    )

    // ---------- touch handling ----------
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        // quick delete-check on ACTION_DOWN (use same coords as drawSelection)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            activePointerId = event.getPointerId(0)
            lastTouchX = x; lastTouchY = y
            _selectedShape?.let { shape ->
                val rect = RectF(shape.left, shape.top, shape.right, shape.bottom)
                val localDelX = rect.right + 8f.dp + (deleteSizeDp / 2f)
                val localDelY = rect.top - 8f.dp - (deleteSizeDp / 2f)
                // rotate this local point to screen coords
                val cx = shape.centerX();
                val cy = shape.centerY()
                val ang = Math.toRadians(shape.rotation.toDouble())
                val cosA = cos(ang);
                val sinA = sin(ang)
                val dx = localDelX - cx;
                val dy = localDelY - cy
                val screenDelX = (cx + (dx * cosA - dy * sinA)).toFloat()
                val screenDelY = (cy + (dx * sinA + dy * cosA)).toFloat()

                if (distance(x, y, screenDelX, screenDelY) <= deleteHitRadius) {
                    shapes.remove(shape)
                    setSelected(null)
                    return true
                }
            }
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                val pid = event.getPointerId(idx)
                val px = event.getX(idx)
                val py = event.getY(idx)

                activePointerId = pid
                lastTouchX = px; lastTouchY = py

                when (mode) {
                    Mode.PEN, Mode.TEXT -> {
                        activePoints[pid] = mutableListOf(PointF(px, py))
                        activePaths[pid] = Path().apply { moveTo(px, py) }
                        activePaints[pid] = Paint(basePaint)
                    }

                    Mode.ERASER -> {
                        // start recording eraser trace (no deletion yet)
                        eraserTrace[pid] = mutableListOf(PointF(px, py))
                        activePaths[pid] =
                            Path().apply { moveTo(px, py) } // used for minimal preview
                        activePaints[pid] =
                            Paint().apply { style = Paint.Style.STROKE; strokeWidth = 1f }
                        lastTouchX = px; lastTouchY = py
                    }

                    Mode.SHAPE -> {
                        val hit = hitTestShape(px, py)
                        if (hit != null && hit.shape != null) {
                            setSelected(hit.shape)
                            activeHit = hit.hitType
                        } else {
                            shapeStart[pid] = Pair(px, py)
                            activePaints[pid] = Paint(basePaint)
                            activePaths[pid] = Path()
                            setSelected(null)
                            activeHit = HitType.NONE
                        }
                    }
                }
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val pid = event.getPointerId(i)
                    val px = event.getX(i)
                    val py = event.getY(i)

                    when (mode) {
                        Mode.PEN, Mode.TEXT -> {
                            activePoints[pid]?.add(PointF(px, py))
                            activePaths[pid]?.lineTo(px, py)
                        }

                        Mode.ERASER -> {
                            // record eraser trace but throttle by distance
                            val trace = eraserTrace[pid]
                            if (trace == null) {
                                eraserTrace[pid] = mutableListOf(PointF(px, py))
                            } else {
                                val last = trace.last()
                                if (distance(px, py, last.x, last.y) >= eraseRecordDistancePx) {
                                    trace.add(PointF(px, py))
                                }
                            }
                            // update preview center
                            activePaths[pid]?.reset(); activePaths[pid]?.moveTo(px, py)
                            lastTouchX = px; lastTouchY = py
                        }

                        Mode.SHAPE -> {
                            val start = shapeStart[pid]
                            if (start != null) {
                                val (sx, sy) = start
                                val preview = Path()
                                when (shape) {
                                    Shape.RECT -> preview.addRect(
                                        RectF(
                                            min(sx, px), min(sy, py), max(sx, px), max(sy, py)
                                        ), Path.Direction.CW
                                    )

                                    Shape.CIRCLE -> {
                                        val left = min(sx, px);
                                        val top = min(sy, py)
                                        val right = max(sx, px);
                                        val bottom = max(sy, py)
                                        val cx = (left + right) / 2f;
                                        val cy = (top + bottom) / 2f
                                        val r = min(right - left, bottom - top) / 2f
                                        preview.addCircle(cx, cy, r, Path.Direction.CW)
                                    }

                                    Shape.LINE -> {
                                        preview.moveTo(sx, sy); preview.lineTo(px, py)
                                    }

                                    Shape.POLYGON -> {
                                        val left = min(sx, px);
                                        val top = min(sy, py)
                                        val right = max(sx, px);
                                        val bottom = max(sy, py)
                                        val cx = (left + right) / 2f;
                                        val cy = (top + bottom) / 2f
                                        val r = min(right - left, bottom - top) / 2f
                                        val p = Path()
                                        for (k in 0..4) {
                                            val ang = Math.toRadians((270 + k * 72).toDouble())
                                            val px2 = (cx + r * cos(ang)).toFloat()
                                            val py2 = (cy + r * sin(ang)).toFloat()
                                            if (k == 0) p.moveTo(px2, py2) else p.lineTo(px2, py2)
                                        }
                                        p.close()
                                        preview.addPath(p)
                                    }
                                }
                                activePaths[pid] = preview
                            } else {
                                _selectedShape?.let { s ->
                                    if (activeHit == HitType.NONE) {
                                        val hit = hitTestShape(px, py)
                                        if (hit != null && hit.shape == s) activeHit = hit.hitType
                                    }
                                    when (activeHit) {
                                        HitType.INSIDE -> {
                                            val dx = px - lastTouchX;
                                            val dy = py - lastTouchY
                                            translateShapeBy(s, dx, dy)
                                        }

                                        HitType.ROTATE -> {
                                            val cx = s.centerX();
                                            val cy = s.centerY()
                                            val a1 = atan2(
                                                (lastTouchY - cy).toDouble(),
                                                (lastTouchX - cx).toDouble()
                                            )
                                            val a2 =
                                                atan2((py - cy).toDouble(), (px - cx).toDouble())
                                            val deltaDeg = Math.toDegrees(a2 - a1).toFloat()
                                            s.rotation = (s.rotation + deltaDeg) % 360f
                                        }

                                        else -> when (activeHit) {
                                            HitType.HANDLE_LT, HitType.HANDLE_T, HitType.HANDLE_RT, HitType.HANDLE_R, HitType.HANDLE_RB, HitType.HANDLE_B, HitType.HANDLE_LB, HitType.HANDLE_L -> {
                                                val dx = px - lastTouchX;
                                                val dy = py - lastTouchY
                                                resizeShapeBy(s, activeHit, dx, dy)
                                            }

                                            else -> {}
                                        }
                                    }
                                }
                            }
                        }
                    }
                    lastTouchX = px; lastTouchY = py
                }
                invalidate()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val idx = event.actionIndex
                val pid = event.getPointerId(idx)
                val px = event.getX(idx)
                val py = event.getY(idx)

                when (mode) {
                    Mode.PEN, Mode.TEXT -> {
                        val pts = activePoints[pid];
                        val paint = activePaints[pid]
                        if (pts != null && pts.size >= 2 && paint != null) strokes.add(
                            Stroke(
                                pts.toMutableList(), Paint(paint)
                            )
                        )
                        activePoints.remove(pid)
                    }

                    Mode.ERASER -> {
                        // ON TOUCH-UP: perform erase for the entire recorded eraser trace
                        val trace = eraserTrace[pid]
                        if (trace != null && trace.isNotEmpty()) {
                            performPartialEraseAlongTrace(trace, eraserRadiusPx)
                        } else {
                            // fallback: single-point erase at up position
                            performPartialEraseAt(px, py, eraserRadiusPx)
                        }
                        // clear trace & preview
                        eraserTrace.remove(pid)
                        activePaths.remove(pid)
                        activePaints.remove(pid)
                    }

                    Mode.SHAPE -> {
                        val start = shapeStart[pid];
                        val paint = activePaints[pid]
                        if (start != null && paint != null) {
                            val (sx, sy) = start
                            val dx = abs(px - sx);
                            val dy = abs(py - sy)
                            val moved = hypot(dx.toDouble(), dy.toDouble()) > touchSlop

                            val left: Float;
                            val top: Float;
                            val right: Float;
                            val bottom: Float
                            if (!moved && (shapeDefaultWidth > 0f && shapeDefaultHeight > 0f)) {
                                val halfW = shapeDefaultWidth / 2f;
                                val halfH = shapeDefaultHeight / 2f
                                left = sx - halfW; top = sy - halfH; right = sx + halfW; bottom =
                                    sy + halfH
                            } else if (!moved && (shapeDefaultWidth <= 0f || shapeDefaultHeight <= 0f)) {
                                val half = 40f.dp
                                left = sx - half; top = sy - half; right = sx + half; bottom =
                                    sy + half
                            } else {
                                left = min(sx, px); top = min(sy, py); right = max(sx, px); bottom =
                                    max(sy, py)
                            }

                            val model = ShapeModel(
                                id = System.currentTimeMillis().toString(),
                                kind = shape,
                                left = left,
                                top = top,
                                right = right,
                                bottom = bottom,
                                rotation = 0f,
                                color = strokeColor,         // use current pen color
                                strokeWidth = strokeWidth,   // use current pen width
                                fillEnabled = shapeFillEnabled,
                                fillColor = shapeFillColor
                            )
                            shapes.add(model)
                            setSelected(model)
                        }
                        shapeStart.remove(pid)
                        activePaths.remove(pid)
                        activePaints.remove(pid)
                    }
                }

                // cleanup
                activePaths.remove(pid)
                activePaints.remove(pid)
                activePointerId = -1
                activeHit = HitType.NONE
                invalidate()
            }
        }
        return true
    }

    // Erase along a recorded trace: for each stroke segment, if ANY trace point's circle intersects it then remove that segment.
    private fun performPartialEraseAlongTrace(trace: List<PointF>, radius: Float) {
        if (trace.isEmpty()) return
        val newStrokes = mutableListOf<Stroke>()
        for (stroke in strokes) {
            val pts = stroke.points
            if (pts.size < 2) {
                val single = pts.firstOrNull()
                if (single != null) {
                    var erased = false
                    for (t in trace) {
                        if (distance(
                                single.x, single.y, t.x, t.y
                            ) <= radius + stroke.paint.strokeWidth / 2f
                        ) {
                            erased = true; break
                        }
                    }
                    if (!erased) newStrokes.add(stroke)
                }
                continue
            }

            val segmentRemoved = BooleanArray(pts.size - 1)
            for (i in 0 until pts.size - 1) {
                val a = pts[i];
                val b = pts[i + 1]
                var removeSeg = false
                val effective = radius + stroke.paint.strokeWidth / 2f
                for (t in trace) {
                    val dist = distancePointToSegment(t.x, t.y, a.x, a.y, b.x, b.y)
                    if (dist <= effective) {
                        removeSeg = true; break
                    }
                }
                if (removeSeg) segmentRemoved[i] = true
            }

            if (!segmentRemoved.any { it }) {
                newStrokes.add(stroke); continue
            }

            // split into runs of kept segments
            var currentPoints = mutableListOf<PointF>()
            currentPoints.add(pts[0])
            for (i in 0 until pts.size - 1) {
                if (!segmentRemoved[i]) {
                    currentPoints.add(pts[i + 1])
                } else {
                    if (currentPoints.size >= 2) {
                        newStrokes.add(Stroke(currentPoints.toMutableList(), Paint(stroke.paint)))
                    }
                    currentPoints = mutableListOf()
                    // start after removed segment - add next point as potential start
                    currentPoints.add(pts[i + 1])
                }
            }
            if (currentPoints.size >= 2) newStrokes.add(
                Stroke(
                    currentPoints.toMutableList(), Paint(stroke.paint)
                )
            )
        }
        strokes.clear(); strokes.addAll(newStrokes)
    }

    // fallback single point erase
    private fun performPartialEraseAt(px: Float, py: Float, radius: Float) {
        performPartialEraseAlongTrace(listOf(PointF(px, py)), radius)
    }

    private fun distancePointToSegment(
        px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float
    ): Float {
        val vx = x2 - x1;
        val vy = y2 - y1
        val wx = px - x1;
        val wy = py - y1
        val c1 = vx * wx + vy * wy
        if (c1 <= 0) return hypot(px - x1, py - y1)
        val c2 = vx * vx + vy * vy
        if (c2 <= c1) return hypot(px - x2, py - y2)
        val b = c1 / c2
        val pbx = x1 + b * vx;
        val pby = y1 + b * vy
        return hypot(px - pbx, py - pby)
    }

    private fun hitTestShape(px: Float, py: Float): HitResult? {
        for (i in shapes.indices.reversed()) {
            val s = shapes[i]
            val local = screenToLocal(s, px, py)
            val rect = RectF(s.left, s.top, s.right, s.bottom)

            val rotateCenter =
                PointF((rect.left + rect.right) / 2f, rect.top - ROTATE_HANDLE_DISTANCE)
            if (distance(local.x, local.y, rotateCenter.x, rotateCenter.y) <= HANDLE_SIZE * 1.2f) {
                return HitResult(s, HitType.ROTATE)
            }

            val handles = getHandlePoints(rect)
            for ((idx, hp) in handles.withIndex()) {
                if (abs(local.x - hp.x) <= HANDLE_SIZE && abs(local.y - hp.y) <= HANDLE_SIZE) {
                    val ht = when (idx) {
                        0 -> HitType.HANDLE_LT
                        1 -> HitType.HANDLE_T
                        2 -> HitType.HANDLE_RT
                        3 -> HitType.HANDLE_R
                        4 -> HitType.HANDLE_RB
                        5 -> HitType.HANDLE_B
                        6 -> HitType.HANDLE_LB
                        7 -> HitType.HANDLE_L
                        else -> HitType.NONE
                    }
                    return HitResult(s, ht)
                }
            }

            if (local.x >= rect.left && local.x <= rect.right && local.y >= rect.top && local.y <= rect.bottom) {
                return HitResult(s, HitType.INSIDE)
            }
        }
        return null
    }

    private data class HitResult(val shape: ShapeModel?, val hitType: HitType)

    private fun screenToLocal(s: ShapeModel, px: Float, py: Float): PointF {
        val cx = s.centerX();
        val cy = s.centerY()
        val ang = Math.toRadians(-s.rotation.toDouble())
        val cos = cos(ang);
        val sin = sin(ang)
        val dx = px - cx;
        val dy = py - cy
        val lx = (dx * cos - dy * sin).toFloat() + cx
        val ly = (dx * sin + dy * cos).toFloat() + cy
        return PointF(lx, ly)
    }

    private fun translateShapeBy(s: ShapeModel, dx: Float, dy: Float) {
        val ang = Math.toRadians(-s.rotation.toDouble())
        val cos = cos(ang);
        val sin = sin(ang)
        val ldx = (dx * cos - dy * sin).toFloat()
        val ldy = (dx * sin + dy * cos).toFloat()
        s.left += ldx; s.right += ldx
        s.top += ldy; s.bottom += ldy
    }

    private fun resizeShapeBy(s: ShapeModel, hit: HitType, dx: Float, dy: Float) {
        val ang = Math.toRadians(-s.rotation.toDouble())
        val cos = cos(ang);
        val sin = sin(ang)
        val ldx = (dx * cos - dy * sin).toFloat()
        val ldy = (dx * sin + dy * cos).toFloat()
        when (hit) {
            HitType.HANDLE_LT -> {
                s.left += ldx; s.top += ldy
            }

            HitType.HANDLE_T -> {
                s.top += ldy
            }

            HitType.HANDLE_RT -> {
                s.right += ldx; s.top += ldy
            }

            HitType.HANDLE_R -> {
                s.right += ldx
            }

            HitType.HANDLE_RB -> {
                s.right += ldx; s.bottom += ldy
            }

            HitType.HANDLE_B -> {
                s.bottom += ldy
            }

            HitType.HANDLE_LB -> {
                s.left += ldx; s.bottom += ldy
            }

            HitType.HANDLE_L -> {
                s.left += ldx
            }

            else -> {}
        }
        if (s.left > s.right) {
            val t = s.left; s.left = s.right; s.right = t
        }
        if (s.top > s.bottom) {
            val t = s.top; s.top = s.bottom; s.bottom = t
        }
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float =
        hypot((x2 - x1), (y2 - y1))

    fun updateSelectedShapeFillColor(color: Int) {
        _selectedShape?.let {
            it.fillColor = color
            it.fillEnabled = true
            invalidate()
            selectionListener?.invoke(getSelectedShape())
        }
    }

    fun setSelectedShapeFillEnabled(enabled: Boolean) {
        _selectedShape?.let {
            it.fillEnabled = enabled
            invalidate()
            selectionListener?.invoke(getSelectedShape())
        }
    }

    fun updateSelectedShapeBorderColor(color: Int) {
        _selectedShape?.let {
            it.color = color
            invalidate()
            selectionListener?.invoke(getSelectedShape())
        }
    }

    fun updateSelectedShapeStrokeWidth(width: Float) {
        _selectedShape?.let {
            it.strokeWidth = width
            invalidate()
            selectionListener?.invoke(getSelectedShape())
        }
    }
}

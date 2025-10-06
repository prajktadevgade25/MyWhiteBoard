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
 * DrawView: freehand strokes and shapes (create/select/transform/delete) with per-shape properties.
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

    // ---------- internal stroke bookkeeping ----------
    private data class Stroke(val path: Path, val paint: Paint)

    private val strokes = mutableListOf<Stroke>()
    private val activePaths = mutableMapOf<Int, Path>()
    private val activePaints = mutableMapOf<Int, Paint>()

    // ---------- shape creation preview ----------
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

        // draw freehand strokes
        for (s in strokes) canvas.drawPath(s.path, s.paint)

        // draw shapes
        for (s in shapes) drawShapeModel(canvas, s)

        // draw previews (activePaths)
        for ((pid, path) in activePaths) {
            val p = activePaints[pid] ?: basePaint
            canvas.drawPath(path, p)
        }

        // draw selection if any
        _selectedShape?.let { drawSelection(canvas, it) }
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
        val bgRadius = deleteSizeDp / 2f
        canvas.drawCircle(localDelX, localDelY, bgRadius, redBgPaint)

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
                    Mode.PEN, Mode.ERASER, Mode.TEXT -> {
                        val path = Path().apply { moveTo(px, py) }
                        activePaths[pid] = path
                        activePaints[pid] = Paint(basePaint)
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
                        Mode.PEN, Mode.ERASER, Mode.TEXT -> {
                            val path = activePaths[pid]
                            if (path != null) path.lineTo(px, py)
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
                    Mode.PEN, Mode.ERASER, Mode.TEXT -> {
                        val path = activePaths[pid];
                        val paint = activePaints[pid]
                        if (path != null && paint != null) strokes.add(
                            Stroke(
                                Path(path), Paint(paint)
                            )
                        )
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

    // ---------- hit testing & transforms ----------
    private data class HitResult(val shape: ShapeModel?, val hitType: HitType)

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

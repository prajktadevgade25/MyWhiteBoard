package com.example.mywhiteboard

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.*

/**
 * Public top-level model so Activity/UI can inspprivate val deleteIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
 *     color = Color.RED
 *     style = Paint.Style.FILL
 * }
 *
 * private val deleteIconRadius = 40fect/update selection.
 */
data class ShapeModel(
    var id: String,
    var kind: DrawView.Shape,
    var left: Float,
    var top: Float,
    var right: Float,
    var bottom: Float,
    var rotation: Float = 0f,            // degrees
    var color: Int = Color.BLACK,        // border color
    var strokeWidth: Float = 6f,         // border width
    var fillEnabled: Boolean = false,    // whether filled
    var fillColor: Int = Color.TRANSPARENT
) {
    fun centerX() = (left + right) / 2f
    fun centerY() = (top + bottom) / 2f
    fun width() = right - left
    fun height() = bottom - top
}

/**
 * DrawView: freehand strokes + shape creation + selection/transform + per-shape properties.
 */
class DrawView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    enum class Mode { PEN, ERASER, SHAPE, TEXT }
    enum class Shape { RECT, CIRCLE, LINE, POLYGON }

    // ---------- Base paint / stroke ------------

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 12f
    }
    private val deleteIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.FILL
    }

    private val deleteIconRadius = 40f
    private val deleteIconPadding = 10f

    private data class Stroke(val path: Path, val paint: Paint)

    private val strokes = mutableListOf<Stroke>()
    private val activePaths = mutableMapOf<Int, Path>()
    private val activePaints = mutableMapOf<Int, Paint>()

    // shape preview start per pointer
    private val shapeStart = mutableMapOf<Int, Pair<Float, Float>>()

    // background
    private var backgroundColor = Color.WHITE

    // public config
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

    // ---------- Shape defaults / per-shape creation config ----------
    /** If >0: tap (no drag) creates a shape with this width/height centered at tap */
    var shapeDefaultWidth: Float = 0f
    var shapeDefaultHeight: Float = 0f

    var shapeBorderColor: Int = Color.BLACK
    var shapeBorderWidth: Float = 8f

    var shapeFillColor: Int = Color.TRANSPARENT
    var shapeFillEnabled: Boolean = false

    // ---------- Shapes storage + selection ----------
    private val shapes = mutableListOf<ShapeModel>()
    private var _selectedShape: ShapeModel? = null

    // Use setSelected(...) to change selection so listeners fire
    private fun setSelected(s: ShapeModel?) {
        _selectedShape = s
        selectionListener?.invoke(getSelectedShape()) // send a copy / snapshot
        invalidate()
    }

    fun getSelectedShape(): ShapeModel? = _selectedShape?.copy()

    // ---------- Selection listener ----------
    private var selectionListener: ((ShapeModel?) -> Unit)? = null
    fun setOnSelectionChangedListener(listener: (ShapeModel?) -> Unit) {
        selectionListener = listener
    }

    // ---------- paints for drawing shapes & selection ----------
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

    // handle size
    private val Float.dp get() = this * resources.displayMetrics.density
    private val HANDLE_SIZE = 14f.dp
    private val ROTATE_HANDLE_DISTANCE = 36f.dp

    // hit / transform tracking
    private enum class HitType { NONE, INSIDE, ROTATE, HANDLE_LT, HANDLE_T, HANDLE_RT, HANDLE_R, HANDLE_RB, HANDLE_B, HANDLE_LB, HANDLE_L }

    private var activeHit = HitType.NONE
    private var activePointerId = -1
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    // ---------- Drawing ----------
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(backgroundColor)

        // draw freehand strokes
        for (s in strokes) canvas.drawPath(s.path, s.paint)

        // draw committed shapes
        for (s in shapes) drawShapeModel(canvas, s)

        // draw previews (activePaths)
        for ((pid, path) in activePaths) {
            val p = activePaints[pid] ?: basePaint
            canvas.drawPath(path, p)
        }

        // draw selection
        _selectedShape?.let { drawSelection(canvas, it) }
    }

    private fun drawShapeModel(canvas: Canvas, s: ShapeModel) {
        val save = canvas.save()
        val cx = s.centerX();
        val cy = s.centerY()
        canvas.rotate(s.rotation, cx, cy)

        // fill then stroke
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
        canvas.drawRect(rect, selectionPaint)

        val handles = getHandlePoints(rect)
        for (pt in handles) {
            val left = pt.x - HANDLE_SIZE / 2f
            val top = pt.y - HANDLE_SIZE / 2f
            val r = RectF(left, top, left + HANDLE_SIZE, top + HANDLE_SIZE)
            canvas.drawRect(r, handleFillPaint)
            canvas.drawRect(r, handleBorderPaint)
        }

        val topCenter = PointF((rect.left + rect.right) / 2f, rect.top - ROTATE_HANDLE_DISTANCE)
        canvas.drawCircle(topCenter.x, topCenter.y, HANDLE_SIZE / 2f, rotateHandlePaint)
        canvas.drawCircle(topCenter.x, topCenter.y, HANDLE_SIZE / 2f, handleBorderPaint)

        // delete handle
        val delX = rect.right + deleteIconPadding + deleteIconRadius
        val delY = rect.top - deleteIconPadding - deleteIconRadius
        canvas.drawCircle(delX, delY, deleteIconRadius, deleteIconPaint)
        val xPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = 6f
            style = Paint.Style.STROKE
        }
        canvas.drawLine(delX - 15, delY - 15, delX + 15, delY + 15, xPaint)
        canvas.drawLine(delX - 15, delY + 15, delX + 15, delY - 15, xPaint)

        canvas.restoreToCount(save)
    }

    private fun getHandlePoints(rect: RectF): List<PointF> {
        return listOf(
            PointF(rect.left, rect.top),                        // LT
            PointF((rect.left + rect.right) / 2f, rect.top),    // T
            PointF(rect.right, rect.top),                       // RT
            PointF(rect.right, (rect.top + rect.bottom) / 2f),  // R
            PointF(rect.right, rect.bottom),                    // RB
            PointF((rect.left + rect.right) / 2f, rect.bottom), // B
            PointF(rect.left, rect.bottom),                     // LB
            PointF(rect.left, (rect.top + rect.bottom) / 2f)    // L
        )
    }

    // ---------- Touch handling ----------
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                lastTouchX = x; lastTouchY = y

                _selectedShape?.let { shape ->
                    // Check delete tap first
                    val rect = RectF(shape.left, shape.top, shape.right, shape.bottom)
                    val delX = rect.right + deleteIconPadding + deleteIconRadius
                    val delY = rect.top - deleteIconPadding - deleteIconRadius
                    if (distance(x, y, delX, delY) <= deleteIconRadius) {
                        shapes.remove(shape)
                        setSelected(null)
                        return true
                    }
                }
            }
        }
        val action = event.actionMasked

        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                val pid = event.getPointerId(index)
                val x = event.getX(index)
                val y = event.getY(index)

                activePointerId = pid
                lastTouchX = x
                lastTouchY = y

                when (mode) {
                    Mode.PEN, Mode.ERASER, Mode.TEXT -> {
                        val path = Path().apply { moveTo(x, y) }
                        activePaths[pid] = path
                        activePaints[pid] = Paint(basePaint)
                    }

                    Mode.SHAPE -> {
                        val hit = hitTestShape(x, y)
                        if (hit != null && hit.shape != null) {
                            // select shape and set activeHit
                            setSelected(hit.shape)
                            activeHit = hit.hitType
                        } else {
                            // start creating a new shape
                            shapeStart[pid] = Pair(x, y)
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
                    val x = event.getX(i)
                    val y = event.getY(i)

                    when (mode) {
                        Mode.PEN, Mode.ERASER, Mode.TEXT -> {
                            val path = activePaths[pid]
                            if (path != null) path.lineTo(x, y)
                        }

                        Mode.SHAPE -> {
                            val start = shapeStart[pid]
                            if (start != null) {
                                val (sx, sy) = start
                                val preview = Path()
                                when (shape) {
                                    Shape.RECT -> preview.addRect(
                                        RectF(
                                            min(sx, x), min(sy, y), max(sx, x), max(sy, y)
                                        ), Path.Direction.CW
                                    )

                                    Shape.CIRCLE -> {
                                        val left = min(sx, x);
                                        val top = min(sy, y)
                                        val right = max(sx, x);
                                        val bottom = max(sy, y)
                                        val cx = (left + right) / 2f;
                                        val cy = (top + bottom) / 2f
                                        val r = min(right - left, bottom - top) / 2f
                                        preview.addCircle(cx, cy, r, Path.Direction.CW)
                                    }

                                    Shape.LINE -> {
                                        preview.moveTo(sx, sy); preview.lineTo(x, y)
                                    }

                                    Shape.POLYGON -> {
                                        val left = min(sx, x);
                                        val top = min(sy, y)
                                        val right = max(sx, x);
                                        val bottom = max(sy, y)
                                        val cx = (left + right) / 2f;
                                        val cy = (top + bottom) / 2f
                                        val r = min(right - left, bottom - top) / 2f
                                        val p = Path()
                                        for (k in 0..4) {
                                            val ang = Math.toRadians((270 + k * 72).toDouble())
                                            val px = (cx + r * cos(ang)).toFloat()
                                            val py = (cy + r * sin(ang)).toFloat()
                                            if (k == 0) p.moveTo(px, py) else p.lineTo(px, py)
                                        }
                                        p.close()
                                        preview.addPath(p)
                                    }
                                }
                                activePaths[pid] = preview
                            } else {
                                // interacting with selected shape
                                _selectedShape?.let { s ->
                                    if (activeHit == HitType.NONE) {
                                        val hit = hitTestShape(x, y)
                                        if (hit != null && hit.shape == s) activeHit = hit.hitType
                                    }
                                    when (activeHit) {
                                        HitType.INSIDE -> {
                                            val dx = x - lastTouchX;
                                            val dy = y - lastTouchY
                                            translateShapeBy(s, dx, dy)
                                        }

                                        HitType.ROTATE -> {
                                            val cx = s.centerX();
                                            val cy = s.centerY()
                                            val a1 = atan2(
                                                (lastTouchY - cy).toDouble(),
                                                (lastTouchX - cx).toDouble()
                                            )
                                            val a2 = atan2((y - cy).toDouble(), (x - cx).toDouble())
                                            val deltaDeg = Math.toDegrees(a2 - a1).toFloat()
                                            s.rotation = (s.rotation + deltaDeg) % 360f
                                        }

                                        HitType.HANDLE_LT, HitType.HANDLE_T, HitType.HANDLE_RT, HitType.HANDLE_R, HitType.HANDLE_RB, HitType.HANDLE_B, HitType.HANDLE_LB, HitType.HANDLE_L -> {
                                            val dx = x - lastTouchX;
                                            val dy = y - lastTouchY
                                            resizeShapeBy(s, activeHit, dx, dy)
                                        }

                                        else -> {}
                                    }
                                }
                            }
                        }
                    }
                    lastTouchX = x; lastTouchY = y
                }
                invalidate()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val index = event.actionIndex
                val pid = event.getPointerId(index)
                val x = event.getX(index)
                val y = event.getY(index)

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
                            val dx = abs(x - sx);
                            val dy = abs(y - sy)
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
                                left = min(sx, x); top = min(sy, y); right = max(sx, x); bottom =
                                    max(sy, y)
                            }

                            val model = ShapeModel(
                                id = System.currentTimeMillis().toString(),
                                kind = shape,
                                left = left,
                                top = top,
                                right = right,
                                bottom = bottom,
                                rotation = 0f,
                                color = shapeBorderColor,
                                strokeWidth = shapeBorderWidth,
                                fillEnabled = shapeFillEnabled,
                                fillColor = shapeFillColor
                            )
                            shapes.add(model)
                            setSelected(model)
                        } else {
                            // nothing to commit (likely transform)
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

    // ---------- Hit testing & transforms ----------
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

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        return hypot((x2 - x1), (y2 - y1))
    }

    // ---------- Public APIs for selected-shape editing ----------
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

    // ---------- Utility / other API ----------
    fun clear() {
        strokes.clear()
        shapes.clear()
        setSelected(null)
        activePaths.clear()
        activePaints.clear()
        shapeStart.clear()
        invalidate()
    }

    fun undoStroke() {
        if (strokes.isNotEmpty()) {
            strokes.removeAt(strokes.lastIndex)
            invalidate()
        }
    }

    fun exportBitmap(): Bitmap {
        val w = width.coerceAtLeast(1);
        val h = height.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        draw(c)
        return bmp
    }
}

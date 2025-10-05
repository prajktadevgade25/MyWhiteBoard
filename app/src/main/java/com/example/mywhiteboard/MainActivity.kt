package com.example.mywhiteboard

import android.app.AlertDialog
import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.PopupWindow
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.mywhiteboard.databinding.ActivityMainBinding
import kotlin.math.hypot

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    // small preset colors for the dialog
    private val presetColors = intArrayOf(
        Color.BLACK, Color.DKGRAY, Color.GRAY, Color.WHITE,
        Color.RED, Color.parseColor("#FF8800"), Color.YELLOW,
        Color.parseColor("#4CAF50"), Color.CYAN, Color.BLUE,
        Color.MAGENTA, Color.parseColor("#8E44AD")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Inflate layout using View Binding
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Handle insets (edge-to-edge)
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Wire toolbar buttons
        binding.btnPen.setOnClickListener {
            binding.drawView.mode = DrawView.Mode.PEN
            showPenChooser(it)
        }
        binding.btnShapes.setOnClickListener {
            binding.drawView.mode = DrawView.Mode.SHAPE
            showShapeChooser(it)
        }

        // Shape creation defaults
        binding.drawView.shapeDefaultWidth = 600f
        binding.drawView.shapeDefaultHeight = 400f
        binding.drawView.shapeBorderColor = Color.BLACK
        binding.drawView.shapeBorderWidth = 8f
        binding.drawView.shapeFillColor = Color.TRANSPARENT
        binding.drawView.shapeFillEnabled = false

        // --- property bar references (safe fallback via findViewById on root) ---
        // This avoids compile-time errors if view binding didn't generate a direct field.
        val propBarRoot: View? = binding.root.findViewById(R.id.shapePropertyBar)
        val btnFill: ImageButton? = propBarRoot?.findViewById(R.id.btnFillColor)
        val btnBorder: ImageButton? = propBarRoot?.findViewById(R.id.btnBorderColor)
        val seekWidth: SeekBar? = propBarRoot?.findViewById(R.id.seekWidth)

        // Initially hide the property bar if present
        propBarRoot?.visibility = View.GONE

        // Selection change: show/hide property bar, populate values
        binding.drawView.setOnSelectionChangedListener { selected ->
            if (selected != null) {
                // show and populate
                propBarRoot?.visibility = View.VISIBLE
                btnFill?.setBackgroundColor(if (selected.fillEnabled) selected.fillColor else Color.TRANSPARENT)
                btnBorder?.setBackgroundColor(selected.color)
                seekWidth?.progress = selected.strokeWidth.toInt().coerceIn(1, seekWidth?.max ?: 60)
            } else {
                propBarRoot?.visibility = View.GONE
            }
        }

        // Fill swatch clicked -> color picker
        btnFill?.setOnClickListener {
            showColorPicker { color ->
                binding.drawView.updateSelectedShapeFillColor(color)
                binding.drawView.setSelectedShapeFillEnabled(true)
                btnFill.setBackgroundColor(color)
            }
        }

        // Border swatch clicked -> color picker
        btnBorder?.setOnClickListener {
            showColorPicker { color ->
                binding.drawView.updateSelectedShapeBorderColor(color)
                btnBorder.setBackgroundColor(color)
            }
        }

        // Width slider
        seekWidth?.max = 60
        seekWidth?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val w = progress.coerceAtLeast(1).toFloat()
                binding.drawView.updateSelectedShapeStrokeWidth(w)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    // ---------- color picker: simple grid of preset colors ----------
    private fun showColorPicker(onColorChosen: (Int) -> Unit) {
        val colors = presetColors
        val cols = 4
        val rows = (colors.size + cols - 1) / cols
        val size = (80 * resources.displayMetrics.density).toInt()

        val container = GridLayout(this).apply {
            rowCount = rows
            columnCount = cols
            setPadding(12, 12, 12, 12)
        }

        var dialog: AlertDialog? = null

        for (c in colors) {
            val v = View(this).apply {
                // Use WRAP_CONTENT here and set layout params in GridLayout.LayoutParams
                setBackgroundColor(c)
                setOnClickListener {
                    onColorChosen(c)
                    dialog?.dismiss()
                }
            }

            val params = GridLayout.LayoutParams().apply {
                width = size
                height = size
                setMargins(8, 8, 8, 8)
            }

            container.addView(v, params)
        }

        val builder = AlertDialog.Builder(this)
            .setTitle("Pick color")
            .setView(container)
            .setNegativeButton("Cancel") { d, _ -> d.dismiss() }

        dialog = builder.show()
    }

    // ---------- shape chooser popup (same strategy you used) ----------
    private fun showShapeChooser(anchorView: View) {
        val inflater = layoutInflater
        val popupView = inflater.inflate(R.layout.dialog_choose_shape, null)

        val popupWindow = PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            popupWindow.elevation = 10f
        }

        popupWindow.isOutsideTouchable = true
        popupWindow.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        popupView.findViewById<ImageButton>(R.id.btnRect).setOnClickListener {
            binding.drawView.shape = DrawView.Shape.RECT
            popupWindow.dismiss()
        }
        popupView.findViewById<ImageButton>(R.id.btnCircle).setOnClickListener {
            binding.drawView.shape = DrawView.Shape.CIRCLE
            popupWindow.dismiss()
        }
        popupView.findViewById<ImageButton>(R.id.btnLine).setOnClickListener {
            binding.drawView.shape = DrawView.Shape.LINE
            popupWindow.dismiss()
        }
        popupView.findViewById<ImageButton>(R.id.btnPolygon).setOnClickListener {
            binding.drawView.shape = DrawView.Shape.POLYGON
            popupWindow.dismiss()
        }

        popupView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val popupWidth = popupView.measuredWidth
        val location = IntArray(2)
        anchorView.getLocationOnScreen(location)
        val anchorX = location[0]
        val anchorY = location[1]
        val screenWidth = Resources.getSystem().displayMetrics.widthPixels
        val marginRight = anchorView.width
        var xOffset = anchorX
        if (xOffset + popupWidth > screenWidth - marginRight) {
            xOffset = screenWidth - popupWidth - marginRight
        }
        val yOffset = anchorY
        popupWindow.showAtLocation(anchorView, Gravity.NO_GRAVITY, xOffset, yOffset)
    }

    // ---------- pen chooser popup ----------
    private fun showPenChooser(anchorView: View) {
        val inflater = layoutInflater
        val popupView = inflater.inflate(R.layout.dialog_size_color, null)

        val popupWindow = PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            popupWindow.elevation = 10f
        }

        popupWindow.isOutsideTouchable = true
        popupWindow.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val drawView = binding.drawView

        popupView.findViewById<ImageView>(R.id.btnClose).setOnClickListener {
            popupWindow.dismiss()
        }
        popupView.findViewById<View>(R.id.btnColorBlack)
            .setOnClickListener { drawView.strokeColor = Color.BLACK }
        popupView.findViewById<View>(R.id.btnColorRed)
            .setOnClickListener { drawView.strokeColor = Color.RED }
        popupView.findViewById<View>(R.id.btnColorBlue)
            .setOnClickListener { drawView.strokeColor = Color.BLUE }
        popupView.findViewById<View>(R.id.btnColorGreen)
            .setOnClickListener { drawView.strokeColor = Color.GREEN }

        val seekStroke = popupView.findViewById<SeekBar>(R.id.seekStrokeWidth)
        seekStroke.progress = drawView.strokeWidth.toInt()
        seekStroke.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (progress > 0) drawView.strokeWidth = progress.toFloat()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        popupView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val popupWidth = popupView.measuredWidth
        val location = IntArray(2)
        anchorView.getLocationOnScreen(location)
        val anchorX = location[0]
        val anchorY = location[1]
        val screenWidth = Resources.getSystem().displayMetrics.widthPixels
        val marginRight = anchorView.width
        var xOffset = anchorX
        if (xOffset + popupWidth > screenWidth - marginRight) {
            xOffset = screenWidth - popupWidth - marginRight
        }
        val yOffset = anchorY
        popupWindow.showAtLocation(anchorView, Gravity.NO_GRAVITY, xOffset, yOffset)
    }
}
package com.example.mywhiteboard

import android.app.AlertDialog
import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.PopupWindow
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.mywhiteboard.databinding.ActivityMainBinding

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
        binding.btnEraser.setOnClickListener {
            binding.drawView.mode = DrawView.Mode.ERASER
        }
        binding.btnShapes.setOnClickListener {
            binding.drawView.mode = DrawView.Mode.SHAPE
            showShapeChooser(it)
        }
        binding.btnText.setOnClickListener {
            binding.drawView.mode = DrawView.Mode.TEXT
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
        val tvPropLabel: TextView? = propBarRoot?.findViewById(R.id.tvPropLabel) // optional label (if available)

        // Initially hide the property bar if present
        propBarRoot?.visibility = View.GONE

        // Selection change: show/hide property bar, populate values
        binding.drawView.setOnSelectionChangedListener { selected ->
            if (selected != null) {
                // show and populate
                propBarRoot?.visibility = View.VISIBLE

                if (selected.kind == DrawView.Shape.TEXT) {
                    // For text boxes:
                    tvPropLabel?.text = "Text properties"
                    // Use border swatch for text color
                    btnBorder?.setBackgroundColor(selected.textColor)
                    // Fill button used for background fill of text box
                    btnFill?.setBackgroundColor(if (selected.fillEnabled) selected.fillColor else Color.TRANSPARENT)
                    // Seekbar controls text size
                    val sizePx = selected.textSize.toInt().coerceIn(8, seekWidth?.max ?: 200)
                    seekWidth?.progress = sizePx
                } else {
                    // For other shapes:
                    tvPropLabel?.text = "Shape properties"
                    btnFill?.setBackgroundColor(if (selected.fillEnabled) selected.fillColor else Color.TRANSPARENT)
                    btnBorder?.setBackgroundColor(selected.color)
                    seekWidth?.progress = selected.strokeWidth.toInt().coerceIn(1, seekWidth?.max ?: 60)
                }
            } else {
                propBarRoot?.visibility = View.GONE
            }
        }

        // Fill swatch clicked -> color picker
        btnFill?.setOnClickListener {
            showColorPicker { color ->
                // If selected is text, treat as text-box background; otherwise shape fill
                val sel = binding.drawView.getSelectedShape()
                if (sel != null && sel.kind == DrawView.Shape.TEXT) {
                    binding.drawView.updateSelectedShapeFillColor(color)
                    binding.drawView.setSelectedShapeFillEnabled(true)
                    btnFill.setBackgroundColor(color)
                } else {
                    binding.drawView.updateSelectedShapeFillColor(color)
                    binding.drawView.setSelectedShapeFillEnabled(true)
                    btnFill.setBackgroundColor(color)
                }
            }
        }

        // Border swatch clicked -> color picker
        btnBorder?.setOnClickListener {
            showColorPicker { color ->
                val sel = binding.drawView.getSelectedShape()
                if (sel != null && sel.kind == DrawView.Shape.TEXT) {
                    // update text color
                    binding.drawView.updateSelectedShapeTextColor(color)
                    btnBorder.setBackgroundColor(color)
                } else {
                    binding.drawView.updateSelectedShapeBorderColor(color)
                    btnBorder.setBackgroundColor(color)
                }
            }
        }

        // Width slider: use for stroke width or text size depending on selection
        seekWidth?.max = 200
        seekWidth?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val sel = binding.drawView.getSelectedShape()
                if (sel != null) {
                    if (sel.kind == DrawView.Shape.TEXT) {
                        val px = progress.coerceAtLeast(8).toFloat()
                        binding.drawView.updateSelectedShapeTextSize(px)
                    } else {
                        val w = progress.coerceAtLeast(1).toFloat()
                        binding.drawView.updateSelectedShapeStrokeWidth(w)
                    }
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // ---------- handle text edit requests coming from DrawView ----------
        binding.drawView.setOnTextEditRequestedListener { shape ->
            // Open a simple dialog with EditText to edit content and buttons to change color/size if desired.
            val inflater = LayoutInflater.from(this)
            val dialogView = inflater.inflate(R.layout.dialog_edit_text, null) // you'll create this layout
            val et = dialogView.findViewById<EditText>(R.id.etText)
            val btnColor = dialogView.findViewById<ImageButton>(R.id.btnTextColor)
            val seekTextSize = dialogView.findViewById<SeekBar>(R.id.seekTextSize)

            // populate initial values
            et.setText(shape.text)
            seekTextSize.max = 200
            seekTextSize.progress = shape.textSize.toInt().coerceIn(8, 200)
            btnColor.setBackgroundColor(shape.textColor)

            val builder = AlertDialog.Builder(this)
                .setTitle("Edit text")
                .setView(dialogView)
                .setPositiveButton("OK") { d, _ ->
                    val newText = et.text.toString()
                    binding.drawView.updateSelectedShapeText(newText)
                    binding.drawView.updateSelectedShapeTextSize(seekTextSize.progress.toFloat())
                    // color already applied via picker callback below
                }
                .setNegativeButton("Cancel") { d, _ -> d.dismiss() }

            val dialog = builder.show()

            // color picker button inside dialog
            btnColor.setOnClickListener {
                showColorPicker { color ->
                    btnColor.setBackgroundColor(color)
                    binding.drawView.updateSelectedShapeTextColor(color)
                }
            }

            // seek listener updates preview on host view directly as user slides
            seekTextSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    binding.drawView.updateSelectedShapeTextSize(progress.toFloat().coerceAtLeast(8f))
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }

    } // onCreate end

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
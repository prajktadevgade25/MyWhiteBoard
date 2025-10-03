package com.example.mywhiteboard

import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.PopupWindow
import android.widget.SeekBar
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.mywhiteboard.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Inflate layout using View Binding
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Handle insets
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        binding.btnPen.setOnClickListener {
            binding.drawView.mode = DrawView.Mode.PEN
            showPenChooser(it)
        }
        binding.btnShapes.setOnClickListener {
            binding.drawView.mode = DrawView.Mode.SHAPE
            showShapeChooser(it)
        }
    }

    /**
     * Displays a custom PopupWindow shape chooser near the clicked icon.
     */
    private fun showShapeChooser(anchorView: View) {
        val inflater = layoutInflater
        val popupView = inflater.inflate(R.layout.dialog_choose_shape, null)

        val popupWindow = PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true // Focusable so it can be dismissed on outside touch
        )

        // Optional: Set elevation if on Lollipop+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            popupWindow.elevation = 10f
        }

        // Dismiss when touched outside
        popupWindow.isOutsideTouchable = true
        popupWindow.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        // Set click listeners
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

        // Get anchor view's location on screen
        val location = IntArray(2)
        anchorView.getLocationOnScreen(location)
        val anchorX = location[0]
        val anchorY = location[1]

        // Optional margin from right side (adjust as needed)
        val screenWidth = Resources.getSystem().displayMetrics.widthPixels
        val marginRight = anchorView.width // pixels

        // Prevent popup from going off the right edge
        var xOffset = anchorX
        if (xOffset + popupWidth > screenWidth - marginRight) {
            xOffset = screenWidth - popupWidth - marginRight
        }

        // Position the popup just below the anchor (or adjust for above, etc.)
        val yOffset = anchorY

        // Show the popup at the calculated location
        popupWindow.showAtLocation(anchorView, Gravity.NO_GRAVITY, xOffset, yOffset)
    }

    private fun showPenChooser(anchorView: View) {
        val inflater = layoutInflater
        val popupView = inflater.inflate(R.layout.dialog_size_color, null)

        val popupWindow = PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true // Focusable so it can be dismissed on outside touch
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            popupWindow.elevation = 10f
        }

        popupWindow.isOutsideTouchable = true
        popupWindow.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        // ✅ Access your DrawView
        val drawView = findViewById<DrawView>(R.id.drawView)

        popupView.findViewById<Button>(R.id.btnClose).setOnClickListener {
            popupWindow.dismiss()
        }
        // Black color button
        popupView.findViewById<Button>(R.id.btnColorBlack).setOnClickListener {
            drawView.strokeColor = Color.BLACK
        }

        // Red color button
        popupView.findViewById<Button>(R.id.btnColorRed).setOnClickListener {
            drawView.strokeColor = Color.RED
        }

        // Blue color button
        popupView.findViewById<Button>(R.id.btnColorBlue).setOnClickListener {
            drawView.strokeColor = Color.BLUE
        }

        // Green color button
        popupView.findViewById<Button>(R.id.btnColorGreen).setOnClickListener {
            drawView.strokeColor = Color.GREEN
        }

        // Stroke size
        val seekStroke = popupView.findViewById<SeekBar>(R.id.seekStrokeWidth)
        seekStroke.progress = drawView.strokeWidth.toInt()
        seekStroke.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (progress > 0) {
                    drawView.strokeWidth = progress.toFloat()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Measure popup width
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
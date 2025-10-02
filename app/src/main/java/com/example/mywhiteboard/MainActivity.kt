package com.example.mywhiteboard

import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.PopupWindow
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

        binding.btnShapes.setOnClickListener {
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
            popupWindow.dismiss()
        }

        popupView.findViewById<ImageButton>(R.id.btnCircle).setOnClickListener {
            popupWindow.dismiss()
        }

        popupView.findViewById<ImageButton>(R.id.btnLine).setOnClickListener {
            popupWindow.dismiss()
        }

        popupView.findViewById<ImageButton>(R.id.btnPolygon).setOnClickListener {
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
}
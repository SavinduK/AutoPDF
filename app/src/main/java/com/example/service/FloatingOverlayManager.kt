package com.example.service

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.MainActivity

class FloatingOverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null
    private var statusTextView: TextView? = null
    private var countTextView: TextView? = null
    private var pauseResumeButton: ImageView? = null

    var onPauseResumeClicked: (() -> Unit)? = null
    var onStopClicked: (() -> Unit)? = null

    fun show() {
        if (overlayView != null) return
        if (!Settings.canDrawOverlays(context)) return

        val layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 180
        }

        // Programmatic sleek dark pill UI
        val density = context.resources.displayMetrics.density
        val dp = { value: Int -> (value * density).toInt() }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))

            val bg = GradientDrawable().apply {
                setColor(0xEE111827.toInt()) // Deep slate/black translucent
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), 0x4400E5FF.toInt()) // Cyan accent border
            }
            background = bg
            elevation = dp(8).toFloat()
        }

        // Drag handle indicator dot
        val dot = View(context).apply {
            val dotBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF00E5FF.toInt())
            }
            background = dotBg
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                rightMargin = dp(8)
            }
        }
        root.addView(dot)

        // Slide Count / Status Text
        val textContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                rightMargin = dp(12)
            }
        }

        countTextView = TextView(context).apply {
            text = "0 slides"
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        textContainer.addView(countTextView)

        statusTextView = TextView(context).apply {
            text = "Recording"
            setTextColor(0xFF00E5FF.toInt())
            textSize = 10f
        }
        textContainer.addView(statusTextView)
        root.addView(textContainer)

        // Pause/Resume Button
        pauseResumeButton = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_media_pause)
            setColorFilter(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply {
                rightMargin = dp(8)
            }
            val btnBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x33FFFFFF)
            }
            background = btnBg
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setOnClickListener {
                onPauseResumeClicked?.invoke()
            }
        }
        root.addView(pauseResumeButton)

        // Stop Button
        val stopButton = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(0xFFFF5252.toInt())
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
            val btnBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x33FF5252.toInt())
            }
            background = btnBg
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setOnClickListener {
                onStopClicked?.invoke()
            }
        }
        root.addView(stopButton)

        // Make root draggable across the screen
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        root.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 5 || Math.abs(dy) > 5) {
                        isDragging = true
                    }
                    params.x = initialX + dx
                    params.y = initialY + dy
                    windowManager.updateViewLayout(root, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        // Clicked on body: Bring MainActivity to foreground
                        val intent = Intent(context, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }
                        context.startActivity(intent)
                    }
                    true
                }
                else -> false
            }
        }

        overlayView = root
        try {
            windowManager.addView(root, params)
        } catch (e: Exception) {
            e.printStackTrace()
            overlayView = null
        }
    }

    fun update(count: Int, isPaused: Boolean) {
        countTextView?.text = "$count ${if (count == 1) "slide" else "slides"}"
        if (isPaused) {
            statusTextView?.text = "Paused"
            statusTextView?.setTextColor(0xFFFFC107.toInt())
            pauseResumeButton?.setImageResource(android.R.drawable.ic_media_play)
        } else {
            statusTextView?.text = "Recording"
            statusTextView?.setTextColor(0xFF00E5FF.toInt())
            pauseResumeButton?.setImageResource(android.R.drawable.ic_media_pause)
        }
    }

    fun hide() {
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
            overlayView = null
        }
    }
}

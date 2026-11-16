package com.rfscout.app

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Dark panel dialogs and rows in the app's theme, with a filled / hollow dot for on / off. */
internal object MenuUi {

    const val ON = "●"
    const val OFF = "○"

    private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    /** A themed dialog: title bar, scrolling body, and a row of buttons at the bottom. */
    fun dialog(ctx: Context, title: String, body: View, buttons: List<Button> = emptyList()): Dialog {
        val d = ctx.resources.displayMetrics.density
        val dlg = Dialog(ctx)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.box(d, Ui.BG, Ui.BORDER, 14f, 1.5f)
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
        }
        panel.addView(TextView(ctx).apply {
            text = title.uppercase()
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.12f
            setTextColor(Ui.ACCENT)
            setPadding(dp(ctx, 2), 0, 0, dp(ctx, 8))
        })
        val scroll = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false }
        scroll.addView(body)
        panel.addView(scroll, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (buttons.isNotEmpty()) {
            val bar = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            for (b in buttons) {
                bar.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(ctx, 3), dp(ctx, 10), dp(ctx, 3), 0)
                })
            }
            panel.addView(bar)
        }
        val frame = android.widget.FrameLayout(ctx).apply {
            setPadding(dp(ctx, 14), dp(ctx, 24), dp(ctx, 14), dp(ctx, 24))
            addView(panel, android.widget.FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        dlg.setContentView(frame)
        dlg.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        return dlg
    }

    fun button(ctx: Context, label: String, primary: Boolean = false, onClick: () -> Unit): Button =
        Button(ctx).apply {
            text = label
            Ui.styleButton(ctx, this, primary)
            setOnClickListener { onClick() }
        }

    fun column(ctx: Context): LinearLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    fun section(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text.uppercase()
        textSize = 10.5f
        letterSpacing = 0.14f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Ui.DIM)
        setPadding(dp(ctx, 4), dp(ctx, 12), 0, dp(ctx, 4))
    }

    fun note(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 12f
        setTextColor(Ui.DIM)
        setPadding(dp(ctx, 4), dp(ctx, 2), dp(ctx, 4), dp(ctx, 4))
    }

    /** One line with a status dot: filled cyan when ok, hollow grey when not. */
    fun status(ctx: Context, ok: Boolean, text: String): TextView = TextView(ctx).apply {
        this.text = (if (ok) ON else OFF) + "  " + text
        textSize = 13f
        setTextColor(if (ok) Ui.ACCENT else Ui.DIM)
        setPadding(dp(ctx, 4), dp(ctx, 3), dp(ctx, 4), dp(ctx, 3))
    }

    /**
     * A tappable row. state = true / false shows "● ON" / "○ OFF" on the right;
     * null shows the hint (if any) and an arrow, for rows that open something.
     */
    fun row(ctx: Context, label: String, state: Boolean?, hint: String? = null, onClick: () -> Unit): View {
        val d = ctx.resources.displayMetrics.density
        val active = state == true
        val r = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 12), dp(ctx, 12), dp(ctx, 12), dp(ctx, 12))
            val body = if (active) Ui.box(d, Ui.PANEL_HI, Ui.ACCENT, 9f) else Ui.box(d, Ui.PANEL, Ui.BORDER, 9f)
            background = RippleDrawable(ColorStateList.valueOf(0x5519E3F0), body, null)
            isClickable = true
            setOnClickListener { onClick() }
        }
        r.addView(TextView(ctx).apply {
            text = label
            textSize = 14f
            setTextColor(Ui.TEXT)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(TextView(ctx).apply {
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            when (state) {
                true -> {
                    text = "$ON  ON"
                    setTextColor(Ui.ACCENT)
                }
                false -> {
                    text = "$OFF  OFF"
                    setTextColor(Ui.DIM)
                }
                null -> {
                    text = (if (hint.isNullOrEmpty()) "" else "$hint   ") + "›"
                    setTextColor(Ui.DIM)
                }
            }
        })
        val wrap = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(r, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(ctx, 3), 0, dp(ctx, 3))
        })
        return wrap
    }

    /**
     * An ON/OFF row that stays on screen when tapped: [onToggle] changes the setting, then the row
     * redraws itself from [isOn] (so a change that was refused or is still pending shows correctly).
     */
    fun toggle(ctx: Context, label: String, isOn: () -> Boolean, onToggle: () -> Unit): View {
        val holder = android.widget.FrameLayout(ctx)
        fun render() {
            holder.removeAllViews()
            holder.addView(row(ctx, label, isOn()) {
                onToggle()
                render()
            })
        }
        render()
        return holder
    }
}

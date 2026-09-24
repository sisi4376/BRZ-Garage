package com.brz.gauge.trips

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class LicensePlateGeneratorActivity : Activity() {
    private val ink = Color.rgb(24, 29, 39)
    private val muted = Color.rgb(109, 119, 132)
    private val accent = Color.rgb(220, 49, 86)
    private val pageColor = Color.rgb(244, 245, 247)
    private lateinit var input: EditText
    private lateinit var preview: LicensePlatePreviewView
    private lateinit var message: TextView
    private lateinit var copyButton: Button
    private lateinit var installSwitch: Switch
    private lateinit var state: AppState
    private var generated: GeneratedLicensePlate? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        state = AppState(this)
        if (state.customLicensePlate == null) {
            val legacyPlate = getPreferences(MODE_PRIVATE).getString(LEGACY_PLATE_INPUT, null)
                ?.let { LicensePlateGenerator.parse(it).plate?.compactText }
            if (legacyPlate != null) state.customLicensePlate = legacyPlate
        }
        window.statusBarColor = pageColor
        window.navigationBarColor = pageColor
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
            View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        val body = column().apply { setPadding(dp(22), dp(18), dp(22), dp(28)) }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(body) }
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(scroll)

        add(body, Button(this).apply {
            text = "‹ 返回设置"
            isAllCaps = false
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setTextColor(ink)
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            setOnClickListener { finish() }
        })
        add(body, label("自定义车牌", 30f, ink, true), 8)
        add(body, label("小型燃油汽车 · GA 36-2018", 13f, muted), 4)

        val previewCard = card(body, "号牌预览")
        preview = LicensePlatePreviewView(this)
        previewCard.addView(preview, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        add(previewCard, label(
            "按 440 mm × 140 mm 版面、45 mm × 90 mm 字符槽位绘制；“·”为号牌版面间隔符。",
            12f,
            muted,
        ), 12)

        val inputCard = card(body, "输入完整车牌号")
        input = EditText(this).apply {
            hint = "例如：粤B12345"
            textSize = 22f
            isSingleLine = true
            maxLines = 1
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            filters = arrayOf(InputFilter.LengthFilter(12))
            imeOptions = EditorInfo.IME_ACTION_DONE
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(Color.rgb(245, 247, 250), 12).apply {
                setStroke(dp(1), Color.rgb(214, 220, 228))
            }
            setOnEditorActionListener { _, actionId, event ->
                val submit = actionId == EditorInfo.IME_ACTION_DONE ||
                    event?.keyCode == KeyEvent.KEYCODE_ENTER
                if (submit) generatePlate()
                submit
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    message.text = "可输入空格或间隔点，生成时会自动规范化。"
                    message.setTextColor(muted)
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        add(inputCard, input, 12)
        message = label("可输入空格或间隔点，生成时会自动规范化。", 12f, muted)
        add(inputCard, message, 8)

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button("生成预览", primary = true) { generatePlate() },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
        copyButton = button("复制号码") { copyNumber() }.apply { isEnabled = false }
        actions.addView(copyButton,
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) })
        add(inputCard, actions, 12)

        val rules = card(body, "当前支持的规则")
        add(rules, label(
            "• 首位：31 个省、自治区、直辖市简称之一\n" +
                "• 第二位：发牌机关英文字母代号 A～Z\n" +
                "• 序号：必须为 5 位数字或英文字母，字母不超过 2 位\n" +
                "• 序号中的英文字母 I、O 不使用",
            13f, ink), 10)
        add(rules, label(
            "此工具只生成小型燃油汽车蓝牌的屏幕示意，不生成新能源汽车、摩托车、警用、使领馆或临时号牌。",
            12f, muted), 10)

        val installation = card(body, "首页车辆")
        installSwitch = Switch(this).apply {
            text = "将此车牌安装在首页车辆示意图上"
            textSize = 14f
            isChecked = state.showCustomLicensePlate
            isEnabled = state.customLicensePlate != null
            setOnCheckedChangeListener { _, checked ->
                state.showCustomLicensePlate = checked
                Toast.makeText(
                    this@LicensePlateGeneratorActivity,
                    if (checked) "首页车辆将显示此车牌" else "首页车辆已隐藏车牌",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
        add(installation, installSwitch, 10)
        add(installation, label(
            "车牌会先按标准平面版式生成，再透视安装到当前 ZD8 或 ZC6 车型的前保险杠牌照位。",
            12f,
            muted,
        ), 8)

        add(body, label(
            "仅用于界面展示和本项目内的测试。预览使用内置的号牌专用字形复刻资源，并非公安机关防伪专用模具；反光膜、防伪暗记、生产序列标识等实物要素未实现，不可作为正式号牌制作依据。",
            12f, muted), 18)

        val restored = savedInstanceState?.getString(STATE_INPUT)
            ?: state.customLicensePlate
            ?: ""
        if (restored.isNotBlank()) {
            input.setText(restored)
            input.setSelection(input.text.length)
            generatePlate(showToast = false)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_INPUT, input.text.toString())
        super.onSaveInstanceState(outState)
    }

    private fun generatePlate(showToast: Boolean = true) {
        val result = LicensePlateGenerator.parse(input.text.toString())
        val plate = result.plate
        if (plate == null) {
            generated = null
            preview.showPlate(null)
            copyButton.isEnabled = false
            message.text = result.error ?: "无法生成预览"
            message.setTextColor(accent)
            return
        }
        generated = plate
        input.setText(plate.compactText)
        input.setSelection(input.text.length)
        preview.showPlate(plate)
        copyButton.isEnabled = true
        state.customLicensePlate = plate.compactText
        installSwitch.isEnabled = true
        message.text = "已按小型汽车号牌规则生成：${plate.displayText}"
        message.setTextColor(Color.rgb(23, 128, 77))
        if (showToast) Toast.makeText(this, "预览已生成", Toast.LENGTH_SHORT).show()
    }

    private fun copyNumber() {
        val value = generated ?: return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("车牌号", value.compactText))
        Toast.makeText(this, "已复制 ${value.compactText}", Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        setLineSpacing(dp(3).toFloat(), 1f)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }
    private fun add(parent: LinearLayout, view: View, top: Int = 0) {
        parent.addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })
    }
    private fun card(parent: LinearLayout, title: String): LinearLayout = column().apply {
        background = rounded(Color.WHITE, 22)
        setPadding(dp(20), dp(18), dp(20), dp(18))
        add(this, label(title, 13f, muted, true))
        add(parent, this, 14)
    }
    private fun button(text: String, primary: Boolean = false, action: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 14f
        setTextColor(if (primary) Color.WHITE else ink)
        backgroundTintList = ColorStateList.valueOf(if (primary) accent else Color.rgb(233, 237, 242))
        setOnClickListener { action() }
    }

    companion object {
        private const val STATE_INPUT = "license_plate_input"
        private const val LEGACY_PLATE_INPUT = "last_valid_license_plate"
    }
}

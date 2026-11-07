package com.aram.voiceengine.app

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.*
import android.text.Editable
import android.text.TextWatcher

class MainActivity : Activity() {

    private lateinit var root: LinearLayout
    private lateinit var regular: Typeface
    private lateinit var medium: Typeface
    private lateinit var semi: Typeface
    private lateinit var bold: Typeface
    private val prefs by lazy { getSharedPreferences("ishtar_settings", Context.MODE_PRIVATE) }

    private var page = "home"
    private var draft = ""

    private val bg = Color.rgb(12, 10, 20)
    private val panel = Color.rgb(25, 21, 37)
    private val panel2 = Color.rgb(35, 28, 51)
    private val violet = Color.rgb(151, 104, 235)
    private val bright = Color.rgb(195, 163, 255)
    private val white = Color.rgb(247, 243, 255)
    private val muted = Color.rgb(157, 146, 177)
    private val green = Color.rgb(112, 207, 164)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility = 0

        regular = Typeface.createFromAsset(assets, "fonts/IBMPlexSansArabic-Regular.ttf")
        medium = Typeface.createFromAsset(assets, "fonts/IBMPlexSansArabic-Medium.ttf")
        semi = Typeface.createFromAsset(assets, "fonts/IBMPlexSansArabic-SemiBold.ttf")
        bold = Typeface.createFromAsset(assets, "fonts/IBMPlexSansArabic-Bold.ttf")

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        setContentView(root)
        render()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun text(
        value: String,
        size: Float = 14f,
        color: Int = white,
        weight: Int = 0,
        gravity: Int = Gravity.RIGHT
    ): TextView = TextView(this).apply {
        this.text = value
        textSize = size
        setTextColor(color)
        typeface = when (weight) {
            1 -> medium
            2 -> semi
            3 -> bold
            else -> regular
        }
        this.gravity = gravity or Gravity.CENTER_VERTICAL
        textDirection = View.TEXT_DIRECTION_RTL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        setLineSpacing(dp(3).toFloat(), 1f)
    }

    private fun space(parent: LinearLayout, height: Int = 12) {
        parent.addView(View(this), LinearLayout.LayoutParams(1, dp(height)))
    }

    private fun rounded(color: Int, radius: Int = 20): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }

    private fun gradient(radius: Int = 22): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.rgb(53, 37, 79), Color.rgb(27, 22, 41), Color.rgb(20, 17, 31))
        ).apply { cornerRadius = dp(radius).toFloat() }

    private fun card(parent: LinearLayout, fancy: Boolean = false): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(17), dp(16), dp(17), dp(16))
            background = if (fancy) gradient() else rounded(panel)
        }
        parent.addView(box, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(12)
        })
        return box
    }

    private fun label(parent: LinearLayout, value: String, size: Float = 14f,
                      color: Int = white, weight: Int = 0) {
        parent.addView(text(value, size, color, weight))
    }

    private fun action(
        parent: LinearLayout,
        title: String,
        subtitle: String? = null,
        onClick: () -> Unit
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(15), dp(13), dp(15), dp(13))
            background = rounded(panel2, 15)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        row.addView(text(title, 14f, white, 2))
        if (subtitle != null) {
            row.addView(text(subtitle, 11f, muted))
        }
        parent.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(9)
        })
    }

    private fun heading(parent: LinearLayout, title: String, subtitle: String? = null) {
        parent.addView(text(title, 22f, white, 3))
        if (subtitle != null) {
            parent.addView(text(subtitle, 12f, muted))
        }
        space(parent, 16)
    }

    private fun pill(value: String, color: Int = bright): TextView =
        text(value, 11f, color, 2, Gravity.CENTER).apply {
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = rounded(Color.rgb(49, 37, 69), 30)
        }

    private fun render() {
        root.removeAllViews()
        root.addView(buildHeader(), LinearLayout.LayoutParams(-1, dp(72)))

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(0, 0, 0, dp(10))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(19), dp(12), dp(19), dp(18))
        }

        when (page) {
            "home" -> home(content)
            "studio" -> studio(content)
            "voices" -> voices(content)
            "history" -> history(content)
            "settings" -> settings(content)
            "about" -> about(content)
        }

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(buildNavigation(), LinearLayout.LayoutParams(-1, dp(76)))
    }

    private fun buildHeader(): View {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(5))
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        val mark = TextView(this).apply {
            text = "I"
            textSize = 24f
            typeface = bold
            setTextColor(white)
            gravity = Gravity.CENTER
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(177, 124, 255), Color.rgb(91, 54, 153))
            ).apply { cornerRadius = dp(14).toFloat() }
        }
        header.addView(mark, LinearLayout.LayoutParams(dp(43), dp(43)))

        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(11), 0, 0, 0)
        }
        brand.addView(text("ISHTAR", 19f, white, 3).apply {
            textDirection = View.TEXT_DIRECTION_LTR
            gravity = Gravity.CENTER_VERTICAL or Gravity.LEFT
        })
        brand.addView(text("VOICE ENGINE", 9f, muted, 1).apply {
            textDirection = View.TEXT_DIRECTION_LTR
            gravity = Gravity.CENTER_VERTICAL or Gravity.LEFT
            letterSpacing = 0.12f
        })
        header.addView(brand, LinearLayout.LayoutParams(0, -1, 1f))

        val status = text("● قيد التطوير", 10f, bright, 1, Gravity.CENTER).apply {
            setPadding(dp(11), dp(8), dp(11), dp(8))
            background = rounded(Color.rgb(43, 32, 62), 30)
        }
        header.addView(status)
        return header
    }

    private fun home(parent: LinearLayout) {
        space(parent, 8)
        label(parent, "مساحة الصوت تبدأ من هنا", 13f, bright, 1)
        space(parent, 5)
        label(parent, "مرحبًا بك في عشتار", 27f, white, 3)
        label(parent, "محركك الصوتي المستقل، قيد البناء ليمنح كلماتك حياة.", 12f, muted)
        space(parent, 22)

        val hero = card(parent, true)
        hero.addView(pill("ISHTAR  •  LOCAL VOICE ENGINE"))
        space(hero, 16)
        label(hero, "الصوت ليس مجرد كلمات.", 22f, white, 3)
        label(hero, "إنه الطريقة التي تصل بها الكلمات إلى العالم.", 13f, Color.rgb(207, 192, 229))
        space(hero, 20)
        val openStudio = TextView(this).apply {
            text = "افتح استوديو الصوت   ←"
            textSize = 13f
            typeface = semi
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(violet, 14)
            isClickable = true
            setOnClickListener { go("studio") }
        }
        hero.addView(openStudio)

        space(parent, 8)
        heading(parent, "حالة المحرك", "نظرة مباشرة على مكونات عشتار")
        val status = card(parent)
        val statusRows = listOf(
            "ترميز النصوص" to "تم تجهيز الدمج",
            "محرك ONNX" to "بانتظار التنفيذ",
            "توليد الصوت" to "لم يُفعّل بعد",
            "تشغيل الصوت" to "بانتظار الربط"
        )
        statusRows.forEachIndexed { index, item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(9), 0, dp(9))
            }
            row.addView(text(item.first, 13f, white, 1), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(text(item.second, 11f, if (index == 0) bright else muted, 0))
            status.addView(row)
            if (index != statusRows.lastIndex) {
                status.addView(View(this).apply { setBackgroundColor(Color.rgb(48, 41, 61)) },
                    LinearLayout.LayoutParams(-1, dp(1)))
            }
        }

        heading(parent, "انتقال سريع", "كل أدواتك في مكان واحد")
        action(parent, "استوديو الصوت", "اكتب النص وجهّزه للتوليد", { go("studio") })
        action(parent, "مكتبة الأصوات", "استكشف قسم الأصوات", { go("voices") })
        action(parent, "سجل التوليد", "ستظهر أعمالك هنا بعد تفعيل التوليد", { go("history") })
    }

    private fun studio(parent: LinearLayout) {
        heading(parent, "استوديو الصوت", "مساحتك لتحويل النص إلى تجربة صوتية")
        val intro = card(parent, true)
        label(intro, "كل صوت يبدأ بنص.", 21f, white, 3)
        label(intro, "اكتب ما تريد سماعه، وسنجهّز مساحة العمل حتى يكتمل ربط المحرك.", 12f, Color.rgb(207, 192, 229))
        space(parent, 12)

        val editorCard = card(parent)
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(text("النص", 14f, white, 2), LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(pill("AR / EN"))
        editorCard.addView(top)
        space(editorCard, 12)

        val counter = text("${draft.length} / 5000", 10f, muted)
        val input = EditText(this).apply {
            setText(draft)
            setTextColor(white)
            setHintTextColor(muted)
            hint = "اكتب النص الذي تريد تحويله إلى صوت..."
            textSize = 15f
            typeface = regular
            gravity = Gravity.TOP or Gravity.RIGHT
            textDirection = View.TEXT_DIRECTION_RTL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            minLines = 7
            maxLines = 12
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(Color.rgb(17, 14, 26), 14)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    draft = s?.toString() ?: ""
                    counter.text = "${draft.length} / 5000"
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        editorCard.addView(input, LinearLayout.LayoutParams(-1, -2))
        space(editorCard, 9)

        editorCard.addView(counter)
        input.filters = arrayOf(android.text.InputFilter.LengthFilter(5000))

        space(parent, 5)
        label(parent, "إعدادات التوليد", 16f, white, 2)
        space(parent, 10)
        val options = card(parent)
        action(options, "الصوت", "اختيار الصوت سيكون متاحًا بعد ربط النموذج") { go("voices") }
        action(options, "سرعة النطق", "سيتم تفعيل التحكم بها مع محرك التوليد") { notice("إعداد السرعة لم يُربط بعد") }

        val generate = TextView(this).apply {
            text = "◉   التوليد غير متاح حاليًا"
            textSize = 14f
            typeface = semi
            setTextColor(muted)
            gravity = Gravity.CENTER
            setPadding(dp(15), dp(17), dp(15), dp(17))
            background = rounded(Color.rgb(43, 36, 53), 15)
            isClickable = true
            setOnClickListener { notice("سيُفعّل التوليد بعد ربط نماذج MOSS واختبارها") }
        }
        parent.addView(generate, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(8)
            bottomMargin = dp(12)
        })
        label(parent, "لن يتم إنشاء ملف صوتي حتى يكتمل ربط المحرك واختباره.", 11f, muted)
    }

    private fun voices(parent: LinearLayout) {
        heading(parent, "مكتبة الأصوات", "مساحة إدارة الأصوات المتاحة للمحرك")
        val hero = card(parent, true)
        label(hero, "لكل صوت شخصية.", 22f, white, 3)
        label(hero, "ستظهر هنا الأصوات المرفقة مع نموذج MOSS، مع تفاصيلها وإمكانية الاستماع إليها واختيارها بعد تفعيل التوليد.", 12f, Color.rgb(207, 192, 229))
        space(hero, 12)
        hero.addView(pill("اختيار الأصوات لم يُفتح بعد"))
        space(parent, 8)
        val waiting = card(parent)
        label(waiting, "مكتبة الأصوات قيد التجهيز", 16f, white, 2)
        label(waiting, "لن نختار صوتًا افتراضيًا نيابةً عنك. ستتمكن من معاينة الأصوات واختيار ما يناسبك عندما يصبح تشغيلها متاحًا.", 12f, muted)
        space(waiting, 14)
        label(waiting, "الحالة: بانتظار ربط محرك MOSS", 11f, bright, 1)
    }

    private fun history(parent: LinearLayout) {
        heading(parent, "سجل التوليد", "كل أعمالك الصوتية في مكان واحد")
        val empty = card(parent, true)
        space(empty, 22)
        empty.addView(text("◷", 42f, bright, 3, Gravity.CENTER))
        space(empty, 10)
        label(empty, "لا توجد تسجيلات بعد", 19f, white, 2)
        label(empty, "عندما يصبح التوليد الصوتي متاحًا، ستجد هنا النصوص والملفات الصوتية السابقة.", 12f, Color.rgb(207, 192, 229))
        space(empty, 15)
        val goStudio = TextView(this).apply {
            text = "الانتقال إلى الاستوديو"
            textSize = 13f
            typeface = semi
            setTextColor(white)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = rounded(violet, 13)
            setOnClickListener { go("studio") }
        }
        empty.addView(goStudio)
    }

    private fun settings(parent: LinearLayout) {
        heading(parent, "الإعدادات", "تحكّم بتجربة استخدام عشتار")
        val appearance = card(parent)
        label(appearance, "التجربة والمظهر", 15f, white, 2)
        space(appearance, 12)
        settingSwitch(appearance, "الحركة والانتقالات", "تفعيل الحركة داخل الواجهة", "animations", true)
        settingSwitch(appearance, "الاهتزاز اللمسي", "استجابة لمسية عند التفاعل", "haptics", false)

        val system = card(parent)
        label(system, "إدارة التطبيق", 15f, white, 2)
        space(system, 10)
        action(system, "حالة المحرك", "عرض وضع المكونات الحالية") { go("home") }
        action(system, "معلومات التطبيق", "الإصدار والتفاصيل التقنية") { go("about") }

        val info = card(parent)
        label(info, "ISHTAR", 18f, white, 3)
        label(info, "محرك صوتي مستقل قيد التطوير.", 12f, muted)
        space(info, 5)
        label(info, "الإصدار 0.1.0", 11f, bright, 1)
    }

    private fun settingSwitch(
        parent: LinearLayout,
        title: String,
        subtitle: String,
        key: String,
        default: Boolean
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(7), 0, dp(7))
        }
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(text(title, 13f, white, 1))
        labels.addView(text(subtitle, 10f, muted))
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        val toggle = Switch(this).apply {
            isChecked = prefs.getBoolean(key, default)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(key, checked).apply()
            }
            buttonTintList = android.content.res.ColorStateList.valueOf(violet)
        }
        row.addView(toggle)
        parent.addView(row)
    }

    private fun about(parent: LinearLayout) {
        heading(parent, "حول عشتار", "هوية المحرك ومعلومات الإصدار")
        val hero = card(parent, true)
        hero.addView(text("ISHTAR", 30f, white, 3, Gravity.CENTER))
        hero.addView(text("VOICE ENGINE", 11f, bright, 1, Gravity.CENTER))
        space(hero, 18)
        label(hero, "محرك صوتي مستقل، نصممه ليعمل محليًا على أجهزة Android، مع بنية قابلة للتوسع والتكامل مع مشاريع أخرى.", 13f, Color.rgb(218, 207, 234))
        val details = card(parent)
        label(details, "معلومات الإصدار", 15f, white, 2)
        space(details, 10)
        label(details, "اسم التطبيق: ISHTAR", 12f, muted)
        label(details, "الإصدار: 0.1.0", 12f, muted)
        label(details, "المنصة: Android", 12f, muted)
        label(details, "حالة المشروع: قيد التطوير", 12f, bright, 1)
        action(parent, "العودة إلى الإعدادات", "إدارة تجربة التطبيق") { go("settings") }
    }

    private fun buildNavigation(): View {
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(7), dp(7), dp(7), dp(8))
            background = rounded(Color.rgb(20, 17, 29), 20)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        val items = listOf(
            Triple("home", "⌂", "الرئيسية"),
            Triple("studio", "◉", "الاستوديو"),
            Triple("voices", "♫", "الأصوات"),
            Triple("history", "▤", "السجل"),
            Triple("settings", "⚙", "الإعدادات")
        )

        items.forEach { item ->
            val active = page == item.first
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(2), dp(6), dp(2), dp(5))
                background = if (active) rounded(Color.rgb(54, 39, 76), 13) else null
                isClickable = true
                isFocusable = true
                setOnClickListener { go(item.first) }
            }
            cell.addView(text(item.second, 20f, if (active) bright else muted, 2, Gravity.CENTER))
            cell.addView(text(item.third, 9f, if (active) white else muted, if (active) 2 else 0, Gravity.CENTER))
            nav.addView(cell, LinearLayout.LayoutParams(0, -1, 1f))
        }
        return nav
    }

    private fun go(target: String) {
        page = target
        render()
    }

    private fun notice(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

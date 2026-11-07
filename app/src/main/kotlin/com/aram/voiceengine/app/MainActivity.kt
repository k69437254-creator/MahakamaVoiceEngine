package com.aram.voiceengine.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher

class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var regular: Typeface
    private lateinit var medium: Typeface
    private lateinit var semi: Typeface
    private lateinit var bold: Typeface

    private val prefs by lazy {
        getSharedPreferences("ishtar_settings", Context.MODE_PRIVATE)
    }

    private var page = "home"
    private var draft = ""
    private var selectedAudioUri: String? = null
    private var drawerOverlay: View? = null
    private var pendingAudioExportUri: Uri? = null

    private val bg = Color.rgb(10, 8, 17)
    private val surface = Color.rgb(22, 18, 32)
    private val surface2 = Color.rgb(32, 26, 46)
    private val surface3 = Color.rgb(42, 34, 59)
    private val violet = Color.rgb(151, 105, 238)
    private val violetBright = Color.rgb(198, 171, 255)
    private val white = Color.rgb(247, 244, 252)
    private val muted = Color.rgb(165, 155, 181)
    private val green = Color.rgb(112, 207, 164)
    private val amber = Color.rgb(230, 181, 104)

    private companion object {
        const val REQUEST_OPEN_AUDIO = 7101
        const val REQUEST_SAVE_TEXT = 7102
        const val REQUEST_SAVE_AUDIO = 7103
        const val REQUEST_NOTIFICATIONS = 7104
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility = 0

        regular = Typeface.createFromAsset(assets, "fonts/IBMPlexSansArabic-Regular.ttf")
        medium = Typeface.createFromAsset(assets, "fonts/IBMPlexSansArabic-Medium.ttf")
        semi = Typeface.createFromAsset(assets, "fonts/IBMPlexSansArabic-SemiBold.ttf")
        bold = Typeface.createFromAsset(assets, "fonts/IBMPlexSansArabic-Bold.ttf")

        draft = prefs.getString("draft_text", "") ?: ""
        selectedAudioUri = prefs.getString("selected_audio_uri", null)

        root = FrameLayout(this).apply {
            setBackgroundColor(bg)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        setContentView(root)
        render()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun panel(radius: Int = 20, color: Int = surface): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }

    private fun heroBackground(radius: Int = 24): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                Color.rgb(65, 43, 94),
                Color.rgb(37, 28, 54),
                Color.rgb(19, 16, 29)
            )
        ).apply { cornerRadius = dp(radius).toFloat() }

    private fun label(
        value: String,
        size: Float = 14f,
        color: Int = white,
        weight: Int = 0,
        gravity: Int = Gravity.RIGHT
    ): TextView = TextView(this).apply {
        text = value
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

    private fun gap(parent: LinearLayout, height: Int = 12) {
        parent.addView(View(this), LinearLayout.LayoutParams(1, dp(height)))
    }

    private fun card(parent: LinearLayout, hero: Boolean = false): LinearLayout {
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(17), dp(16), dp(17), dp(16))
            background = if (hero) heroBackground() else panel()
        }
        parent.addView(view, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(13)
        })
        return view
    }

    private fun pill(value: String, color: Int = violetBright): TextView =
        label(value, 10f, color, 2, Gravity.CENTER).apply {
            setPadding(dp(11), dp(7), dp(11), dp(7))
            background = panel(30, Color.rgb(53, 40, 73))
        }

    private fun sectionTitle(parent: LinearLayout, title: String, subtitle: String? = null) {
        parent.addView(label(title, 18f, white, 3))
        if (subtitle != null) parent.addView(label(subtitle, 11f, muted))
        gap(parent, 13)
    }

    private fun actionCard(
        parent: LinearLayout,
        title: String,
        subtitle: String? = null,
        enabled: Boolean = true,
        onClick: () -> Unit
    ) {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(15), dp(13), dp(15), dp(13))
            background = panel(15, surface2)
            isClickable = enabled
            isFocusable = enabled
            alpha = if (enabled) 1f else 0.55f
            setOnClickListener { if (enabled) { haptic(); onClick() } }
        }
        item.addView(label(title, 13f, white, 2))
        if (subtitle != null) item.addView(label(subtitle, 10f, muted))
        parent.addView(item, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(9)
        })
    }

    private fun primaryButton(value: String, onClick: () -> Unit): TextView =
        label(value, 13f, Color.WHITE, 2, Gravity.CENTER).apply {
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = panel(14, violet)
            isClickable = true
            isFocusable = true
            setOnClickListener { haptic(); onClick() }
        }

    private fun secondaryButton(value: String, onClick: () -> Unit): TextView =
        label(value, 12f, violetBright, 2, Gravity.CENTER).apply {
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = panel(14, surface3)
            isClickable = true
            isFocusable = true
            setOnClickListener { haptic(); onClick() }
        }

    private fun haptic() {
        if (prefs.getBoolean("haptics", false)) {
            root.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    private fun render() {
        drawerOverlay = null
        root.removeAllViews()

        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(bg)
        }
        shell.addView(buildHeader(), LinearLayout.LayoutParams(-1, dp(74)))

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(0, 0, 0, dp(12))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(19), dp(12), dp(19), dp(20))
        }

        when (page) {
            "home" -> home(content)
            "studio" -> studio(content)
            "voices" -> voices(content)
            "history" -> history(content)
            "settings" -> settings(content)
            "about" -> about(content)
            else -> home(content)
        }

        scroll.addView(content)
        shell.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        shell.addView(buildFooter(), LinearLayout.LayoutParams(-1, dp(34)))
        root.addView(shell, FrameLayout.LayoutParams(-1, -1))
    }

    private fun buildHeader(): View {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(17), dp(7), dp(17), dp(5))
        }

        val menu = label("☰", 21f, white, 2, Gravity.CENTER).apply {
            background = panel(14, surface2)
            isClickable = true
            isFocusable = true
            contentDescription = "فتح القائمة الجانبية"
            setOnClickListener { haptic(); showDrawer() }
        }
        header.addView(menu, LinearLayout.LayoutParams(dp(45), dp(45)))

        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(11), 0, 0, 0)
        }
        brand.addView(label("ISHTAR", 18f, white, 3))
        brand.addView(label("VOICE ENGINE", 9f, muted, 1))
        header.addView(brand, LinearLayout.LayoutParams(0, -1, 1f))

        header.addView(pill("نسخة العمل"))
        return header
    }

    private fun buildFooter(): View = label(
        "ISHTAR  ·  مساحة صوتية مستقلة",
        9f,
        Color.rgb(111, 102, 128),
        1,
        Gravity.CENTER
    )

    private fun home(parent: LinearLayout) {
        gap(parent, 7)
        parent.addView(label("مساحتك الخاصة للصوت والكلمة", 12f, violetBright, 1))
        gap(parent, 5)
        parent.addView(label("مرحبًا بك في عشتار", 26f, white, 3))
        parent.addView(label("نصّك ينتظر أن يجد صوته.", 13f, muted))
        gap(parent, 20)

        val hero = card(parent, true)
        hero.addView(pill("ISHTAR  /  VOICE WORKSPACE"))
        gap(hero, 17)
        hero.addView(label("امنح كلماتك\nمساحةً لتُسمَع.", 25f, white, 3))
        gap(hero, 8)
        hero.addView(label(
            "استوديو هادئ، أدوات واضحة، وبنية مستقلة تتطور لتصبح محركًا صوتيًا قابلًا للدمج.",
            12f,
            Color.rgb(215, 203, 232)
        ))
        gap(hero, 17)
        hero.addView(primaryButton("افتح استوديو الصوت  ←") { go("studio") })

        sectionTitle(parent, "حالة المشروع", "ما هو موجود فعليًا في النسخة الحالية")
        val status = card(parent)
        statusLine(status, "واجهة التطبيق", "موجودة وقابلة للتطوير", green)
        statusLine(status, "ترميز SentencePiece", "طبقة JNI موجودة", green)
        statusLine(status, "تنفيذ ONNX داخل Android", "لم يُربط بعد", amber)
        statusLine(status, "توليد الصوت الفعلي", "غير متاح في هذه النسخة", amber)
        statusLine(status, "تشغيل الملفات بالخلفية", "مدعوم عبر خدمة مستقلة", green, false)

        sectionTitle(parent, "انتقال سريع", "اختر المساحة التي تريد العمل فيها")
        actionCard(parent, "استوديو الصوت", "كتابة النص وتصديره ومراجعة ملف صوتي", true) { go("studio") }
        actionCard(parent, "مكتبة الأصوات", "قسم مخصص للأصوات؛ الاختيار مؤجل بقرارك", true) { go("voices") }
        actionCard(parent, "سجل الأعمال", "سيعرض التسجيلات بعد ربط التوليد الحقيقي", true) { go("history") }
    }

    private fun statusLine(
        parent: LinearLayout,
        name: String,
        state: String,
        stateColor: Int,
        divider: Boolean = true
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(0, dp(10), 0, dp(10))
        }
        row.addView(label(name, 12f, white, 1), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(label(state, 10f, stateColor, 1, Gravity.LEFT))
        parent.addView(row)
        if (divider) parent.addView(View(this).apply {
            setBackgroundColor(Color.rgb(52, 43, 67))
        }, LinearLayout.LayoutParams(-1, dp(1)))
    }

    private fun studio(parent: LinearLayout) {
        sectionTitle(parent, "استوديو الصوت", "مساحة تجهيز النصوص والملفات الصوتية")
        val intro = card(parent, true)
        intro.addView(label("كل تجربة تبدأ\nبكلماتك.", 22f, white, 3))
        gap(intro, 7)
        intro.addView(label(
            "اكتب النص الذي تريد تحويله لاحقًا إلى صوت. سيبقى مسودتك محفوظة على هذا الجهاز.",
            12f,
            Color.rgb(215, 203, 232)
        ))

        val editorCard = card(parent)
        val editorHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        editorHead.addView(label("النص", 14f, white, 2), LinearLayout.LayoutParams(0, -2, 1f))
        editorHead.addView(pill("AR  /  EN"))
        editorCard.addView(editorHead)
        gap(editorCard, 12)

        val counter = label("${draft.length} / 5000", 10f, muted, 0)
        val input = EditText(this).apply {
            setText(draft)
            setTextColor(white)
            setHintTextColor(Color.rgb(124, 114, 145))
            hint = "اكتب النص الذي تريد سماعه..."
            textSize = 15f
            typeface = regular
            gravity = Gravity.TOP or Gravity.RIGHT
            textDirection = View.TEXT_DIRECTION_RTL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            minLines = 7
            maxLines = 12
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = panel(15, Color.rgb(14, 12, 22))
            filters = arrayOf(InputFilter.LengthFilter(5000))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    draft = s?.toString() ?: ""
                    counter.text = "${draft.length} / 5000"
                    prefs.edit().putString("draft_text", draft).apply()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        editorCard.addView(input, LinearLayout.LayoutParams(-1, -2))
        gap(editorCard, 8)
        editorCard.addView(counter)

        val textActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        textActions.addView(primaryButton("حفظ النص") { saveText() },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
        textActions.addView(secondaryButton("مشاركة النص") { shareText() },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) })
        parent.addView(textActions, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(13)
        })

        sectionTitle(parent, "محرك التوليد", "لن نظهر زرًا يوحي بأن التوليد يعمل قبل ربط المحرك")
        val engineCard = card(parent)
        engineCard.addView(pill("ENGINE NOT CONNECTED", amber))
        gap(engineCard, 10)
        engineCard.addView(label("التوليد غير متاح بعد", 16f, white, 2))
        engineCard.addView(label(
            "توجد واجهات المحرك والترميز، لكن تنفيذ ONNX وملفات النموذج غير موصولة بالتطبيق حتى الآن. لذلك أبقينا التوليد معطّلًا بوضوح.",
            11f,
            muted
        ))
        gap(engineCard, 12)
        val disabled = label("◉   التوليد غير متاح حاليًا", 13f, muted, 2, Gravity.CENTER).apply {
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = panel(14, Color.rgb(42, 36, 51))
            alpha = 0.75f
        }
        engineCard.addView(disabled)

        sectionTitle(parent, "الصوت والاختيار", "لا يوجد صوت افتراضي معتمد")
        actionCard(parent, "مكتبة الأصوات", "اختيار الأصوات مؤجل حتى اكتمال المحرك وبموافقتك", true) { go("voices") }

        sectionTitle(parent, "تشغيل ملف صوتي", "تشغيل ملف موجود مع إمكانية استمرار التشغيل بالخلفية")
        val audioCard = card(parent)
        if (selectedAudioUri.isNullOrBlank()) {
            audioCard.addView(label("لم تختر ملفًا صوتيًا بعد", 13f, white, 2))
            audioCard.addView(label("يمكنك اختيار ملف صوتي محفوظ للاستماع إليه، وسيظهر إشعار تحكم دائم أثناء التشغيل.", 11f, muted))
        } else {
            audioCard.addView(label("يوجد ملف صوتي محدد", 13f, white, 2))
            audioCard.addView(label(selectedAudioUri ?: "", 9f, muted))
        }
        gap(audioCard, 12)
        audioCard.addView(primaryButton("اختيار ملف صوتي") { openAudioPicker() })
        if (!selectedAudioUri.isNullOrBlank()) {
            gap(audioCard, 8)
            audioCard.addView(secondaryButton("تشغيل في الخلفية") { playSelectedAudio() })
            gap(audioCard, 8)
            val exportRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
            }
            exportRow.addView(secondaryButton("حفظ نسخة") { saveAudioCopy() },
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(5) })
            exportRow.addView(secondaryButton("مشاركة الصوت") { shareAudio() },
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(5) })
            audioCard.addView(exportRow)
        }
    }

    private fun voices(parent: LinearLayout) {
        sectionTitle(parent, "مكتبة الأصوات", "مساحة إدارة الأصوات التي سيتعرف إليها المحرك")
        val hero = card(parent, true)
        hero.addView(label("لكل صوت\nحضوره الخاص.", 23f, white, 3))
        gap(hero, 8)
        hero.addView(label(
            "ستعرض هذه المكتبة الأصوات المتاحة وبياناتها ومعايناتها، بعد اكتمال مسار التوليد الصوتي.",
            12f,
            Color.rgb(215, 203, 232)
        ))
        gap(hero, 13)
        hero.addView(pill("VOICE SELECTION POSTPONED", amber))

        val note = card(parent)
        note.addView(label("لا اختيار نيابةً عنك", 15f, white, 2))
        gap(note, 6)
        note.addView(label(
            "لن أضيف صوتًا افتراضيًا أو أغيّر خيارات الأصوات. إضافة الأصوات العربية واختيارها مؤجلان إلى ما بعد اكتمال المحرك والتطبيق، وفق قرارك.",
            12f,
            muted
        ))
    }

    private fun history(parent: LinearLayout) {
        sectionTitle(parent, "سجل الأعمال", "مكان واحد للنصوص والملفات التي ينتجها المحرك")
        val empty = card(parent, true)
        gap(empty, 17)
        empty.addView(label("◷", 38f, violetBright, 3, Gravity.CENTER))
        gap(empty, 8)
        empty.addView(label("لا توجد أعمال مولّدة بعد", 17f, white, 2, Gravity.CENTER))
        gap(empty, 6)
        empty.addView(label(
            "لن نسجل ملفات وهمية. سيظهر هنا كل نص وصوت بعد أن يصبح التوليد الحقيقي متاحًا.",
            11f,
            Color.rgb(215, 203, 232),
            0,
            Gravity.CENTER
        ))
        gap(empty, 14)
        empty.addView(primaryButton("العودة إلى الاستوديو") { go("studio") })
    }

    private fun settings(parent: LinearLayout) {
        sectionTitle(parent, "الإعدادات", "إعدادات محلية تحفظ تفضيلاتك على هذا الجهاز")
        val preferences = card(parent)
        preferences.addView(label("التجربة", 15f, white, 2))
        gap(preferences, 10)
        settingSwitch(preferences, "الحركة والانتقالات", "تفضيل الحركة داخل الواجهة", "animations", true)
        settingSwitch(preferences, "الاهتزاز اللمسي", "استجابة لمسية عند الضغط", "haptics", false)

        val background = card(parent)
        background.addView(label("العمل في الخلفية", 15f, white, 2))
        gap(background, 6)
        background.addView(label(
            "عند تشغيل ملف صوتي، تتولى خدمة مستقلة إبقاء التشغيل قائمًا بعد مغادرة التطبيق، مع إشعار دائم يتضمن الإيقاف والاستئناف.",
            11f,
            muted
        ))
        gap(background, 8)
        background.addView(pill("FOREGROUND PLAYBACK SERVICE", green))

        val app = card(parent)
        app.addView(label("التطبيق", 15f, white, 2))
        gap(app, 7)
        app.addView(label("ISHTAR Voice Engine", 12f, muted))
        app.addView(label("الإصدار 0.1.0", 11f, violetBright, 1))
        actionCard(parent, "حول عشتار", "الهوية والبنية التقنية", true) { go("about") }
    }

    private fun settingSwitch(
        parent: LinearLayout,
        title: String,
        subtitle: String,
        key: String,
        defaultValue: Boolean
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(0, dp(8), 0, dp(8))
        }
        val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        words.addView(label(title, 12f, white, 1))
        words.addView(label(subtitle, 10f, muted))
        row.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        val toggle = Switch(this).apply {
            isChecked = prefs.getBoolean(key, defaultValue)
            buttonTintList = android.content.res.ColorStateList.valueOf(violet)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(key, checked).apply()
            }
        }
        row.addView(toggle)
        parent.addView(row)
    }

    private fun about(parent: LinearLayout) {
        sectionTitle(parent, "حول عشتار", "هوية المحرك ومعلومات النسخة")
        val hero = card(parent, true)
        hero.addView(label("ISHTAR", 31f, white, 3, Gravity.CENTER))
        hero.addView(label("VOICE ENGINE", 10f, violetBright, 1, Gravity.CENTER))
        gap(hero, 18)
        hero.addView(label(
            "مشروع مستقل لبناء محرك تحويل النص إلى كلام على Android، ببنية قابلة للفصل والاستدعاء من تطبيقات أخرى.",
            12f,
            Color.rgb(215, 203, 232)
        ))
        val details = card(parent)
        details.addView(label("البنية الحالية", 14f, white, 2))
        gap(details, 8)
        details.addView(label("واجهة Android: موجودة", 11f, muted))
        details.addView(label("SentencePiece JNI: موجود", 11f, muted))
        details.addView(label("ONNX Android runtime: غير مكتمل", 11f, amber))
        details.addView(label("ملفات النموذج: خارج المستودع", 11f, amber))
        details.addView(label("المنصة: Android  ·  RTL", 11f, muted))
    }

    private fun showDrawer() {
        if (drawerOverlay != null) return
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
        }
        val scrim = View(this).apply {
            setBackgroundColor(Color.argb(175, 0, 0, 0))
            setOnClickListener { closeDrawer() }
        }
        overlay.addView(scrim, FrameLayout.LayoutParams(-1, -1))

        val drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(18), dp(26), dp(18), dp(18))
            background = panel(0, Color.rgb(19, 15, 29))
            isClickable = true
        }
        val params = FrameLayout.LayoutParams(dp(304), -1, Gravity.RIGHT)
        overlay.addView(drawer, params)
        drawer.addView(label("ISHTAR", 24f, white, 3))
        drawer.addView(label("VOICE ENGINE", 9f, violetBright, 1))
        gap(drawer, 22)
        drawer.addView(pill("القائمة الرئيسية"))
        gap(drawer, 14)
        drawerItem(drawer, "⌂", "الرئيسية", "home")
        drawerItem(drawer, "◉", "استوديو الصوت", "studio")
        drawerItem(drawer, "♫", "مكتبة الأصوات", "voices")
        drawerItem(drawer, "▤", "سجل الأعمال", "history")
        drawerItem(drawer, "⚙", "الإعدادات", "settings")
        drawerItem(drawer, "ⓘ", "حول عشتار", "about")
        val spacer = View(this)
        drawer.addView(spacer, LinearLayout.LayoutParams(1, 0, 1f))
        drawer.addView(label("تُفتح هذه القائمة عند الطلب فقط.", 10f, muted))
        drawer.addView(label("ISHTAR  ·  0.1.0", 9f, Color.rgb(112, 102, 132), 1))

        drawerOverlay = overlay
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        if (prefs.getBoolean("animations", true)) {
            drawer.translationX = dp(28).toFloat()
            drawer.animate().translationX(0f).setDuration(180).start()
        }
    }

    private fun drawerItem(parent: LinearLayout, icon: String, title: String, target: String) {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(13), dp(13), dp(13), dp(13))
            background = panel(14, if (page == target) surface3 else Color.TRANSPARENT)
            isClickable = true
            isFocusable = true
            setOnClickListener { haptic(); closeDrawer(); go(target) }
        }
        item.addView(label(icon, 18f, violetBright, 2, Gravity.CENTER),
            LinearLayout.LayoutParams(dp(32), dp(30)))
        item.addView(label(title, 13f, white, if (page == target) 2 else 1))
        parent.addView(item, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(5)
        })
    }

    private fun closeDrawer() {
        val overlay = drawerOverlay ?: return
        if (prefs.getBoolean("animations", true)) {
            overlay.animate().alpha(0f).setDuration(120).withEndAction {
                root.removeView(overlay)
            }.start()
        } else {
            root.removeView(overlay)
        }
        drawerOverlay = null
    }

    private fun go(target: String) {
        page = target
        render()
    }

    private fun saveText() {
        if (draft.isBlank()) {
            notice("اكتب نصًا أولًا قبل التصدير")
            return
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "ISHTAR-Text.txt")
        }
        startActivityForResult(intent, REQUEST_SAVE_TEXT)
    }

    private fun shareText() {
        if (draft.isBlank()) {
            notice("لا يوجد نص لمشاركته")
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, draft)
        }
        startActivity(Intent.createChooser(intent, "مشاركة نص عشتار"))
    }

    private fun openAudioPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_OPEN_AUDIO)
    }

    private fun playSelectedAudio() {
        val value = selectedAudioUri
        if (value.isNullOrBlank()) {
            notice("اختر ملفًا صوتيًا أولًا")
            return
        }
        requestNotificationPermissionIfNeeded()
        val intent = Intent(this, IshtarPlaybackService::class.java).apply {
            action = IshtarPlaybackService.ACTION_PLAY
            putExtra(IshtarPlaybackService.EXTRA_URI, value)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
            notice("بدأ تجهيز التشغيل في الخلفية")
        } catch (error: Exception) {
            notice("تعذر بدء تشغيل الملف: ${error.message ?: "خطأ غير معروف"}")
        }
    }

    private fun saveAudioCopy() {
        val value = selectedAudioUri
        if (value.isNullOrBlank()) {
            notice("اختر ملفًا صوتيًا أولًا")
            return
        }
        pendingAudioExportUri = Uri.parse(value)
        val mime = contentResolver.getType(pendingAudioExportUri!!) ?: "audio/*"
        val extension = when (mime.lowercase()) {
            "audio/mpeg" -> "mp3"
            "audio/ogg" -> "ogg"
            "audio/mp4" -> "m4a"
            else -> "wav"
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
            putExtra(Intent.EXTRA_TITLE, "ISHTAR-Audio.$extension")
        }
        startActivityForResult(intent, REQUEST_SAVE_AUDIO)
    }

    private fun shareAudio() {
        val value = selectedAudioUri
        if (value.isNullOrBlank()) {
            notice("لا يوجد ملف صوتي لمشاركته")
            return
        }
        val uri = Uri.parse(value)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = contentResolver.getType(uri) ?: "audio/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "مشاركة ملف عشتار الصوتي"))
    }

    @Deprecated("Uses the platform document picker to keep the app free of extra dependencies")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val destination = data?.data ?: return

        when (requestCode) {
            REQUEST_OPEN_AUDIO -> {
                try {
                    contentResolver.takePersistableUriPermission(
                        destination,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {
                    // Some document providers do not offer persistable permissions.
                }
                selectedAudioUri = destination.toString()
                prefs.edit().putString("selected_audio_uri", selectedAudioUri).apply()
                notice("تم اختيار الملف الصوتي")
                render()
            }
            REQUEST_SAVE_TEXT -> {
                try {
                    contentResolver.openOutputStream(destination)?.use { stream ->
                        stream.write(draft.toByteArray(Charsets.UTF_8))
                    } ?: throw IllegalStateException("تعذر فتح الملف للحفظ")
                    notice("تم حفظ النص")
                } catch (error: Exception) {
                    notice("تعذر حفظ النص: ${error.message ?: "خطأ"}")
                }
            }
            REQUEST_SAVE_AUDIO -> {
                val source = pendingAudioExportUri
                if (source == null) {
                    notice("مصدر الملف الصوتي غير متاح")
                    return
                }
                try {
                    val input = contentResolver.openInputStream(source)
                        ?: throw IllegalStateException("تعذر قراءة الملف الصوتي")
                    val output = contentResolver.openOutputStream(destination)
                        ?: throw IllegalStateException("تعذر فتح وجهة الحفظ")
                    input.use { sourceStream ->
                        output.use { targetStream -> sourceStream.copyTo(targetStream) }
                    }
                    notice("تم حفظ نسخة من الملف الصوتي")
                } catch (error: Exception) {
                    notice("تعذر حفظ الصوت: ${error.message ?: "خطأ"}")
                } finally {
                    pendingAudioExportUri = null
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    private fun notice(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    @Deprecated("Back navigation is handled by the activity")
    override fun onBackPressed() {
        if (drawerOverlay != null) {
            closeDrawer()
            return
        }
        if (page != "home") {
            go("home")
            return
        }
        super.onBackPressed()
    }
}

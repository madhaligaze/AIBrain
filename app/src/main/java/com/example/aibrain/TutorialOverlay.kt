package com.example.aibrain

import android.app.Activity
import android.content.SharedPreferences
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView

class TutorialOverlay(
    private val activity: Activity,
    private val prefs: SharedPreferences,
    private val onDone: () -> Unit
) {
    private var root: View? = null
    private var step: Int = 0

    companion object {
        const val PREF_TUTORIAL_DONE = "tutorial_done_v2"
    }

    private data class Step(val title: String, val body: String)

    private val steps = listOf(
        Step(
            "Шаг 1 из 3 — Опора",
            "Нажмите кнопку «Опора» и наведите камеру на пол рядом с объектом.\n\nПоявится маркер — это точка привязки для AI-анализа.\n\nДобавьте 1–2 опоры вокруг конструкции."
        ),
        Step(
            "Шаг 2 из 3 — Сканирование",
            "Медленно обойдите опоры, снимая с разных углов.\n\nШкала «Ready» станет зелёной, когда покрытие достаточно.\n\nПоднимайте и опускайте камеру — нужны виды сверху и снизу."
        ),
        Step(
            "Шаг 3 из 3 — Анализ",
            "Нажмите кнопку «Анализ».\n\nAI построит 3D-модель и предложит варианты конструкции.\n\nВыберите вариант и нажмите «Принять»."
        )
    )

    fun showIfNeeded() {
        if (prefs.getBoolean(PREF_TUTORIAL_DONE, false)) {
            onDone()
            return
        }
        show()
    }

    fun show() {
        val view = LayoutInflater.from(activity).inflate(R.layout.overlay_tutorial, null)
        val title: TextView = view.findViewById(R.id.tv_tutorial_title)
        val body: TextView = view.findViewById(R.id.tv_tutorial_body)
        val next: Button = view.findViewById(R.id.btn_tutorial_next)
        val skip: Button = view.findViewById(R.id.btn_tutorial_skip)
        val cbNoMore: CheckBox = view.findViewById(R.id.cb_tutorial_no_more)

        fun render() {
            val s = steps[step]
            title.text = s.title
            body.text = s.body
            next.text = if (step == steps.lastIndex) "ГОТОВО" else "ДАЛЕЕ"
        }

        next.setOnClickListener {
            if (step < steps.lastIndex) {
                step++
                render()
            } else {
                if (cbNoMore.isChecked) prefs.edit().putBoolean(PREF_TUTORIAL_DONE, true).apply()
                onDone()
            }
        }

        skip.setOnClickListener {
            if (cbNoMore.isChecked) prefs.edit().putBoolean(PREF_TUTORIAL_DONE, true).apply()
            onDone()
        }

        render()

        val decor = activity.window.decorView as ViewGroup
        decor.addView(
            view,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        root = view
    }

    fun resetTutorial() {
        prefs.edit().putBoolean(PREF_TUTORIAL_DONE, false).apply()
    }

    fun dismiss() {
        val view = root ?: return
        val decor = activity.window.decorView as ViewGroup
        decor.removeView(view)
        root = null
    }
}

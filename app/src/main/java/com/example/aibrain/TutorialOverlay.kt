package com.example.aibrain

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView

class TutorialOverlay(
    private val activity: Activity,
    private val onDone: () -> Unit
) {
    private var root: View? = null
    private var step: Int = 0

    private val steps = listOf(
        "1) Connect: проверь /health и перейди в Main",
        "2) Scan: наведи камеру, поставь 2+ маркера",
        "3) Analyze: отправь кадры + anchors, получи варианты",
        "4) Select: выбери вариант, проверь physics, Accept",
        "5) Export: сохрани GLB и отчёт"
    )

    fun show() {
        val inflater = LayoutInflater.from(activity)
        val view = inflater.inflate(R.layout.overlay_tutorial, null)
        val title: TextView = view.findViewById(R.id.tv_tutorial_title)
        val body: TextView = view.findViewById(R.id.tv_tutorial_body)
        val next: Button = view.findViewById(R.id.btn_tutorial_next)
        val skip: Button = view.findViewById(R.id.btn_tutorial_skip)

        fun render() {
            title.text = "Tutorial (${step + 1}/${steps.size})"
            body.text = steps[step]
            next.text = if (step == steps.lastIndex) "DONE" else "NEXT"
        }

        next.setOnClickListener {
            if (step < steps.lastIndex) {
                step++
                render()
            } else {
                onDone()
            }
        }

        skip.setOnClickListener { onDone() }

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

    fun dismiss() {
        val view = root ?: return
        val decor = activity.window.decorView as ViewGroup
        decor.removeView(view)
        root = null
    }
}

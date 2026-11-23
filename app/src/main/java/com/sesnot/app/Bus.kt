package com.sesnot.app

import android.content.Context
import android.widget.Toast

object Bus {
    @Volatile var running = false
    var overlay: ((String) -> Unit)? = null
    var onChange: (() -> Unit)? = null

    fun show(ctx: Context, msg: String) {
        val o = overlay
        if (o != null) o(msg)
        else Toast.makeText(ctx.applicationContext, msg, Toast.LENGTH_SHORT).show()
    }

    fun showPartial(msg: String) {
        overlay?.invoke(msg)
    }

    fun changed() {
        onChange?.invoke()
    }
}

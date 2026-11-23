package com.sesnot.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var list: ListView
    private var shown: List<Note> = emptyList()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NoteStore.init(this)

        status = TextView(this).apply { textSize = 15f }
        toggle = Button(this).apply { setOnClickListener { onToggle() } }
        val accBtn = Button(this).apply {
            text = "Erişilebilirlik ayarlarını aç"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        val newBtn = Button(this).apply {
            text = "Yeni not (elle)"
            setOnClickListener { editNote(null) }
        }
        list = ListView(this)
        list.setOnItemClickListener { _, _, pos, _ -> editNote(shown[pos]) }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(status)
            addView(toggle)
            addView(accBtn)
            addView(newBtn)
            addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        Bus.onChange = { refresh() }
        refresh()
    }

    override fun onPause() {
        Bus.onChange = null
        super.onPause()
    }

    private fun refresh() {
        val acc = if (Bus.overlay != null) "açık" else "kapalı (üstte metin yerine kısa mesaj çıkar)"
        status.text = "Dinleme: ${if (Bus.running) "açık" else "kapalı"}\nErişilebilirlik: $acc"
        toggle.text = if (Bus.running) "Dinlemeyi durdur" else "Dinlemeyi başlat"
        shown = NoteStore.all()
        val labels = shown.map {
            (it.title.ifBlank { "(adsız)" }) + " — " + it.body.take(60).replace('\n', ' ')
        }
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
    }

    private fun onToggle() {
        if (Bus.running) {
            startService(Intent(this, VoiceService::class.java).setAction(VoiceService.ACTION_STOP))
            return
        }
        val need = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            need += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) need += Manifest.permission.POST_NOTIFICATIONS
        if (need.isEmpty()) startVoice() else requestPermissions(need.toTypedArray(), 10)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startVoice()
    }

    private fun startVoice() {
        startForegroundService(Intent(this, VoiceService::class.java))
    }

    private fun editNote(n: Note?) {
        val title = EditText(this).apply { hint = "Başlık"; setText(n?.title ?: "") }
        val body = EditText(this).apply {
            hint = "Not"; setText(n?.body ?: ""); minLines = 6; gravity = Gravity.TOP
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
            addView(title); addView(body)
        }
        val b = AlertDialog.Builder(this).setView(box)
            .setPositiveButton("Kaydet") { _, _ ->
                if (n == null) NoteStore.create(title.text.toString(), body.text.toString())
                else NoteStore.update(n.id, title.text.toString(), body.text.toString())
                refresh()
            }
            .setNegativeButton("İptal", null)
        if (n != null) b.setNeutralButton("Sil") { _, _ -> NoteStore.delete(n.id); refresh() }
        b.show()
    }
}

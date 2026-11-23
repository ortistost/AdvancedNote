package com.sesnot.app

import java.util.Locale

/** Saf mantık: Android'e bağımlı değil. Tanınan metni olaylara çevirir. */
class CommandParser(private val armTimeoutMs: Long = 15_000L) {

    enum class State { IDLE, ARMED_NEW, ARMED_NAME, WRITING, NAMING }

    sealed interface Event {
        object NoteOpened : Event
        object WritingStarted : Event
        data class TextAdded(val text: String) : Event
        object NoteClosed : Event
        object NameArmed : Event
        object NamingStarted : Event
        data class TitleSet(val title: String) : Event
        object NameCancelled : Event
    }

    var state = State.IDLE
        private set

    private val window = ArrayList<String>()
    private val buffer = ArrayList<String>()
    private var armedAt = 0L

    fun reset() {
        state = State.IDLE
        window.clear()
        buffer.clear()
    }

    fun tick(nowMs: Long): List<Event> {
        val out = ArrayList<Event>()
        expire(nowMs, out)
        return out
    }

    fun feed(text: String, nowMs: Long): List<Event> {
        val out = ArrayList<Event>()
        expire(nowMs, out)
        for (word in tokenize(text)) step(word, nowMs, out)
        if (state == State.WRITING && buffer.isNotEmpty()) {
            out += Event.TextAdded(buffer.joinToString(" "))
            buffer.clear()
        }
        return out
    }

    private fun expire(nowMs: Long, out: MutableList<Event>) {
        if (nowMs - armedAt <= armTimeoutMs) return
        when (state) {
            State.ARMED_NEW -> { out += Event.NoteClosed; reset() }
            State.ARMED_NAME -> { out += Event.NameCancelled; reset() }
            else -> {}
        }
    }

    private fun step(word: String, nowMs: Long, out: MutableList<Event>) {
        val k = fold(word)
        when (state) {
            State.WRITING -> {
                if (k == "dur") {
                    if (buffer.isNotEmpty()) {
                        out += Event.TextAdded(buffer.joinToString(" "))
                        buffer.clear()
                    }
                    out += Event.NoteClosed
                    reset()
                } else buffer += word
            }
            State.NAMING -> {
                if (k == "dur") {
                    val t = buffer.joinToString(" ").trim()
                    if (t.isEmpty()) out += Event.NameCancelled else out += Event.TitleSet(t)
                    reset()
                } else buffer += word
            }
            else -> idleStep(k, nowMs, out)
        }
    }

    private fun idleStep(k: String, nowMs: Long, out: MutableList<Event>) {
        window += k
        if (window.size > 3) window.removeAt(0)

        if (k == "yaz") {
            if (state == State.ARMED_NEW) {
                state = State.WRITING; buffer.clear(); window.clear()
                out += Event.WritingStarted
                return
            }
            if (state == State.ARMED_NAME) {
                state = State.NAMING; buffer.clear(); window.clear()
                out += Event.NamingStarted
                return
            }
        }
        if (k == "dur") {
            if (state == State.ARMED_NEW) { out += Event.NoteClosed; reset() }
            else if (state == State.ARMED_NAME) { out += Event.NameCancelled; reset() }
            return
        }
        if (window.size == 3 && window[0] == "yeni") {
            if (window[1] == "not" && k == "ac") {
                if (state == State.ARMED_NEW) out += Event.NoteClosed
                state = State.ARMED_NEW; armedAt = nowMs; window.clear()
                out += Event.NoteOpened
            } else if (window[1] == "notu" && (k == "adlandir" || k == "isimlendir")) {
                if (state == State.ARMED_NEW) out += Event.NoteClosed
                state = State.ARMED_NAME; armedAt = nowMs; window.clear()
                out += Event.NameArmed
            }
        }
    }

    companion object {
        private val TR = Locale("tr", "TR")

        fun tokenize(text: String): List<String> =
            text.lowercase(TR).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

        /** Eşleştirme anahtarı: Türkçe harfleri sadeleştirir (aç/ac, adlandır/adlandir). */
        fun fold(w: String): String = buildString {
            for (c in w) append(
                when (c) {
                    'ç' -> 'c'; 'ğ' -> 'g'; 'ı' -> 'i'; 'ö' -> 'o'; 'ş' -> 's'; 'ü' -> 'u'
                    else -> c
                }
            )
        }
    }
}

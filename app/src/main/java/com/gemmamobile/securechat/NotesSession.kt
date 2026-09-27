package com.gemmamobile.securechat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class Segment(val atSec: Int, val text: String)

/**
 * One live meeting or one call recording: its transcript and the notes Gemma keeps updating.
 * Held in memory only, like chats.
 */
class NotesSession(val kind: Kind) {
    enum class Kind { Meeting, Recording }

    val startedAt = System.currentTimeMillis()
    val segments = mutableStateListOf<Segment>()
    var notes by mutableStateOf("")
    var updatingNotes by mutableStateOf(false)
    /** Notes being streamed by Gemma right now (shown live instead of [notes] while non-empty). */
    var notesDraft by mutableStateOf("")
    /** Recording: fraction of the file transcribed so far. */
    var progress by mutableStateOf<Float?>(null)
    var status by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
    /** Background microphone recording is on. */
    var micActive by mutableStateOf(false)
    /** No more transcript is coming (meeting stopped / file fully processed). */
    var inputDone by mutableStateOf(false)
    /** Input done and notes caught up. */
    var finished by mutableStateOf(false)

    /** Index of the first segment not yet folded into [notes]. */
    var notedUpTo = 0
    var lastNotesAt = 0L

    fun elapsedSec() = ((System.currentTimeMillis() - startedAt) / 1000).toInt()

    fun pendingWords(): Int =
        segments.drop(notedUpTo).sumOf { it.text.split(Regex("\\s+")).count(String::isNotBlank) }

    fun transcriptText(fromIndex: Int = 0, toIndex: Int = segments.size): String =
        segments.subList(fromIndex, toIndex).joinToString("\n") { "[${fmt(it.atSec)}] ${it.text}" }

    companion object {
        fun fmt(sec: Int) = "%d:%02d".format(sec / 60, sec % 60)

        const val NOTES_SYSTEM_PROMPT =
            "You are a note-taking assistant. You keep short, accurate notes of a conversation " +
                "from its transcript. Transcripts come from speech recognition and may contain errors. " +
                "Never invent facts that are not in the transcript."
    }
}

package io.github.velcheor137.shiori.engine.typography

/** Режим правил (§9.5.8 плана). В SPIKE-TYPO-001 реализован только [Conservative]. */
enum class NormalizationMode { Conservative, Extended }

/**
 * Настройки нормализации оформления текста главы.
 *
 * @property dialogues правило D: реплики в кавычках → оформление с тире.
 * @property thoughts правило T: мысли в одинарных кавычках → «ёлочки».
 * @property straightQuotesAsSpeech D7: считать прямые и английские двойные кавычки репликами.
 * @property nbspAroundDialogueDash TY1: неразрывный пробел после начального тире и перед тире слов автора.
 */
data class NormalizationSettings(
    val dialogues: Boolean = false,
    val thoughts: Boolean = false,
    val mode: NormalizationMode = NormalizationMode.Conservative,
    val straightQuotesAsSpeech: Boolean = false,
    val nbspAroundDialogueDash: Boolean = true,
) {
    val anyEnabled: Boolean get() = dialogues || thoughts

    companion object {
        val Off = NormalizationSettings()
        val DialoguesAndThoughts = NormalizationSettings(dialogues = true, thoughts = true)
    }
}

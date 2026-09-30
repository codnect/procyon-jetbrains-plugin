package io.codnect.procyon.project

/** Application templates offered by the Procyon new project form. */
enum class ProcyonTemplate(val id: String, val displayName: String) {
    HTTP("http", "HTTP"),
    CLI_RUNNER("cli-runner", "CLI Runner");

    override fun toString(): String = displayName
}

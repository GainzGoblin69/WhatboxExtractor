package com.gainzgoblin.whatboxextractor

enum class ArchiveKind { ZIP, RAR, SEVEN_ZIP }

object Archives {
    private val partRegex = Regex("""^(.*)\.part(\d+)\.rar$""", RegexOption.IGNORE_CASE)

    fun kind(name: String): ArchiveKind? {
        val n = name.lowercase()
        return when {
            n.endsWith(".zip") -> ArchiveKind.ZIP
            n.endsWith(".7z") || n.endsWith(".7z.001") -> ArchiveKind.SEVEN_ZIP
            n.endsWith(".rar") -> if (isLaterPart(name)) null else ArchiveKind.RAR
            else -> null
        }
    }

    /** True for game.part2.rar, game.part3.rar... (only part1 should be tapped). */
    fun isLaterPart(name: String): Boolean {
        val m = partRegex.find(name) ?: return false
        return (m.groupValues[2].toIntOrNull() ?: 1) > 1
    }

    private fun multipartBase(name: String): String? = partRegex.find(name)?.groupValues?.get(1)

    /** Name without the archive extension, used for the optional new folder. */
    fun stem(name: String): String {
        multipartBase(name)?.let { return it }
        if (name.lowercase().endsWith(".7z.001")) return name.dropLast(7)
        return name.substringBeforeLast('.')
    }

    private data class Tool(val bin: String, val cmd: String, val warningsOk: Boolean)

    /**
     * Shell script run on the seedbox in the background (nohup), so extraction keeps going
     * even if the phone locks or loses signal. Archives are extracted one after another.
     * It writes its exit code to JOBDIR/exit when done (0 = every archive succeeded).
     */
    fun buildScript(
        dir: String,
        names: List<String>,
        intoFolder: Boolean,
        deleteAfter: Boolean,
        jobDir: String,
    ): String {
        val sb = StringBuilder()
        fun l(s: String) = sb.append(s).append('\n')

        val exitTmp = shQuote("$jobDir/exit.tmp")
        val exitFile = shQuote("$jobDir/exit")

        l("#!/bin/sh")
        l("have() { command -v \"\$1\" >/dev/null 2>&1; }")
        l("finish() { echo \"\$1\" > $exitTmp; mv -f $exitTmp $exitFile; exit \"\$1\"; }")
        l("cd ${shQuote(dir)} || { echo 'Could not open the folder'; finish 1; }")
        l("failed=0")

        val archives = names.filter { kind(it) != null }
        archives.forEachIndexed { idx, name ->
            val kind = kind(name)!!
            if (archives.size > 1) l("echo; echo '[${idx + 1}/${archives.size}]'")
            l("(")
            l("cd ${shQuote(dir)} || exit 1")
            l("F=${shQuote(name)}")
            l("[ -f \"\$F\" ] || { echo \"File not found: \$F\"; exit 1; }")
            l("SRC=\"\$F\"")
            if (intoFolder) {
                val out = stem(name)
                l("mkdir -p ${shQuote(out)} || exit 1")
                l("cd ${shQuote(out)} || exit 1")
                l("SRC=\"../\$F\"")
            }
            l("echo \"Extracting \$F\"")

            val tools = toolsFor(kind)
            l("rc=127")
            tools.forEachIndexed { i, t ->
                l((if (i == 0) "if" else "elif") + " have ${t.bin}; then")
                l("  ${t.cmd}; rc=\$?")
                // These tools use exit code 1 for "finished with warnings".
                if (t.warningsOk) l("  [ \"\$rc\" -eq 1 ] && rc=0")
            }
            l("else")
            l("  echo 'No program found on the server to open this archive (tried: ${tools.joinToString(", ") { it.bin }})'")
            l("fi")

            if (deleteAfter) {
                l("if [ \"\$rc\" -eq 0 ]; then")
                l("  cd ${shQuote(dir)} || exit \"\$rc\"")
                val base = multipartBase(name)
                val lower = name.lowercase()
                when {
                    base != null ->
                        l("  rm -f -- ${shQuote(base)}.part*.rar ${shQuote(base)}.PART*.RAR && echo 'Deleted archive parts'")
                    lower.endsWith(".7z.001") ->
                        l("  rm -f -- ${shQuote(name.dropLast(4))}.[0-9][0-9][0-9] && echo 'Deleted archive parts'")
                    lower.endsWith(".rar") ->
                        l("  rm -f -- \"\$F\" ${shQuote(name.dropLast(4))}.r[0-9][0-9] && echo 'Deleted archive'")
                    else -> l("  rm -f -- \"\$F\" && echo 'Deleted archive'")
                }
                l("fi")
            }
            l("if [ \"\$rc\" -eq 0 ]; then echo \"Finished \$F\"; else echo \"FAILED \$F (exit code \$rc)\"; fi")
            l("exit \"\$rc\"")
            l(")")
            l("[ \$? -eq 0 ] || failed=\$((failed + 1))")
        }

        l("echo")
        l("if [ \"\$failed\" -eq 0 ]; then echo 'Done!'; finish 0; else echo \"\$failed archive(s) failed\"; finish 1; fi")
        return sb.toString()
    }

    private fun toolsFor(kind: ArchiveKind): List<Tool> = when (kind) {
        ArchiveKind.ZIP -> listOf(
            Tool("unzip", "unzip -o \"\$SRC\"", true),
            Tool("7z", "7z x -y -bsp1 \"\$SRC\"", true),
            Tool("7za", "7za x -y -bsp1 \"\$SRC\"", true),
            Tool("7zz", "7zz x -y -bsp1 \"\$SRC\"", true),
            Tool("bsdtar", "bsdtar -xvf \"\$SRC\"", false),
            Tool("python3", "python3 -m zipfile -e \"\$SRC\" .", false),
        )
        ArchiveKind.RAR -> listOf(
            Tool("unrar", "unrar x -o+ -y \"\$SRC\"", true),
            Tool("7z", "7z x -y -bsp1 \"\$SRC\"", true),
            Tool("7zz", "7zz x -y -bsp1 \"\$SRC\"", true),
            Tool("unar", "unar -f -D \"\$SRC\"", false),
            Tool("bsdtar", "bsdtar -xvf \"\$SRC\"", false),
        )
        ArchiveKind.SEVEN_ZIP -> listOf(
            Tool("7z", "7z x -y -bsp1 \"\$SRC\"", true),
            Tool("7za", "7za x -y -bsp1 \"\$SRC\"", true),
            Tool("7zz", "7zz x -y -bsp1 \"\$SRC\"", true),
            Tool("bsdtar", "bsdtar -xvf \"\$SRC\"", false),
        )
    }

    fun pollCommand(jobDir: String) =
        "cd ${shQuote(jobDir)} 2>/dev/null || { echo '@@GONE@@'; exit 0; }; " +
            "tail -c 8000 log 2>/dev/null; echo; echo '@@EXIT@@'; cat exit 2>/dev/null"

    /** Tidy the tool output (7z/unrar use backspaces and carriage returns for progress). */
    fun cleanLog(raw: String): String =
        raw.replace(Regex("\u0008+"), "\n")
            .replace('\r', '\n')
            .lines()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
            .takeLast(14)
            .joinToString("\n")

    fun lastPercent(raw: String): Int? =
        Regex("""(\d{1,3})%""").findAll(raw).lastOrNull()
            ?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 100)
}

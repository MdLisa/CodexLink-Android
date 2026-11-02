package com.xukaizhe.codexlink

/** Adapt complete math delimiters for Markwon; never modify the saved reply. */
internal fun prepareMathMarkdown(source: String): String {
    val result = StringBuilder(source.length)
    var i = 0
    var fence: Char? = null
    var fenceLength = 0
    while (i < source.length) {
        if (i == 0 || source[i - 1] == '\n') {
            val end = source.indexOf('\n', i).let { if (it < 0) source.length else it + 1 }
            val line = source.substring(i, end)
            val marker = mathCodeFence.find(line)
            if (fence != null) {
                if (marker != null && marker.groupValues[1][0] == fence && marker.groupValues[1].length >= fenceLength && line.substring(marker.range.last + 1).isBlank()) fence = null
                result.append(line); i = end; continue
            }
            if (marker != null) {
                fence = marker.groupValues[1][0]; fenceLength = marker.groupValues[1].length
                result.append(line); i = end; continue
            }
            if (line.startsWith("    ") || line.startsWith('\t') || mathReferenceDefinition.containsMatchIn(line)) {
                result.append(line); i = end; continue
            }
        }
        if (source[i] == '`') {
            val count = source.substring(i).takeWhile { it == '`' }.length
            var close = source.indexOf("`".repeat(count), i + count)
            while (close >= 0 && (source.getOrNull(close - 1) == '`' || source.getOrNull(close + count) == '`')) close = source.indexOf("`".repeat(count), close + count)
            if (close < 0) { result.append(source, i, source.length); break }
            result.append(source, i, close + count); i = close + count; continue
        }
        // Link targets and plain URLs are literal, even when they contain dollars or parentheses.
        if (source.startsWith("](", i)) {
            var end = i + 2; var depth = 1
            while (end < source.length && depth > 0) {
                if (source[end] == '\\') end++
                else if (source[end] == '(') depth++ else if (source[end] == ')') depth--
                end++
            }
            result.append(source, i, end.coerceAtMost(source.length)); i = end; continue
        }
        if (source.startsWith("https://", i) || source.startsWith("http://", i) || source.startsWith("mailto:", i)) {
            val end = (i until source.length).firstOrNull { source[it].isWhitespace() || source[it] == '>' } ?: source.length
            result.append(source, i, end); i = end; continue
        }
        val opening = when {
            source.startsWith("\\(", i) -> "\\("
            source.startsWith("\\[", i) -> "\\["
            source.startsWith("$$", i) -> "$$"
            source[i] == '$' -> "$"
            else -> null
        }
        if (opening != null) {
            val closing = when (opening) { "\\(" -> "\\)"; "\\[" -> "\\]"; else -> opening }
            var end = source.indexOf(closing, i + opening.length)
            while (end >= 0 && mathEscaped(source, end)) end = source.indexOf(closing, end + closing.length)
            val body = if (end >= 0) source.substring(i + opening.length, end) else ""
            val singleDollar = opening == "$"
            val complete = end >= 0 && body.isNotBlank() && (!singleDollar || (!body.first().isWhitespace() && !body.last().isWhitespace() && '\n' !in body && source.getOrNull(end + 1)?.isDigit() != true))
            if (complete) {
                val raw = source.substring(i, end + closing.length)
                if (body.length > 8192 || unsafeMathCommand.containsMatchIn(body)) {
                    result.append(literalMath(raw))
                } else if (opening == "\\[" || opening == "$$") {
                    val lineStart = source.lastIndexOf('\n', i - 1) + 1
                    val before = source.substring(lineStart, i)
                    val prefix = mathContainerPrefix(before)
                    val displayBody = body.lines().joinToString("\n") {
                        if ('>' in prefix) it.removePrefix(prefix) else it
                    }.trim()
                    if (!before.matches(Regex("[ \\t>]*"))) result.append("\n\n").append(prefix)
                    result.append("$$\n").append(displayBody.lines().joinToString("\n") { prefix + it.trimEnd() })
                        .append('\n').append(prefix).append("$$\n")
                } else {
                    result.append("$$").append(body.trim().replace('\n', ' ')).append("$$")
                }
                i = end + closing.length; continue
            }
            // A still-streaming display delimiter must not start Markwon's open-ended math block.
            if (opening == "$$") { result.append("\\$\\$"); i += 2; continue }
        }
        if (source[i] == '\\' && i + 1 < source.length) { result.append(source, i, i + 2); i += 2 }
        else result.append(source[i++])
    }
    return result.toString()
}

private val mathCodeFence = Regex("^[ \\t]*(?:(?:>[ \\t]*)|(?:(?:[-+*]|[0-9]+[.)])[ \\t]+))*(`{3,}|~{3,})")
private val mathReferenceDefinition = Regex("^ {0,3}\\[[^]]+]:")
// Typeset mathematics only. Do not let a formula load resources or define persistent/recursive macros.
private val unsafeMathCommand = Regex("\\\\(?:includegraphics|input|include|write|openin|openout|read|usepackage|documentclass|def|gdef|edef|xdef|newcommand|renewcommand|newenvironment|renewenvironment|catcode|csname|loop|repeat|href|url)\\b", RegexOption.IGNORE_CASE)
private fun mathEscaped(source: String, index: Int): Boolean { var i = index - 1; while (i >= 0 && source[i] == '\\') i--; return (index - i - 1) % 2 == 1 }
private fun literalMath(source: String) = buildString { source.forEach { if (it in "\\`*_{}[]()#+-.!|<>$") append('\\'); append(it) } }
private fun mathContainerPrefix(before: String): String {
    val match = Regex("^([ \\t]*(?:>[ \\t]*)*)(?:([-+*]|[0-9]+[.)])([ \\t]+))?").find(before)!!
    return match.groupValues[1] + " ".repeat(match.groupValues[2].length + match.groupValues[3].length)
}

package wemi.util

import java.util.*
import kotlin.collections.ArrayList

/**
 * Convert [data] to hexadecimal number string.
 *
 * @see fromHexString
 */
fun toHexString(data: ByteArray): String {
    val chars = CharArray(data.size * 2)
    for (i in data.indices) {
        chars[2 * i] = Character.forDigit((data[i].toInt() ushr 4) and 0xF, 16)
        chars[2 * i + 1] = Character.forDigit(data[i].toInt() and 0xF, 16)
    }
    return String(chars)
}

fun toHexString(checksums:List<Pair<ByteArray, String>>):CharSequence {
    val sb = StringBuilder(128)
    sb.append('[')
    var first = true
    for ((checksum, fileName) in checksums) {
        if (first) {
            first = false
        } else {
            sb.append(", ")
        }
        sb.append(toHexString(checksum))
        if (fileName.isNotBlank()) {
            sb.append(": ").append(fileName)
        }
    }
    return sb.append(']')
}

/**
 * Convert hexadecimal number string, like one generated by [toHexString], to [ByteArray].
 *
 * Skips whitespace. Returns null when [data] contains non-whitespace that can't be converted to hexadecimal digit,
 * or when the amount of hexadecimal digits is odd.
 */
fun fromHexString(data: CharSequence): ByteArray? {
    val bytes = ByteArray(data.length / 2)
    var byteI = 0

    var lowByte = -1

    for (c in data) {
        if (c.isWhitespace()) continue
        val digit = Character.digit(c, 16)
        if (digit == -1) {
            return null
        }

        if (lowByte != -1) {
            bytes[byteI++] = (lowByte or digit).toByte()
            lowByte = -1
        } else {
            lowByte = digit shl 4
        }
    }

    if (lowByte != -1) {
        return null
    }

    return if (byteI == bytes.size) {
        bytes
    } else {
        bytes.copyOf(byteI)
    }
}

/**
 * Parse file created by program from `sha1sum` family, such as `md5sum` or `sha256sum`.
 *
 * @param content of the sum file
 * @return list of file entries, with read hashes (may be null if invalid) and file names
 */
fun parseHashSum(content:CharSequence?):List<Pair<ByteArray, String>> {
    content ?: return emptyList()

    val result = ArrayList<Pair<ByteArray, String>>()

    // https://unix.stackexchange.com/a/310401
    var nameProblematic = false
    val hash = StringBuilder()
    val fileName = StringBuilder()

    fun endLine() {
        try {
            if (hash.isEmpty()) {
                // Probably an empty line
                return
            }
            val hashBytes = fromHexString(hash) ?: return
            result.add(hashBytes to fileName.toString())
        } finally {
            nameProblematic = false
            hash.setLength(0)
            fileName.setLength(0)
        }

    }

    val STATUS_NEW_LINE = 0
    val STATUS_READING_HASH = 1
    val STATUS_READING_TYPE = 2
    val STATUS_READING_FILENAME = 3
    val STATUS_READING_FILENAME_ESCAPED = 4
    var status = STATUS_NEW_LINE

    // Written during the first launch of Falcon Heavy

    content.forCodePoints { cp ->
        when (status) {
            STATUS_NEW_LINE ->
                when {
                    Character.isWhitespace(cp) -> {
                        // Ignore whitespace against spec
                    }
                    cp == '\\'.toInt() -> {
                        // Backslash-prefixed hash
                        nameProblematic = true
                        status = STATUS_READING_HASH
                    }
                    else -> {
                        hash.appendCodePoint(cp)
                        status = STATUS_READING_HASH
                    }
                }
            STATUS_READING_HASH ->
                    when {
                        Character.isWhitespace(cp) -> {
                            // Whitespace after hash
                            status = STATUS_READING_TYPE
                        }
                        else -> {
                            // Continue reading hash
                            hash.appendCodePoint(cp)
                        }
                    }
            STATUS_READING_TYPE -> {
                when (cp) {
                    ' '.toInt(), '*'.toInt(), '?'.toInt(), '^'.toInt() -> {
                        // Valid hash modes
                    }
                    else -> {
                        //Unknown hash mode, probably part of the file-name
                        fileName.appendCodePoint(cp)
                    }
                }
                status = STATUS_READING_FILENAME
            }
            STATUS_READING_FILENAME ->
                    when {
                        nameProblematic && cp == '\\'.toInt() -> {
                            status = STATUS_READING_FILENAME_ESCAPED
                        }
                        cp == '\n'.toInt() -> {
                            // Only \n is supported, mimicking sha1sum
                            endLine()
                            status = STATUS_NEW_LINE
                        }
                        else -> {
                            fileName.appendCodePoint(cp)
                        }
                    }
            STATUS_READING_FILENAME_ESCAPED -> {
                if (cp == 'n'.toInt()) {
                    fileName.append('\n')
                } else {
                    fileName.appendCodePoint(cp)
                }
                status = STATUS_READING_FILENAME
            }
        }
    }
    endLine()

    return result
}

fun hashMatches(hashFileContent:List<Pair<ByteArray?, String>>, expectedHash:ByteArray, fileName:String?):Boolean {
    when {
        hashFileContent.isEmpty() -> return false
        hashFileContent.size == 1 -> return Arrays.equals(hashFileContent[0].first, expectedHash)
        else -> {
            val found = hashFileContent.find { it.second == fileName }?.first
            if (found != null) {
                return Arrays.equals(found, expectedHash)
            }
            // Consider matching if at least one matches
            return hashFileContent.any { Arrays.equals(it.first, expectedHash) }
        }
    }
}

/**
 * Create output like `sha1sum`-like program.
 * Counterpart to [parseHashSum].
 *
 * @param hash of the file
 * @param fileName optional file name
 */
fun createHashSum(hash:ByteArray, fileName:String?):CharSequence {
    val sb = StringBuilder()
    val usedFileName = fileName ?: "-"
    val badFileName = usedFileName.contains('\\') || usedFileName.contains('\n')
    if (badFileName) {
        sb.append('\\')
    }
    sb.append(toHexString(hash))
    sb.append(' ').append(' ')
    if (badFileName) {
        for (c in usedFileName) {
            when (c) {
                '\\' -> sb.append('\\').append('\\')
                '\n' -> sb.append('\\').append('n')
                else -> sb.append(c)
            }
        }
    } else {
        sb.append(usedFileName)
    }
    return sb.append('\n')
}

package wemiplugin.jvmhotswap

import org.slf4j.LoggerFactory
import wemi.util.LocatedPath
import wemi.util.isDirectory
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.*

private val LOG = LoggerFactory.getLogger("FileTreeWatcher")

typealias FileSnapshot = Map<String, Pair<Path, ByteArray>>
typealias MutableFileSnapshot = MutableMap<String, Pair<Path, ByteArray>>

/**
 * Compute digest of given file.
 * Returns null if file fails to be read or digested, for any reason.
 */
private fun MessageDigest.digest(path:Path):ByteArray? {
    try {
        return Files.newInputStream(path).use {
            val buffer = ByteArray(4096)
            while (true) {
                val read = it.read(buffer)
                if (read <= 0) {
                    break
                }
                this@digest.update(buffer, 0, read)
            }

            this@digest.digest()
        }
    } catch (e:Exception) {
        LOG.debug("Failed to digest {}", path, e)
        return null
    }
}

/**
 * Used by [snapshotFiles].
 */
private fun snapshotFile(digest:MessageDigest, result:MutableFileSnapshot, isIncluded: (LocatedPath) -> Boolean, path:LocatedPath) {
    val pathClasspathName = path.path
    if (result.containsKey(pathClasspathName) || !isIncluded(path)) {
        // Already processed, probably nested roots or symlinks, or filtered out
        return
    }

    val file = path.file
    if (file.isDirectory()) {
        Files.list(file).forEachOrdered {
            snapshotFile(digest, result, isIncluded, LocatedPath(path.root, it))
        }
    } else {
        result[pathClasspathName] = file to (digest.digest(file) ?: return /* Ignore weird files */)
    }// else dunno, we don't care
}

/**
 * Creates a snapshot of a file tree, starting at given roots, following links.
 *
 * Result is a map from file path to its digest. Digest is null for directories, not null for files.
 * Files that are not readable/traversable are silently ignored and not included in the result.
 *
 * @param roots files or directories (that are searched recursively for files) to be included in the report
 * @param isIncluded function to filter out files, that should not be included in the report
 */
fun snapshotFiles(roots:Collection<LocatedPath>, isIncluded:(LocatedPath) -> Boolean):FileSnapshot {
    val digest = MessageDigest.getInstance("MD5")!! // Fast digest, guaranteed to be present
    val result = HashMap<String, Pair<Path, ByteArray>>(roots.size + roots.size / 2)

    for (root in roots) {
        snapshotFile(digest, result, isIncluded, root)
    }

    return result
}

fun snapshotsAreEqual(first:FileSnapshot, second:FileSnapshot):Boolean {
    if (first.size != second.size) {
        return false
    }

    for ((key, value) in first) {
        if (!second.containsKey(key)) {
            return false
        }
        // Not using MessageDigest.isEqual because we don't need time-constant comparisons
        if (!Arrays.equals(value.second, second[key]?.second)) {
            return false
        }
    }

    return true
}


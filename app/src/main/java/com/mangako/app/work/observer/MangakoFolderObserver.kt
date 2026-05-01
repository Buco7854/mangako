package com.mangako.app.work.observer

import android.os.FileObserver
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Recursive directory watcher built on top of [FileObserver]. Emits a callback
 * whenever a `.cbz` finishes being written into the watched tree (CLOSE_WRITE)
 * or is moved into the tree (MOVED_TO).
 *
 * Why we manage subdirectories ourselves: vanilla [FileObserver] watches a
 * single directory only — it doesn't recurse. The [FileObserver(File, mask)]
 * constructor (API 29+) advertises a "list of files" overload, but we still
 * need to track subdirectory creation/deletion to keep the watch set in sync
 * as the user (or downloader) creates new chapter folders.
 *
 * Limitations carried over from inotify itself:
 *  - Events fire on a single shared worker thread inside the framework.
 *    Keep [onCbzReady] cheap; defer real work to WorkManager.
 *  - When the process is killed, observers stop. The caller's responsibility
 *    is to re-arm on the next process start (typically via [MangakoApp]).
 *  - Network filesystems (FUSE-mounted SMB/NFS, etc.) don't always deliver
 *    inotify events. Periodic + scan-on-resume remain as fallbacks.
 *
 * Nextcloud's auto-upload does the same shape of thing, hence this matches
 * the user's reference point of "well, Nextcloud manages it".
 */
class MangakoFolderObserver(
    private val rootPath: String,
    private val onCbzReady: (File) -> Unit,
) {

    private val watchers = ConcurrentHashMap<String, FileObserver>()

    fun start() {
        val root = File(rootPath)
        if (!root.exists() || !root.isDirectory) {
            Log.w(TAG, "Skipping watcher for missing directory: $rootPath")
            return
        }
        watchRecursive(root)
        Log.i(TAG, "Watching $rootPath (${watchers.size} observers)")
    }

    fun stop() {
        watchers.values.forEach { runCatching { it.stopWatching() } }
        watchers.clear()
    }

    private fun watchRecursive(dir: File) {
        if (watchers.containsKey(dir.absolutePath)) return
        val observer = createObserver(dir)
        watchers[dir.absolutePath] = observer
        runCatching { observer.startWatching() }
            .onFailure { Log.w(TAG, "Could not watch ${dir.absolutePath}: ${it.message}") }

        // Walk what's already on disk:
        //   - subdirectories: recurse so they get their own observer
        //   - .cbz files: emit. Two cases this catches that pure inotify
        //     misses —
        //       1. The file was written into a directory that was created
        //          AFTER we started watching the parent, in the millisecond
        //          between the parent's CREATE event firing and us actually
        //          arming an observer here. The kernel doesn't queue events
        //          for an unwatched path, so without this rescan they'd be
        //          gone forever.
        //       2. The watcher is starting after the writer already
        //          finished (process restart, boot, user toggling
        //          watcher off then on) and we missed CLOSE_WRITE for
        //          files that landed while we were dead.
        //   We don't follow symlinks — `listFiles` reports the resolved
        //   File but we only descend on isDirectory, which is enough.
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                watchRecursive(child)
            } else if (child.name.endsWith(".cbz", ignoreCase = true)) {
                onCbzReady(child)
            }
        }
    }

    @Suppress("DEPRECATION") // String constructor is still the only one available pre-API 29.
    private fun createObserver(dir: File): FileObserver = object : FileObserver(dir.absolutePath, MASK) {
        override fun onEvent(event: Int, path: String?) {
            if (path == null) return
            val target = File(dir, path)
            // Bitmask constants on FileObserver are ints OR'd together; use
            // explicit `and` checks rather than equality to be lenient about
            // any extra event flags the kernel surfaces in the same delivery.
            val finished = event and (CLOSE_WRITE or MOVED_TO) != 0
            val created = event and CREATE != 0
            val gone = event and (DELETE_SELF or MOVE_SELF) != 0

            when {
                finished -> {
                    if (target.isDirectory) {
                        // Directory landed via mv/rename — start watching it
                        // and any subtree it brought along.
                        watchRecursive(target)
                    } else if (target.name.endsWith(".cbz", ignoreCase = true)) {
                        onCbzReady(target)
                    }
                }
                created && target.isDirectory -> watchRecursive(target)
                gone -> {
                    watchers.remove(dir.absolutePath)?.runCatching { stopWatching() }
                }
            }
        }
    }

    companion object {
        private const val TAG = "MangakoFolderObs"
        private const val MASK = FileObserver.CLOSE_WRITE or
            FileObserver.MOVED_TO or
            FileObserver.CREATE or
            FileObserver.DELETE_SELF or
            FileObserver.MOVE_SELF
    }
}

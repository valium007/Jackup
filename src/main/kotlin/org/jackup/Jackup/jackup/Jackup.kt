package org.jackup.Jackup.jackup

import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask
import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.absolutePathString
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

class Jackup : JavaPlugin() {

    private var backupFrequency = 21600 // seconds
    private var initialDelay = 300 // seconds before the first backup
    private var maxBackups = 4
    private var compressionLevel = 10 // zstd level (1-22)
    private lateinit var backupPath: String
    private var worlds: List<String> = emptyList()

    private var backupTask: BukkitTask? = null

    private val timestampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")

    override fun onEnable() {
        saveDefaultConfig()
        loadConfig()

        val backupDir = Paths.get(backupPath)
        if (!Files.exists(backupDir)) {
            Files.createDirectories(backupDir)
            logger.info("Backup directory created at: ${backupDir.absolutePathString()}")
        }

        startBackup()
        logger.info("Jackup enabled! Backing up every $backupFrequency seconds.")
    }

    override fun onDisable() {
        backupTask?.cancel()
        logger.info("Jackup disabled!")
    }

    private fun loadConfig() {
        backupFrequency = config.getInt("backup-frequency", 3600)
        initialDelay = config.getInt("initial-delay", 300)
        maxBackups = config.getInt("max-backups", 5)
        compressionLevel = config.getInt("compression-level", 10)
        backupPath = config.getString("backup-path", "backups")!!
        worlds = config.getStringList("worlds")
    }

    private fun startBackup() {
        // Times are in seconds; the scheduler counts ticks (20 per second).
        // The timer runs on the main thread so it can flush worlds safely; the
        // actual archiving is handed off to an async task (see doBackup).
        backupTask = object : BukkitRunnable() {
            override fun run() {
                doBackup()
            }
        }.runTaskTimer(this, initialDelay * 20L, backupFrequency * 20L)
    }

    // Runs on the main thread.
    private fun doBackup() {
        logger.info("Starting backup process...")

        // Flush all loaded worlds to disk and pause auto-save so the async copy
        // sees a consistent on-disk snapshot. Without this, the archive can pick
        // up half-written region/level files, producing a "corrupted" world.
        val loadedWorlds = server.worlds
        for (world in loadedWorlds) {
            world.isAutoSave = false
            world.save()
        }

        runAsyncTask {
            try {
                for (worldName in worlds) {
                    val worldDir = Paths.get(worldName)
                    if (!worldDir.isDirectory()) {
                        logger.warning("World directory not found: $worldName")
                        continue
                    }

                    val timestamp = LocalDateTime.now().format(timestampFormat)
                    val archive = Paths.get(backupPath, "${worldName}_$timestamp.tar.zst")
                    try {
                        archiveDirectory(worldDir, archive)
                        logger.info("Backed up world: $worldName to $archive")
                    } catch (e: Exception) {
                        logger.severe("Failed to backup world: $worldName - ${e.message}")
                    }
                }

                cleanupOldBackups()
            } finally {
                // Re-enable auto-save back on the main thread, even if archiving failed.
                runSyncTask {
                    for (world in loadedWorlds) {
                        world.isAutoSave = true
                    }
                }
                logger.info("Backup process completed.")
            }
        }
    }

    private fun cleanupOldBackups() {
        val backupDir = Paths.get(backupPath)
        if (!backupDir.isDirectory()) return

        val backups = Files.list(backupDir).use { stream ->
            stream.filter { it.isRegularFile() && it.name.endsWith(".tar.zst") }.toList()
        }.sortedBy { Files.getLastModifiedTime(it) }

        val toDelete = backups.size - maxBackups
        for (i in 0 until toDelete) {
            val backup = backups[i]
            try {
                Files.delete(backup)
                logger.info("Deleted old backup: ${backup.name}")
            } catch (e: Exception) {
                logger.warning("Failed to delete old backup: ${backup.name} - ${e.message}")
            }
        }
    }

    private fun archiveDirectory(sourceDir: Path, archivePath: Path) {
        TarArchiveOutputStream(
            ZstdCompressorOutputStream(
                BufferedOutputStream(Files.newOutputStream(archivePath)),
                compressionLevel
            )
        ).use { tar ->
            // Allow file names / paths longer than the historic 100-char tar limit.
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)

            Files.walk(sourceDir).use { paths ->
                paths.filter { it.isRegularFile() && it.name != "session.lock" }
                    .forEach { file ->
                        val entryName = sourceDir.relativize(file).toString().replace('\\', '/')
                        val entry = tar.createArchiveEntry(file.toFile(), entryName)
                        tar.putArchiveEntry(entry)
                        Files.copy(file, tar)
                        tar.closeArchiveEntry()
                    }
            }
        }
    }

    private fun runAsyncTask(task: () -> Unit) {
        object : BukkitRunnable() {
            override fun run() {
                task()
            }
        }.runTaskAsynchronously(this)
    }

    private fun runSyncTask(task: () -> Unit) {
        object : BukkitRunnable() {
            override fun run() {
                task()
            }
        }.runTask(this)
    }
}

/*
 * ArchiveTune (2026)
 * Â© Rukamori â€” github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.storage

import android.content.Context
import android.media.MediaScannerConnection
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.exoplayer.offline.Download
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.db.entities.FormatEntity
import moe.rukamori.archivetune.db.entities.Song
import moe.rukamori.archivetune.di.DownloadCache
import moe.rukamori.archivetune.di.PlayerCache
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

@Singleton
class DownloadedSongExporter
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val database: MusicDatabase,
        @DownloadCache private val downloadCache: Cache,
        @PlayerCache private val playerCache: Cache,
    ) {
        private val activeExports = HashSet<String>()

        suspend fun export(download: Download): Boolean =
            export(
                songId = download.request.id,
                fallbackTitle = download.request.data.toString(Charsets.UTF_8).takeIf(String::isNotBlank),
            )

        suspend fun export(
            songId: String,
            fallbackTitle: String? = null,
        ): Boolean =
            withContext(Dispatchers.IO) {
                if (!markExportActive(songId)) return@withContext true
                try {
                    val song = loadSongForExport(songId)
                    val format = song?.format
                    val cachedSpans =
                        downloadCache.exportableSpans(songId)
                            .ifEmpty { playerCache.exportableSpans(songId) }
                    if (cachedSpans.isEmpty()) return@withContext false

                    val metadata = ExportedSongMetadata.from(songId, song, fallbackTitle, context)
                    val targetDirectory = StorageLocationRepository.exportedDownloadsDirectory(context)
                    if (!targetDirectory.ensureWritableDirectory()) return@withContext false

                    val targetFile =
                        targetDirectory.resolve(
                            buildExportFileName(
                                metadata = metadata,
                                mimeType = format?.mimeType,
                            ),
                        )
                    deleteExistingExports(
                        directory = targetDirectory,
                        songId = songId,
                        except = targetFile,
                    )
                    val copied =
                        if (targetFile.exists() && targetFile.length() == cachedSpans.sumOf { span -> span.length }) {
                            true
                        } else {
                            copyCachedSpans(
                                spans = cachedSpans,
                                targetFile = targetFile,
                            )
                        }
                    if (!copied) return@withContext false

                    writeMetadataFile(
                        audioFile = targetFile,
                        metadata = metadata,
                        format = format,
                    )
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(targetFile.absolutePath),
                        arrayOf(exportMimeType(format?.mimeType)),
                        null,
                    )
                    true
                } catch (throwable: Throwable) {
                    if (throwable is CancellationException) throw throwable
                    Timber.tag(LogTag).w(throwable, "Failed to export downloaded song %s", songId)
                    false
                } finally {
                    markExportInactive(songId)
                }
            }

        suspend fun remove(songId: String): Boolean =
            withContext(Dispatchers.IO) {
                val targetDirectory = StorageLocationRepository.exportedDownloadsDirectory(context)
                if (!targetDirectory.exists()) return@withContext true
                deleteExistingExports(
                    directory = targetDirectory,
                    songId = songId,
                    except = null,
                )
            }

        private fun markExportActive(songId: String): Boolean =
            synchronized(activeExports) {
                activeExports.add(songId)
            }

        private fun markExportInactive(songId: String) {
            synchronized(activeExports) {
                activeExports.remove(songId)
            }
        }

        private fun Cache.exportableSpans(songId: String): List<CacheSpan> =
            runCatching {
                getCachedSpans(songId)
                    .filter { span -> span.isCached && span.length > 0L && span.file?.isFile == true }
                    .sortedBy { span -> span.position }
            }.getOrDefault(emptyList())

        private suspend fun loadSongForExport(songId: String): Song? {
            repeat(MetadataLoadRetryCount) {
                val song = database.getSongById(songId)
                if (song?.format != null) return song
                delay(MetadataLoadRetryDelayMillis)
            }
            return database.getSongById(songId)
        }

        private fun copyCachedSpans(
            spans: List<CacheSpan>,
            targetFile: File,
        ): Boolean =
            runCatching {
                targetFile.parentFile?.mkdirs()
                val tempFile = targetFile.resolveSibling("${targetFile.name}.tmp-${System.currentTimeMillis()}")
                var exportedBytes = 0L
                tempFile.outputStream().use { outputStream ->
                    var expectedPosition = 0L
                    spans.forEach { span ->
                        val sourceFile = span.file ?: error("Missing cache span file")
                        if (span.position > expectedPosition) {
                            error("Cache span gap at $expectedPosition for ${span.key}")
                        }
                        val overlapBytes = (expectedPosition - span.position).coerceAtLeast(0L)
                        if (overlapBytes >= span.length) return@forEach
                        sourceFile.inputStream().use { inputStream ->
                            inputStream.skipFully(overlapBytes)
                            inputStream.copyLimitedTo(
                                outputStream = outputStream,
                                byteCount = span.length - overlapBytes,
                            )
                        }
                        expectedPosition = max(expectedPosition, span.position + span.length)
                    }
                    exportedBytes = expectedPosition
                }
                if (targetFile.exists() && !targetFile.delete()) {
                    tempFile.delete()
                    return@runCatching false
                }
                if (!tempFile.renameTo(targetFile)) {
                    tempFile.copyTo(targetFile, overwrite = true)
                    tempFile.delete()
                }
                targetFile.exists() && targetFile.length() == exportedBytes
            }.getOrDefault(false)

        private fun writeMetadataFile(
            audioFile: File,
            metadata: ExportedSongMetadata,
            format: FormatEntity?,
        ) {
            val metadataFile = audioFile.resolveSibling("${audioFile.name}.json")
            metadataFile.writeText(
                JSONObject()
                    .put("id", metadata.id)
                    .put("title", metadata.title)
                    .put("artists", JSONArray().apply { metadata.artists.forEach { artist -> put(artist) } })
                    .putOptional("album", metadata.album)
                    .put("durationSeconds", metadata.durationSeconds)
                    .putOptional("thumbnailUrl", metadata.thumbnailUrl)
                    .putOptional("downloadedAt", metadata.downloadedAt)
                    .putOptional("mimeType", format?.mimeType)
                    .putOptional("codecs", format?.codecs?.takeIf(String::isNotBlank))
                    .put("bitrate", format?.bitrate ?: 0)
                    .put("sampleRate", format?.sampleRate ?: 0)
                    .put("contentLength", format?.contentLength ?: audioFile.length())
                    .put("source", "ArchiveTune")
                    .toString(MetadataJsonIndentSpaces),
            )
        }

        private fun deleteExistingExports(
            directory: File,
            songId: String,
            except: File?,
        ): Boolean {
            val marker = exportIdMarker(songId)
            var deleted = true
            directory
                .listFiles()
                ?.filter { file -> file.isFile && file.name.contains(marker) }
                ?.forEach { file ->
                    if (except != null && file.canonicalPath == except.canonicalPath) return@forEach
                    if (!runCatching { file.delete() || !file.exists() }.getOrDefault(false)) {
                        deleted = false
                    }
                }
            return deleted
        }

        private companion object {
            const val LogTag = "DownloadedSongExporter"
            const val MetadataJsonIndentSpaces = 2
            const val MetadataLoadRetryCount = 5
            const val MetadataLoadRetryDelayMillis = 200L
        }
    }

private fun buildExportFileName(
    metadata: ExportedSongMetadata,
    mimeType: String?,
): String {
    val artist = metadata.artists.joinToString(", ").toFileNamePart(maxLength = 72)
    val title = metadata.title.toFileNamePart(maxLength = 96)
    return "$artist - $title ${exportIdMarker(metadata.id)}.${exportFileExtension(mimeType)}"
}

private fun exportIdMarker(id: String): String = "[${id.toFileNamePart(maxLength = 48)}]"

private fun String.toFileNamePart(maxLength: Int): String {
    val normalized =
        replace(UnsafeFileNameCharacters, "_")
            .replace(WhitespaceRegex, " ")
            .trim(' ', '.')
    return normalized
        .takeIf(String::isNotBlank)
        ?.take(maxLength)
        ?.trim(' ', '.')
        ?.takeIf(String::isNotBlank)
        ?: "Unknown"
}

private fun exportFileExtension(mimeType: String?): String =
    when (mimeType.normalizedMimeType()) {
        "audio/aac",
        "audio/aac-adts",
        "audio/x-aac",
        -> "aac"

        "audio/flac",
        "audio/x-flac",
        -> "flac"

        "audio/mpeg",
        "audio/x-mpeg",
        -> "mp3"

        "audio/ogg",
        "audio/opus",
        "application/ogg",
        -> "ogg"

        "audio/webm",
        "video/webm",
        -> "webm"

        "audio/wav",
        "audio/wave",
        "audio/x-wav",
        -> "wav"

        else -> "m4a"
    }

private fun exportMimeType(mimeType: String?): String =
    when (mimeType.normalizedMimeType()) {
        "video/mp4",
        "application/mp4",
        "audio/x-m4a",
        -> "audio/mp4"

        "video/webm" -> "audio/webm"
        else -> mimeType.normalizedMimeType().ifBlank { "audio/mp4" }
    }

private fun String?.normalizedMimeType(): String =
    this
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase(Locale.ROOT)
        .orEmpty()

private fun File.ensureWritableDirectory(): Boolean =
    runCatching {
        if (exists() && !isDirectory) return@runCatching false
        if (!exists() && !mkdirs()) return@runCatching false
        val probe = resolve(".archivetune-download-export-probe")
        probe.writeText("ok")
        probe.delete()
    }.isSuccess

private fun InputStream.skipFully(byteCount: Long) {
    var remaining = byteCount
    while (remaining > 0L) {
        val skipped = skip(remaining)
        if (skipped <= 0L) {
            if (read() == -1) break
            remaining--
        } else {
            remaining -= skipped
        }
    }
}

private fun InputStream.copyLimitedTo(
    outputStream: java.io.OutputStream,
    byteCount: Long,
) {
    val buffer = ByteArray(CopyBufferSizeBytes)
    var remaining = byteCount
    while (remaining > 0L) {
        val read = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
        if (read < 0) break
        outputStream.write(buffer, 0, read)
        remaining -= read
    }
}

private fun JSONObject.putOptional(
    key: String,
    value: Any?,
): JSONObject =
    if (value == null) {
        this
    } else {
        put(key, value)
    }

private val UnsafeFileNameCharacters = Regex("""[\\/:*?"<>|\u0000-\u001F]""")
private val WhitespaceRegex = Regex("\\s+")
private const val CopyBufferSizeBytes = 256 * 1024

private data class ExportedSongMetadata(
    val id: String,
    val title: String,
    val artists: List<String>,
    val album: String?,
    val durationSeconds: Int,
    val thumbnailUrl: String?,
    val downloadedAt: String?,
) {
    companion object {
        fun from(
            songId: String,
            song: Song?,
            fallbackTitle: String?,
            context: Context,
        ): ExportedSongMetadata {
            val unknownTitle = context.getString(R.string.unknown)
            val unknownArtist = context.getString(R.string.unknown_artist)
            return ExportedSongMetadata(
                id = songId,
                title =
                    song
                        ?.song
                        ?.title
                        ?.takeIf(String::isNotBlank)
                        ?: fallbackTitle
                        ?: unknownTitle,
                artists =
                    song
                        ?.artists
                        ?.mapNotNull { artist -> artist.name.takeIf(String::isNotBlank) }
                        ?.takeIf { artists -> artists.isNotEmpty() }
                        ?: listOf(unknownArtist),
                album = song?.album?.title ?: song?.song?.albumName,
                durationSeconds = song?.song?.duration?.takeIf { duration -> duration > 0 } ?: 0,
                thumbnailUrl = song?.song?.thumbnailUrl,
                downloadedAt =
                    song
                        ?.song
                        ?.dateDownload
                        ?.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            )
        }
    }
}

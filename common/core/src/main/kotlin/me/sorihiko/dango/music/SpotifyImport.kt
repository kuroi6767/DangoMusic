package me.sorihiko.dango.music

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.DangoMusic
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Impor playlist Spotify (hanya daftar lagunya). Audio TIDAK diambil dari Spotify:
 * tiap lagu dicari di NetEase lalu diputar langsung di dalam Minecraft oleh pemutar Dango.
 *
 * Daftar ditulis di config/dangomusic/spotify_import.txt (dibuat otomatis, lihat TEMPLATE).
 */
object SpotifyImport {
    private class Query(val title: String, val artist: String)
    private class Entry(val id: Long, val name: String, val links: List<String>, val lines: List<String>)

    @Volatile private var entries: Map<Long, Entry> = emptyMap()
    private val cache = ConcurrentHashMap<Long, List<NeteaseTrack>>()

    private val file get() = DangoMusic.instance.clientDir.resolve("spotify_import.txt").toFile()

    private const val TEMPLATE = """// Impor playlist Spotify ke Dango Music.
// Baris yang diawali // diabaikan. Hapus // di depan contoh di bawah, lalu ganti isinya.
// Satu playlist = satu blok. Blok diawali baris: # Nama Playlist
//
// # Playlist Santai
// https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M
// Judul Lagu - Nama Artis
// Judul Lagu Lain - Artis Lain
//
// Link Spotify harus dari playlist PUBLIK. Kalau link gagal dibaca, tulis manual: Judul - Artis
"""

    /** Daftar playlist impor untuk ditampilkan di sidebar. Dipanggil tiap kali daftar playlist dimuat. */
    fun playlists(): List<NeteasePlaylist> {
        val parsed = try { load() } catch (e: Exception) {
            DangoLogger.warn("Spotify import file unreadable: {}", e.toString())
            emptyList<Entry>()
        }
        entries = parsed.associateBy { it.id }
        return parsed.map { NeteasePlaylist(it.id, "Spotify · " + it.name, it.lines.size, "", false) }
    }

    fun owns(id: Long): Boolean = entries.containsKey(id)

    /** Cari tiap lagu di NetEase. Hasil disimpan di memori sampai game ditutup. */
    fun tracks(id: Long): List<NeteaseTrack> {
        cache[id]?.let { return it }
        val entry = entries[id] ?: return emptyList()
        val queries = ArrayList<Query>()
        for (link in entry.links) queries.addAll(fetchPlaylist(link))
        for (line in entry.lines) queries.add(parseLine(line))
        val seen = HashSet<String>()
        val out = ArrayList<NeteaseTrack>()
        for (q in queries) {
            val hit = match(q)
            if (hit != null && seen.add(hit.key)) out.add(hit)
            try { Thread.sleep(150) } catch (_: InterruptedException) { break }
        }
        if (out.isNotEmpty()) cache[id] = out
        return out
    }

    private fun load(): List<Entry> {
        val f = file
        if (!f.exists()) {
            f.parentFile?.mkdirs()
            f.writeText(TEMPLATE, Charsets.UTF_8)
            return emptyList()
        }
        val result = ArrayList<Entry>()
        var name = ""
        var links = ArrayList<String>()
        var lines = ArrayList<String>()
        fun flush() {
            if (links.isEmpty() && lines.isEmpty()) return
            val title = name.ifBlank { "Import " + (result.size + 1) }
            var id = MusicSources.stableId(MusicSource.NETEASE, "spotify-import:$title")
            while (result.any { it.id == id }) id += 1
            result.add(Entry(id, title, links, lines))
        }
        for (raw in f.readLines(Charsets.UTF_8)) {
            val line = raw.trim().removePrefix("\uFEFF")
            if (line.isEmpty() || line.startsWith("//")) continue
            if (line.startsWith("#")) {
                flush()
                name = line.trimStart('#').trim()
                links = ArrayList()
                lines = ArrayList()
                continue
            }
            if (line.contains("open.spotify.com/")) links.add(line) else lines.add(line)
        }
        flush()
        return result
    }

    private fun parseLine(line: String): Query {
        val i = line.indexOf(" - ")
        return if (i > 0) Query(line.substring(0, i).trim(), line.substring(i + 3).trim()) else Query(line.trim(), "")
    }

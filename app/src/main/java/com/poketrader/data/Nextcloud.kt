package com.poketrader.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

/**
 * A Nextcloud account: [login] and [password] (an app password) to log in with, and [userId], the
 * id in WebDAV paths (it differs from the login when logging in with an e-mail address). The sync
 * file lives in [folder] ("Games/Poké Trader"; empty for the top level), chosen after logging in. Since 1.20.
 */
data class NextcloudAccount(
    val server: String,
    val login: String,
    val password: String,
    val userId: String = login,
    val folder: String = NextcloudClient.FOLDER,
)

/** What a Nextcloud folder holds: its subfolders, and whether there's a sync file in it already. */
data class FolderListing(val folders: List<String>, val hasSyncFile: Boolean)

/** A file on Nextcloud: its name, size in bytes and when it was last changed. */
data class RemoteFile(val name: String, val size: Long, val modified: Long)

/** Talks to Nextcloud: logging in (Login Flow v2) and reading/writing the sync file over WebDAV. Since 1.20 (as MTG Trader's). */
class NextcloudClient(http: OkHttpClient) {
    // Nextcloud names the app password after the User-Agent ("Poké Trader (Android)" in the user's security settings).
    private val http = http.newBuilder()
        .addInterceptor { it.proceed(it.request().newBuilder().header("User-Agent", "Poké Trader (Android)").build()) }
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    sealed interface Remote {
        /** Unchanged since the ETag we passed. */
        data object NotModified : Remote
        data object Missing : Remote
        class Found(val bytes: ByteArray, val etag: String?) : Remote
    }

    sealed interface PutResult {
        class Ok(val etag: String?) : PutResult
        /** Someone else wrote the file since we read it. */
        data object Conflict : PutResult
    }

    data class LoginStart(val loginUrl: String, val pollUrl: String, val token: String)

    /** Starts a browser login: the user approves the app at [LoginStart.loginUrl]; then [pollLogin] returns the app password. */
    suspend fun startLogin(server: String): LoginStart = call {
        val url = base(server).newBuilder().addPathSegments("index.php/login/v2").build()
        http.newCall(Request.Builder().url(url).post(FormBody.Builder().build()).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException(if (r.code == 404) "No Nextcloud found at $server" else "Nextcloud answered ${r.code}")
            val o = json.parseToJsonElement(r.body!!.string()).jsonObject
            val poll = o["poll"]!!.jsonObject
            LoginStart(o.str("login")!!, poll.str("endpoint")!!, poll.str("token")!!)
        }
    }

    /** The account once the user has approved the login in the browser; null while still waiting. */
    suspend fun pollLogin(start: LoginStart): NextcloudAccount? = call {
        val body = FormBody.Builder().add("token", start.token).build()
        http.newCall(Request.Builder().url(start.pollUrl).post(body).build()).execute().use { r ->
            if (r.code == 404) return@call null
            if (!r.isSuccessful) throw IOException("Nextcloud answered ${r.code}")
            val o = json.parseToJsonElement(r.body!!.string()).jsonObject
            NextcloudAccount(o.str("server")!!.trimEnd('/'), o.str("loginName")!!, o.str("appPassword")!!)
        }
    }

    /** Checks the login and returns the account with Nextcloud's user id (which may differ from the login name, e.g. an e-mail address). */
    suspend fun verify(account: NextcloudAccount): NextcloudAccount = call {
        val url = base(account.server).newBuilder().addPathSegments("ocs/v2.php/cloud/user").addQueryParameter("format", "json").build()
        http.newCall(ocs(account, url).get().build()).execute().use { r ->
            if (r.code == 401) throw SyncException("Nextcloud didn't accept the user name or app password.")
            if (!r.isSuccessful) throw IOException("Nextcloud answered ${r.code}")
            val id = json.parseToJsonElement(r.body!!.string()).jsonObject["ocs"]?.jsonObject?.get("data")?.jsonObject?.str("id")
            account.copy(userId = id ?: account.login)
        }
    }

    /** Deletes the app password on the server (on disconnecting). */
    suspend fun revoke(account: NextcloudAccount) = call {
        val url = base(account.server).newBuilder().addPathSegments("ocs/v2.php/core/apppassword").build()
        http.newCall(ocs(account, url).delete().build()).execute().close()
    }

    /** The subfolders of [path] (relative to the user's files, "" for the top level). */
    suspend fun list(account: NextcloudAccount, path: String): FolderListing = call {
        val url = dir(account, path)
        val body = """<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/></d:prop></d:propfind>"""
        val req = auth(account, url).method("PROPFIND", body.toRequestBody("application/xml".toMediaType())).header("Depth", "1").build()
        http.newCall(req).execute().use { r ->
            if (r.code == 404) throw SyncException("That folder isn't there any more.")
            if (!r.isSuccessful) throw failure(r)
            parseListing(r.body!!.string(), url.encodedPath)
        }
    }

    /** Creates folder [path]; fine if it exists already. */
    suspend fun makeFolder(account: NextcloudAccount, path: String) = call {
        http.newCall(auth(account, dir(account, path)).method("MKCOL", null).build()).execute().use { r ->
            if (!r.isSuccessful && r.code != 405) throw if (r.code == 409) SyncException("The folder above it isn't there.") else failure(r)
        }
    }

    suspend fun get(account: NextcloudAccount, etag: String?): Remote = getIn(account, FILE, etag)

    /** Writes the file if it's still the version with [ifMatch] (or still absent when null). */
    suspend fun put(account: NextcloudAccount, bytes: ByteArray, ifMatch: String?): PutResult = putIn(account, FILE, bytes, ifMatch)

    // ---- Other files in the sync folder (backups). ----

    /** A file in the sync folder; [name] may hold a subfolder ("Backups/x.json.gz"). */
    suspend fun getIn(account: NextcloudAccount, name: String, etag: String? = null): Remote = call {
        val req = auth(account, inFolder(account, name)).get().apply { if (etag != null) header("If-None-Match", etag) }.build()
        http.newCall(req).execute().use { r ->
            when {
                r.code == 304 -> Remote.NotModified
                r.code == 404 -> Remote.Missing
                r.isSuccessful -> Remote.Found(r.body!!.bytes(), r.etag())
                else -> throw failure(r)
            }
        }
    }

    /**
     * Writes [name] in the sync folder (making the folders it needs). With [ifMatch] it only
     * replaces that version; with [onlyNew] only writes if the file isn't there yet; otherwise it
     * overwrites whatever is there.
     */
    suspend fun putIn(account: NextcloudAccount, name: String, bytes: ByteArray, ifMatch: String?, onlyNew: Boolean = ifMatch == null && name == FILE): PutResult = call {
        val url = inFolder(account, name)
        fun attempt(): Response {
            val req = auth(account, url)
                .put(bytes.toRequestBody("application/gzip".toMediaType()))
                .apply {
                    if (ifMatch != null) header("If-Match", ifMatch) else if (onlyNew) header("If-None-Match", "*")
                }
                .build()
            return http.newCall(req).execute()
        }
        // The sync folder and any subfolder on the way (Nextcloud answers 405 for ones that exist already).
        fun makeFolders() {
            val parts = (account.folder.split('/') + name.split('/').dropLast(1)).filter { it.isNotBlank() }
            for (i in parts.indices) http.newCall(auth(account, dir(account, parts.take(i + 1).joinToString("/"))).method("MKCOL", null).build()).execute().close()
        }
        if (ifMatch == null) makeFolders()
        var r = attempt()
        if (r.code == 404 || r.code == 409) {
            r.close()
            makeFolders()
            r = attempt()
        }
        r.use {
            when {
                it.code == 412 -> PutResult.Conflict
                it.isSuccessful -> PutResult.Ok(it.etag() ?: head(account, url))
                else -> throw failure(it)
            }
        }
    }

    /** Deletes [name] in the sync folder; fine if it's gone already. */
    suspend fun deleteIn(account: NextcloudAccount, name: String) = call {
        http.newCall(auth(account, inFolder(account, name)).delete().build()).execute().use { r ->
            if (!r.isSuccessful && r.code != 404) throw failure(r)
        }
    }

    /** The files (not folders) in [sub], a folder inside the sync folder; empty if it isn't there. */
    suspend fun filesIn(account: NextcloudAccount, sub: String): List<RemoteFile> = call {
        val url = inFolder(account, sub)
        val body = """<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"""
        val req = auth(account, url).method("PROPFIND", body.toRequestBody("application/xml".toMediaType())).header("Depth", "1").build()
        http.newCall(req).execute().use { r ->
            if (r.code == 404) return@call emptyList()
            if (!r.isSuccessful) throw failure(r)
            parseFiles(r.body!!.string(), url.encodedPath)
        }
    }

    private fun head(account: NextcloudAccount, url: HttpUrl): String? =
        http.newCall(auth(account, url).head().build()).execute().use { it.etag() }

    private fun Response.etag() = header("OC-ETag") ?: header("ETag")

    private fun failure(r: Response) = when (r.code) {
        401 -> SyncException("Nextcloud no longer accepts this phone's login. Disconnect and connect again.")
        507 -> SyncException("Your Nextcloud storage is full.")
        else -> IOException("Nextcloud answered ${r.code}")
    }

    private fun auth(a: NextcloudAccount, url: HttpUrl) =
        Request.Builder().url(url).header("Authorization", Credentials.basic(a.login, a.password, Charsets.UTF_8))

    private fun ocs(a: NextcloudAccount, url: HttpUrl) = auth(a, url).header("OCS-APIRequest", "true")

    /** A folder of the user's files over WebDAV: "" is the top level, "A/B" a subfolder. */
    private fun dir(a: NextcloudAccount, path: String) =
        base(a.server).newBuilder().addPathSegments("remote.php/dav/files").addPathSegment(a.userId)
            .apply { path.split('/').filter { it.isNotBlank() }.forEach { addPathSegment(it) } }
            .build()

    private fun folder(a: NextcloudAccount) = dir(a, a.folder)

    private fun inFolder(a: NextcloudAccount, name: String) =
        folder(a).newBuilder().apply { name.split('/').filter { it.isNotBlank() }.forEach { addPathSegment(it) } }.build()

    private suspend fun <T> call(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.content

    companion object {
        const val FOLDER = "Poké Trader"
        const val FILE = "sync.json.gz"

        /** "cloud.example.com" or "https://cloud.example.com/index.php/apps/files/" → "https://cloud.example.com". */
        fun normalizeServer(input: String): String? {
            var s = input.trim().trimEnd('/')
            if (s.isEmpty()) return null
            if (!s.contains("://")) s = "https://$s"
            for (cut in listOf("/index.php", "/apps/", "/remote.php", "/login")) {
                val i = s.indexOf(cut, s.indexOf("://") + 3)
                if (i > 0) s = s.substring(0, i)
            }
            s = s.trimEnd('/')
            return s.takeIf { it.toHttpUrlOrNull() != null }
        }

        private val response = Regex("""<(?:[\w-]+:)?response[\s>](.*?)</(?:[\w-]+:)?response>""", RegexOption.DOT_MATCHES_ALL)
        private val href = Regex("""<(?:[\w-]+:)?href>(.*?)</(?:[\w-]+:)?href>""", RegexOption.DOT_MATCHES_ALL)
        private val collection = Regex("""<(?:[\w-]+:)?collection\s*/?>""")

        /** Reads a WebDAV PROPFIND answer for the folder at [selfPath] (URL-encoded, as requested). */
        fun parseListing(xml: String, selfPath: String): FolderListing {
            // Some servers answer with full URLs, most with paths; "+" is a plus sign in paths, not a space.
            fun clean(p: String): String {
                val path = if ("://" in p) "/" + p.substringAfter("://").substringAfter('/') else p
                return java.net.URLDecoder.decode(path.replace("+", "%2B"), "UTF-8").trimEnd('/')
            }
            val self = clean(selfPath)
            val folders = mutableListOf<String>()
            var hasFile = false
            for (m in response.findAll(xml)) {
                val block = m.groupValues[1]
                val path = href.find(block)?.groupValues?.get(1)?.trim()?.let(::clean) ?: continue
                if (path == self) continue
                val name = path.substringAfterLast('/')
                if (collection.containsMatchIn(block)) folders += name else if (name == FILE) hasFile = true
            }
            return FolderListing(folders.sortedWith(String.CASE_INSENSITIVE_ORDER), hasFile)
        }

        private val length = Regex("""<(?:[\w-]+:)?getcontentlength>(\d+)</""")
        private val modified = Regex("""<(?:[\w-]+:)?getlastmodified>(.*?)</""", RegexOption.DOT_MATCHES_ALL)

        /** The files in a WebDAV PROPFIND answer for the folder at [selfPath], with their size and date. */
        fun parseFiles(xml: String, selfPath: String): List<RemoteFile> {
            fun clean(p: String): String {
                val path = if ("://" in p) "/" + p.substringAfter("://").substringAfter('/') else p
                return java.net.URLDecoder.decode(path.replace("+", "%2B"), "UTF-8").trimEnd('/')
            }
            val self = clean(selfPath)
            return response.findAll(xml).mapNotNull { m ->
                val block = m.groupValues[1]
                val path = href.find(block)?.groupValues?.get(1)?.trim()?.let(::clean) ?: return@mapNotNull null
                if (path == self || collection.containsMatchIn(block)) return@mapNotNull null
                val date = modified.find(block)?.groupValues?.get(1)?.trim()?.let {
                    runCatching { java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US).parse(it)?.time }.getOrNull()
                } ?: 0L
                RemoteFile(path.substringAfterLast('/'), length.find(block)?.groupValues?.get(1)?.toLongOrNull() ?: 0L, date)
            }.toList()
        }

        private fun base(server: String): HttpUrl = server.toHttpUrlOrNull() ?: throw SyncException("That doesn't look like a server address.")
    }
}

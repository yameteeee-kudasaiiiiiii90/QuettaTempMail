package com.quetta.tempmail

import android.text.Html
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

data class AttachmentItem(
    val blobId: String,
    val name: String,
    val type: String,
    val size: Long
)

data class EmailMessage(
    val id: String,
    val from: String,
    val to: String,
    val subject: String,
    val preview: String,
    val date: String,
    val hasAttachment: Boolean,
    val attachments: List<AttachmentItem>,
    val otp: String?,
    val verifyLink: String?,
    val textBody: String,
    val htmlBody: String
)

class QuettaApiClient(private val onStateChanged: (() -> Unit)? = null) {

    companion object {
        private const val MASTER_TOKEN = "qt_E_SUl1WhhRkNHIElYiyEwJeJbaLPKYKjXWnR1nfuA8g8xM-WfKs2A-Bj9KjdiFJ_CX3RJlX4rV5H_KCPSmEld_NqKXx3vNCCgsyjId3xPKA="
        private const val INSTANT_ID = "4f831689-1b3a-479b-a02b-b113213b2c2a"
        private const val API_MAILBOXES = "https://account.quetta.net/mailboxes"
        private const val API_JMAP = "https://mx.quetta.email/jmap/"
        private const val UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Mobile Safari/537.36"
        private const val QUETTA_HDR = "android/Quetta/5877"

        private val OTP_KEYWORDS = Pattern.compile(
            "\\b(code|otp|verification|verify|pin|passcode|token|security|confirm|login|auth)\\b",
            Pattern.CASE_INSENSITIVE
        )
        private val OTP_CANDIDATE = Pattern.compile("(?<![0-9A-Za-z#])(\\d{4,8})(?![0-9A-Za-z])")
        private val VERIFY_LINK_RE = Pattern.compile(
            "https?://[^\\s<>"']+(?:verify|confirm|token|auth|activate|magic|login|code=)[^\\s<>"']*",
            Pattern.CASE_INSENSITIVE
        )
    }

    private val lock = Any()
    private val pollLock = Any()

    @Volatile var accountId: String? = null
    @Volatile var email: String = "Initializing..."
    @Volatile var token: String? = null
    @Volatile var mboxId: String = "c"
    @Volatile var status: String = "Starting up..."
    @Volatile var error: String? = null
    @Volatile var lastChecked: String = "-"
    @Volatile var consecutiveFailures: Int = 0
    @Volatile var deleted: Boolean = false

    private val seenIds = mutableSetOf<String>()
    private val messages = mutableListOf<EmailMessage>()

    private fun nowTimeStr(): String =
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

    private fun notifyStateChanged() {
        onStateChanged?.invoke()
    }

    fun getMessageCount(): Int = synchronized(lock) { messages.size }
    fun getMessages(): List<EmailMessage> = synchronized(lock) { ArrayList(messages) }

    private fun http(
        method: String,
        urlStr: String,
        headers: Map<String, String> = emptyMap(),
        bodyJson: String? = null
    ): Pair<Int, String> {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 20000
                readTimeout = 20000
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Connection", "keep-alive")
                for ((k, v) in headers) {
                    setRequestProperty(k, v)
                }
                if (bodyJson != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    outputStream.use { os ->
                        os.write(bodyJson.toByteArray(Charsets.UTF_8))
                    }
                }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = if (stream != null) {
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
            } else ""
            Pair(code, resp.trim())
        } catch (e: Exception) {
            Pair(0, e.message ?: "Network error")
        } finally {
            conn?.disconnect()
        }
    }

    private fun mailboxHeaders(): Map<String, String> = mapOf(
        "Authorization" to "Bearer $MASTER_TOKEN",
        "X-Instant-Id" to INSTANT_ID,
        "X-Quetta-Header" to QUETTA_HDR
    )

    fun deleteMailboxById(aid: String?): Boolean {
        if (aid.isNullOrBlank()) return false
        val (code, _) = http("DELETE", "$API_MAILBOXES/$aid", mailboxHeaders())
        return code in listOf(200, 202, 204)
    }

    fun deleteOldAndCreateNew(): Triple<Boolean, String, Int> {
        synchronized(lock) {
            status = "Deleting old address & creating new..."
            error = null
        }
        notifyStateChanged()

        var deletedCount = 0
        val oldAid: String?
        synchronized(lock) {
            oldAid = accountId
            accountId = null
            deleted = true
        }
        if (!oldAid.isNullOrBlank()) {
            if (deleteMailboxById(oldAid)) {
                deletedCount++
            }
        }

        val (listCode, listRaw) = http("GET", API_MAILBOXES, mailboxHeaders())
        if (listCode == 200 && listRaw.startsWith("[")) {
            try {
                val arr = JSONArray(listRaw)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val aid = obj.optString("account_id", "")
                    if (aid.isNotBlank() && aid != oldAid) {
                        if (deleteMailboxById(aid)) {
                            deletedCount++
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        val (postCode, postRaw) = http("POST", API_MAILBOXES, mailboxHeaders(), "{}")
        if (postCode != 201) {
            val errMsg = "Could not create address (HTTP $postCode)"
            synchronized(lock) {
                error = errMsg
                status = "Error creating address"
            }
            notifyStateChanged()
            return Triple(false, errMsg, deletedCount)
        }

        return try {
            val data = JSONObject(postRaw)
            val newAid = data.getString("account_id")
            val newEmail = data.getString("email")
            val newToken = data.getString("token")

            synchronized(lock) {
                accountId = newAid
                email = newEmail
                token = newToken
                deleted = false
                consecutiveFailures = 0
                seenIds.clear()
                messages.clear()
            }

            val resolvedMbox = fetchInboxId()
            synchronized(lock) {
                mboxId = resolvedMbox
                status = "Watching inbox live"
                lastChecked = nowTimeStr()
                error = null
            }
            notifyStateChanged()
            Triple(true, newEmail, deletedCount)
        } catch (e: Exception) {
            val errMsg = e.message ?: "Parse error"
            synchronized(lock) {
                error = errMsg
                status = "Error"
            }
            notifyStateChanged()
            Triple(false, errMsg, deletedCount)
        }
    }

    fun cleanupCurrent() {
        val aid: String?
        synchronized(lock) {
            if (deleted || accountId == null) return
            deleted = true
            aid = accountId
            accountId = null
        }
        deleteMailboxById(aid)
    }

    fun deleteSingleMessage(emailId: String): Boolean {
        val aid: String?
        synchronized(lock) {
            aid = accountId
            messages.removeAll { it.id == emailId }
        }
        notifyStateChanged()
        if (!aid.isNullOrBlank() && emailId.isNotBlank()) {
            try {
                val args = JSONObject().apply {
                    put("accountId", aid)
                    put("destroy", JSONArray().put(emailId))
                }
                val calls = JSONArray().put(JSONArray().put("Email/set").put(args).put("del"))
                jmap(calls)
            } catch (_: Exception) {}
        }
        return true
    }

    private fun jwtExp(jwt: String?): Long {
        if (jwt.isNullOrBlank()) return 0L
        return try {
            val parts = jwt.split(".")
            if (parts.size < 2) return 0L
            val decoded = String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
            JSONObject(decoded).optLong("exp", 0L)
        } catch (_: Exception) {
            0L
        }
    }

    private fun refreshToken() {
        val (code, raw) = http("GET", API_MAILBOXES, mailboxHeaders())
        if (code != 200 || !raw.startsWith("[")) {
            throw RuntimeException("Token refresh failed (HTTP $code)")
        }
        val arr = JSONArray(raw)
        val currentAid = accountId
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            if (m.optString("account_id") == currentAid && m.has("token")) {
                token = m.getString("token")
                return
            }
        }
        throw RuntimeException("Mailbox disappeared from account")
    }

    private fun ensureToken() {
        val nowSec = System.currentTimeMillis() / 1000L
        if (token.isNullOrBlank() || (jwtExp(token) - nowSec < 60L)) {
            refreshToken()
        }
    }

    private fun jmap(calls: JSONArray): JSONArray {
        ensureToken()
        val payload = JSONObject().apply {
            put("using", JSONArray().put("urn:ietf:params:jmap:core").put("urn:ietf:params:jmap:mail"))
            put("methodCalls", calls)
        }
        var lastCode = 0
        var lastRaw = ""
        for (attempt in 0..1) {
            val (code, raw) = http(
                "POST",
                API_JMAP,
                mapOf("Authorization" to "Bearer ${token ?: ""}"),
                payload.toString()
            )
            lastCode = code
            lastRaw = raw
            if (code == 401 && attempt == 0) {
                refreshToken()
                continue
            }
            break
        }
        if (lastCode != 200) {
            throw RuntimeException("JMAP error (HTTP $lastCode)")
        }
        val obj = JSONObject(lastRaw)
        if (!obj.has("methodResponses")) {
            throw RuntimeException("Invalid JMAP response")
        }
        return obj.getJSONArray("methodResponses")
    }

    private fun fetchInboxId(): String {
        val calls = JSONArray().put(
            JSONArray()
                .put("Mailbox/get")
                .put(JSONObject().put("accountId", accountId))
                .put("mb")
        )
        val resp = jmap(calls)
        val list = resp.optJSONArray(0)?.optJSONObject(1)?.optJSONArray("list") ?: return "c"
        for (i in 0 until list.length()) {
            val mb = list.optJSONObject(i) ?: continue
            if (mb.optString("role") == "inbox") {
                return mb.optString("id", "c")
            }
        }
        return "c"
    }

    private fun queryInboxIds(): List<String> {
        val args = JSONObject().apply {
            put("accountId", accountId)
            put("filter", JSONObject().put("inMailbox", mboxId))
            put("sort", JSONArray().put(JSONObject().put("property", "receivedAt").put("isAscending", false)))
            put("limit", 100)
            put("position", 0)
        }
        val calls = JSONArray().put(JSONArray().put("Email/query").put(args).put("q"))
        val resp = jmap(calls)
        val idsArr = resp.optJSONArray(0)?.optJSONObject(1)?.optJSONArray("ids") ?: return emptyList()
        val result = mutableListOf<String>()
        for (i in 0 until idsArr.length()) {
            result.add(idsArr.getString(i))
        }
        return result
    }

    private fun extractOtpAndLink(subject: String, textBody: String): Pair<String?, String?> {
        val combined = "$subject\n$textBody"
        var otp: String? = null
        if (OTP_KEYWORDS.matcher(combined).find()) {
            val m = OTP_CANDIDATE.matcher(combined)
            while (m.find()) {
                val candidate = m.group(1) ?: continue
                if (candidate.length == 4 && candidate in listOf("2023", "2024", "2025", "2026", "2027", "1970")) {
                    continue
                }
                otp = candidate
                break
            }
        }
        var link: String? = null
        val ml = VERIFY_LINK_RE.matcher(combined)
        if (ml.find()) {
            link = ml.group(0)?.trimEnd('.', ',', ';', ')', ']', '>', '"', '\'')
        }
        return Pair(otp, link)
    }

    private fun fetchEmailsById(ids: List<String>): List<EmailMessage> {
        val props = JSONArray().apply {
            listOf(
                "id", "from", "to", "subject", "preview", "receivedAt",
                "sentAt", "hasAttachment", "attachments", "textBody", "htmlBody", "bodyValues"
            ).forEach { put(it) }
        }
        val idsJson = JSONArray()
        ids.forEach { idsJson.put(it) }

        val args = JSONObject().apply {
            put("accountId", accountId)
            put("ids", idsJson)
            put("properties", props)
            put("fetchTextBodyValues", true)
            put("fetchHTMLBodyValues", true)
            put("maxBodyValueBytes", 524288)
        }
        val calls = JSONArray().put(JSONArray().put("Email/get").put(args).put("g"))
        val resp = jmap(calls)
        val list = resp.optJSONArray(0)?.optJSONObject(1)?.optJSONArray("list") ?: return emptyList()

        val out = mutableListOf<EmailMessage>()
        for (i in 0 until list.length()) {
            val em = list.optJSONObject(i) ?: continue
            val (textBody, htmlBody) = extractBodies(em)
            val subj = em.optString("subject").ifBlank { "(no subject)" }
            val (otp, verifyLink) = extractOtpAndLink(subj, textBody)

            val atts = mutableListOf<AttachmentItem>()
            val attArr = em.optJSONArray("attachments")
            if (attArr != null) {
                for (j in 0 until attArr.length()) {
                    val a = attArr.optJSONObject(j) ?: continue
                    val bid = a.optString("blobId", "")
                    if (bid.isNotEmpty()) {
                        atts.add(
                            AttachmentItem(
                                blobId = bid,
                                name = a.optString("name", "attachment"),
                                type = a.optString("type", "application/octet-stream"),
                                size = a.optLong("size", 0L)
                            )
                        )
                    }
                }
            }

            out.add(
                EmailMessage(
                    id = em.optString("id"),
                    from = formatAddr(em.optJSONArray("from")),
                    to = formatAddr(em.optJSONArray("to")),
                    subject = subj,
                    preview = em.optString("preview").ifBlank { textBody.take(120) },
                    date = em.optString("receivedAt").ifBlank { em.optString("sentAt", "-") },
                    hasAttachment = em.optBoolean("hasAttachment", false) || atts.isNotEmpty(),
                    attachments = atts,
                    otp = otp,
                    verifyLink = verifyLink,
                    textBody = textBody,
                    htmlBody = htmlBody
                )
            )
        }
        return out
    }

    private fun formatAddr(arr: JSONArray?): String {
        if (arr == null || arr.length() == 0) return "-"
        val parts = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            val name = p.optString("name", "").trim()
            val email = p.optString("email", "").trim()
            if (name.isNotEmpty() && email.isNotEmpty() && name != email) {
                parts.add("$name <$email>")
            } else {
                parts.add(name.ifEmpty { email })
            }
        }
        return if (parts.isEmpty()) "-" else parts.joinToString(", ")
    }

    private fun extractBodies(em: JSONObject): Pair<String, String> {
        val vals = em.optJSONObject("bodyValues") ?: JSONObject()
        var textVal = ""
        var htmlVal = ""

        val textParts = em.optJSONArray("textBody")
        if (textParts != null) {
            for (i in 0 until textParts.length()) {
                val pid = textParts.optJSONObject(i)?.optString("partId") ?: continue
                val v = vals.optJSONObject(pid)?.optString("value", "")?.trim() ?: ""
                if (v.isNotEmpty()) {
                    textVal = v
                    break
                }
            }
        }

        val htmlParts = em.optJSONArray("htmlBody")
        if (htmlParts != null) {
            for (i in 0 until htmlParts.length()) {
                val pid = htmlParts.optJSONObject(i)?.optString("partId") ?: continue
                val v = vals.optJSONObject(pid)?.optString("value", "")?.trim() ?: ""
                if (v.isNotEmpty()) {
                    htmlVal = v
                    break
                }
            }
        }

        if (textVal.isEmpty() && htmlVal.isNotEmpty()) {
            val spanned = Html.fromHtml(htmlVal, Html.FROM_HTML_MODE_LEGACY)
            textVal = spanned.toString().trim()
        }
        if (textVal.isEmpty()) {
            textVal = em.optString("preview", "(empty body)")
        }
        return Pair(textVal, htmlVal)
    }

    fun pollOnce(): List<EmailMessage> {
        synchronized(pollLock) {
            if (accountId.isNullOrBlank() || deleted) return emptyList()
            return try {
                val ids = queryInboxIds()
                val checkedTime = nowTimeStr()
                val newIds = synchronized(lock) {
                    lastChecked = checkedTime
                    consecutiveFailures = 0
                    ids.filter { it !in seenIds }
                }
                if (newIds.isEmpty()) {
                    synchronized(lock) {
                        status = "Watching inbox live"
                        error = null
                    }
                    notifyStateChanged()
                    return emptyList()
                }
                val fetched = fetchEmailsById(newIds)
                val newlyAdded = mutableListOf<EmailMessage>()
                synchronized(lock) {
                    for (item in fetched.reversed()) {
                        if (seenIds.add(item.id)) {
                            messages.add(0, item)
                            newlyAdded.add(item)
                        }
                    }
                    status = "Watching inbox live"
                    error = null
                }
                notifyStateChanged()
                newlyAdded
            } catch (e: Exception) {
                synchronized(lock) {
                    consecutiveFailures++
                    error = "Reconnecting... (${e.message ?: "error"})"
                }
                notifyStateChanged()
                throw e
            }
        }
    }
}

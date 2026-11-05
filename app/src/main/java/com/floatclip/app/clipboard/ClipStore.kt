package com.floatclip.app.clipboard

import android.content.Context
import com.floatclip.app.backup.VaultBackupManager
import com.floatclip.app.model.ClipEntry
import com.floatclip.app.prefs.CategoryStore
import com.floatclip.app.security.LocalVaultCrypto
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import android.util.Base64

class ClipStore(context: Context, private val crypto: com.floatclip.app.security.VaultCipher = LocalVaultCrypto()) {
    private val appContext = context.applicationContext
    private val legacyPrefs = appContext.getSharedPreferences("floatclip_store", Context.MODE_PRIVATE)
    private val vaultFile = File(appContext.filesDir, "floatclip_vault.bin")
    private val images = ImageBlobStore(File(appContext.filesDir,"clipboard-images"),crypto)
    private val categoryStore = CategoryStore(appContext)
    internal val imageCacheScope = appContext.filesDir.absolutePath

    init {
        if(File(vaultFile.path+".bak").exists())runCatching {android.util.AtomicFile(vaultFile).openRead().close()}
        migrateLegacyIfNeeded()
    }

    fun entries(): List<ClipEntry> = synchronized(LOCK) {
        val stamp=vaultFile.lastModified() to vaultFile.length()
        val key=vaultFile.absolutePath
        if(crypto is LocalVaultCrypto) metadata[key]?.takeIf { it.stamp==stamp }?.let { return@synchronized it.entries }
        val result=parseEntries(readVaultJson())
        if(crypto is LocalVaultCrypto) {
            if(metadata.size>=4 && key !in metadata)metadata.remove(metadata.keys.first())
            metadata[key]=Metadata(stamp,result)
        }
        result
    }

    private fun nextEntryId(current:List<ClipEntry>):Long {
        val previous=maxOf(legacyPrefs.getLong("last_entry_id",0L),current.maxOfOrNull { it.id }?:0L)
        check(previous<Long.MAX_VALUE){ "记录编号已满" }
        val next=maxOf(System.currentTimeMillis(),previous+1)
        check(legacyPrefs.edit().putLong("last_entry_id",next).commit()){ "无法保存记录编号" }
        return next
    }

    fun addText(text: String): ClipEntry? = synchronized(LOCK) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return@synchronized null
        val current = entries().toMutableList()
        val duplicate = current.firstOrNull { it.kind=="text" && it.text == trimmed && it.sourceDeviceId == "local" }
        if (duplicate != null) current.remove(duplicate)
        val inferredCategory = ClipClassifier.classify(trimmed, categoryStore.categories()) ?: CategoryStore.DEFAULT_CATEGORY
        val entry = duplicate?.copy(createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis())
            ?: ClipEntry(id = nextEntryId(current), text = trimmed, category = inferredCategory)
        if (current.size >= MAX_ITEMS) {
            val oldest = current.filterNot { it.pinned || it.locked }.minByOrNull { it.updatedAt }
            if (oldest == null) return@synchronized null
            current.remove(oldest)
        }
        current.add(0, entry)
        persist(current)
        entry
    }

    fun addImage(png:ByteArray):ClipEntry? = synchronized(LOCK) {
        val info=images.validate(png)
        val current=entries().toMutableList()
        val duplicate=current.firstOrNull { it.kind=="image" && it.imageSha256==info.sha256 && it.sourceDeviceId=="local" }
        val now=System.currentTimeMillis()
        if(duplicate!=null) {
            images.write(png,duplicate.imageRef)
            val refreshed=duplicate.copy(createdAt=now,updatedAt=now)
            persist(current.map { if(it.key==duplicate.key)refreshed else it });return@synchronized refreshed
        }
        if(current.size>=MAX_ITEMS) {
            val oldest=current.filterNot { it.pinned||it.locked }.minByOrNull { it.updatedAt } ?: return@synchronized null
            current.remove(oldest)
        }
        require(current.filter { it.kind=="image" }.sumOf { it.imageBytes.toLong() }+png.size<=ImageBlobStore.MAX_ACTIVE_BYTES) { "图片总量超过 32 MiB，请先整理普通记录" }
        val referenced=entries().filter { it.kind=="image" }.map { it.imageRef }.toMutableSet()
        undoImageReferences.values.forEach { referenced.addAll(it) }
        runCatching {
            appContext.getSystemService(android.content.ClipboardManager::class.java).primaryClip?.let { clip ->
                if(clip.itemCount>0)ClipboardImageProvider.reference(clip.getItemAt(0).uri,appContext.packageName)?.let(referenced::add)
            }
        }
        images.prune(referenced)
        val reference=images.write(png)
        val entry=ClipEntry(id=nextEntryId(current),text="图片",kind="image",imageRef=reference,
            imageSha256=info.sha256,imageBytes=info.bytes,imageWidth=info.width,imageHeight=info.height)
        try { persist(listOf(entry)+current) } catch(e:Exception) { images.delete(reference);throw e }
        entry
    }
    internal fun readThumbnail(key:String):ByteArray?=runCatching {
        val file=thumbnailFile(key)
        if(file.length() !in 1..2_000_000L)return@runCatching null
        crypto.decrypt(file.readBytes()).also { file.setLastModified(System.currentTimeMillis()) }
    }.getOrNull()
    internal fun writeThumbnail(key:String,png:ByteArray) {
        if(png.size>1_500_000)return
        synchronized(THUMBNAIL_LOCK) { runCatching {
            val file=thumbnailFile(key)
            val temp=File(file.parentFile,file.name+".tmp")
            temp.writeBytes(crypto.encrypt(png));check(temp.renameTo(file))
            val files=file.parentFile!!.listFiles().orEmpty().filter { it.extension=="bin" }.sortedByDescending{it.lastModified()}
            var bytes=0L
            files.forEachIndexed { index,f -> bytes+=f.length();if(index>=96 || bytes>32L*1024*1024)f.delete() }
        } }
    }
    private fun thumbnailFile(key:String):File {
        val directory=File(appContext.cacheDir,"clipboard-thumbnails").apply { mkdirs() }
        val name=MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString(""){"%02x".format(it.toInt() and 255)}
        return File(directory,name+".bin")
    }
    fun readImage(entry:ClipEntry):ByteArray {
        require(entry.kind=="image") { "这条记录不是图片" }
        return images.read(entry.imageRef,entry.imageSha256)
    }
    fun readImageReference(reference:String):ByteArray {
        // URI read grants can outlive a history deletion. The authenticated blob stays readable
        // until it is neither the current clipboard nor a history/undo reference and is pruned.
        return images.read(reference)
    }
    fun findImage(reference:String):ClipEntry? = synchronized(LOCK) {
        entries().firstOrNull { it.kind=="image" && it.imageRef==reference }
    }
    fun receiveSharedImage(
        eventId:java.util.UUID,
        source:com.floatclip.share.ClipboardSync.Device,
        png:ByteArray,
        width:Int,
        height:Int,
        createdAt:Long,
    ):ClipEntry = synchronized(LOCK) {
        require(eventId!=java.util.UUID(0,0)) { "图片事件无效" }
        val info=images.validate(png)
        require(info.width==width && info.height==height) { "图片尺寸校验失败" }
        val key=eventId.toString()
        val current=entries().toMutableList()
        current.firstOrNull { it.key==key }?.let {
            require(it.kind=="image" && it.imageSha256==info.sha256) { "图片事件编号冲突" }
            return@synchronized it
        }
        val previous=current.firstOrNull {
            it.kind=="image" && it.imageSha256==info.sha256 && it.sourceDeviceId==source.id.toString()
        }
        if(previous!=null)current.remove(previous)
        if(current.size>=MAX_ITEMS) {
            val oldest=current.filterNot { it.pinned||it.locked }.minByOrNull { it.updatedAt }
                ?: error("固定和置顶记录已满")
            current.remove(oldest)
        }
        val activeImageBytes=current.filter { it.kind=="image" }.sumOf { it.imageBytes.toLong() }
        require(activeImageBytes+png.size<=ImageBlobStore.MAX_ACTIVE_BYTES) { "图片总量超过 32 MiB，请先整理普通记录" }
        val reference=previous?.imageRef?.takeIf { it.isNotBlank() } ?: java.util.UUID.randomUUID().toString().replace("-","")
        val wasNew=previous==null
        images.write(png,reference)
        val now=maxOf(System.currentTimeMillis(),(current.maxOfOrNull { it.createdAt }?:0L)+1)
        val row=ClipEntry(
            id=previous?.id ?: nextEntryId(current),
            text="图片",
            pinned=previous?.pinned?:false,
            locked=previous?.locked?:false,
            category=previous?.category?:CategoryStore.DEFAULT_CATEGORY,
            createdAt=now,
            key=key,
            updatedAt=now,
            sourceDeviceId=source.id.toString(),
            sourceDeviceName=source.name,
            sourcePlatform=source.platform,
            kind="image",
            imageRef=reference,
            imageSha256=info.sha256,
            imageBytes=info.bytes,
            imageWidth=info.width,
            imageHeight=info.height,
        )
        try { persist(listOf(row)+current);row }
        catch(t:Throwable) { if(wasNew)images.delete(reference);throw t }
    }
    fun retainUndoImages(owner:String,items:List<ClipEntry>) = synchronized(LOCK) {
        if(items.isEmpty()) undoImageReferences.remove(owner)
        else {
            undoImageReferences[owner]=items.filter { it.kind=="image" }.map { it.imageRef }.toSet()
        }
    }

    fun togglePinned(id: Long) = synchronized(LOCK) { persist(entries().map { if (it.id == id) it.copy(pinned = !it.pinned) else it }) }
    fun toggleLocked(id: Long) = synchronized(LOCK) { persist(entries().map { if (it.id == id) it.copy(locked = !it.locked) else it }) }

    fun updateCategory(id: Long, category: String) = synchronized(LOCK) {
        val clean = category.trim().ifEmpty { CategoryStore.DEFAULT_CATEGORY }
        persist(entries().map { if (it.id == id) it.copy(category = clean) else it })
    }

    fun renameCategory(category: String, name: String) = synchronized(LOCK) {
        persist(entries().map { if(it.category==category)it.copy(category=name) else it })
    }

    fun moveCategoryToDefault(category: String) = synchronized(LOCK) {
        persist(entries().map { if (it.category == category) it.copy(category = CategoryStore.DEFAULT_CATEGORY) else it })
    }

    fun delete(id: Long) = synchronized(LOCK) { persist(entries().filterNot { it.id == id }) }
    /** Idempotent delivery; retain organization when identical content is copied again by the same source. */
    fun receiveShared(entry: com.floatclip.share.ClipboardSync.Entry) = synchronized(LOCK) {
        val snapshot=unifiedSnapshot();val array=snapshot.getJSONArray("entries");val key=entry.id.toString()
        if((0 until array.length()).any{array.getJSONObject(it).getString("id")==key})return@synchronized
        val duplicate=(0 until array.length()).firstOrNull {
            val row=array.getJSONObject(it);row.optString("kind","text")=="text" && row.getString("text")==entry.text && row.getJSONObject("source").getString("id")==entry.source.id.toString()
        }
        val old=duplicate?.let{array.getJSONObject(it)}
        if(duplicate!=null)array.remove(duplicate)
        if(array.length()>=MAX_ITEMS) {
            val removable=(0 until array.length()).filter{
                val row=array.getJSONObject(it)
                !row.getBoolean("pinned") && !row.optBoolean("locked",false)
            }.minByOrNull{array.getJSONObject(it).getLong("updatedAt")}
                ?:error("置顶或保护记录已满")
            array.remove(removable)
        }
        val now=maxOf(System.currentTimeMillis(),(0 until array.length()).maxOfOrNull { array.getJSONObject(it).getLong("createdAt") }?.plus(1)?:0L)
        array.put(JSONObject().put("id",key).put("text",entry.text).put("pinned",old?.optBoolean("pinned")?:false)
            .put("locked",old?.optBoolean("locked")?:false)
            .put("category",old?.optString("category")?:CategoryStore.DEFAULT_CATEGORY).put("createdAt",now).put("updatedAt",now)
            .put("source",JSONObject().put("id",entry.source.id.toString()).put("name",entry.source.name).put("platform",entry.source.platform)))
        commitUnified(snapshot.getString("revision"),array)
    }
    fun restore(entry: ClipEntry) = synchronized(LOCK) {
        val current = entries().toMutableList()
        if(current.any { it.key == entry.key || it.id == entry.id }) return@synchronized
        if(current.size >= MAX_ITEMS) {
            val oldest = current.filterNot { it.pinned || it.locked }.minByOrNull { it.createdAt }
                ?: error("置顶或保护记录已满，请先整理后恢复")
            current.remove(oldest)
        }
        persist(listOf(entry) + current)
    }
    /** Return exactly the removed snapshot so both clipboard surfaces can offer batch undo. */
    fun clearUnpinned(): List<ClipEntry> = synchronized(LOCK) {
        val current = entries()
        val removed = current.filterNot { it.pinned || it.locked }
        if (removed.isNotEmpty()) persist(current.filter { it.pinned || it.locked })
        removed
    }
    /** Bulk clear always preserves both kinds of protection; explicit single-row deletion is separate. */
    fun clearAll(): List<ClipEntry> = clearUnpinned()

    /** Restore a deletion batch without replacing content copied or organized since the clear. */
    fun restoreAll(removed: List<ClipEntry>): List<ClipEntry> = synchronized(LOCK) {
        val current = entries().toMutableList()
        val keys = current.map { it.key }.toMutableSet()
        val ids = current.map { it.id }.toMutableSet()
        val remaining = mutableListOf<ClipEntry>()
        var changed = false
        removed.forEach { entry ->
            if (entry.key !in keys && entry.id !in ids) {
                if (current.size < MAX_ITEMS) {
                    current.add(entry); keys.add(entry.key); ids.add(entry.id); changed = true
                } else remaining.add(entry)
            }
        }
        if (changed) persist(current)
        remaining
    }
    fun exportJson(): String = synchronized(LOCK) { exportEntries(entries()) }

    private fun exportEntries(items:List<ClipEntry>):String {
        val array=JSONArray(buildJson(items))
        for(index in 0 until array.length()) {
            val entry=items[index]
            if(entry.kind=="image")array.getJSONObject(index).put("imageData",Base64.encodeToString(readImage(entry),Base64.NO_WRAP))
        }
        return array.toString()
    }

    /** Validate and commit the union atomically; never evict current history to fit an import. */
    fun mergeImportJson(raw:String):Boolean = synchronized(LOCK) {
        runCatching {
            if(vaultFile.exists())readStrict()
            require(raw.length<=MAX_PORTABLE_CHARACTERS)
            val incoming=JSONArray(raw)
            val merged=JSONArray(exportEntries(entries()))
            val keys=(0 until merged.length()).map { merged.getJSONObject(it).optString("key",merged.getJSONObject(it).getLong("id").toString()) }.toMutableSet()
            var next=maxOf(System.currentTimeMillis(),entries().maxOfOrNull {it.id}?:0L)
            for(index in 0 until incoming.length()) {
                val row=incoming.getJSONObject(index)
                val key=row.optString("key",row.getLong("id").toString())
                if(keys.add(key)) {
                    check(next<Long.MAX_VALUE);row.put("id",++next)
                    merged.put(row)
                }
            }
            require(merged.length()<=MAX_ITEMS)
            importJson(merged.toString())
        }.getOrDefault(false)
    }

    fun importJson(raw: String): Boolean = synchronized(LOCK) {
        val written=mutableListOf<String>()
        try {
            require(raw.length<=MAX_PORTABLE_CHARACTERS) { "备份过大" }
            val array=JSONArray(raw)
            val parsed=parseEntries(raw)
            require(parsed.size==array.length() && parsed.size<=MAX_ITEMS) { "备份记录无效" }
            require(parsed.filter { it.kind=="image" }.sumOf { it.imageBytes.toLong() }<=ImageBlobStore.MAX_ACTIVE_BYTES) { "备份图片总量过大" }
            val rows=(0 until array.length()).map { array.getJSONObject(it) }.associateBy { it.optString("key",it.getLong("id").toString()) }
            require(rows.size==parsed.size) { "备份编号重复" }
            val attachments=parsed.map { entry ->
                if(entry.kind!="image")null else {
                    val encoded=rows.getValue(entry.key).getString("imageData")
                    require(encoded.length<=ImageBlobStore.MAX_IMAGE_BYTES*4/3+8) { "备份图片过大" }
                    val bytes=Base64.decode(encoded,Base64.NO_WRAP)
                    val info=images.validate(bytes)
                    require(info.sha256==entry.imageSha256 && info.bytes==entry.imageBytes && info.width==entry.imageWidth && info.height==entry.imageHeight) { "备份图片校验失败" }
                    bytes
                }
            }
            val imported=parsed.mapIndexed { index,entry ->
                attachments[index]?.let { bytes ->
                    val ref=images.write(bytes);written.add(ref);entry.copy(imageRef=ref)
                } ?: entry
            }
            persist(imported,allowRecovery=true)
            true
        } catch(_:Exception) { written.forEach(images::delete);false }
    }

    private fun persist(items: List<ClipEntry>, allowRecovery: Boolean = false) {
        // Never replace a damaged or unreadable vault with an empty snapshot.
        if (vaultFile.exists() && !allowRecovery) readStrict()
        val raw = buildJson(items)
        require(raw.toByteArray(Charsets.UTF_8).size<=16*1024*1024) { "剪贴板库超过 16 MiB" }
        val encrypted = crypto.encrypt(raw.toByteArray(Charsets.UTF_8))
        val atomic=android.util.AtomicFile(vaultFile)
        val output=atomic.startWrite()
        try {
            output.write(encrypted)
            atomic.finishWrite(output)
        } catch(error:Exception) {
            atomic.failWrite(output)
            throw error
        }
        metadata.remove(vaultFile.absolutePath)
        if(crypto is LocalVaultCrypto)metadata[vaultFile.absolutePath]=Metadata(
            vaultFile.lastModified() to vaultFile.length(),items.sortedWith(compareByDescending<ClipEntry>{it.pinned}.thenByDescending{it.createdAt}))
        val generation=++backupGeneration
        backupWorker.execute { runCatching {
            if(com.floatclip.app.security.VaultSettings(appContext).backupWriteArmed()) {
                val portable=synchronized(LOCK) { if(generation==backupGeneration)exportEntries(items) else null }
                if(portable!=null)VaultBackupManager(appContext).backupIfConfigured(portable)
            }
        } }
    }

    private fun readVaultJson(): String {
        if (!vaultFile.exists()) return "[]"
        return runCatching { crypto.decrypt(android.util.AtomicFile(vaultFile).readFully()).toString(Charsets.UTF_8) }.getOrDefault("[]")
    }

    private fun parseEntries(raw: String): List<ClipEntry> = runCatching {
        val array = JSONArray(raw)
        buildList {
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                add(ClipEntry(
                    id = o.getLong("id"),
                    text = o.getString("text"),
                    pinned = o.optBoolean("pinned", false),
                    locked = o.optBoolean("locked", false),
                    category = o.optString("category", CategoryStore.DEFAULT_CATEGORY),
                    createdAt = o.optLong("createdAt", 0L),
                    key = o.optString("key", o.getLong("id").toString()),
                    updatedAt = o.optLong("updatedAt", o.optLong("createdAt", 0L)),
                    sourceDeviceId = o.optJSONObject("source")?.optString("id", "local") ?: "local",
                    sourceDeviceName = o.optJSONObject("source")?.optString("name", "本机") ?: "本机",
                    sourcePlatform = o.optJSONObject("source")?.optString("platform", "android") ?: "android",
                    kind=o.optString("kind","text"),imageRef=o.optString("imageRef",""),imageSha256=o.optString("imageSha256",""),
                    imageBytes=o.optInt("imageBytes",0),imageWidth=o.optInt("imageWidth",0),imageHeight=o.optInt("imageHeight",0),
                ))
            }
        }.onEach(::validateEntry).sortedWith(compareByDescending<ClipEntry> { it.pinned }.thenByDescending { it.createdAt })
    }.getOrDefault(emptyList())

    private fun buildJson(items: List<ClipEntry>): String {
        val array = JSONArray()
        items.forEach { item ->
            val row=JSONObject()
                .put("id", item.id)
                .put("text", item.text)
                .put("pinned", item.pinned)
                .put("locked", item.locked)
                .put("category", item.category)
                .put("createdAt", item.createdAt)
                .put("key", item.key).put("updatedAt", item.updatedAt)
                .put("source", JSONObject().put("id", item.sourceDeviceId)
                    .put("name", item.sourceDeviceName).put("platform", item.sourcePlatform))
            putImageFields(row,item)
            array.put(row)
        }
        return array.toString()
    }

    private fun readStrict(): String {
        if (!vaultFile.exists()) return "[]"
        val raw = crypto.decrypt(android.util.AtomicFile(vaultFile).readFully()).toString(Charsets.UTF_8)
        check(parseEntries(raw).size == JSONArray(raw).length()) { "本机剪贴板损坏，请保留数据并从备份恢复" }
        return raw
    }

    fun unifiedSnapshot(): JSONObject = synchronized(LOCK) {
        val raw = readStrict()
        val revision = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val array = JSONArray()
        val device = DeviceIdentity.json(appContext)
        parseEntries(raw).forEach { item ->
            val source = if (item.sourceDeviceId == "local") device else JSONObject()
                .put("id", item.sourceDeviceId).put("name", item.sourceDeviceName).put("platform", item.sourcePlatform)
            val row=JSONObject().put("id", item.key).put("text", item.text).put("pinned", item.pinned)
                .put("locked", item.locked).put("category", item.category).put("createdAt", item.createdAt).put("updatedAt", item.updatedAt)
                .put("source", source)
            putImageFields(row,item);array.put(row)
        }
        JSONObject().put("revision", revision).put("entries", array)
    }

    fun commitUnified(revision: String, array: JSONArray): JSONObject = synchronized(LOCK) {
        check(unifiedSnapshot().getString("revision") == revision) { "VAULT_CONFLICT" }
        check(array.length() <= MAX_ITEMS && array.toString().toByteArray(Charsets.UTF_8).size <= 16 * 1024 * 1024) { "剪贴板库过大" }
        val current = entries().associateBy { it.key }
        var nextId = maxOf(System.currentTimeMillis(), (current.values.maxOfOrNull { it.id } ?: 0L) + 1)
        val keys = mutableSetOf<String>()
        val ownId = DeviceIdentity.id(appContext)
        val next = (0 until array.length()).map { index ->
            val o = array.getJSONObject(index)
            val key = o.getString("id")
            val text = o.getString("text")
            val source = o.getJSONObject("source")
            val sourceId = source.getString("id")
            val name = source.getString("name")
            val platform = source.getString("platform")
            val category = o.getString("category")
            val created = o.getLong("createdAt")
            val updated = o.getLong("updatedAt")
            require(key.matches(Regex("[-a-zA-Z0-9_:]{1,128}")) && keys.add(key)) { "记录编号无效" }
            require(text.isNotBlank() && text.length <= 1000000 && category.length <= 80 && created >= 0 && updated >= 0) { "记录格式无效" }
            require(sourceId.matches(Regex("[-a-zA-Z0-9_:]{1,100}")) && name.isNotBlank() && name.length <= 80 && platform in listOf("android", "windows", "ipad", "web")) { "设备来源无效" }
            val existing=current[key]
            val kind=o.optString("kind","text")
            if(kind=="image")require(existing?.kind=="image" && o.optString("imageRef")==existing.imageRef &&
                o.optString("imageSha256")==existing.imageSha256) { "图片附件不能由文字入口创建或替换" }
            ClipEntry(
                current[key]?.id ?: nextId++,
                text,
                o.getBoolean("pinned"),
                o.optBoolean("locked", current[key]?.locked ?: false),
                category.ifBlank { CategoryStore.DEFAULT_CATEGORY },
                created,
                key,
                updated,
                if (sourceId == ownId) "local" else sourceId,
                name,
                platform,
                kind,if(kind=="image")existing!!.imageRef else "",if(kind=="image")existing!!.imageSha256 else "",
                if(kind=="image")existing!!.imageBytes else 0,if(kind=="image")existing!!.imageWidth else 0,if(kind=="image")existing!!.imageHeight else 0,
            )
        }
        persist(next)
        unifiedSnapshot()
    }

    /** The older optional Web route edits text only and cannot remove native image attachments. */
    fun textSnapshot():JSONObject = synchronized(LOCK) {
        val snapshot=unifiedSnapshot();val array=snapshot.getJSONArray("entries")
        snapshot.put("entries",JSONArray().also { text ->
            for(index in 0 until array.length())if(array.getJSONObject(index).optString("kind","text")=="text")text.put(array.getJSONObject(index))
        })
    }
    fun commitTextUnified(revision:String,array:JSONArray):JSONObject = synchronized(LOCK) {
        val current=unifiedSnapshot()
        require(current.getString("revision")==revision) { "VAULT_CONFLICT" }
        val merged=JSONArray()
        for(index in 0 until array.length()) {
            val row=array.getJSONObject(index)
            require(row.optString("kind","text")=="text") { "此入口仅支持文字" }
            merged.put(row)
        }
        val all=current.getJSONArray("entries")
        for(index in 0 until all.length())if(all.getJSONObject(index).optString("kind","text")=="image")merged.put(all.getJSONObject(index))
        commitUnified(revision,merged);textSnapshot()
    }

    private fun putImageFields(row:JSONObject,item:ClipEntry) {
        row.put("kind",item.kind)
        if(item.kind=="image")row.put("imageRef",item.imageRef).put("imageSha256",item.imageSha256)
            .put("imageBytes",item.imageBytes).put("imageWidth",item.imageWidth).put("imageHeight",item.imageHeight).put("mimeType","image/png")
    }
    private fun validateEntry(entry:ClipEntry) {
        require(entry.kind in listOf("text","image")) { "记录类型无效" }
        if(entry.kind=="image")require(entry.imageRef.matches(Regex("[a-f0-9]{32}")) && entry.imageSha256.matches(Regex("[a-f0-9]{64}")) &&
            entry.imageBytes in 45..ImageBlobStore.MAX_IMAGE_BYTES && entry.imageWidth in 1..16384 && entry.imageHeight in 1..16384 &&
            entry.imageWidth.toLong()*entry.imageHeight<=ImageBlobStore.MAX_PIXELS) { "图片记录无效" }
    }

    private fun migrateLegacyIfNeeded() {
        if (vaultFile.exists()) return
        val legacy = legacyPrefs.getString(KEY_ITEMS, null) ?: return
        val migrated = parseEntries(legacy)
        if (migrated.isEmpty() && runCatching { JSONArray(legacy).length() }.getOrNull() != 0) return
        persist(migrated)
        legacyPrefs.edit().remove(KEY_ITEMS).apply()
    }

    companion object {
        private data class Metadata(val stamp:Pair<Long,Long>,val entries:List<ClipEntry>)
        private val metadata=linkedMapOf<String,Metadata>()
        private val THUMBNAIL_LOCK = Any()
        private val LOCK = Any()
        private const val KEY_ITEMS = "items"
        private const val MAX_ITEMS = 500
        private const val MAX_PORTABLE_CHARACTERS=48*1024*1024
        private val undoImageReferences=mutableMapOf<String,Set<String>>()
        private var backupGeneration=0L
        private val backupWorker=java.util.concurrent.Executors.newSingleThreadExecutor { task -> Thread(task,"FloatClip backup").apply { isDaemon=true } }
    }
}

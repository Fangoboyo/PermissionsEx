/**
 * PermissionsEx
 * Copyright (C) zml and PermissionsEx contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ca.stellardrift.permissionsex.backend.conversion.luckperms

import ca.stellardrift.permissionsex.PermissionsEx.SUBJECTS_GROUP
import ca.stellardrift.permissionsex.backend.AbstractDataStore
import ca.stellardrift.permissionsex.backend.DataStore
import ca.stellardrift.permissionsex.context.ContextValue
import ca.stellardrift.permissionsex.data.ContextInheritance
import ca.stellardrift.permissionsex.data.ImmutableSubjectData
import ca.stellardrift.permissionsex.exception.PermissionsException
import ca.stellardrift.permissionsex.rank.AbstractRankLadder
import ca.stellardrift.permissionsex.rank.RankLadder
import ca.stellardrift.permissionsex.util.ThrowingSupplier
import ca.stellardrift.permissionsex.util.Translations.t
import ca.stellardrift.permissionsex.util.configurate.ReloadableConfig
import ca.stellardrift.permissionsex.util.configurate.get
import ca.stellardrift.permissionsex.util.configurate.set
import com.google.common.collect.ImmutableSet.toImmutableSet
import com.google.common.collect.Maps
import com.google.common.io.Files.getNameWithoutExtension
import com.google.common.reflect.TypeToken
import ninja.leaping.configurate.ConfigurationNode
import ninja.leaping.configurate.gson.GsonConfigurationLoader
import ninja.leaping.configurate.hocon.HoconConfigurationLoader
import ninja.leaping.configurate.loader.ConfigurationLoader
import ninja.leaping.configurate.objectmapping.Setting
import ninja.leaping.configurate.objectmapping.serialize.ConfigSerializable
import ninja.leaping.configurate.yaml.YAMLConfigurationLoader
import org.yaml.snakeyaml.DumperOptions
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletableFuture.completedFuture
import java.util.function.Function
import java.util.stream.Collectors
import kotlin.math.absoluteValue


@ConfigSerializable
class LuckPermsFileDataStore constructor(): AbstractDataStore(FACTORY) {
    companion object {
        @JvmField
        val FACTORY = Factory("luckperms-file", LuckPermsFileDataStore::class.java)
    }

    constructor(format: ConfigFormat, combined: Boolean) : this() {
        this.format = format
        this.combined = combined
    }

    @Setting(comment = "The location of the luckperms base directory, relative to the server plugins folder")
    var pluginDir: Path = FileSystems.getDefault().getPath("LuckPerms")

    @Setting(comment = "Whether or not all files are stored as one, equivalent to putting the '-combined' suffix on a data storage option in LuckPerms")
    var combined: Boolean = false

    @Setting(comment = "The file format to attempt to load from")
    var format: ConfigFormat = ConfigFormat.YAML

    private lateinit var lpConfig: ReloadableConfig<ConfigurationNode>
    private lateinit var subjectLayout: SubjectLayout

    override fun initializeInternal() {
        val rootDir = manager.baseDirectory.parent.resolve(pluginDir).resolve(this.format.storageDirName)
        subjectLayout = if (combined) {
            CombinedSubjectLayout(this, rootDir)
        } else {
            SeparateSubjectLayout(this, rootDir)
        }
        lpConfig = ReloadableConfig(
            YAMLConfigurationLoader.builder()
                .setFlowStyle(DumperOptions.FlowStyle.BLOCK)
                .setPath(rootDir.resolve("config.yml"))
                .build()
        )
    }


    override fun close() {
    }

    override fun getAllIdentifiers(type: String): Set<String> {
        return this.subjectLayout.getIdentifiers(type)
    }

    override fun getRegisteredTypes(): Set<String> {
        return subjectLayout.types
    }

    override fun getDefinedContextKeys(): CompletableFuture<Set<String>> {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun getAll(): Iterable<Map.Entry<Map.Entry<String, String>, ImmutableSubjectData>> {
        return this.registeredTypes.parallelStream()
            .flatMap { type -> getAllIdentifiers(type).stream().map { ident -> Maps.immutableEntry(type, ident)} }
            .map { key -> Maps.immutableEntry(key, getDataInternal(key.key, key.value).join()) }
            .collect(toImmutableSet())
    }

    override fun getDataInternal(type: String, identifier: String): CompletableFuture<ImmutableSubjectData> {
        return completedFuture(this.subjectLayout[type, identifier].toSubjectData())
    }

    override fun setDataInternal(
        type: String?,
        identifier: String?,
        data: ImmutableSubjectData?
    ): CompletableFuture<ImmutableSubjectData> {
        TODO("read-only")
    }

    override fun isRegistered(type: String, identifier: String): CompletableFuture<Boolean> {
        return completedFuture(Pair(type, identifier) in subjectLayout)
    }

    override fun getContextInheritanceInternal(): CompletableFuture<ContextInheritance> {
        return completedFuture(contextParentsFromConfig(lpConfig.node))
    }

    override fun setContextInheritanceInternal(contextInheritance: ContextInheritance?): CompletableFuture<ContextInheritance> {
        return runAsync(ThrowingSupplier<ContextInheritance, Exception> {
            val inherit = if (contextInheritance != null && contextInheritance !is LuckPermsContextInheritance) {
                LuckPermsContextInheritance(contextInheritance.allParents)
            } else {
                contextInheritance as LuckPermsContextInheritance?
            }

            lpConfig.update {
                if (inherit == null) {
                    lpConfig["world-rewrite"] = emptyMap<String, String>()
                } else {
                    inherit.writeToConfig(lpConfig.node)
                }
            }
            return@ThrowingSupplier inherit
        })
    }

    override fun <T : Any?> performBulkOperationSync(function: Function<DataStore, T>): T {
        return function.apply(this)
    }

    override fun getAllRankLadders(): Iterable<String> {
        return this.subjectLayout.tracks.node.childrenMap.keys.map(Any::toString)
    }

    override fun hasRankLadder(ladder: String): CompletableFuture<Boolean> {
        return completedFuture(ladder in this.subjectLayout.tracks)
    }

    override fun getRankLadderInternal(ladder: String): CompletableFuture<RankLadder> {
        return completedFuture(
            LuckPermsTrack(
                ladder,
                this.subjectLayout.tracks[ladder, "groups"].getList({ it!!.toString() }, listOf())
            )
        )
    }

    override fun setRankLadderInternal(ladder: String, newLadder: RankLadder?): CompletableFuture<RankLadder?> {
        val lpLadder: LuckPermsTrack? = when {
            newLadder is LuckPermsTrack -> newLadder
            newLadder != null -> LuckPermsTrack(
                ladder,
                newLadder.ranks.filter { it.key == SUBJECTS_GROUP }.map { it.value })
            else -> null
        }

        return runAsync(ThrowingSupplier<RankLadder?, Exception> {
            this.subjectLayout.tracks.update {
                if (lpLadder == null) {
                    it[ladder] = null
                } else {
                    it[ladder, "groups"] = lpLadder.groups
                }
            }
            return@ThrowingSupplier lpLadder
        })
    }

}

/** Configuration format to use -- gives format-specific paths/loaders **/
enum class ConfigFormat(val loaderProvider: (Path) -> ConfigurationLoader<*>, val extension: String) {
    YAML({ YAMLConfigurationLoader.builder().setPath(it).build() }, "yml"),
    JSON({ GsonConfigurationLoader.builder().setPath(it).build() }, "json"),
    HOCON({ HoconConfigurationLoader.builder().setPath(it).build() }, "conf");

    val storageDirName: String = "${this.name.toLowerCase(Locale.ROOT)}-storage"
}

interface SubjectLayout {
    operator fun get(type: String, ident: String): ConfigurationNode
    operator fun contains(subj: Pair<String, String>): Boolean = isRegistered(subj.first, subj.second)
    fun isRegistered(type: String, ident: String): Boolean
    fun getIdentifiers(type: String): Set<String>
    val types: Set<String>

    val tracks: ReloadableConfig<*>
}

class CombinedSubjectLayout(private val store: LuckPermsFileDataStore, private val rootDir: Path) : SubjectLayout {
    private val files: MutableMap<String, ReloadableConfig<*>> = mutableMapOf()

    override val tracks: ReloadableConfig<*> =
        ReloadableConfig(store.format.loaderProvider(rootDir.resolve("tracks.${store.format.extension}")))

    override val types: Set<String>
        get() {
            return Files.list(rootDir)
                .map { getNameWithoutExtension(it.fileName.toString()) }
                .filter { it != "tracks" }
                .collect(toImmutableSet())
        }

    private fun getFileFor(type: String): ReloadableConfig<*> {
        var rootNode = files[type]
        if (rootNode == null) {
            rootNode =
                ReloadableConfig(store.format.loaderProvider(rootDir.resolve("${type}s.${store.format.extension}")))
            files[type] = rootNode
        }
        return rootNode
    }

    override fun get(type: String, ident: String): ConfigurationNode {
        val rootNode = getFileFor(type)
        return rootNode[ident]
    }

    override fun isRegistered(type: String, ident: String): Boolean {
        if (!Files.exists(rootDir.resolve("${type}s.${store.format.extension}"))) {
            return false
        }

        return !this[type, ident].isVirtual
    }

    override fun getIdentifiers(type: String): Set<String> {
        return getFileFor(type).node.childrenMap.keys.map(Any::toString).toSet()
    }

}

class SeparateSubjectLayout(private val store: LuckPermsFileDataStore, private val rootDir: Path) : SubjectLayout {

    override val tracks: ReloadableConfig<*> =
        ReloadableConfig(store.format.loaderProvider(rootDir.resolve("tracks.${store.format.extension}")))

    override val types: Set<String>
        get() {
            return Files.list(rootDir)
                .filter { Files.isDirectory(it) }
                .map { it.fileName.toString().substring(0, -1) }
                .collect(toImmutableSet())
        }

    override fun isRegistered(type: String, ident: String): Boolean {
        return Files.exists(rootDir.resolve("${type}s").resolve("$ident.${store.format.extension}"))
    }

    override fun get(type: String, ident: String): ConfigurationNode {
        return store.format.loaderProvider(rootDir.resolve("${type}s").resolve("$ident.${store.format.extension}"))
            .load()
    }

    override fun getIdentifiers(type: String): Set<String> {
        return Files.list(rootDir.resolve("${type}s"))
            .map { x ->
                val name = x.fileName.toString()
                name.substring(0..(name.length - store.format.extension.length))
            }
            .collect(Collectors.toSet())
    }
}

fun contextParentsFromConfig(node: ConfigurationNode): LuckPermsContextInheritance {
    val worldRewrite = node["world-rewrite"].getValue(object : TypeToken<Map<String, String>>() {}, mapOf())
    val ret = mutableMapOf<ContextValue<*>, List<ContextValue<*>>>()
    worldRewrite.forEach {
        ret[ContextValue<String>("world", it.key)] = listOf(ContextValue<String>("world", it.value))
    }
    return LuckPermsContextInheritance(ret)
}

/**
 * Our generalization of LP world inheritance
 */
class LuckPermsContextInheritance(private val contextParents: Map<ContextValue<*>, List<ContextValue<*>>>) :
    ContextInheritance {
    override fun getParents(context: ContextValue<*>): List<ContextValue<*>>? {
        return contextParents[context]
    }

    override fun setParents(context: ContextValue<*>, parents: List<ContextValue<*>>?): LuckPermsContextInheritance {
        require(context.key.equals("world", ignoreCase = true))
        return if (parents == null) {
            LuckPermsContextInheritance(contextParents - context)
        } else {
            require(parents.size == 1) { "worlds can only have one parent" }
            require(
                parents.first().key.equals(
                    "world",
                    ignoreCase = true
                )
            ) { "context inheritance only happens between worlds" }
            LuckPermsContextInheritance(contextParents + (context to parents))
        }
    }

    override fun getAllParents(): Map<ContextValue<*>, List<ContextValue<*>>> {
        return contextParents
    }

    fun writeToConfig(node: ConfigurationNode) {
        val worldRewriteNode = node["world-rewrite"]
        worldRewriteNode.value = null
        this.contextParents.forEach { (k, v) ->
            worldRewriteNode[k.rawValue] = v.first().rawValue
        }
    }
}

fun Boolean.asInteger(): Int {
    return if (this) {
        1
    } else {
        -1
    }
}

class LuckPermsTrack internal constructor(name: String, val groups: List<String>) : AbstractRankLadder(name) {

    constructor(name: String) : this(name, listOf())

    override fun getRanks(): List<Map.Entry<String, String>> {
        return this.groups.map { Maps.immutableEntry(SUBJECTS_GROUP, it) }
    }

    override fun newWithRanks(ents: List<Map.Entry<String, String>>): LuckPermsTrack {
        return LuckPermsTrack(this.name, ents.map { (k, v) ->
            if (k != SUBJECTS_GROUP) {
                throw PermissionsException(
                    t(
                        "LuckPerms backend only supports subject type group, but a subject with type %s was passed to rank ladder %s",
                        k,
                        this.name
                    )
                )
            }
            v
        })
    }

}

private data class LuckPermsDefinition<ValueType>(
    val key: String,
    val value: ValueType,
    val weight: Int,
    val contexts: Set<ContextValue<*>>
)

private fun addIfNotVirtual(
    parentNode: ConfigurationNode,
    key: String,
    values: MutableSet<ContextValue<*>>,
    keyAs: String = key
) {
    val childNode = parentNode[key]
    if (!childNode.isVirtual) {
        values.add(ContextValue<String>(keyAs, childNode.string!!))
    }
}

private fun <T> defListFromNode(
    node: ConfigurationNode,
    valueConverter: (ConfigurationNode) -> T,
    valueKey: String = "value",
    keyOverride: String? = null
): Set<LuckPermsDefinition<T>> {
    val ret = mutableSetOf<LuckPermsDefinition<T>>()
    node.childrenList.forEach {
        val contexts = mutableSetOf<ContextValue<*>>()
        node["context"].childrenMap.forEach { (k, v) -> contexts.add(ContextValue<String>(k.toString(), v.string!!)) }
        addIfNotVirtual(node, "world", contexts)
        addIfNotVirtual(node, "server", contexts, "server-tag")
        addIfNotVirtual(node, "expiry", contexts, "before-time")

        ret.add(
            LuckPermsDefinition(
                keyOverride
                    ?: it["permission"].string!!, valueConverter(it[valueKey]), it["priority"].getInt(0), contexts
            )
        )
    }
    return ret
}

private fun ConfigurationNode.toSubjectData(): LuckPermsSubjectData {
    return LuckPermsSubjectData(defListFromNode(this["permissions"], ConfigurationNode::getBoolean),
        defListFromNode(this["meta"], { it.string!! }),
        defListFromNode(this["parents"], { Maps.immutableEntry("group", it.string!!)},
            valueKey = "group", keyOverride = "group"))
}

private class LuckPermsSubjectData(val permissions: Set<LuckPermsDefinition<Boolean>>, val options: Set<LuckPermsDefinition<String>>, val parents: Set<LuckPermsDefinition<Map.Entry<String, String>>>) : ImmutableSubjectData {

    override fun getAllOptions(): Map<Set<ContextValue<*>>, Map<String, String>> {
        return options.groupBy({ it.contexts }, { it.key to it.value }).mapValues { (_, v) -> v.toMap() }
    }

    override fun getOptions(contexts: Set<ContextValue<*>>?): Map<String, String> {
        return options.filter { it.contexts.equals(contexts) }.associate { it.key to it.value }
    }

    override fun setOption(contexts: Set<ContextValue<*>>, key: String, value: String?): ImmutableSubjectData {
        TODO("read-only")
    }

    override fun setOptions(contexts: Set<ContextValue<*>>, values: MutableMap<String, String>?): ImmutableSubjectData {
        TODO("read-only")
    }

    override fun clearOptions(contexts: MutableSet<ContextValue<*>>): ImmutableSubjectData {
        TODO("read-only")
    }

    override fun clearOptions(): ImmutableSubjectData {
        TODO("read-only")
    }

    override fun getAllPermissions(): Map<Set<ContextValue<*>>, Map<String, Int>> {
        return permissions.groupBy({ it.contexts }, { it.key to if (it.value) { 1 } else { -1 }}).mapValues { (_, v) -> v.toMap() }
    }

    override fun getPermissions(contexts: MutableSet<ContextValue<*>>?): Map<String, Int> {
        return this.permissions.filter { it.contexts == contexts }.associate { it.key to if (it.value) 1 else -1 }
    }

    override fun setPermission(
        contexts: MutableSet<ContextValue<*>>?,
        permission: String?,
        value: Int
    ): ImmutableSubjectData {
        TODO("read-only")
    }

    override fun setPermissions(
        contexts: MutableSet<ContextValue<*>>?,
        values: MutableMap<String, Int>?
    ): ImmutableSubjectData {
        TODO("read-only")
    }

    override fun clearPermissions(): ImmutableSubjectData {
        TODO("read-only")
    }

    override fun clearPermissions(contexts: MutableSet<ContextValue<*>>?): ImmutableSubjectData {
        TODO("read-only")
    }

    override fun getAllParents(): Map<Set<ContextValue<*>>, List<Map.Entry<String, String>>> {
        return parents.groupBy({ it.contexts }, { it.value})
    }

    override fun getParents(contexts: Set<ContextValue<*>>?): List<Map.Entry<String, String>> {
        return parents.filter { it.contexts == contexts }.map { it.value }
    }

    override fun addParent(
        contexts: MutableSet<ContextValue<*>>?,
        type: String?,
        identifier: String?
    ): ImmutableSubjectData {
        TODO("read-only")
    }

    override fun removeParent(
        contexts: MutableSet<ContextValue<*>>?,
        type: String?,
        identifier: String?
    ): ImmutableSubjectData {
        TODO("read-only")
    }

    override fun setParents(
        contexts: MutableSet<ContextValue<*>>?,
        parents: MutableList<MutableMap.MutableEntry<String, String>>?
    ): ImmutableSubjectData {
        TODO("read-only")
    }

    override fun clearParents(): ImmutableSubjectData {
        TODO("read-only")
    }

    override fun clearParents(contexts: MutableSet<ContextValue<*>>?): ImmutableSubjectData {
        TODO("read-only")
    }

    override fun getDefaultValue(contexts: MutableSet<ContextValue<*>>?): Int {
        return permissions.filter { it.contexts == contexts && it.key == "*" }.map { it.value.asInteger() }.maxBy { it.absoluteValue } ?: 0
    }

    override fun setDefaultValue(contexts: MutableSet<ContextValue<*>>?, defaultValue: Int): ImmutableSubjectData {
        TODO("read-only")
    }

    override fun getAllDefaultValues(): Map<Set<ContextValue<*>>, Int> {
        return permissions.filter { it.key == "*" }.groupBy({ it.contexts }, {it.value.asInteger()}).mapValues { (_, v) -> v.maxBy { it.absoluteValue } ?: 0 }
    }

    override fun getActiveContexts(): Set<Set<ContextValue<*>>> {
        return (this.permissions + this.options + this.parents).map { it.contexts }.distinct().toSet()
    }
}
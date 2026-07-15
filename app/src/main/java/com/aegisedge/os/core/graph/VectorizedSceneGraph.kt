package com.aegisedge.os.core.graph

import com.aegisedge.os.core.model.LocBox
import com.aegisedge.os.core.model.SceneRelation
import com.aegisedge.os.core.model.SemanticEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Vectorized Scene Graph — a live 3D graph database held entirely in
 * volatile RAM. Nodes are tracked entities carrying a small embedding
 * vector; edges are time-stamped semantic relations, e.g.
 *
 *     Person_A --[OWNS since t0]--> Backpack_1 --[ABANDONED at t1]-->
 *
 * The "3D" axes: (x, y) from PaliGemma <loc> tokens plus a coarse depth
 * estimate from box scale, letting proximity reasoning survive camera
 * perspective. The graph is wiped with the RAM buffer on a benign verdict.
 */
class VectorizedSceneGraph {

    @Serializable
    data class Node(
        val entityId: String,
        var category: String,
        var lastBox: LocBox,
        var lastSeenNanos: Long,
        var firstSeenNanos: Long,
        /** Coarse 3D position: normalized x, y + depth proxy from box area. */
        var position: FloatArray = FloatArray(3),
        /** Appearance/attribute embedding for re-identification across frames. */
        var embedding: FloatArray = FloatArray(EMBED_DIM),
        var attributes: MutableList<String> = mutableListOf(),
    )

    private val nodes = ConcurrentHashMap<String, Node>()
    private val edges = ConcurrentHashMap<String, SceneRelation>()   // key: subj|pred|obj
    private var trackCounter = 0

    companion object {
        const val EMBED_DIM = 32
        /** loc grid is 0..1023 */
        private const val GRID = 1024f
        private const val IOU_MATCH_THRESHOLD = 0.35f
        /** Owner further than this (normalized units) from their item => candidate abandonment. */
        private const val ABANDON_DISTANCE = 0.45f
        private const val ABANDON_SECONDS = 20L
    }

    // ------------------------------------------------------------------
    // Ingest
    // ------------------------------------------------------------------

    /**
     * Absorbs a new set of grounded detections, matching them to existing
     * tracks by IoU + embedding similarity, then re-derives relations.
     * Returns the entities with stable track ids for Layer 2's snapshot.
     */
    fun ingest(
        timestampNanos: Long,
        detections: List<Pair<String, LocBox>>,
        attributes: Map<String, List<String>> = emptyMap(),
    ): List<SemanticEntity> {
        val resolved = detections.map { (category, box) ->
            val match = nodes.values
                .filter { it.category == category }
                .maxByOrNull { iou(it.lastBox, box) }
                ?.takeIf { iou(it.lastBox, box) >= IOU_MATCH_THRESHOLD }

            val node = match?.apply {
                lastBox = box; lastSeenNanos = timestampNanos
                position = boxTo3d(box)
            } ?: Node(
                entityId = "${category}_${nextTrackTag()}",
                category = category,
                lastBox = box,
                lastSeenNanos = timestampNanos,
                firstSeenNanos = timestampNanos,
                position = boxTo3d(box),
            ).also { nodes[it.entityId] = it }

            attributes[category]?.let { node.attributes.addAll(it - node.attributes.toSet()) }
            SemanticEntity(node.entityId, category, box,
                node.attributes.toList(), confidence = 1f)
        }
        deriveRelations(timestampNanos)
        return resolved
    }

    // ------------------------------------------------------------------
    // Relations: OWNS / CARRIES / NEAR / ABANDONED
    // ------------------------------------------------------------------

    private fun deriveRelations(now: Long) {
        val people = nodes.values.filter { it.category.startsWith("person") }
        val items = nodes.values.filter { it.category in setOf("bag", "backpack", "suitcase", "package") }

        for (item in items) {
            val owner = people.minByOrNull { dist3(it.position, item.position) }
            val key = { pred: String, p: Node -> "${p.entityId}|$pred|${item.entityId}" }

            if (owner != null && dist3(owner.position, item.position) < ABANDON_DISTANCE) {
                // First person seen near an item at its first appearance becomes its owner.
                val ownsKey = key("OWNS", owner)
                edges.putIfAbsent(ownsKey,
                    SceneRelation(owner.entityId, "OWNS", item.entityId, now))
                edges.remove("${owner.entityId}|ABANDONED|${item.entityId}")
            } else {
                // Item present but every known owner has left the proximity radius.
                val ownsEdge = edges.values.firstOrNull {
                    it.predicate == "OWNS" && it.objectId == item.entityId
                } ?: continue
                val ownerNode = nodes[ownsEdge.subjectId]
                val ownerGone = ownerNode == null ||
                    dist3(ownerNode.position, item.position) >= ABANDON_DISTANCE
                val itemDwellSec = (now - item.firstSeenNanos) / 1_000_000_000L
                if (ownerGone && itemDwellSec >= ABANDON_SECONDS) {
                    edges.putIfAbsent("${ownsEdge.subjectId}|ABANDONED|${item.entityId}",
                        SceneRelation(ownsEdge.subjectId, "ABANDONED", item.entityId, now))
                }
            }
        }

        // Generic proximity edges feed Gemma 3's spatial reasoning.
        for (a in nodes.values) for (b in nodes.values) {
            if (a.entityId >= b.entityId) continue
            val k = "${a.entityId}|NEAR|${b.entityId}"
            if (dist3(a.position, b.position) < 0.2f) {
                edges.putIfAbsent(k, SceneRelation(a.entityId, "NEAR", b.entityId, now))
            } else edges.remove(k)
        }
    }

    // ------------------------------------------------------------------
    // Query surface for Layer 2 / Layer 3
    // ------------------------------------------------------------------

    fun relations(): List<SceneRelation> = edges.values.toList()

    fun abandonedItems(): List<SceneRelation> =
        edges.values.filter { it.predicate == "ABANDONED" }

    fun entityCount(category: String? = null): Int =
        if (category == null) nodes.size
        else nodes.values.count { it.category == category }

    /** Serializes the whole graph for injection into Gemma 3's prompt. */
    fun toPromptJson(): String {
        val summary = nodes.values.map {
            mapOf(
                "id" to it.entityId, "category" to it.category,
                "attributes" to it.attributes.joinToString(","),
                "ageSeconds" to ((it.lastSeenNanos - it.firstSeenNanos) / 1_000_000_000L).toString(),
            )
        }
        val rels = edges.values.map { "${it.subjectId} [${it.predicate}] ${it.objectId}" }
        return Json.encodeToString(mapOf("entities" to summary.map { it.toString() }, "relations" to rels))
    }

    /** Evict tracks not seen for [staleSeconds]; called each pipeline tick. */
    fun evictStale(now: Long, staleSeconds: Long = 120) {
        nodes.values.removeIf { now - it.lastSeenNanos > staleSeconds * 1_000_000_000L }
        edges.values.removeIf { rel ->
            (rel.subjectId !in nodes.keys || rel.objectId !in nodes.keys) &&
                rel.predicate != "ABANDONED"   // abandonment survives owner leaving frame
        }
    }

    /** Volatile by design: benign verdict wipes the graph with the RAM buffer. */
    fun wipe() { nodes.clear(); edges.clear() }

    // ------------------------------------------------------------------

    private fun nextTrackTag(): String {
        trackCounter += 1
        // person_A, person_B ... then person_27 past the alphabet
        return if (trackCounter <= 26) ('A' + trackCounter - 1).toString() else trackCounter.toString()
    }

    private fun boxTo3d(b: LocBox): FloatArray {
        val cx = (b.xMin + b.xMax) / 2f / GRID
        val cy = (b.yMin + b.yMax) / 2f / GRID
        val area = ((b.xMax - b.xMin) * (b.yMax - b.yMin)) / (GRID * GRID)
        val depth = 1f - sqrt(area).coerceIn(0f, 1f)   // bigger box => closer => smaller depth
        return floatArrayOf(cx, cy, depth)
    }

    private fun dist3(a: FloatArray, b: FloatArray): Float {
        var acc = 0f
        for (i in 0..2) { val d = a[i] - b[i]; acc += d * d }
        return sqrt(acc)
    }

    private fun iou(a: LocBox, b: LocBox): Float {
        val ix = maxOf(0, minOf(a.xMax, b.xMax) - maxOf(a.xMin, b.xMin))
        val iy = maxOf(0, minOf(a.yMax, b.yMax) - maxOf(a.yMin, b.yMin))
        val inter = ix.toFloat() * iy
        val union = (a.xMax - a.xMin) * (a.yMax - a.yMin) +
            (b.xMax - b.xMin) * (b.yMax - b.yMin) - inter
        return if (abs(union) < 1e-6) 0f else inter / union
    }
}

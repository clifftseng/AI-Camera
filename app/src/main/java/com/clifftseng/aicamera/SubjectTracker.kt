package com.clifftseng.aicamera

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 跨幀追蹤 + 主體判定：分辨「要拍的人」跟「路過的路人」。
 *
 * 單看大小會誤判（拉遠景時主體很小、路人可能離鏡頭近），所以用綜合分數：
 * 大小 40% + 停留穩定度 30%（站定擺姿勢 vs 走動路過）+ 面向鏡頭 20% + 靠近畫面中心 10%。
 * 使用者也可以點擊畫面直接鎖定主體，鎖定期間其他人一律當路人。
 */
class SubjectTracker {

    class Track(
        val id: Int,
        var pts: List<FloatArray>,
        var bbox: FloatArray,
    ) {
        var cx = (bbox[0] + bbox[2]) / 2f
        var cy = (bbox[1] + bbox[3]) / 2f
        var height = bbox[3] - bbox[1]
        var age = 1          // 連續出現的偵測幀數
        var missed = 0
        var motionEma = 0f   // 每幀移動量（以自身身高正規化）的指數平均
        var score = 0f
    }

    private var nextId = 1
    private val tracks = mutableListOf<Track>()

    /** 鎖定的主體 id；null = 自動判定 */
    var lockedId: Int? = null
        private set

    /** 餵入本幀所有偵測到的人（view 座標），回傳判定為主體的 track。 */
    fun update(persons: List<List<FloatArray>>, w: Float, h: Float): List<Track> {
        val dets = persons.filter { it.size >= 33 }
        val detBoxes = dets.map { bboxOf(it) }
        val used = BooleanArray(dets.size)

        // 貪婪配對：每條既有 track 找最近的偵測
        for (t in tracks) {
            var best = -1
            var bestDist = Float.MAX_VALUE
            for (i in dets.indices) {
                if (used[i]) continue
                val dcx = (detBoxes[i][0] + detBoxes[i][2]) / 2f
                val dcy = (detBoxes[i][1] + detBoxes[i][3]) / 2f
                val dist = hypot((dcx - t.cx).toDouble(), (dcy - t.cy).toDouble()).toFloat()
                if (dist < bestDist) {
                    bestDist = dist
                    best = i
                }
            }
            // 允許的幀間位移：半個身高
            if (best >= 0 && bestDist < t.height.coerceAtLeast(h * 0.05f) * 0.5f) {
                used[best] = true
                val box = detBoxes[best]
                val dcx = (box[0] + box[2]) / 2f
                val dcy = (box[1] + box[3]) / 2f
                val move = hypot((dcx - t.cx).toDouble(), (dcy - t.cy).toDouble()).toFloat() /
                    t.height.coerceAtLeast(1f)
                t.motionEma += 0.2f * (move - t.motionEma)
                t.pts = dets[best]
                t.bbox = box
                t.cx = dcx
                t.cy = dcy
                t.height = box[3] - box[1]
                t.age++
                t.missed = 0
            } else {
                t.missed++
            }
        }
        tracks.removeAll { it.missed > MAX_MISSED }
        for (i in dets.indices) {
            if (!used[i]) tracks.add(Track(nextId++, dets[i], detBoxes[i]))
        }
        // 鎖定的人離開畫面太久就自動解鎖
        if (lockedId != null && tracks.none { it.id == lockedId }) lockedId = null

        return selectSubjects(w, h)
    }

    private fun selectSubjects(w: Float, h: Float): List<Track> {
        val visible = tracks.filter { it.missed == 0 }
        if (visible.isEmpty()) return emptyList()

        lockedId?.let { id ->
            return visible.filter { it.id == id }
        }

        val tallest = visible.maxOf { it.height }.coerceAtLeast(1f)
        for (t in visible) {
            val size = t.height / tallest
            // 站得夠久（約 1 秒起跳滿分）且不太會動 → 穩定
            val stability = min(t.age / 20f, 1f) * (1f - min(t.motionEma / 0.05f, 1f))
            val facing = if (isFacingCamera(t.pts)) 1f else 0.3f
            val centerProx = 1f - min(abs(t.cx - w / 2f) / (w / 2f), 1f)
            t.score = 0.4f * size + 0.3f * stability + 0.2f * facing + 0.1f * centerProx
        }
        val best = visible.maxOf { it.score }
        // 分數要接近最高者，且不能小到只是雜訊（門檻放低到 8%，遠景主體才留得住）
        return visible.filter { it.score >= best * 0.65f && it.height >= h * 0.08f }
    }

    /** 鼻子落在雙耳之間 → 大致面向鏡頭；側臉或背影時鼻子會偏出去 */
    private fun isFacingCamera(pts: List<FloatArray>): Boolean {
        val noseX = pts[0][0]
        val lo = min(pts[7][0], pts[8][0])
        val hi = max(pts[7][0], pts[8][0])
        return noseX in lo..hi
    }

    /**
     * 點擊處理：點到人 → 鎖定（再點同一人解除）；點空白處 → 解除鎖定。
     * @return true=鎖定了某人、false=解除了鎖定、null=沒點到任何東西也沒鎖定可解
     */
    fun toggleLockAt(x: Float, y: Float): Boolean? {
        val hit = tracks.filter { it.missed == 0 }.minByOrNull { t ->
            if (containsWithMargin(t.bbox, x, y)) {
                hypot((x - t.cx).toDouble(), (y - t.cy).toDouble()).toFloat()
            } else {
                Float.MAX_VALUE
            }
        }?.takeIf { containsWithMargin(it.bbox, x, y) }

        return when {
            hit != null && lockedId == hit.id -> {
                lockedId = null
                false
            }
            hit != null -> {
                lockedId = hit.id
                true
            }
            lockedId != null -> {
                lockedId = null
                false
            }
            else -> null
        }
    }

    fun lockedTrack(): Track? = tracks.find { it.id == lockedId && it.missed == 0 }

    /** 切換鏡頭時呼叫：畫面內容完全換掉，舊 track 全部作廢 */
    fun reset() {
        tracks.clear()
        lockedId = null
    }

    private fun containsWithMargin(b: FloatArray, x: Float, y: Float): Boolean {
        val margin = (b[3] - b[1]) * 0.15f
        return x in (b[0] - margin)..(b[2] + margin) && y in (b[1] - margin)..(b[3] + margin)
    }

    private fun bboxOf(pts: List<FloatArray>): FloatArray {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE; var maxY = Float.MIN_VALUE
        for (i in BBOX_POINTS) {
            minX = min(minX, pts[i][0]); maxX = max(maxX, pts[i][0])
            minY = min(minY, pts[i][1]); maxY = max(maxY, pts[i][1])
        }
        return floatArrayOf(minX, minY, maxX, maxY)
    }

    private companion object {
        const val MAX_MISSED = 8
        val BBOX_POINTS = listOf(0, 11, 12, 15, 16, 23, 24, 27, 28, 29, 30)
    }
}

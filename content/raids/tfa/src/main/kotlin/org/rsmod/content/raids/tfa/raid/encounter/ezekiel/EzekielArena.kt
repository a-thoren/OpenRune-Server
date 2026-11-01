package org.rsmod.content.raids.tfa.raid.encounter.ezekiel

import org.rsmod.api.bosses.dsl.area
import org.rsmod.api.bosses.dsl.spawnTile
import org.rsmod.api.bosses.spec.Area
import org.rsmod.api.player.isValidTarget
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList
import org.rsmod.map.CoordGrid

internal object EzekielVarns {
    const val HP_MAX = "varn.ezekiel_hp_max"
    const val WEAKENED = "varn.ezekiel_weakened"
    const val FINALE = "varn.ezekiel_finale"
    const val DEAD = "varn.ezekiel_dead"
    const val DONE = "varn.ezekiel_done"
}

/** The side a sword enters from; it flies across the arena away from that side. */
internal enum class SwordSide(val dx: Int, val dz: Int) {
    NORTH(0, -1),
    SOUTH(0, 1),
    WEST(1, 0),
    EAST(-1, 0);

    val vertical: Boolean
        get() = dx == 0
}

/** One sword of a pattern: its side, its lane's first tile offset, and its stagger in ticks. */
internal data class SwordPlacement(val side: SwordSide, val lane: Int, val stagger: Int = 0)

internal data class SwordPattern(val line: String, val swords: List<SwordPlacement>)

/**
 * A finale sword on his footprint: its tile, its asset number, its lightning sound and safe row.
 */
internal data class FinaleSword(
    val dx: Int,
    val dz: Int,
    val number: Int,
    val synth: String,
    val safeRow: Int,
)

/**
 * Everything here is relative to Ezekiel's spawn tile (`origin`), so an instance copy works. The
 * tsps capture (`Ezekiel.FracturedArchive.js`) gives absolute tiles; spawn is 4062,5537.
 */
internal object EzekielArena {
    const val ENTRY_X = 4079
    const val ENTRY_Z = 5536
    const val SPAWN_X = 4062
    const val SPAWN_Z = 5537
    const val REACH = 30

    const val SIZE = 3
    const val SWORD_SIZE = 3
    const val ARENA_SW_DX = -6
    const val ARENA_SW_DZ = -9
    const val ARENA_NE_DX = 6
    const val ARENA_NE_DZ = 3
    const val SWORD_FLIGHT = 12

    const val FINALE_DX = -3
    const val FINALE_DZ = -3
    const val WALL_SW_DX = -3
    const val WALL_SW_DZ = -6
    const val WALL_NE_DX = 5
    const val WALL_NE_DZ = 2
    const val PEN_SW_DX = 0
    const val PEN_SW_DZ = -3
    const val PEN_NE_DX = 2
    const val PEN_NE_DZ = -1

    // Every damage figure is a tsps estimate. tunable
    const val MELEE_MAX = 35
    const val MELEE_PRAYER_LEAK = 30
    const val MAGIC_MAX = 25
    const val STAR_MIN = 12
    const val STAR_MAX = 25
    const val SWORD_MAX = 45
    const val SWORD_ON_HIM = 30
    const val BARRAGE_MIN = 20
    const val BARRAGE_MAX = 40
    const val HEAL_PERCENT = 5

    /** Charge at which a sword's damage doubles. tunable */
    const val CHARGE_FULL = 32
    const val STAR_REACH = 3

    private const val MAP_SQUARE_MASK = 63

    val AREA: Area = area(spawnTile(ARENA_SW_DX, ARENA_SW_DZ), spawnTile(ARENA_NE_DX, ARENA_NE_DZ))

    /** "Divine consequence" (a cross) and the two "Divine judgement" shapes (tsps). */
    fun patterns(pick: (Int) -> Int): SwordPattern =
        when (pick(3)) {
            0 -> {
                val x = if (pick(2) == 0) -3 else 3
                val z = if (pick(2) == 0) -3 else 0
                SwordPattern(
                    "Divine consequence!",
                    listOf(
                        SwordPlacement(SwordSide.NORTH, x),
                        SwordPlacement(SwordSide.SOUTH, x),
                        SwordPlacement(SwordSide.WEST, z),
                        SwordPlacement(SwordSide.EAST, z),
                    ),
                )
            }
            1 ->
                SwordPattern(
                    "Divine judgement!",
                    listOf(
                        SwordPlacement(SwordSide.NORTH, -3, 0),
                        SwordPlacement(SwordSide.NORTH, 0, 1),
                        SwordPlacement(SwordSide.NORTH, 3, 2),
                    ),
                )
            else ->
                SwordPattern(
                    "Divine judgement!",
                    listOf(
                        SwordPlacement(SwordSide.NORTH, -2),
                        SwordPlacement(SwordSide.SOUTH, -2),
                        SwordPlacement(SwordSide.NORTH, 2),
                        SwordPlacement(SwordSide.SOUTH, 2),
                    ),
                )
        }

    /** The fence swords round the pen: offset and drop/idle asset number (captured). */
    val FENCE =
        listOf(
            Triple(3, 0, 3),
            Triple(-3, 0, 1),
            Triple(0, 0, 2),
            Triple(-3, -6, 6),
            Triple(0, -6, 7),
            Triple(3, -3, 4),
            Triple(3, -6, 1),
        )

    val FINALE_SWORDS =
        listOf(
            FinaleSword(-2, -1, 3, "synth.tfa_ezekiel_sword_telegraph_3", -1),
            FinaleSword(-1, -2, 1, "synth.tfa_ezekiel_sword_telegraph_1", -2),
            FinaleSword(-2, -3, 4, "synth.tfa_ezekiel_sword_telegraph_4", -3),
        )

    fun inArena(origin: CoordGrid, tile: CoordGrid): Boolean {
        val dx = tile.x - origin.x
        val dz = tile.z - origin.z
        return tile.level == origin.level &&
            dx in ARENA_SW_DX..ARENA_NE_DX &&
            dz in ARENA_SW_DZ..ARENA_NE_DZ
    }

    fun inRoom(origin: CoordGrid, tile: CoordGrid): Boolean {
        val swX = origin.x - (SPAWN_X and MAP_SQUARE_MASK)
        val swZ = origin.z - (SPAWN_Z and MAP_SQUARE_MASK)
        return tile.level == origin.level &&
            tile.x in swX..swX + MAP_SQUARE_MASK &&
            tile.z in swZ..swZ + MAP_SQUARE_MASK
    }

    fun inPen(origin: CoordGrid, tile: CoordGrid): Boolean {
        val dx = tile.x - origin.x
        val dz = tile.z - origin.z
        return tile.level == origin.level &&
            dx in PEN_SW_DX..PEN_NE_DX &&
            dz in PEN_SW_DZ..PEN_NE_DZ
    }

    fun players(origin: CoordGrid, playerList: PlayerList): List<Player> =
        playerList.filter { it.isValidTarget() && inArena(origin, it.coords) }

    fun penTiles(origin: CoordGrid): List<CoordGrid> = buildList {
        for (dx in PEN_SW_DX..PEN_NE_DX) {
            for (dz in PEN_SW_DZ..PEN_NE_DZ) add(origin.translate(dx, dz))
        }
    }

    /** The 9x9 round his finale tile, less the pen: blocked while the finale lasts. */
    fun wallTiles(origin: CoordGrid): List<CoordGrid> = buildList {
        for (dx in WALL_SW_DX..WALL_NE_DX) {
            for (dz in WALL_SW_DZ..WALL_NE_DZ) {
                if (dx in PEN_SW_DX..PEN_NE_DX && dz in PEN_SW_DZ..PEN_NE_DZ) continue
                add(origin.translate(dx, dz))
            }
        }
    }

    /** Chebyshev distance from his 3x3 footprint at [sw] to [tile]; 0 inside it. */
    fun footprintDistance(sw: CoordGrid, tile: CoordGrid): Int {
        val dx = maxOf(sw.x - tile.x, 0, tile.x - (sw.x + SIZE - 1))
        val dz = maxOf(sw.z - tile.z, 0, tile.z - (sw.z + SIZE - 1))
        return maxOf(dx, dz)
    }

    /** The ring touching his 3x3, less the three tiles on the side away from [toward]. */
    fun cleaveTiles(sw: CoordGrid, toward: CoordGrid): List<CoordGrid> {
        val last = SIZE - 1
        val dx = if (toward.x < sw.x) -1 else if (toward.x > sw.x + last) 1 else 0
        val dz = if (toward.z < sw.z) -1 else if (toward.z > sw.z + last) 1 else 0
        // The side behind him: the axis the target is off, the vertical one when off both.
        val behindZ = if (dz != 0) (if (dz > 0) -1 else SIZE) else null
        val behindX = if (dz == 0 && dx != 0) (if (dx > 0) -1 else SIZE) else null
        return buildList {
            for (x in -1..SIZE) {
                for (z in -1..SIZE) {
                    if (x in 0..last && z in 0..last) continue
                    if (behindZ != null && z == behindZ && x in 0..last) continue
                    if (behindX != null && x == behindX && z in 0..last) continue
                    add(sw.translate(x, z))
                }
            }
        }
    }

    /**
     * The star: tiles one to [STAR_REACH] steps (Manhattan) off his footprint, off the diagonals.
     */
    fun starTiles(sw: CoordGrid, step: Int? = null): List<CoordGrid> = buildList {
        val last = SIZE - 1
        for (x in -STAR_REACH..last + STAR_REACH) {
            for (z in -STAR_REACH..last + STAR_REACH) {
                val dx = if (x < 0) -x else if (x > last) x - last else 0
                val dz = if (z < 0) -z else if (z > last) z - last else 0
                val manhattan = dx + dz
                if (manhattan !in 1..STAR_REACH || dx == dz) continue
                if (step != null && manhattan != step) continue
                add(sw.translate(x, z))
            }
        }
    }

    /** The tile a sword is raised on: its lane's first tile at the edge of [side]. */
    fun swordStart(origin: CoordGrid, placement: SwordPlacement): CoordGrid =
        when (placement.side) {
            SwordSide.NORTH -> origin.translate(placement.lane, ARENA_NE_DZ)
            SwordSide.SOUTH -> origin.translate(placement.lane, ARENA_SW_DZ)
            SwordSide.WEST -> origin.translate(ARENA_SW_DX, placement.lane)
            SwordSide.EAST -> origin.translate(ARENA_NE_DX, placement.lane)
        }

    fun swordEnd(start: CoordGrid, side: SwordSide): CoordGrid =
        start.translate(side.dx * SWORD_FLIGHT, side.dz * SWORD_FLIGHT)

    /** Whether [tile] is in the three-wide lane a sword starting at [start] flies along. */
    fun inLane(start: CoordGrid, side: SwordSide, tile: CoordGrid): Boolean =
        if (side.vertical) tile.x in start.x until start.x + SWORD_SIZE
        else tile.z in start.z until start.z + SWORD_SIZE

    /** Whether the lane crosses his footprint at [sw]. */
    fun laneCrosses(start: CoordGrid, side: SwordSide, sw: CoordGrid): Boolean =
        if (side.vertical) start.x <= sw.x + SIZE - 1 && start.x + SWORD_SIZE - 1 >= sw.x
        else start.z <= sw.z + SIZE - 1 && start.z + SWORD_SIZE - 1 >= sw.z
}

package org.rsmod.content.raids.tfa.raid.nexus

import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.rsmod.api.instances.InstanceAccess
import org.rsmod.api.instances.InstanceArea
import org.rsmod.api.instances.InstanceId
import org.rsmod.api.instances.InstanceManager
import org.rsmod.api.instances.InstanceSession
import org.rsmod.api.instances.InstanceSpec
import org.rsmod.api.instances.RegionLocal
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.region.RegionTemplate
import org.rsmod.content.raids.tfa.raid.TfaFight
import org.rsmod.content.raids.tfa.raid.TfaRaid
import org.rsmod.content.raids.tfa.raid.TfaSide
import org.rsmod.content.raids.tfa.raid.reward.TfaVault
import org.rsmod.game.MapClock
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.loc.LocAngle
import org.rsmod.game.loc.LocShape
import org.rsmod.game.map.Direction
import org.rsmod.game.queue.WorldQueueList
import org.rsmod.map.CoordGrid
import org.rsmod.map.zone.ZoneKey

/**
 * The Nexus between bosses, and the section doors it shares with the Guardian's room.
 *
 * Live copies map square 64,87's levels 2 and 3 onto its instance's levels 0 and 1 (tsps
 * `docs/fractured-archive.md`, captured build_area): drawn in place, the Archive Door's end sinks
 * under the Guardian's heights. Tiles here are real-map tiles (level 2 for the Nexus) and are
 * resolved into whichever instance holds them.
 */
@Singleton
class TfaNexus
@Inject
constructor(
    private val manager: InstanceManager,
    private val npcRepo: NpcRepository,
    private val locRepo: LocRepository,
    private val worldQueues: WorldQueueList,
    private val clock: MapClock,
) {
    private val rooms = HashMap<InstanceId, Room>()

    internal class Room(override val session: InstanceSession) : TfaFight {
        override val elapsedTicks: Int? = null
        override val finished: Boolean = false
        val staff = ArrayList<Npc>()
    }

    internal fun of(id: InstanceId): Room? = rooms[id]

    /** Opens a Nexus for [raid]'s next visit and returns it with the tile beside [side]'s door. */
    internal fun open(
        owner: Player,
        raid: TfaRaid,
        section: Int,
        side: TfaSide,
    ): Pair<Room, CoordGrid>? {
        val result = manager.create(owner, KEY, SPEC, InstanceAccess.Private, clock.cycle)
        if (result !is InstanceManager.Result.Created) return null
        val room = Room(result.session)
        rooms[result.session.id] = room
        placeDoors(raid, result.session, NEXUS_LEVEL, openSection = raid.cleared + 1)
        placeOcellusDoor(raid, result.session, NEXUS_LEVEL, open = raid.cleared >= SECTIONS.size)
        spawnStaff(room, visit = raid.cleared)
        val arrival =
            manager.resolveCoord(result.session, returnTile(section, side)) ?: result.enter
        return room to arrival
    }

    internal fun close(id: InstanceId) {
        val room = rooms.remove(id) ?: return
        for (npc in room.staff) if (npc.isSlotAssigned) npcRepo.del(npc, Int.MAX_VALUE)
    }

    /** The Guardian's room before it falls: every section door shut (captured, 63094). */
    internal fun placeGuardianDoors(raid: TfaRaid, session: InstanceSession) {
        placeDoors(raid, session, GUARDIAN_LEVEL, openSection = null)
        placeOcellusDoor(raid, session, GUARDIAN_LEVEL, open = false)
    }

    /**
     * The Guardian has fallen: section 1's doors open on its level, "slightly" first and fully
     * [DOOR_OPENS] ticks later (captured).
     */
    internal fun openFirstSection(raid: TfaRaid, session: InstanceSession) {
        placeSection(raid, session, GUARDIAN_LEVEL, section = 1, open = true, opened = false)
        worldQueues.add(DOOR_OPENS) {
            if (raid.doorRoom != session.id) return@add
            placeSection(raid, session, GUARDIAN_LEVEL, section = 1, open = true, opened = true)
        }
    }

    /** Once a side is taken, the other door of that section shuts (tsps estimate). */
    internal fun closeOtherSide(raid: TfaRaid, section: Int, taken: TfaSide) {
        val other = if (taken == TfaSide.West) TfaSide.East else TfaSide.West
        val door = raid.doors[section to other] ?: return
        raid.doors[section to other] =
            locRepo.add(door.coords, CLOSED_DOOR, Int.MAX_VALUE, door.angle, door.shape)
    }

    /** The section and side of the door at [coords] in [session], if it is one. */
    internal fun doorAt(session: InstanceSession, coords: CoordGrid): Pair<Int, TfaSide>? {
        val level = if (rooms.containsKey(session.id)) NEXUS_LEVEL else GUARDIAN_LEVEL
        for ((section, z) in SECTIONS) {
            for (side in TfaSide.entries) {
                val tile = manager.resolveCoord(session, CoordGrid(side.doorX, z, level))
                if (tile == coords) return section to side
            }
        }
        return null
    }

    private fun placeDoors(raid: TfaRaid, session: InstanceSession, level: Int, openSection: Int?) {
        raid.doorRoom = session.id
        raid.doors.clear()
        for (section in SECTIONS.keys) {
            placeSection(
                raid,
                session,
                level,
                section,
                open = section == openSection,
                opened = true,
            )
        }
    }

    private fun placeSection(
        raid: TfaRaid,
        session: InstanceSession,
        level: Int,
        section: Int,
        open: Boolean,
        opened: Boolean,
    ) {
        val z = SECTIONS.getValue(section)
        for (side in TfaSide.entries) {
            val tile = manager.resolveCoord(session, CoordGrid(side.doorX, z, level)) ?: continue
            val taken = raid.path[section]
            val usable = open && (taken == null || taken == side)
            val loc = if (usable) doorLoc(section, side, opened) else CLOSED_DOOR
            raid.doors[section to side] =
                locRepo.add(tile, loc, Int.MAX_VALUE, side.angle, LocShape.CentrepieceStraight)
        }
    }

    private fun placeOcellusDoor(
        raid: TfaRaid,
        session: InstanceSession,
        level: Int,
        open: Boolean,
    ) {
        val tile = manager.resolveCoord(session, CoordGrid(OCELLUS_X, OCELLUS_Z, level)) ?: return
        val loc = if (open) OCELLUS_DOOR_OPEN else OCELLUS_DOOR_CLOSED
        raid.ocellusDoor =
            locRepo.add(tile, loc, Int.MAX_VALUE, LocAngle.West, LocShape.CentrepieceStraight)
    }

    internal fun isOcellusDoor(session: InstanceSession, coords: CoordGrid): Boolean {
        val level = if (rooms.containsKey(session.id)) NEXUS_LEVEL else GUARDIAN_LEVEL
        return manager.resolveCoord(session, CoordGrid(OCELLUS_X, OCELLUS_Z, level)) == coords
    }

    private fun spawnStaff(room: Room, visit: Int) {
        val members = TfaNexusStaff.visits[visit] ?: return
        for (member in members) {
            val tile = manager.resolveCoord(room.session, member.tile) ?: continue
            val npc = runCatching { Npc(member.npc, tile) }.getOrNull() ?: continue
            npc.respawnDir = member.face
            npcRepo.add(npc, Int.MAX_VALUE)
            manager.attachNpc(room.session.id, npc)
            room.staff += npc
            if (member.lines.isNotEmpty()) scheduleLines(room, npc, member.lines)
        }
    }

    private fun scheduleLines(room: Room, npc: Npc, lines: List<String>) {
        worldQueues.add(TfaNexusStaff.LINES_EVERY) {
            if (rooms[room.session.id] !== room || !npc.isSlotAssigned) return@add
            npc.say(lines.random())
            scheduleLines(room, npc, lines)
        }
    }

    internal companion object {
        const val KEY = "tfa_nexus"

        /** Section rows (tsps captures): each has a west door at x 4113 and an east at 4141. */
        val SECTIONS: Map<Int, Int> = linkedMapOf(1 to 5584, 2 to 5597, 3 to 5610)
        const val GUARDIAN_LEVEL = 0
        const val NEXUS_LEVEL = 2
        const val OCELLUS_X = 4124
        const val OCELLUS_Z = 5621
        const val DOOR_OPENS = 3
        private const val RETURN_DZ = 2

        const val CLOSED_DOOR = "loc.tfa_nexus_section_door_closed"
        const val OCELLUS_DOOR_CLOSED = "loc.tfa_nexus_ocellus_door_closed"
        const val OCELLUS_DOOR_OPEN = "loc.tfa_nexus_ocellus_door_open"

        fun doorLoc(section: Int, side: TfaSide, opened: Boolean): String {
            val suffix = if (opened) "_slightly_opened" else "_slightly"
            return "loc.tfa_nexus_section${section}_door_${side.key}$suffix"
        }

        /** Every loc a section door can be, for op bindings. */
        val DOOR_LOCS: List<String> =
            listOf(CLOSED_DOOR) +
                SECTIONS.keys.flatMap { section ->
                    TfaSide.entries.flatMap { side ->
                        listOf(doorLoc(section, side, opened = false), doorLoc(section, side, true))
                    }
                }

        /** Coming back from a boss: beside the door taken, two tiles north of its row (tsps). */
        fun returnTile(section: Int, side: TfaSide): CoordGrid =
            CoordGrid(side.returnX, SECTIONS.getValue(section) + RETURN_DZ, NEXUS_LEVEL)

        private const val SOURCE_ZONE_X = 4096 shr 3
        private const val SOURCE_ZONE_Z = 5568 shr 3
        private const val SQUARE_ZONES = 8
        private const val SOURCE_LEVEL = 2
        private const val COPIED_LEVELS = 2

        private val TEMPLATE =
            RegionTemplate.create {
                for (level in 0 until COPIED_LEVELS) {
                    for (x in 0 until SQUARE_ZONES) {
                        for (z in 0 until SQUARE_ZONES) {
                            this[x, z, level] =
                                ZoneKey(SOURCE_ZONE_X + x, SOURCE_ZONE_Z + z, SOURCE_LEVEL + level)
                        }
                    }
                }
            }

        private val ARRIVAL = CoordGrid(4127, 5590, NEXUS_LEVEL)

        private val SPEC =
            InstanceSpec(
                fee = 0,
                maxPlayers = 8,
                reclaimTicks = 0,
                graceTicks = 0,
                destroyWhenEmpty = true,
                area =
                    InstanceArea.template(
                        template = TEMPLATE,
                        enterCoord =
                            RegionLocal(
                                ARRIVAL.level,
                                ARRIVAL.mx,
                                ARRIVAL.mz,
                                ARRIVAL.lx,
                                ARRIVAL.lz,
                            ),
                        exitCoord = TfaVault.LOBBY,
                    ),
                settingsRowId = 0,
            )
    }
}

internal val TfaSide.key: String
    get() = if (this == TfaSide.West) "west" else "east"

internal val TfaSide.doorX: Int
    get() = if (this == TfaSide.West) 4113 else 4141

internal val TfaSide.returnX: Int
    get() = if (this == TfaSide.West) 4116 else 4139

/** tsps: west doors face 3, east doors face 1. */
internal val TfaSide.angle: LocAngle
    get() = if (this == TfaSide.West) LocAngle.South else LocAngle.North

internal data class TfaStaffMember(
    val npc: String,
    val tile: CoordGrid,
    val face: Direction,
    val lines: List<String> = emptyList(),
)

package org.rsmod.content.bosses.kreearra

import jakarta.inject.Inject
import org.rsmod.api.instances.BossInstanceRegistry
import org.rsmod.api.instances.InstanceArea
import org.rsmod.api.instances.InstanceNpc
import org.rsmod.api.instances.InstanceScript
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Kree'arra lobby.
 *
 * Mirrors [org.rsmod.content.bosses.graardor.GraardorInstance]: there is no public *instance*
 * room, the public encounter is the live overworld Armadyl boss room. Both routes into the fight
 * (walking into the overworld room, or creating/joining a private instance) are gated behind
 * [REQUIRED_KILLCOUNT] Armadyl kills, tracked in `varbit.godwars_counter_armadyl`.
 *
 * The Armadyl god-door is wired as the instance enter object, so opening it runs [enterArmadylDoor]:
 * it gates on killcount and then lets the player choose the public overworld room or a private
 * instance. The same god-door is also the way out of both the public room and a private instance.
 * The interior Armadyl altar is the exit object ([leaveArmadylRoom]) purely as an instance-only
 * shortcut out; it never ejects players from the public overworld room.
 */
class KreeArraInstance
@Inject
constructor(registry: BossInstanceRegistry) : InstanceScript(registry) {

    override fun settingsRow(): String = "dbrow.instance_kreearra"

    override fun area(): InstanceArea = INSTANCE

    override fun ScriptContext.configure() {
        onEnterObject { enterArmadylDoor() }
        onExitObject { leaveArmadylRoom() }
    }

    private suspend fun ProtectedAccess.enterArmadylDoor() {
        // Leaving a private instance: the god-door is the way out.
        if (manager.sessionForPlayer(player) != null) {
            defaultLeaveFlow()
            return
        }

        // Already inside the live overworld boss room: the door lets the player back out.
        if (inOverworldBossRoom(player.coords)) {
            telejump(OVERWORLD_EXIT)
            return
        }

        // Outside the door: gate entry on killcount, then let the player pick public or private.
        val killcount = player.vars["varbit.godwars_counter_armadyl"]
        if (killcount < REQUIRED_KILLCOUNT) {
            mes(
                "You need a killcount of at least $REQUIRED_KILLCOUNT of Armadyl's followers " +
                    "to enter."
            )
            return
        }

        val choice =
            choice2(
                "Enter Kree'arra's eyrie.",
                PUBLIC,
                "Create or join a private instance.",
                PRIVATE,
                title = "Armadyl's Eyrie",
            )
        when (choice) {
            PUBLIC -> telejump(OVERWORLD_ENTER)
            PRIVATE -> defaultInstanceEntry()
        }
    }

    private suspend fun ProtectedAccess.leaveArmadylRoom() {
        // Instance-only shortcut out via the altar. Public overworld players leave through the
        // god-door (see [enterArmadylDoor]); the altar must not eject them mid-fight.
        if (manager.sessionForPlayer(player) != null) {
            defaultLeaveFlow()
        }
    }

    private fun inOverworldBossRoom(coords: CoordGrid): Boolean =
        coords.level == BOSS_ROOM_LEVEL && coords.x in BOSS_ROOM_X && coords.z in BOSS_ROOM_Z

    private companion object {
        private const val REQUIRED_KILLCOUNT = 40

        private const val PUBLIC = 1
        private const val PRIVATE = 2

        // Live overworld Armadyl boss room (region 11346 / map square 44,82, level 2).
        private const val BOSS_ROOM_LEVEL = 2
        private val BOSS_ROOM_X = 2826..2841
        private val BOSS_ROOM_Z = 5294..5306

        // Interior spawn just inside the door; matches dbrow.instance_kreearra ENTER_COORD.
        private val OVERWORLD_ENTER = CoordGrid(2838, 5300, 2)
        // Landing tile just outside the door in the main dungeon; matches EXIT_COORD.
        private val OVERWORLD_EXIT = CoordGrid(2843, 5300, 2)

        private val INSTANCE =
            InstanceArea.copyRegions(
                regionIds = listOf(11346),
                level = 2,
                npcSpawns =
                    listOf(
                        InstanceNpc("npc.godwars_armadyl_avatar", CoordGrid(2832, 5302, 2)),
                        InstanceNpc(
                            "npc.godwars_armadyl_bodyguard_geerin",
                            CoordGrid(2828, 5299, 2),
                        ),
                        InstanceNpc(
                            "npc.godwars_armadyl_bodyguard_kilisa",
                            CoordGrid(2833, 5297, 2),
                        ),
                        InstanceNpc(
                            "npc.godwars_armadyl_bodyguard_skree",
                            CoordGrid(2840, 5303, 2),
                        ),
                    ),
            )
    }
}

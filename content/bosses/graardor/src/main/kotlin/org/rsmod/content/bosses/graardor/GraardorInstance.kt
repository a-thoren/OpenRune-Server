package org.rsmod.content.bosses.graardor

import jakarta.inject.Inject
import org.rsmod.api.instances.BossInstanceRegistry
import org.rsmod.api.instances.InstanceArea
import org.rsmod.api.instances.InstanceNpc
import org.rsmod.api.instances.InstanceScript
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.ScriptContext

/**
 * General Graardor lobby.
 *
 * Unlike Scurrius/KBD there is no public *instance* room: the public encounter is the live
 * overworld Bandos boss room where [GeneralGraardor] already spawns. Both routes into the fight
 * (walking into the overworld room, or creating/joining a private instance) are gated behind
 * [REQUIRED_KILLCOUNT] Bandos kills, tracked in `varbit.godwars_counter_bandos`.
 *
 * The Bandos god-door is wired as the instance enter object, so opening it runs [enterBandosDoor]:
 * it gates on killcount and then lets the player choose the public overworld room or a private
 * instance. The same god-door is also the way out of both the public room and a private instance.
 * The interior Bandos altar is the exit object ([leaveBandosRoom]) purely as an instance-only
 * shortcut out; it never ejects players from the public overworld room.
 */
class GraardorInstance
@Inject
constructor(registry: BossInstanceRegistry) : InstanceScript(registry) {

    override fun settingsRow(): String = "dbrow.instance_graardor"

    override fun area(): InstanceArea = INSTANCE

    override fun ScriptContext.configure() {
        onEnterObject { enterBandosDoor() }
        onExitObject { leaveBandosRoom() }
    }

    private suspend fun ProtectedAccess.enterBandosDoor() {
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
        val killcount = player.vars["varbit.godwars_counter_bandos"]
        if (killcount < REQUIRED_KILLCOUNT) {
            mes(
                "You need a killcount of at least $REQUIRED_KILLCOUNT of Bandos' followers " +
                    "to enter."
            )
            return
        }

        val choice =
            choice2(
                "Enter General Graardor's stronghold.",
                PUBLIC,
                "Create or join a private instance.",
                PRIVATE,
                title = "Bandos' Stronghold",
            )
        when (choice) {
            PUBLIC -> telejump(OVERWORLD_ENTER)
            PRIVATE -> defaultInstanceEntry()
        }
    }

    private suspend fun ProtectedAccess.leaveBandosRoom() {
        // Instance-only shortcut out via the altar. Public overworld players leave through the
        // god-door (see [enterBandosDoor]); the altar must not eject them mid-fight.
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

        // Live overworld Bandos boss room (region 11347 / map square 44,83, level 2).
        private const val BOSS_ROOM_LEVEL = 2
        private val BOSS_ROOM_X = 2861..2876
        private val BOSS_ROOM_Z = 5350..5366

        // Interior spawn just inside the door; matches dbrow.instance_graardor ENTER_COORD.
        private val OVERWORLD_ENTER = CoordGrid(2868, 5354, 2)
        // Landing tile just outside the door in the main dungeon; matches EXIT_COORD.
        private val OVERWORLD_EXIT = CoordGrid(2869, 5347, 2)

        private val INSTANCE =
            InstanceArea.copyRegions(
                regionIds = listOf(11347),
                level = 2,
                npcSpawns =
                    listOf(
                        InstanceNpc("npc.godwars_bandos_avatar", CoordGrid(2872, 5358, 2)),
                        InstanceNpc("npc.godwars_sergeant_goblin1", CoordGrid(2866, 5358, 2)),
                        InstanceNpc("npc.godwars_sergeant_goblin2", CoordGrid(2872, 5352, 2)),
                        InstanceNpc("npc.godwars_sergeant_goblin3", CoordGrid(2868, 5362, 2)),
                    ),
            )
    }
}

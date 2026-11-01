package org.rsmod.content.raids.tfa.raid.nexus

import org.rsmod.game.map.Direction
import org.rsmod.map.CoordGrid

/**
 * The Alliance standing about the Nexus on each visit (1-3: after the first, second and third boss
 * past the Guardian), with their overhead lines. Npcs, tiles, facings and lines are tsps' spectator
 * captures (`data/fractured-archive.json` `hub.visits`); the line interval is its estimate.
 */
internal object TfaNexusStaff {
    const val LINES_EVERY = 12 // tunable: tsps estimate
    const val QUARTERMASTER = "npc.tfa_midraid_supplies_npc"

    private fun at(x: Int, z: Int) = CoordGrid(x, z, TfaNexus.NEXUS_LEVEL)

    val visits: Map<Int, List<TfaStaffMember>> =
        mapOf(
            1 to
                listOf(
                    TfaStaffMember(
                        "npc.eva_cashien_sword",
                        at(4127, 5597),
                        Direction.South,
                        listOf(
                            "There's no time to waste, pick a side!",
                            "Pick a side, and may Saradomin guide you!",
                            "The middle defences are buckling, pick a side!",
                        ),
                    ),
                    TfaStaffMember("npc.armacus_selles", at(4120, 5582), Direction.East),
                    TfaStaffMember("npc.alliance_druid_2", at(4125, 5603), Direction.West),
                    TfaStaffMember(
                        "npc.alliance_guardian_of_armadyl_4",
                        at(4124, 5600),
                        Direction.North,
                    ),
                    TfaStaffMember("npc.alliance_temple_knight_2", at(4124, 5588), Direction.West),
                    TfaStaffMember("npc.alliance_druid_1", at(4132, 5589), Direction.East),
                    TfaStaffMember(
                        "npc.alliance_temple_knight_1",
                        at(4122, 5580),
                        Direction.SouthWest,
                    ),
                ),
            2 to
                listOf(
                    TfaStaffMember("npc.eva_cashien", at(4135, 5603), Direction.West),
                    TfaStaffMember(
                        QUARTERMASTER,
                        at(4129, 5609),
                        Direction.South,
                        listOf(
                            "Stock available!",
                            "Supplies are available!",
                            "Shop's open!",
                            "Supplies are ready!",
                            "Fully stocked!",
                        ),
                    ),
                    TfaStaffMember(
                        "npc.tfa_ivy_sophista_noop",
                        at(4127, 5613),
                        Direction.South,
                        listOf(
                            "One final push, pick a side!",
                            "Pick a side! Do not relent!",
                            "Give them no quarter, pick a side!",
                        ),
                    ),
                    TfaStaffMember("npc.alliance_druid_1", at(4124, 5600), Direction.South),
                    TfaStaffMember("npc.alliance_temple_knight_2", at(4127, 5596), Direction.West),
                    TfaStaffMember(
                        "npc.alliance_guardian_of_armadyl_3",
                        at(4124, 5588),
                        Direction.West,
                        listOf("Runes ready!"),
                    ),
                    TfaStaffMember(
                        "npc.alliance_guardian_of_armadyl_3",
                        at(4132, 5589),
                        Direction.East,
                    ),
                    TfaStaffMember(
                        "npc.alliance_temple_knight_1",
                        at(4122, 5580),
                        Direction.SouthWest,
                    ),
                ),
            3 to
                listOf(
                    TfaStaffMember(
                        "npc.armacus_selles_postguardian",
                        at(4128, 5619),
                        Direction.South,
                    ),
                    TfaStaffMember("npc.eva_cashien_sword", at(4126, 5619), Direction.South),
                    TfaStaffMember(
                        "npc.tfa_ivy_sophista",
                        at(4127, 5618),
                        Direction.South,
                        listOf(
                            "The inner vault must be protected!",
                            "Go inside and clear out any threats!",
                            "There's a disturbance in the inner vault!",
                            "Deal with it and let's end this!",
                        ),
                    ),
                    TfaStaffMember(
                        "npc.alliance_guardian_of_armadyl_6",
                        at(4125, 5602),
                        Direction.South,
                    ),
                    TfaStaffMember(
                        "npc.alliance_guardian_of_armadyl_2",
                        at(4124, 5599),
                        Direction.South,
                    ),
                    TfaStaffMember("npc.alliance_temple_knight_2", at(4124, 5588), Direction.West),
                    TfaStaffMember(
                        "npc.alliance_druid_2",
                        at(4132, 5589),
                        Direction.East,
                        listOf("More runes coming!"),
                    ),
                    TfaStaffMember(
                        "npc.alliance_temple_knight_1",
                        at(4122, 5580),
                        Direction.SouthWest,
                    ),
                ),
        )
}
